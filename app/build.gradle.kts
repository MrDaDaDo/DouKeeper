plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.mrdadado.doukeeper"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.mrdadado.doukeeper"
        // 29 起 MediaStore 寫入公用目錄不需要儲存權限（docs/SPEC.md §5.D）。
        minSdk = 29
        targetSdk = 36
        versionCode = 10
        versionName = "0.7.1"
    }

    // 正式簽章：金鑰檔與密碼放在使用者層級的 ~/.gradle/gradle.properties（DOUKEEPER_*），
    // 不進 repo。沒有設定時 release 不簽章，exportReleaseApk 會直接報錯，避免散佈出沒簽的 APK。
    // 步驟見 docs/RELEASE.md。
    val releaseStoreFile = providers.gradleProperty("DOUKEEPER_STORE_FILE").orNull
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("DOUKEEPER_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("DOUKEEPER_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("DOUKEEPER_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            // 不開 R8：WebView 注入的 JS 與 WebMessageListener 靠名稱對接，混淆後的問題只會在實機上出現，
            // 自用 App 省下的那點體積不值得。
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Java 與 Kotlin 的位元組碼版本必須一致，否則 compileDebugKotlin 直接失敗。
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.webkit)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

// 手動散佈用：assembleDebug 之後把 APK 複製到 dist/ 並帶上版本號。
// rename 用字串而不用 lambda：.kts 裡的 lambda 會捕獲建置腳本物件，
// configuration cache 序列化不了（實際踩過）。
val exportedApkName = "DouKeeper-debug-v" + android.defaultConfig.versionName + ".apk"

val exportDebugApk by tasks.registering(Copy::class) {
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename("app-debug\\.apk", exportedApkName)
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(exportDebugApk)
}

// 正式版：用固定金鑰簽章，在哪台電腦建置都能覆蓋安裝。散佈請用這份。
val exportedReleaseApkName = "DouKeeper-v" + android.defaultConfig.versionName + ".apk"
val hasReleaseKey = providers.gradleProperty("DOUKEEPER_STORE_FILE").isPresent

val exportReleaseApk by tasks.registering(Copy::class) {
    val keyPresent = hasReleaseKey
    doFirst {
        check(keyPresent) { "沒有設定 DOUKEEPER_* 簽章參數，release APK 未簽章（見 docs/RELEASE.md）" }
    }
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename("app-release\\.apk", exportedReleaseApkName)
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(exportReleaseApk)
}
