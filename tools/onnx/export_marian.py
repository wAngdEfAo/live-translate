"""Export Helsinki-NLP OPUS-MT (Marian) to split encoder/decoder ONNX + compact tokenizer.

Runs on the torch-enabled interpreter (3.15). Uses hf-mirror.com because
huggingface.co is not reachable from this host.
"""
import json
import os
import sys
import time

os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")
os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

import torch
from sentencepiece import sentencepiece_model_pb2 as spb
from transformers import MarianMTModel, MarianTokenizer
from transformers.modeling_outputs import BaseModelOutput

OUT_ROOT = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.join(OUT_ROOT, "build")


def log(*a):
    print(*a, flush=True)


class Enc(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, input_ids, attention_mask):
        o = self.m.get_encoder()(
            input_ids=input_ids, attention_mask=attention_mask, return_dict=False
        )
        return o[0]


class Dec(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, decoder_input_ids, encoder_hidden_states, encoder_attention_mask):
        o = self.m(
            decoder_input_ids=decoder_input_ids,
            encoder_outputs=BaseModelOutput(last_hidden_state=encoder_hidden_states),
            attention_mask=encoder_attention_mask,
            return_dict=False,
        )
        return o[0]


def dump_one(sp):
    """One SentencePiece unigram table as parallel arrays (small and cheap to parse)."""
    mp = spb.ModelProto()
    mp.ParseFromString(sp.serialized_model_proto())
    ns = mp.normalizer_spec
    ts = mp.trainer_spec
    n = len(mp.pieces)
    return {
        "size": n,
        "model_type": int(ts.model_type),
        "byte_fallback": bool(ts.byte_fallback),
        "add_dummy_prefix": bool(ns.add_dummy_prefix),
        "escape_whitespaces": bool(ns.escape_whitespaces),
        "remove_extra_whitespaces": bool(ns.remove_extra_whitespaces),
        "normalization": ns.name or "nfkc",
        "unk_id": int(sp.unk_id()),
        "bos_id": int(sp.bos_id()),
        "eos_id": int(sp.eos_id()),
        "pad_id": int(sp.pad_id()),
        "pieces": [p.piece for p in mp.pieces],
        "scores": [round(float(p.score), 6) for p in mp.pieces],
        "types": [int(p.type) for p in mp.pieces],
    }


GOLDEN_EN = [
    "Hello world.",
    "The quick brown fox jumps over the lazy dog.",
    "Please restart the application.",
    "Connection timed out after 30 seconds.",
    "I can't find the settings menu.",
]
GOLDEN_ZH = [
    "你好，世界。",
    "请重启应用程序。",
    "连接超时，请检查网络。",
]


