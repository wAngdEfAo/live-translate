# Live Translate / 实时翻译

[中文](README.md) · **English**

On-screen translation for Android: foreign text already on screen is **replaced in place** with
Chinese or English. Only the text areas are covered — blank space and images are untouched.

## Features

- Overlay rendering: an opaque block covers the original text at its own position and shows the
  translation at the same font size
- Works on one band of the screen (top 60% by default; drag the divider to change it, or flip it
  to work bottom-up)
- Draggable control bar, draggable divider, and the last 3 translations as history
- Fully offline: ML Kit on-device models — no network needed after download
- **58 languages → Chinese, 58 languages → English** (59 languages selectable; the target language
  itself is excluded from each list)
- Chinese ↔ English is direct; other languages pivot through English
  (e.g. Japanese→Chinese = ja→en→zh), which is slower
- Missing models are **not** downloaded in the background — tap “下载” (Download) in the language
  list yourself; size and expected time are shown in the UI
- **Optional high-accuracy engine**: OPUS-MT (Marian) int8, English⇄Chinese both ways, noticeably
  better on long sentences; the model pack is ~294MB and must be downloaded or imported separately
- Automatic source-language detection across all 59 languages, with separate “auto → Chinese” and
  “auto → English” entry points
- Language packs can be exported to / imported from a zip, so switching phones needs no re-download
- Privacy: translations live in memory only — never written to disk, never uploaded

## Install

1. Download `live-translate-vX.Y-release.apk` from the Releases page (or build it yourself)
2. Allow installation from unknown sources
3. Follow the three steps in the app: accessibility permission → overlay permission → download a
   language pack

### Optional: enable the accessibility service with one command

```bash
adb shell pm grant com.shishifanyi.app android.permission.WRITE_SECURE_SETTINGS
```

After this grant the app can toggle its own accessibility service from inside the UI without
touching other accessibility services. See the security notes below before doing it.

## Build

Requirements:

- JDK 17
- Android SDK: platform 36, build-tools 36.0.0
- Gradle 8.11.1

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug     # debug build: not minified, maps 1:1 to the source
./gradlew assembleRelease   # signed release build: needs keystore.properties
```

Signing config belongs in `keystore.properties` (**not in this repo**, already gitignored):

```properties
storeFile=/absolute/path/to/your.jks
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

Without that file the release build is produced unsigned; sign it yourself before distributing.

> On arm64 Linux hosts without a working aapt2, point `android.aapt2FromMavenOverride` in
> `gradle.properties` at a local aapt2 binary.

> On Windows the project path must not contain non-ASCII characters — AGP refuses to build
> (`Your project path contains non-ASCII characters`). Keep it under a pure-ASCII path.

## Implementation notes

| Module | Role |
|---|---|
| `ScreenReaderService` | Accessibility service; reads on-screen text nodes and their bounds |
| `OcrService` | On-demand screen capture + ML Kit OCR, covering text the node tree doesn't expose, plus background colour sampling |
| `TextPipeline` | Filters text that isn't in the source language; decides whether a node's bounds are trustworthy |
| `Translator` / `LanguagePack` / `TransEngine` | ML Kit offline translation wrapper, caching and model management; `TransEngine` is the engine abstraction, `MlKitEngine` the default implementation |
| `OnnxEngine` / `MarianTokenizer` / `EnginePack` | High-accuracy engine: ONNX Runtime inference, pure-Kotlin Marian tokenizer, model-pack import/export |
| `OverlayService` | Overlay rendering, gestures, history, control bar, foreground service |
| `MainActivity` | Permission onboarding, language packs, engine and storage management |

## High-accuracy engine (optional)

