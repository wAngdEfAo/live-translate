"""Run the int8 ONNX path end-to-end with the exact runtime tokenizer (Python 3.12 + ORT).

This is the M1 gate: if the numbers or the text are wrong here, the Android port has no
point. The tokenizer code below is a line-by-line preview of what the Kotlin will do.
"""
import json
import os
import re
import sys
import time
import unicodedata

import numpy as np
import onnxruntime as ort

BUILD = os.path.join(os.path.dirname(os.path.abspath(__file__)), "build")
SPACE = "\u2581"


def log(*a):
    print(*a, flush=True)


class Tok:
    def __init__(self, path):
        d = json.load(open(path, encoding="utf-8"))
        self.order = d.get("normalize_order", "nfkc_first")
        self.cmap = {cp: s for cp, s in d["char_map"]}
        self.protect = d.get("protect") or []
        self.pieces = d["spm"]["pieces"]
        self.scores = d["spm"]["scores"]
        self.id_of = {}
        for i, p in enumerate(self.pieces):
            self.id_of.setdefault(p, i)
        self.vocab = d["vocab"]
        self.piece2id = {}
        for i, p in enumerate(self.vocab):
            if p is not None:
                self.piece2id.setdefault(p, i)
        sp = d["specials"]
        self.unk, self.eos, self.pad = sp["unk"], sp["eos"], sp["pad"]
        self.decoder_start = sp["decoder_start"]
        self.moses = [(re.compile(p), r) for p, r in (d.get("moses") or [])]
        self.max_piece = max(len(p) for p in self.pieces)
        self.min_score = min(self.scores) - 10.0

    def normalize(self, text):
        s = text
        for pat, rep in self.moses:
            s = pat.sub(rep, s)
        s = s.strip()
        if self.order == "nfkc_first":
            if self.protect:
                holder = {cp: chr(0xE0000 + i) for i, cp in enumerate(self.protect)}
                back = {v: cp for cp, v in holder.items()}
                s = "".join(holder.get(ord(ch), ch) for ch in s)
                s = unicodedata.normalize("NFKC", s)
                s = "".join(chr(back[ord(ch)]) if ord(ch) in back else ch for ch in s)
            else:
                s = unicodedata.normalize("NFKC", s)
            s = "".join(self.cmap.get(ord(ch), ch) for ch in s)
        else:
            s = "".join(self.cmap.get(ord(ch), ch) for ch in s)
            s = unicodedata.normalize("NFKC", s)
        s = " ".join(s.split())
        s = s.replace(" ", SPACE)
        return SPACE + s

    def encode(self, text):
        norm = self.normalize(text)
        n = len(norm)
        best = [float("-inf")] * (n + 1)
        step_len = [1] * (n + 1)
        step_piece = [None] * (n + 1)
        if n:
            best[n] = 0.0
        for i in range(n - 1, -1, -1):
            bs = float("-inf")
            for l in range(1, min(self.max_piece, n - i) + 1):
                sub = norm[i:i + l]
                if sub in self.id_of:
                    sc = self.scores[self.id_of[sub]] + best[i + l]
                    if sc > bs:
                        bs, step_len[i], step_piece[i] = sc, l, sub
                elif l == 1:
                    sc = self.min_score + best[i + 1]
                    if sc > bs:
                        bs, step_len[i], step_piece[i] = sc, 1, None
            best[i] = bs
        ids = []
        i = 0
        while i < n:
            if step_piece[i] is None:
                j = i + 1
                while j < n and step_piece[j] is None:
                    j += 1
                ids.append(self.piece2id.get(norm[i:j], self.unk))
                i = j
            else:
                ids.append(self.piece2id.get(step_piece[i], self.unk))
                i += step_len[i]
        ids.append(self.eos)
        return ids

    def decode(self, ids):
        out = []
        for i in ids:
            if i == self.eos or i == self.pad:
                break
            p = self.vocab[i] if 0 <= i < len(self.vocab) else None
            out.append(p if p is not None else "")
        return "".join(out).replace(SPACE, " ").strip()


def greedy(tok, sess_enc, sess_dec, text, max_new=96):
    src_ids = tok.encode(text)
    ids = np.array([src_ids], dtype=np.int64)
    mask = np.ones_like(ids)
    hidden = sess_enc.run(None, {"input_ids": ids, "attention_mask": mask})[0]
    cur = [tok.decoder_start]
    steps = 0
    for _ in range(max_new):
        dec = np.array([cur], dtype=np.int64)
        logits = sess_dec.run(None, {
            "decoder_input_ids": dec,
            "encoder_hidden_states": hidden,
            "encoder_attention_mask": mask,
        })[0]
        nxt = int(np.argmax(logits[0, -1]))
        steps += 1
        if nxt == tok.eos:
            break
        cur.append(nxt)
    return tok.decode(cur[1:]), steps, len(src_ids)


def run(tag):
    d = os.path.join(BUILD, tag)
    tok = Tok(os.path.join(d, "pack", "tokenizer.json"))
    so = ort.SessionOptions()
    so.intra_op_num_threads = 4
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    log(f"[{tag}] loading int8 sessions ...")
    t0 = time.time()
    enc = ort.InferenceSession(os.path.join(d, "encoder.int8.onnx"), so)
    dec = ort.InferenceSession(os.path.join(d, "decoder.int8.onnx"), so)
    log(f"[{tag}] loaded in {time.time()-t0:.1f}s")

    ref = json.load(open(os.path.join(d, "ref.json"), encoding="utf-8"))
    same = 0
    times = []
    for row in ref:
        t1 = time.time()
        out, steps, nsrc = greedy(tok, enc, dec, row["src"])
        dt = (time.time() - t1) * 1000
        times.append(dt)
        hit = "=" if out == row["ref"] else "~"
        if out == row["ref"]:
            same += 1
        log(f"  {hit} {ascii(row['src'])[:48]:50s} int8={ascii(out)[:60]:62s} ref={ascii(row['ref'])[:60]:62s} {dt:5.0f}ms {steps} steps")
    times.sort()
    p50 = times[len(times) // 2]
    log(f"[{tag}] exact match {same}/{len(ref)}  p50={p50:.0f}ms  p90={times[int(len(times)*0.9)-1]:.0f}ms  max={times[-1]:.0f}ms")
    return same, len(ref), p50


if __name__ == "__main__":
    res = {}
    for tag in ("en_zh", "zh_en"):
        res[tag] = run(tag)
    log(json.dumps(res))
    log("VALIDATE DONE")