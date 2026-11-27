"""Build the runtime tokenizer pack and prove my reimplementation matches sentencepiece.

Output per direction (work/onnx/build/<tag>/pack/):
  tokenizer.json  : char map + spm unigram table + marian vocab + moses rules + specials
  golden.json     : (text -> normalized / ids / decoded) cases for the Kotlin unit test
  compat.json     : mismatch report from the verification sweep
"""
import json
import os
import sys
import unicodedata

import sentencepiece as spm
from sacremoses import MosesPunctNormalizer
from transformers import MarianTokenizer

BUILD = os.path.join(os.path.dirname(os.path.abspath(__file__)), "build")

SPACE = "\u2581"


def log(*a):
    print(*a, flush=True)


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
]


def char_map(sp):
    """cp -> replacement text (already un-escaped), for every code point that changes.

    Whitespace is skipped on purpose: it is handled by the normalize pipeline
    (collapse runs -> single space -> \\u2581), and including it here would delete
    every space (sp.normalize(" ") is just the dummy prefix).
    """
    out = {}
    rng = list(range(0x0, 0xD800)) + list(range(0xE000, 0x30000))
    for cp in rng:
        ch = chr(cp)
        if ch.isspace():
            continue
        try:
            norm = sp.normalize(ch)
        except Exception:
            continue
        # normalize() also escapes whitespace and adds the dummy prefix; undo both
        if norm.startswith(SPACE):
            norm = norm[len(SPACE):]
        norm = norm.replace(SPACE, " ")
        if norm != ch:
            out[cp] = norm
    return out


def apply_char_map(text, cmap):
    return "".join(cmap.get(ord(ch), ch) for ch in text)


def build_protect(sp, cmap):
    """Code points that NFKC would rewrite but sentencepiece keeps verbatim.

    Punctuation normalization inside sentencepiece is _not_ plain NFKC: it keeps
    e.g. U+FF5E and U+32FF as-is. Guarding them (swap out before NFKC, swap back
    after) makes my pipeline match without enumerating composition pairs.
    """
    out = []
    rng = list(range(0x0, 0xD800)) + list(range(0xE000, 0x30000))
    for cp in rng:
        ch = chr(cp)
        if ch.isspace() or cp in cmap:
            continue
        if unicodedata.normalize("NFKC", ch) != ch:
            out.append(cp)
    return out


def my_normalize(text, cmap, order="map_first", protect=(), add_dummy_prefix=True,
                 escape_ws=True, remove_extra_ws=True):
    s = text
    if order == "nfkc_first":
        if protect:
            holder = {cp: chr(0xE0000 + i) for i, cp in enumerate(protect)}
            back = {v: cp for cp, v in holder.items()}
            s = "".join(holder.get(ord(ch), ch) for ch in s)
            s = unicodedata.normalize("NFKC", s)
            s = "".join(chr(back[ord(ch)]) if ord(ch) in back else ch for ch in s)
        else:
            s = unicodedata.normalize("NFKC", s)
        s = apply_char_map(s, cmap)
    else:
        s = apply_char_map(s, cmap)
        s = unicodedata.normalize("NFKC", s)
    if remove_extra_ws:
        s = " ".join(s.split())
    if escape_ws:
        s = s.replace(" ", SPACE)
    if add_dummy_prefix:
        s = SPACE + s
    return s


def viterbi(norm, pieces, scores, id_of):
    """sentencepiece unigram segmentation; unknown chars come back as raw single chars."""
    n = len(norm)
    if n == 0:
        return []
    max_len = max(len(p) for p in pieces)
    min_score = min(scores) - 10.0
    best = [float("-inf")] * (n + 1)
    step_len = [1] * (n + 1)
    step_piece = [None] * (n + 1)
    best[n] = 0.0
    for i in range(n - 1, -1, -1):
        bs = float("-inf")
        for l in range(1, min(max_len, n - i) + 1):
            sub = norm[i:i + l]
            if sub in id_of:
                sc = scores[id_of[sub]] + best[i + l]
                if sc > bs:
                    bs, step_len[i], step_piece[i] = sc, l, sub
            elif l == 1:
                sc = min_score + best[i + 1]
                if sc > bs:
                    bs, step_len[i], step_piece[i] = sc, 1, None
        best[i] = bs
    out = []
    i = 0
    while i < n:
        if step_piece[i] is None:
            # merge a run of unknown chars into one raw piece, like EncodeAsPieces does
            j = i + 1
            while j < n and step_piece[j] is None:
                j += 1
            out.append(norm[i:j])
            i = j
        else:
            out.append(step_piece[i])
            i += step_len[i]
    return out


