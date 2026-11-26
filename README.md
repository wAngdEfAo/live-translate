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

需要 JDK 17、Android SDK（platform 36、build-tools 36.0.0）。

```bash
# 在 local.properties 写入本机 SDK 路径
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug
```

如果使用 arm64 Linux 主机，可能需要在 `gradle.properties` 中通过
`android.aapt2FromMavenOverride` 指定可用的 aapt2 路径。

## 实现要点

| 模块 | 作用 |
|---|---|
| `ScreenReaderService` | 无障碍服务，读取屏幕文本节点及其矩形 |
| `OcrService` | 按需截屏 + ML Kit OCR，补齐节点拿不到的文字与背景取色 |
| `TextPipeline` | 过滤非目标语言文本，判断矩形是否可靠 |
| `Translator` / `LanguagePack` | ML Kit 离线翻译封装，含缓存与模型空闲释放 |
| `OverlayService` | 覆盖层渲染、手势、历史、控制条、前台常驻 |
| `MainActivity` | 权限引导、语言包管理、存储管理 |

## 已知限制

- 银行、支付类 App 常屏蔽无障碍节点，只能依赖 OCR
- 覆盖不到状态栏 / 通知栏
- 部分国产 ROM 会回收无障碍服务，需要手动重新开启
- 这是 debug 签名构建，不适合直接上架应用商店

## 许可

未指定，使用前请联系仓库作者。

---

本软件的代码编写与发布均由 AI 完成。
