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
        versionCode = 6
        versionName = "0.4.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
val exportedApkName = "DouyinVideoDownloader-debug-v" + android.defaultConfig.versionName + ".apk"

val exportDebugApk by tasks.registering(Copy::class) {
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename("app-debug\\.apk", exportedApkName)
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(exportDebugApk)
}
