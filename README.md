# 实时翻译 / Live Translate

Android 屏幕实时翻译：把屏幕上显示的外文**就地**换成中文或英文，只覆盖文字区域，空白与图片不受影响。

## 功能

- 悬浮覆盖式显示：在原文位置用不透明色块盖住原文并显示译文，字号与原文一致
- 只处理屏幕上的一段区域（默认顶部 60%，可拖动分界线调整比例、也能切成从下往上）
- 拖动控制条、上下拖分界线调整处理区域、查看最近 3 条历史
- 完全离线：ML Kit 本地模型，下载后无需联网
- **58 种语言 → 中文，58 种语言 → 英文**（共 59 种语言可选，各自排除目标语言自己）
- 中文 ↔ 英文直连；其他语言经英语中转（如 日语→中文 = 日→英→中），速度略慢
- 缺模型时**不会**在后台自动下载，请在语言列表里手动点「下载」（体积与耗时都写在界面上）
- **可选高精度引擎**：OPUS-MT（Marian）int8 模型，英⇄中双向，句子更通顺；模型包约 294MB，需单独导入
- 自动检测源语言：识别池覆盖全部 59 种，自动检测 → 中文 / → 英文 两个独立入口
- 语言包可导出/导入 zip，换机免重新下载
- 隐私：译文只留在内存，不写盘、不上传

## 安装

1. 下载 Release 页里的 `live-translate-vX.Y-release.apk`（或自行编译）
2. 允许未知来源安装
3. 按 App 内三步引导：无障碍权限 → 悬浮窗权限 → 下载语言包

### 可选：一条命令开启无障碍

```bash
adb shell pm grant com.shishifanyi.app android.permission.WRITE_SECURE_SETTINGS
```

授权后可在 App 内一键开关无障碍，且不会影响其他无障碍服务。

## 编译

环境要求：

