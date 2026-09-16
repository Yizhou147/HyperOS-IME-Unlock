@file:Suppress("UnstableApiUsage")
plugins {
    id("com.android.application")
    id("kotlin-android")
}

android {
    compileSdk = 34
    namespace = "com.xposed.miuiime"

    defaultConfig {
        applicationId = "com.xposed.miuiime"
        minSdk = 28
        targetSdk = 34
        versionName = "1.0"
        // versionCode 不从 1 重新开始：本模块此前以 1.17(15) 对外发布，
        // 降到 1 会让已安装用户无法覆盖升级（Android 拒绝降级安装）。
        versionCode = 16
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
        }
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        resources {
            excludes += arrayOf("META-INF/**", "kotlin/**", "google/**", "**.bin")
        }
    }
    applicationVariants.all {
        val outputFileName = "Unlock_HyperOS_IME-${versionName}_${buildType.name}.apk"
        outputs.all {
            val output = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output?.outputFileName = outputFileName
        }
    }
    dependenciesInfo {
        includeInApk = false
    }
}

kotlin {
    sourceSets.all {
        languageSettings.languageVersion = "2.0"
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("com.github.kyuubiran:EzXHelper:1.0.3")
    implementation("org.luckypray:dexkit:2.2.0")
}
