"""Assemble the runtime model pack: strip dead rules, write manifest, zip it."""
import hashlib
import json
import os
import zipfile

BUILD = os.path.join(os.path.dirname(os.path.abspath(__file__)), "build")
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dist")
OUT_NAME = "live-translate-engine-opus-mt-int8.zip"
PAIRS = [("en_zh", "en", "zh"), ("zh_en", "zh", "en")]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def strip_moses(tok_path):
    d = json.load(open(tok_path, encoding="utf-8"))
    d.pop("moses", None)
    with open(tok_path, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, separators=(",", ":"))
    return len(json.dumps(d))


def main():
    manifest = {
        "format": 1,
        "name": "opus-mt-marian-int8",
        "version": "1.0",
        "license": "CC-BY-4.0 (Helsinki-NLP/opus-mt-en-zh, Helsinki-NLP/opus-mt-zh-en)",
        "note": "Marian NMT, dynamic int8 quantized. Import via the app: 高精度引擎 → 导入模型包.",
        "packs": [],
    }
    for tag, src, tgt in PAIRS:
        d = os.path.join(BUILD, tag)
        tok = os.path.join(d, "pack", "tokenizer.json")
        strip_moses(tok)
        files = {}
        total = 0
        for name in ("encoder.onnx", "decoder.onnx", "tokenizer.json"):
            src_path = os.path.join(d, name) if name.endswith(".onnx") else tok
            src_path = src_path.replace("encoder.onnx", "encoder.int8.onnx") if name == "encoder.onnx" else src_path
            src_path = src_path.replace("decoder.onnx", "decoder.int8.onnx") if name == "decoder.onnx" else src_path
            size = os.path.getsize(src_path)
            total += size
            files[name] = {"size": size, "sha256": sha256(src_path)}
        manifest["packs"].append({"pair": tag, "src": src, "tgt": tgt, "bytes": total, "files": files})
        print(f"[{tag}] total {total/1e6:.1f} MB")

    os.makedirs(OUT_DIR, exist_ok=True)
    out = os.path.join(OUT_DIR, OUT_NAME)
    if os.path.exists(out):
        os.remove(out)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        z.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=1))
        for tag, _, _ in PAIRS:
            d = os.path.join(BUILD, tag)
            z.write(os.path.join(d, "encoder.int8.onnx"), f"packs/{tag}/encoder.onnx",
                    compress_type=zipfile.ZIP_STORED)  # already int8; deflate gains ~1%
            z.write(os.path.join(d, "decoder.int8.onnx"), f"packs/{tag}/decoder.onnx",
                    compress_type=zipfile.ZIP_STORED)
            z.write(os.path.join(d, "pack", "tokenizer.json"), f"packs/{tag}/tokenizer.json",
                    compress_type=zipfile.ZIP_DEFLATED)
    size = os.path.getsize(out)
    print(f"PACK {out}  {size/1e6:.1f} MB  sha256={sha256(out)}")
    with open(out + ".sha256", "w", encoding="ascii") as f:
        f.write(sha256(out) + "\n")
    json.dump(manifest, open(os.path.join(OUT_DIR, "engine-manifest.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    print("PACK DONE")


if __name__ == "__main__":
    main()