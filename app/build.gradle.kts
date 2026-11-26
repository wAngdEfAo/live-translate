plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yg.livetranslate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yg.livetranslate"
        minSdk = 24
        targetSdk = 36
        versionCode = 26
        versionName = "3.5"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = false
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
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:language-id:17.0.6")
}
