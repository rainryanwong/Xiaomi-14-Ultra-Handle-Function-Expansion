plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val handleFixVersionName = "13.0.0"
val handleFixVersionCode = 1359

android {
    namespace = "com.handlefix.all"
    compileSdk = 37
    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "com.handlefix.all"
        minSdk = 33
        targetSdk = 34
        versionCode = handleFixVersionCode
        versionName = handleFixVersionName
    }

    signingConfigs {
        create("release") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            // R8 开启：压缩 dex 体积（v10.56 体积优化）。
            // 我们的代码通过 proguard-rules.pro 整体 keep，只裁剪依赖库
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            if (file("../debug.keystore").isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            excludes += setOf("META-INF/**", "META-INF/versions/**", "**/LICENSE*", "**/NOTICE*")
        }
    }

}

dependencies {
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation(compose.animation)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.3")
    compileOnly(files("libs/api-82.jar"))
}



























