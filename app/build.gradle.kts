import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 起 Kotlin 支持已内建，不能再单独应用 kotlin.android 插件
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.dzsun.bookkeeping"
    // 依赖（Compose UI 1.12、core-ktx 1.19）要求 compileSdk ≥ 37
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.dzsun.bookkeeping"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "0.21.1"

        ndk {
            // 真机只发 arm64（包体小、也是目标机型的架构）。
            // x86_64 是**模拟器专用**的开关：`-PincludeX86=true` 时才编进去，
            // 默认关闭，不改变发布产物。CMake 侧对 x86_64 有独立分支（关 KleidiAI）。
            abiFilters += "arm64-v8a"
            if (project.findProperty("includeX86") == "true") abiFilters += "x86_64"
        }
    }

    // 发布签名：材料在 keystore/（不进 git，见 keystore/README.txt）。
    // properties 缺失时不禁用构建——release 出未签名包，由 tools/release-apk.sh 断言拦住。
    signingConfigs {
        val propsFile = rootProject.file("keystore/release.properties")
        if (propsFile.exists()) {
            create("release") {
                val p = Properties().apply { propsFile.inputStream().use { load(it) } }
                // properties 里 storeFile 是相对 keystore/ 目录的
                storeFile = rootProject.file("keystore/${p.getProperty("storeFile", "release.keystore")}")
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // 原生构建用的 NDK 固定住（AGP 不写的话用它自己的默认值，升级 AGP 会静默换 NDK）
    ndkVersion = "28.2.13676358"

    // llama.cpp 走 CMake 原生构建（只编 libllama + ggml 核心库，见 src/main/cpp/CMakeLists.txt）
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // CaptureClient 把版本号报给调度层，用 BuildConfig 保证与构建一致
        buildConfig = true
    }

    // 端侧模型（GGUF）打进 APK，装上即用——不走「用户自己 adb push」那条路。
    // 资产放在**仓库外**的 ~/projects/model-assets/（1.1 GB，绝不能进 git），
    // 这里用硬链接保持同一 inode，改模型不用重拷。
    // 目录不存在时 AGP 会跳过该 srcDir，所以没有模型也能正常构建（只是端侧功能退化）。
    sourceSets {
        getByName("main") {
            assets.srcDir(rootProject.file("../model-assets"))
        }
    }

    androidResources {
        // .gguf 本身已是压缩格式，再 deflate 一遍纯属浪费 CPU，
        // 且 APK 内存映射读取要求它按 STORED 存放。
        noCompress += "gguf"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs {
            // 必须把 .so 解压到 nativeLibraryDir：llama.cpp 运行时用
            // ggml_backend_load_all_from_path(nativeLibraryDir) 去 dlopen 后端库。
            // extractNativeLibs=false 时该目录是空的（库只在 APK 里内存映射），
            // 结果是 "no backends are loaded" → 模型加载直接失败。
            // System.loadLibrary 不受影响，所以这个坑编译期完全看不出来，真机同样会踩。
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    // 可下载艺术字体（Noto Serif SC / Playfair / Inter / Cormorant），版本由 compose-bom 管理
    implementation("androidx.compose.ui:ui-text-google-fonts")
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

