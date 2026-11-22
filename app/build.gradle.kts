plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// 12.1.50 正式版：Phoenix 相机外快捷跳转统一在启动前清理旧相机任务，
// 修复息屏/锁屏时首次长拨只振动、需要第二次操作才显示目标模式的问题。
val handleFixVersionName = "12.1.50"
val handleFixVersionCode = 1358

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
            signingConfig = signingConfigs.getByName("release")
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

/**
 * 内置源码按版本导出。Release 构建只允许存在一个当前版本 ZIP，避免旧源码包继续被
 * mergeReleaseAssets 打进 APK，造成每次发布无意义地增加约 3 MB。
 */
val verifySingleSourceArchive = tasks.register("verifySingleSourceArchive") {
    group = "verification"
    description = "Verify that release assets contain only the current HandleFix source archive"
    doLast {
        val sourceAssets = file("src/main/assets")
        val archives = sourceAssets.listFiles { file ->
            file.isFile && file.name.startsWith("handlefix-src-") && file.extension == "zip"
        }?.toList().orEmpty()
        val expectedName = "handlefix-src-12.1.50.zip"
        if (archives.size != 1 || archives.single().name != expectedName) {
            throw GradleException(
                "Release assets must contain only $expectedName; found: " +
                    archives.joinToString { it.name },
            )
        }
    }

}

tasks.configureEach {
    if (name == "mergeReleaseAssets") {
        dependsOn(verifySingleSourceArchive)
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



