The default engine stays ML Kit: fast, 59 languages, offline. There is an optional second path built
on [OPUS-MT](https://huggingface.co/Helsinki-NLP) (Marian, 6 layers, 74M parameters), int8 ONNX,
covering **en→zh / zh→en only**. It is clearly better on long sentences and clearly slower.

- Models are not bundled in the APK: per direction encoder 53MB + decoder 93MB ≈ 148MB,
  ≈294MB for both directions
- How to install: `高精度引擎 → 下载模型包` (downloaded inside the app, Wi-Fi-only by default), or
  bring your own zip and use `导入 zip`
- `导出` before switching phones, then import on the new one
- If the download URL ever breaks: long-press “下载模型包” to copy the URL, fetch it in a browser,
  then use `导入 zip`
- By default only **long sentences** (≥24 characters) use the high-accuracy engine; short UI strings
  stay on ML Kit. The threshold can be disabled in the UI
- `Engines.pick` falls back to ML Kit when no model is installed, the language pair isn't en⇄zh, or
  the text is below the threshold / above 400 characters.
  **A failed inference (corrupt model, session failure, timeout) is retried on ML Kit for that
  segment**, so nothing is left blank
- Paragraphs over 400 characters do not use the high-accuracy engine: without a KV cache decoding is
  O(n²), so long paragraphs blow the time budget

Measured on desktop CPU, 4 threads, no KV cache:

| Metric | Result |
|---|---|
| Size per direction | encoder 52.9MB + decoder 93.4MB (fp32: 210MB + 369MB) |
| Single-sentence latency | p50 30ms (en→zh) / 36ms (zh→en), 96-token cap |
| Agreement with fp32 PyTorch reference | en→zh 12/20 identical, rest synonymous; zh→en 8/10 |
| Tokenizer agreement | 31/31 ids match the real `MarianTokenizer` (see `MarianTokenizerTest`) |

Model licence: CC-BY 4.0 (Helsinki-NLP). Inference uses `onnxruntime-android`, which is why the APK
is ~11MB larger than the ML Kit-only build.

## Security and privacy

This project **uploads nothing**. Every permission and what it is for:

| Permission | Purpose | When it is used |
|---|---|---|
| Accessibility service | Read on-screen text and its position | When screen content changes and the translation switch is on |
| Overlay (draw over other apps) | Draw translation blocks over the original text | Whenever translations are displayed |
| Screen capture (optional) | Screenshot + OCR fallback when accessibility yields no text; also samples background colour | Only when accessibility returns nothing; with “精准模式” (accurate mode) on, every content change |
| Foreground service | Keep the overlay and capture service alive | While the overlay is shown |
| Notifications | Show foreground-service status | Persistent notification, can be dismissed |

### About `WRITE_SECURE_SETTINGS`

The `pm grant` above gives the app the ability to rewrite the system accessibility switches itself
(that is how the in-app one-tap toggle works).

The trade-off is real: **it hands this app control over every accessibility service on the device** —
any process holding this permission can enable or disable accessibility services installed by
others. If you don't need the one-tap toggle, **don't run that command**; enabling accessibility
manually in system settings gives identical behaviour.

### What's inside an exported language-pack zip

Export packs three things from the app's private directory into one zip:

- `files/` — the app's own config and runtime files
- `nobackup/` — ML Kit model directory (`com.google.mlkit.translate.models/dict.*/`), the part that
  saves a re-download on a new phone
- `cache/` — temporary files

It does **not** contain `shared_prefs/`: ML Kit's model registration state is not packed, the app
rewrites it from the model directory on import. It also contains no screen content, source text, or
translations.

Data flow:

- Translation happens entirely on **local models**; screen content never leaves the device
- Translations live in memory only, at most the last 3, never written to disk
- No ads, no analytics SDK, no third-party reporting
- The only network request is the initial language-pack download (importing a zip works offline)

You can verify this yourself: the source is MIT-licensed and debug builds are not minified, so you
can compile it and compare behaviour against the release APK.

## Known limitations

- Banking and payment apps often block accessibility nodes; only OCR works there
- The status bar and notification shade cannot be covered
- Some Chinese OEM ROMs kill accessibility services; re-enable manually
- The overlay does not keep the screen awake (no `FLAG_KEEP_SCREEN_ON`, battery first)
- Password and OTP fields are never translated (accessibility skips `isPassword`; OCR skips
  dot/asterisk rows)
- Non-English → Chinese pivots through English, requiring two models (~70MB) and roughly double the
  latency
- “Auto detect” is still in testing: short or mixed-script text may be misdetected, producing wrong
  translations or extra latency
- Released with a self-signed certificate, so the first install needs “unknown sources”
- The UI is currently Chinese only

## Feedback

- GitHub Issues: <https://github.com/wAngdEfAo/live-translate/issues>
- Email: <qzwtml@outlook.com>

The “反馈” (Feedback) card in the app opens either link and pre-fills version, Android version and
device model.

When reporting a problem, describe the symptom and the steps to reproduce. These details help most:

- Which screen / which app the problem appeared on (banking and payment apps block accessibility
  nodes, so only OCR applies)
- Whether other accessibility services are running on the same screen
- Whether turning off “精准模式” (accurate mode) or “OCR 兜底” (OCR fallback) changes anything

## Latency benchmark (compare versions using the same sentence)

To compare high-accuracy speed across versions, measure with one fixed sentence:
`翻译与语言 → 高精度 → 高级 → 填入基准句 → 精翻这段`, and **wait for each result before tapping again**
(tapping repeatedly queues requests, which inflates the measurement). Then paste the
`高精度延迟` lines from `关于 → 复制诊断信息`.

The benchmark sentence is chosen by target language (`Latency.benchFor`):

- Target = Chinese (en→zh): `The committee postponed the vote because several members asked for more
  time to review the proposed budget.` (~20 words / 110 chars)
- Target = English (zh→en): `委员会推迟了表决，因为几位成员要求有更多时间审阅这份预算提案，会议因此顺延到下周同一时间继续。` (~40 chars)

Changing either sentence makes results incomparable. Long-press “填入基准句” to clear the current
direction's samples.

Recording rules: only **decode** time is recorded (queueing and the first model load are excluded —
see `OnnxEngine.translateFresh(onStart)`); at most 7 samples per direction, and when there are ≥4
samples the maximum is dropped before taking the median (one scheduling hiccup or one
loading-inclusive sample is enough to shift the median of five samples). The diagnostics text also
lists min / median / max, each sample's input length, and the raw millisecond values.

## Versions and changes

Per-version changes, checksums and download links live in GitHub Releases:
<https://github.com/wAngdEfAo/live-translate/releases>

## License

[MIT License](LICENSE) — free to use, modify, distribute and sell, as long as the copyright notice is
kept.

> Note: the Google ML Kit components bundled in the APK are governed by Google's own terms and are
> not covered by this project's licence.

---

The code and releases of this software are produced by AI.