# 实时翻译 / Live Translate

Android 屏幕实时翻译：把屏幕上显示的英文**就地**换成中文，只覆盖文字区域，空白与图片不受影响。

## 功能

- 悬浮覆盖式显示：在原文位置用不透明色块盖住英文并显示中文，字号与原文一致
- 只处理屏幕顶部 60% 区域（界面里有分界线标出）
- 双指缩放、拖动、查看最近 3 条历史
- 完全离线：ML Kit 英译中模型，首次下载后无需联网
- 支持多语言：英语/日语/韩语/法语/德语/西班牙语/俄语/葡萄牙语/意大利语/泰语/越南语/阿拉伯语 → 中文
- 自动检测源语言
- 语言包可导出/导入 zip，换机免重新下载
- 隐私：译文只留在内存，不写盘、不上传

## 安装

1. 下载 `app-debug.apk`（Release 页或自行编译）
2. 允许未知来源安装
3. 按 App 内三步引导：无障碍权限 → 悬浮窗权限 → 下载语言包

### 可选：一条命令开启无障碍

```bash
adb shell pm grant com.yg.livetranslate android.permission.WRITE_SECURE_SETTINGS
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
| `Translator` / `LanguagePack` | ML Kit 离线翻译封装，含缓存与模型空闲释放 |
| `OverlayService` | 覆盖层渲染、手势、历史、控制条、前台常驻 |
| `MainActivity` | 权限引导、语言包管理、存储管理 |

## 安全与隐私

本项目**不做任何数据上传**。逐条说明权限用途：

| 权限 | 用途 | 何时触发 |
|---|---|---|
| 无障碍服务 | 读取当前屏幕上的文字与其位置 | 屏幕内容变化且翻译开关开启时 |
| 悬浮窗 | 在原文位置绘制译文覆盖层 | 显示译文时 |
| 屏幕捕获（可选） | 无障碍读不到文字时截图做 OCR 兜底、并采样背景色 | 仅在该界面无可用文字时 |
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
- 使用自签名证书发布，首次安装需允许「未知来源」

## 许可

本项目采用 [MIT License](LICENSE)，可自由使用、修改、分发与商用，保留版权声明即可。

> 说明：APK 内打包的 Google ML Kit 组件受 Google 自身条款约束，不受本项目许可影响。

---

本软件的代码编写与发布均由 AI 完成。