- JDK 17
- Android SDK：platform 36、build-tools 36.0.0
- Gradle 8.11.1

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug     # 调试包：不混淆，产物与源码一一对应
./gradlew assembleRelease   # 正式签名包：需 keystore.properties
```

签名配置放在 `keystore.properties`（**不在仓库中**，已在 .gitignore 忽略）：

```properties
storeFile=/absolute/path/to/your.jks
storePassword=你的密钥库密码
keyAlias=你的别名
keyPassword=你的密钥密码
```

缺少该文件时 release 以未签名方式产出，需自行签名后分发。

> arm64 Linux 主机若缺少可用 aapt2，可在 `gradle.properties` 中通过
> `android.aapt2FromMavenOverride` 指向本地 aapt2 路径。

> Windows 上项目路径不能含非 ASCII 字符：AGP 会直接拒绝构建
> （`Your project path contains non-ASCII characters`）。请放在纯英文路径下。

## 实现要点

| 模块 | 作用 |
|---|---|
| `ScreenReaderService` | 无障碍服务，读取屏幕文本节点及其矩形 |
| `OcrService` | 按需截屏 + ML Kit OCR，补齐节点拿不到的文字与背景取色 |
| `TextPipeline` | 过滤非目标语言文本，判断矩形是否可靠 |
| `Translator` / `LanguagePack` / `TransEngine` | ML Kit 离线翻译封装、缓存与模型管理；`TransEngine` 是引擎抽象，`MlKitEngine` 是默认实现 |
| `OnnxEngine` / `MarianTokenizer` / `EnginePack` | 高精度引擎：ONNX Runtime 推理、纯 Kotlin 的 Marian 分词、模型包导入导出 |
| `OverlayService` | 覆盖层渲染、手势、历史、控制条、前台常驻 |
| `MainActivity` | 权限引导、语言包管理、引擎与存储管理 |

## 高精度引擎（可选）

默认引擎仍是 ML Kit：快、支持 59 种语言、离线。另有一条可选的高精度路径，
用 [OPUS-MT](https://huggingface.co/Helsinki-NLP)（Marian，6 层，74M 参数）的 int8 ONNX 模型，
只做**英→中 / 中→英**两个方向，长句明显比 ML Kit 通顺，代价是慢。

- 模型不进 APK：单方向 encoder 53MB + decoder 93MB ≈ 148MB，双向合计约 294MB
- 装法：`高精度引擎 → 下载模型包`（App 内直接下，仅 Wi-Fi 可关），或自己下载 zip 后 `导入 zip`
- 换机前可 `导出` 成 zip，再在新机导入
- 地址失效时：长按「下载模型包」复制地址，用浏览器下载后走 `导入 zip`
- 默认只在**长句**（≥24 字符）上用高精度，短 UI 文案仍走 ML Kit；可在界面关掉这个限制
- 没装模型、语言对不是英⇄中、文本短于阈值或长于 400 字符时，`Engines.pick` 直接选 ML Kit；
  **推理失败（模型损坏 / 会话加载不出来 / 超时）会自动用 ML Kit 重试那一段**，不会留空
- 长于 400 字符的段落不走高精度：无 KV cache 的解码是 O(n²)，长段落必然超预算

模型与推理链路的实测数据（桌面 CPU，4 线程，无 KV cache）：

| 指标 | 结果 |
|---|---|
| 单方向体积 | encoder 52.9MB + decoder 93.4MB（fp32 为 210MB + 369MB） |
| 单句延迟 | p50 30ms（英→中）/ 36ms（中→英），96 token 上限 |
| 与 fp32 PyTorch 参考的一致性 | 英→中 12/20 完全相同，其余为同义改写；中→英 8/10 |
| 分词一致性 | 与真实 `MarianTokenizer` 逐 id 对比 31/31 一致（见 `MarianTokenizerTest`） |

模型许可证 CC-BY 4.0（Helsinki-NLP）。推理用 `onnxruntime-android`，因此 APK 比纯 ML Kit 版大约多 11MB。

## 安全与隐私

本项目**不做任何数据上传**。逐条说明权限用途：

| 权限 | 用途 | 何时触发 |
|---|---|---|
| 无障碍服务 | 读取当前屏幕上的文字与其位置 | 屏幕内容变化且翻译开关开启时 |
| 悬浮窗 | 在原文位置绘制译文覆盖层 | 显示译文时 |
| 屏幕捕获（可选） | 无障碍读不到文字时截图做 OCR 兜底、并采样背景色 | 无障碍读不到时；开了「精准模式」则每次内容变化都截 |
| 前台服务 | 保持悬浮层与截屏服务存活 | 悬浮窗开启期间 |
| 通知 | 显示前台服务状态 | 常驻通知，可关闭 |

### 关于 `WRITE_SECURE_SETTINGS`

上面那条 `pm grant` 授权之后，App 才有能力自己改写系统的无障碍开关（「一键开关」就是这么实现的）。

代价要说清楚：**这等于把设备上所有无障碍服务的控制权交给这个 App**——任何持有该权限的进程都能开启或关闭别人安装的无障碍服务。
如果不需要一键开关，**不要执行这条命令**，照常用系统设置手动开启无障碍，功能完全一样。

### 语言包导出 zip 里有什么

导出会把 App 私有目录下的三类内容打成一个 zip：

- `files/`——App 自己的配置与运行期文件
- `nobackup/`——ML Kit 模型目录（`com.google.mlkit.translate.models/dict.*/`），换机免下载的关键
- `cache/`——临时文件

**不包含** `shared_prefs/`：ML Kit 的模型登记状态不打包，导入时由 App 按模型目录重新补写。
也不包含任何屏幕内容、原文或译文。

数据流向：

- 翻译全程在**本地模型**完成，屏幕内容不出设备
- 译文只存在于内存，最多保留最近 3 条，不写磁盘
- 无广告、无统计 SDK、无第三方上报
- 唯一的网络请求是首次下载语言包（也可用导入 zip 离线完成）

可自行验证：源码 MIT 公开、debug 构建不混淆，可自行编译后与本 Release APK 比对代码行为。

## 已知限制

- 银行、支付类 App 常屏蔽无障碍节点，只能依赖 OCR
- 覆盖不到状态栏 / 通知栏
- 部分国产 ROM 会回收无障碍服务，需要手动重新开启
- 覆盖层不会阻止系统熄屏（未加 `FLAG_KEEP_SCREEN_ON`，优先省电）
- 密码 / 验证码输入框不会翻译（无障碍侧按 `isPassword` 跳过，OCR 侧跳过圆点/星号行）
- 非英语语言翻中文要经英语中转，需要两个模型（约 70MB），延迟约为直连的两倍
- 「自动检测」仍在测试阶段：短文本、混排文本的识别可能出错，导致翻译不准确或耗时变长
- 使用自签名证书发布，首次安装需允许「未知来源」

## 反馈

- GitHub Issues：<https://github.com/wAngdEfAo/live-translate/issues>
- 邮件：<qzwtml@outlook.com>

App 里的「反馈」卡片可以直接打开这两个入口，并把版本号、Android 版本、机型自动填进正文。

报问题时请写清现象和复现步骤，另外这几个信息最有帮助：

- 是哪个界面 / 哪个 App 上出的问题（银行、支付类 App 常屏蔽无障碍节点，只能走 OCR）
- 同一屏幕上其他无障碍服务是否也在运行
- 关掉「精准模式」或「OCR 兜底」后是否复现

## 高精度延迟基准（跨版本对比用同一句）

想比较不同版本的高精度速度时，用同一句固定文本量： 「翻译与语言 → 高精度 → 高级 →
填入基准句 → 精翻这段」，**每点一次等结果出来再点下一次**
（同时点多次会把请求排队，量出来的时间会被算大），
然后把「关于 → 复制诊断信息」里的 `高精度延迟` 几行贴出来。

基准句按当前目标语言自动选（`Latency.benchFor`）：

- 目标=中文（en→zh）：`The committee postponed the vote because several members asked for more time to review the proposed budget.`（~20 词 / 110 字符）
- 目标=英文（zh→en）：`委员会推迟了表决，因为几位成员要求有更多时间审阅这份预算提案，会议因此顺延到下周同一时间继续。`（~40 字）

改动这两句即失去可比性。长按「填入基准句」可清空当前方向的样本重新统计。

记录规则：只记**解码**耗时（排队与首次加载不计入，见 `OnnxEngine.translateFresh(onStart)`）；
每个方向最多 7 个样本，样本 ≥4 时**去掉最大值**再取中位数
（一次调度抖动或一次含加载的样本就足以把 5 个样本的中位数带偏）；
诊断信息里同时给 min / median / max、每个样本的输入字符数与原始毫秒数。

## 版本与变更

每个版本的改动、校验值与下载地址都在 GitHub Releases：
<https://github.com/wAngdEfAo/live-translate/releases>

## 许可

本项目采用 [MIT License](LICENSE)，可自由使用、修改、分发与商用，保留版权声明即可。

> 说明：APK 内打包的 Google ML Kit 组件受 Google 自身条款约束，不受本项目许可影响。

---

本软件的代码编写与发布均由 AI 完成。

