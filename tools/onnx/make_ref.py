"""Reference translations from the fp32 PyTorch model, for comparing the int8 ONNX path."""
import json
import os

import torch
from transformers import MarianMTModel, MarianTokenizer

BUILD = os.path.join(os.path.dirname(os.path.abspath(__file__)), "build")

EN = [
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
    "double  space   here",
    "Settings > Network > Proxy",
    "Battery low. Please connect the charger.",
    "The operation could not be completed because the file is in use.",
    "Tap to retry",
    "This app requires access to your photos.",
    "Your session has expired, please sign in again.",
    "Search",
]
ZH = [
    "\u4f60\u597d\uff0c\u4e16\u754c\u3002",
    "\u8bf7\u91cd\u542f\u5e94\u7528\u7a0b\u5e8f\u3002",
    "\u8fde\u63a5\u8d85\u65f6\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u3002",
    "\u8bbe\u7f6e > \u7f51\u7edc > \u4ee3\u7406",
    "\u7535\u91cf\u4e0d\u8db3\uff0c\u8bf7\u8fde\u63a5\u5145\u7535\u5668\u3002",
    "\u64cd\u4f5c\u65e0\u6cd5\u5b8c\u6210\uff0c\u56e0\u4e3a\u6587\u4ef6\u6b63\u5728\u4f7f\u7528\u4e2d\u3002",
    "\u70b9\u51fb\u91cd\u8bd5",
    "\u6b64\u5e94\u7528\u9700\u8981\u8bbf\u95ee\u60a8\u7684\u76f8\u518c\u3002",
    "\u60a8\u7684\u4f1a\u8bdd\u5df2\u8fc7\u671f\uff0c\u8bf7\u91cd\u65b0\u767b\u5f55\u3002",
    "\u641c\u7d22",
]


def run(tag, repo, lines):
    tok = MarianTokenizer.from_pretrained(repo)
    model = MarianMTModel.from_pretrained(repo).eval()
    out = []
    with torch.no_grad():
        for s in lines:
            ids = tok(s, return_tensors="pt").input_ids
            gen = model.generate(ids, max_new_tokens=128, num_beams=1)
            out.append(tok.decode(gen[0], skip_special_tokens=True))
    d = os.path.join(BUILD, tag)
    with open(os.path.join(d, "ref.json"), "w", encoding="utf-8") as f:
        json.dump([{"src": s, "ref": t} for s, t in zip(lines, out)], f,
                  ensure_ascii=False, indent=1)
    print(f"[{tag}] {len(out)} references written")
    for s, t in list(zip(lines, out))[:5]:
        print("  ", ascii(s), "->", ascii(t))


if __name__ == "__main__":
    run("en_zh", "Helsinki-NLP/opus-mt-en-zh", EN)
    run("zh_en", "Helsinki-NLP/opus-mt-zh-en", ZH)
    print("REF DONE")