def build(tag: str, repo: str):
    log(f"[{tag}] loading tokenizer {repo}")
    tok = MarianTokenizer.from_pretrained(repo)
    sp = tok.current_spm
    vocab = dict(tok.current_encoder)  # piece -> id
    id_to_piece = [None] * (max(vocab.values()) + 1)
    for p, i in vocab.items():
        id_to_piece[i] = p

    pieces = [sp.id_to_piece(i) for i in range(sp.get_piece_size())]
    scores = [float(sp.get_score(i)) for i in range(sp.get_piece_size())]
    id_of = {}
    for i, p in enumerate(pieces):
        id_of.setdefault(p, i)
    unk = int(tok.unk_token_id)

    cmap = char_map(sp)
    protect = build_protect(sp, cmap)
    log(f"[{tag}] char map entries: {len(cmap)}  protected cps: {len(protect)}")

    # ---- compatibility sweep: my pipeline vs sentencepiece + marian vocab ----
    probes = list(CORPUS)
    for lo, hi in ((0x0, 0x33FF), (0xFF00, 0xFFEF)):
        for cp in range(lo, hi):
            ch = chr(cp)
            if ch.isspace() or ch == "\x00":
                continue
            probes.append(ch + "a")
            probes.append("a" + ch)
            probes.append("x " + ch + " y")

    stats = {}
    for order in ("map_first", "nfkc_first"):
        mism_norm = 0
        mism_pieces = 0
        mism_ids = 0
        samples = []
        for t in probes:
            mine_norm = my_normalize(t, cmap, order, protect)
            theirs_norm = sp.normalize(t)
            if mine_norm != theirs_norm:
                mism_norm += 1
                if len(samples) < 12:
                    samples.append(["norm/" + order, ascii(t), ascii(mine_norm), ascii(theirs_norm)])
            mine_pieces = viterbi(mine_norm, pieces, scores, id_of)
            theirs_pieces = sp.encode_as_pieces(t)
            if mine_pieces != theirs_pieces:
                mism_pieces += 1
                if len(samples) < 24:
                    samples.append(["pieces/" + order, ascii(t),
                                    [ascii(x) for x in mine_pieces[:10]],
                                    [ascii(x) for x in theirs_pieces[:10]]])
            mine_ids = [vocab.get(p, unk) for p in mine_pieces]
            # reference ids only for the corpus: calling tok() 40k times is far too slow,
            # and piece equality already implies id equality through the same vocab
            if t in CORPUS and mine_ids + [int(tok.eos_token_id)] != tok(t)["input_ids"]:
                mism_ids += 1
        stats[order] = {"norm": mism_norm, "pieces": mism_pieces, "ids": mism_ids,
                        "samples": samples}
        log(f"[{tag}] order={order}: norm_mismatch={mism_norm} pieces_mismatch={mism_pieces} "
            f"ids_mismatch={mism_ids} / {len(probes)}")
    best = min(stats.items(), key=lambda kv: kv[1]["norm"] + kv[1]["pieces"] + kv[1]["ids"])
    order = best[0]
    log(f"[{tag}] choosing order={order}")

    moses = MosesPunctNormalizer("en")
    rules = [[pat, repl.replace("\\g<", "$").replace(">", "")] for pat, repl in moses.substitutions]

    pack = {
        "version": 2,
        "normalize_order": order,
        "protect": list(protect),
        "specials": {
            "unk": int(tok.unk_token_id),
            "eos": int(tok.eos_token_id),
            "pad": int(tok.pad_token_id),
            "decoder_start": 65000,
            "max_len": 512,
        },
        "char_map": [[cp, s] for cp, s in sorted(cmap.items())],
        "spm": {"pieces": pieces, "scores": scores},
        "vocab": id_to_piece,
        "moses": rules,
    }
    d = os.path.join(BUILD, tag, "pack")
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "tokenizer.json"), "w", encoding="utf-8") as f:
        json.dump(pack, f, ensure_ascii=False, separators=(",", ":"))

    # ---- golden cases for the Kotlin unit test ----
    golden = []
    for t in CORPUS:
        norm = sp.normalize(t)
        gold_pieces = sp.encode_as_pieces(t)
        ids = [vocab.get(p, int(tok.unk_token_id)) for p in gold_pieces] + [int(tok.eos_token_id)]
        golden.append({
            "text": t,
            "moses": moses.normalize(t),
            "normalized": norm,
            "pieces": gold_pieces,
            "ids": ids,
            "decoded": "".join(gold_pieces).replace(SPACE, " ").strip(),
        })
    with open(os.path.join(d, "golden.json"), "w", encoding="utf-8") as f:
        json.dump(golden, f, ensure_ascii=False, indent=1)
    with open(os.path.join(d, "compat.json"), "w", encoding="utf-8") as f:
        json.dump({"probes": len(probes), "chosen": order,
                   "orders": {k: {kk: vv for kk, vv in v.items() if kk != "samples"}
                              for k, v in stats.items()},
                   "samples": best[1]["samples"]},
                  f, ensure_ascii=False, indent=1)

    log(f"[{tag}] pack written: tokenizer={os.path.getsize(os.path.join(d,'tokenizer.json'))/1e6:.2f} MB")
    return {"probes": len(probes), "chosen": order,
            "norm_mismatch": best[1]["norm"], "pieces_mismatch": best[1]["pieces"],
            "ids_mismatch": best[1]["ids"]}


if __name__ == "__main__":
    res = {}
    for tag, repo in (("en_zh", "Helsinki-NLP/opus-mt-en-zh"),
                      ("zh_en", "Helsinki-NLP/opus-mt-zh-en")):
        res[tag] = build(tag, repo)
    log(json.dumps(res, ensure_ascii=False, indent=1))
    log("PACK DONE")