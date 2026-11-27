import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名信息放在 keystore.properties（不入库）；缺失时 release 不签名
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.shishifanyi.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shishifanyi.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 77
        versionName = "5.5.9"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        // debug 保持常规：不混淆，产物与源码一一对应，便于审计
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/*.version",
            "META-INF/*.md",
            "META-INF/*.txt",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1"
        )
        jniLibs {
            // 保持 true：直接发 APK 时下载体积优先。
            // 实测 false 会让 APK 从 24.7MB 涨到 48.4MB（so 不再压缩），
            // 换来安装后占用更小、启动更快——走 App Bundle 分发时才值得切换。
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:language-id:17.0.6")

    // 高精度引擎（可选）：ONNX Runtime + 自带 int8 Marian 模型，模型包另行下载/导入
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    testImplementation("junit:junit:4.13.2")
    // Android 的 org.json 在 JVM 单测里是空壳，这里换成真实现
    testImplementation("org.json:json:20240303")
}
