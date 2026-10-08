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
        versionCode = 15
        versionName = "0.16.0"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // CaptureClient 把版本号报给调度层，用 BuildConfig 保证与构建一致
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
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

