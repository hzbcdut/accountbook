import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "nt.ddeoid.accountbook"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "nt.ddeoid.accountbook"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "0.5.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        // 中文 + 英文,语言切换在设置里手动控制。
        localeFilters += listOf("zh", "en")
    }

    // 复用 debug keystore 签名 release 包,方便直接 adb install。
    //
    // v0.5.0 起改用项目内的稳定 keystore(`keystore/debug.keystore`),**不再**依赖
    // `~/.android/debug.keystore`。原因:CI runner 每次都现场生成一个新的 debug
    // keystore(Gradle 在 Linux 上找不到就生成),导致同一个 GitHub release 频道
    // 的相邻版本(v0.4.3 → v0.5.0)签名不一致,用户必须卸装重装才能升,数据会丢。
    //
    // 现在 keystore 是项目仓库外的固定文件(已加 .gitignore),GitHub Actions 通过
    // KEYSTORE_BASE64 secret 注入到同一路径。**sha256 永久稳定** —— 后续 v0.5.x
    // 互相覆盖安装不需要卸装。
    //
    // 本机首次 setup:见 `keystore/README.md`。
    signingConfigs {
        create("releaseDebugSigning") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            // v0.5.0+:debug 也用稳定 keystore,跟 release 同一个 cert。
            // CI 上 Gradle 默认会用 ubuntu runner 上的 ~/.android/debug.keystore
            // (每次 run 重新生成 → 签名不稳定)。这里显式指向项目 keystore 后,
            // GitHub release 上的 debug APK 跟 release APK 签名一致。
            signingConfig = signingConfigs.getByName("releaseDebugSigning")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("releaseDebugSigning")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    // Bug #38(v0.4.2):KeyVault.initializeWithExistingEntropy 在生物识别失败路径
    // 会调 android.util.Log.w,默认 JVM 单测会抛 RuntimeException("not mocked")。
    // 开 isReturnDefaultValues 让 Log.* 等返回默认值 0,免得给每个 log call 都包 mockk。
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md"
            )
        }
    }
}

// AGP 9.0:kotlinOptions 已移出 android {} 块。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// 把 Room schema 导出到项目内,版本迁移时方便 diff。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Room + SQLCipher
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.sqlcipher.android)
    ksp(libs.androidx.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // Storage & Security
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)

    // Coroutines + Serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}