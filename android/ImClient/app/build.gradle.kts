plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.imclient"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.imclient"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // 签名方案：必须同时输出 V1 与 V2。
    // AGP 在 minSdk >= 24 时会自动关掉 V1（认为没必要），结果 APK 只剩 V2 签名。
    // 而 MIUI / ColorOS 的包安装器校验常要求 V1 + V2 同时存在，只有 V2 时会给出
    // 「安装包位置错误」「未知错误」这类毫无指向性的提示，权限开了也照样装不上。
    // （可用 unzip -l 看 META-INF 下有没有 .RSA 判断 V1 是否存在）
    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // HTTP + WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
