"""Regenerate golden.json with the full runtime expectation (Moses then sentencepiece)."""
import json
import os

from transformers import MarianTokenizer

BUILD = os.path.join(os.path.dirname(os.path.abspath(__file__)), "build")
SPACE = "\u2581"


def regen(tag, repo, corpus):
    tok = MarianTokenizer.from_pretrained(repo)
    sp = tok.current_spm
    vocab = dict(tok.current_encoder)
    pad = int(tok.pad_token_id)
    eos = int(tok.eos_token_id)
    rows = []
    for t in corpus:
        # 不做 Moses：transformers 的 __call__ 路径本身也不做，模型吃的是原始标点
        pieces = sp.encode_as_pieces(t)
        ids = [vocab.get(p, int(tok.unk_token_id)) for p in pieces] + [eos]

        rows.append({
            "text": t,
            "normalized": sp.normalize(t),
            "pieces": pieces,
            "ids": ids,
            "decoded": decode_from_ids(tok, ids),
            "reference_ids": tok(t)["input_ids"],
        })
    out = os.path.join(BUILD, tag, "pack", "golden.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=1)
    same = sum(1 for r in rows if r["ids"] == r["reference_ids"])
    print(f"[{tag}] golden regenerated: {len(rows)} rows, ids==reference {same}/{len(rows)} -> {out}")


def decode_from_ids(tok, ids):
    """模型侧的 id -> 文本：跳 eos/pad，▁ 还原空格。"""
    inv = {v: k for k, v in dict(tok.current_encoder).items()}
    eos = int(tok.eos_token_id)
    pad = int(tok.pad_token_id)
    out = []
    for i in ids:
        if i == eos or i == pad:
            break
        out.append(inv.get(i, ""))
    return "".join(out).replace(SPACE, " ").strip()


CORPUS = [
    "Hello world.",
    "The quick brown fox jumps over the lazy dog.",
    "Please restart the application.",
    "Connection timed out after 30 seconds.",
    "I can't find the settings menu.",
    "He said \u201chello\u201d and left.",
    "It\u2019s 50% off \u2014 today only!",
    "Wait\u2026 what?",
    "Press (OK) to continue.",
    "v1.2.3 \u2013 build 45",
    "caf\u00e9 na\u00efve r\u00e9sum\u00e9",
    "100 % sure",
    "  leading and trailing  ",
    "double  space   here",
    "line\nbreak\tand tab",
    "\uff21\uff22\uff23 \uff10\uff11\uff12",
    "\u300c\u4f60\u597d\u300d\u3068\u8a00\u3063\u305f",
    "\u4f60\u597d\uff0c\u4e16\u754c\u3002",
    "\u0440\u0443\u0441\u0441\u043a\u0438\u0439 \u0442\u0435\u043a\u0441\u0442",
    "\u0645\u0631\u062d\u0628\u0627 \u0628\u0627\u0644\u0639\u0627\u0644\u0645",
    "emoji \U0001F600 test",
    "a\u00a0b\u00a0c",
    "quote \"inside\" and 'apostrophe'",
    "dash - hyphen -- double",
    "slash/a\\b c",
    "[\u4e2d\u6587] (test) {x}",
    "Wi\u2011Fi enabled",
    "\u00bd \u00bc \u00be fractions",
    "\uff5e\uff01\uff1f\uff1a\uff1b",
    "Tabs\t\tand   spaces",
    "   ",
]

if __name__ == "__main__":
    regen("en_zh", "Helsinki-NLP/opus-mt-en-zh", CORPUS)
    print("GOLDEN DONE")