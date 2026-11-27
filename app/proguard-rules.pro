-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-keepattributes *Annotation*
-keepattributes Signature

# ONNX Runtime 的 JNI 按名字查找 Java 类与方法（libonnxruntime4j_jni.so 里有
# FindClass("ai/onnxruntime/OrtException") 和 444 个 Java_ai_onnxruntime_* 符号）。
# onnxruntime-android 的 AAR 不带 consumer proguard 规则，R8 一改名就会：
#   ai.onnxruntime.OrtException -> a.e  →  错误路径 FindClass 失败 → NoClassDefFoundError
# 所以整包保留，不参与混淆与裁剪。
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
