"""Dynamic int8 quantization for the exported Marian graphs (runs on Python 3.12 + ORT)."""
import json
import os
import sys
import time

from onnxruntime.quantization import QuantType, quantize_dynamic

BUILD = os.path.join(os.path.dirname(os.path.abspath(__file__)), "build")


def log(*a):
    print(*a, flush=True)


def main():
    for tag in ("en_zh", "zh_en"):
        d = os.path.join(BUILD, tag)
        meta = json.load(open(os.path.join(d, "meta.json"), encoding="utf-8"))
        for name in ("encoder", "decoder"):
            src = os.path.join(d, name + ".onnx")
            dst = os.path.join(d, name + ".int8.onnx")
            if os.path.exists(dst) and os.path.getsize(dst) > 0:
                log(f"[{tag}] {name}: already quantized ({os.path.getsize(dst)} B)")
            else:
                t0 = time.time()
                log(f"[{tag}] quantizing {name} ({os.path.getsize(src)/1e6:.0f} MB) ...")
                quantize_dynamic(
                    model_input=src,
                    model_output=dst,
                    weight_type=QuantType.QInt8,
                    per_channel=True,
                    reduce_range=False,
                    extra_options={"EnableSubgraph": False},
                )
                log(f"[{tag}] {name}: {os.path.getsize(dst)/1e6:.1f} MB in {time.time()-t0:.0f}s")
            meta.setdefault("files_int8", {})[name + ".onnx"] = os.path.getsize(dst)
        json.dump(meta, open(os.path.join(d, "meta.json"), "w", encoding="utf-8"),
                  ensure_ascii=False, indent=2)
        log(f"[{tag}] {json.dumps(meta['files_int8'])}")
    log("QUANTIZE DONE")


if __name__ == "__main__":
    main()