def export(model_name, tag, src_is_en, seq_len=8):
    t0 = time.time()
    log(f"[{tag}] loading {model_name} (HF_ENDPOINT={os.environ['HF_ENDPOINT']}) ...")
    tok = MarianTokenizer.from_pretrained(model_name)
    model = MarianMTModel.from_pretrained(model_name)
    model.eval()
    log(f"[{tag}] loaded in {time.time() - t0:.1f}s")

    d = os.path.join(WORK, tag)
    os.makedirs(d, exist_ok=True)

    src_sp = tok.spm_source
    tgt_sp = tok.spm_target
    tk = {"version": 1, "src": dump_one(src_sp), "tgt": dump_one(tgt_sp)}
    tk_path = os.path.join(d, "tokenizer.json")
    with open(tk_path, "w", encoding="utf-8") as f:
        json.dump(tk, f, ensure_ascii=False, separators=(",", ":"))
    log(f"[{tag}] tokenizer: src={tk['src']['size']} tgt={tk['tgt']['size']} "
        f"unk={tk['src']['unk_id']} pad={tk['src']['pad_id']} "
        f"dummy_prefix={tk['src']['add_dummy_prefix']} byte_fallback={tk['src']['byte_fallback']}")

    # golden samples so the Kotlin unigram implementation can be diffed against real
    # sentencepiece output instead of trusting my own reimplementation
    gold = []
    for s in (GOLDEN_EN if src_is_en else GOLDEN_ZH):
        gold.append({"text": s, "src_ids": src_sp.encode(s, out_type=int)})
    for s in (GOLDEN_ZH if src_is_en else GOLDEN_EN):
        gold.append({
            "text": s,
            "tgt_ids": tgt_sp.encode(s, out_type=int),
            "tgt_decoded": tgt_sp.decode(tgt_sp.encode(s, out_type=int)),
        })
    with open(os.path.join(d, "golden.json"), "w", encoding="utf-8") as f:
        json.dump(gold, f, ensure_ascii=False, indent=1)

    ids = torch.randint(3, 1000, (1, seq_len), dtype=torch.long)
    mask = torch.ones((1, seq_len), dtype=torch.long)
    hid = torch.randn(1, seq_len, model.config.d_model, dtype=torch.float32)
    dec_ids = torch.randint(3, 1000, (1, 4), dtype=torch.long)

    enc_path = os.path.join(d, "encoder.onnx")
    dec_path = os.path.join(d, "decoder.onnx")

    with torch.no_grad():
        log(f"[{tag}] exporting encoder ...")
        torch.onnx.export(
            Enc(model).eval(),
            (ids, mask),
            enc_path,
            input_names=["input_ids", "attention_mask"],
            output_names=["encoder_hidden_states"],
            dynamic_axes={
                "input_ids": {0: "batch", 1: "src_len"},
                "attention_mask": {0: "batch", 1: "src_len"},
                "encoder_hidden_states": {0: "batch", 1: "src_len"},
            },
            opset_version=17,
            do_constant_folding=True,
            dynamo=False,
        )
        log(f"[{tag}] exporting decoder ...")
        torch.onnx.export(
            Dec(model).eval(),
            (dec_ids, hid, mask),
            dec_path,
            input_names=["decoder_input_ids", "encoder_hidden_states", "encoder_attention_mask"],
            output_names=["logits"],
            dynamic_axes={
                "decoder_input_ids": {0: "batch", 1: "tgt_len"},
                "encoder_hidden_states": {0: "batch", 1: "src_len"},
                "encoder_attention_mask": {0: "batch", 1: "src_len"},
                "logits": {0: "batch", 1: "tgt_len"},
            },
            opset_version=17,
            do_constant_folding=True,
            dynamo=False,
        )

    meta = {
        "model": model_name,
        "tag": tag,
        "src": "en" if src_is_en else "zh",
        "tgt": "zh" if src_is_en else "en",
        "d_model": int(model.config.d_model),
        "enc_layers": int(model.config.encoder_layers),
        "dec_layers": int(model.config.decoder_layers),
        "vocab_size": int(model.config.vocab_size),
        "pad_token_id": int(tok.pad_token_id),
        "eos_token_id": int(tok.eos_token_id),
        "bos_token_id": int(tok.bos_token_id if tok.bos_token_id is not None else tok.eos_token_id),
        "decoder_start_token_id": int(model.config.decoder_start_token_id),
        "unk_token_id": int(tok.unk_token_id),
        "files": {
            "encoder.onnx": os.path.getsize(enc_path),
            "decoder.onnx": os.path.getsize(dec_path),
            "tokenizer.json": os.path.getsize(tk_path),
        },
    }
    with open(os.path.join(d, "meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
    log(f"[{tag}] done in {time.time() - t0:.1f}s  {json.dumps(meta['files'])}")
    return meta


if __name__ == "__main__":
    os.makedirs(WORK, exist_ok=True)
    result = {}
    for name, tag, src_en in (
        ("Helsinki-NLP/opus-mt-en-zh", "en_zh", True),
        ("Helsinki-NLP/opus-mt-zh-en", "zh_en", False),
    ):
        result[tag] = export(name, tag, src_en)
    with open(os.path.join(WORK, "export_summary.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    log("ALL DONE")