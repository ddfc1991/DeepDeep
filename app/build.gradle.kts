import java.util.Properties
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) keystorePropertiesFile.inputStream().use { load(it) }
}

// 固定签名：CI 通过 DEEPDEEP_KEYSTORE_PATH / DEEPDEEP_KEYSTORE_PASS / DEEPDEEP_KEYSTORE_ALIAS
// 注入构建环境（密码只从环境变量读取，绝不进仓库）。本地无 secret 时不启用，
// debug 回退 AGP 默认签名，release 回退 keystore.properties / debug。
val fixedStorePath = System.getenv("DEEPDEEP_KEYSTORE_PATH")
val fixedKeyPass = System.getenv("DEEPDEEP_KEYSTORE_PASS")
val fixedKeyAlias = System.getenv("DEEPDEEP_KEYSTORE_ALIAS") ?: "deepdeep"
val fixedSigningAvailable = fixedStorePath != null && fixedKeyPass != null && File(fixedStorePath).isFile

android {
    namespace = "io.github.whywood.deepdeep"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.whywood.deepdeep"
        minSdk = 29
        targetSdk = 36
        versionCode = 10008
        versionName = "1.0.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 本地离线语音（sherpa-onnx）只打包 arm64-v8a，减小 APK 体积
        ndk {
            abiFilters += "arm64-v8a"
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

    androidResources {
        noCompress += "rootfs"
        // 大模型文件不压缩，避免运行时解压开销（sherpa-onnx 直接从 APK 读取）
        noCompress += "onnx"
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    signingConfigs {
        create("deepdeep") {
            if (fixedSigningAvailable) {
                storeFile = file(fixedStorePath!!)
                storePassword = fixedKeyPass
                keyAlias = fixedKeyAlias
                keyPassword = fixedKeyPass
            }
        }
        create("release") {
            if (keystorePropertiesFile.isFile) {
                storeFile = file(keystoreProperties.getProperty("storeFile", "release.keystore"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (fixedSigningAvailable) {
                signingConfig = signingConfigs.getByName("deepdeep")
            }
        }
        release {
            signingConfig = when {
                fixedSigningAvailable -> signingConfigs.getByName("deepdeep")
                keystorePropertiesFile.isFile -> signingConfigs.getByName("release")
                else -> signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
    reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
}

dependencies {
    implementation(project(":runtime"))
    implementation(project(":shell"))
    implementation(project(":design"))
    implementation(project(":plugin-api"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.preview)
    implementation(libs.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.compose)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.webkit)
    implementation(libs.libadb)
    implementation(libs.hiddenapibypass)
    implementation(libs.bcpkix)
    implementation(libs.okhttp)

    debugImplementation(libs.compose.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.json.org)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    debugImplementation(libs.compose.test.manifest)
}
