plugins {
    id("pam.test-conventions")
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.postsaimanager"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.postsaimanager"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ONNX Runtime ships native libs for 4 ABIs. Without a filter every install
        // carries all of them (~160 MB of lib/, ~120 MB of it unusable on any given
        // device). Release ships arm64-v8a only — 32-bit cannot host an LLM, and x86
        // has no modern device. Debug adds x86_64 for the emulator.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Upload key: read from Gradle properties (~/.gradle/gradle.properties) or the environment.
    // Never commit these values. When any is missing, the release build stays unsigned.
    // Trimmed: a value pasted into gradle.properties often carries trailing spaces, which would break the path.
    fun signingValue(name: String): String? =
        (project.findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() }
            ?: System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

    val uploadStoreFile = signingValue("PAM_UPLOAD_STORE_FILE")
    val uploadStorePassword = signingValue("PAM_UPLOAD_STORE_PASSWORD")
    val uploadKeyAlias = signingValue("PAM_UPLOAD_KEY_ALIAS")
    val uploadKeyPassword = signingValue("PAM_UPLOAD_KEY_PASSWORD")
    val hasUploadKey = uploadStoreFile != null && uploadStorePassword != null &&
        uploadKeyAlias != null && uploadKeyPassword != null

    signingConfigs {
        if (hasUploadKey) {
            create("release") {
                storeFile = file(uploadStoreFile!!)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            ndk {
                abiFilters += listOf("x86_64")
            }
        }
        release {
            if (hasUploadKey) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Native libs (llama JNI, ONNX Runtime) stay uncompressed and page-aligned inside the
            // bundle and are mapped straight from the APK: smaller installs, and the 16 KB page
            // support Play requires. System.loadLibrary works with this (minSdk 26).
            useLegacyPackaging = false
        }
    }

    bundle {
        // The app offers its own in-app locales (en, de, ar) and reads string resources at runtime
        // for the chosen one; a language split would strip the non-device languages from the
        // install, so the in-app picker would find nothing.
        language { enableSplit = false }
    }
}

dependencies {
    // Core modules
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:ai:core"))
    implementation(project(":core:ai:catalog"))
    implementation(project(":core:ai:local"))
    implementation(project(":core:ai:embed"))
    implementation(project(":core:download"))
    implementation(project(":core:config"))
    implementation(project(":core:designsystem"))

    // Feature modules
    implementation(project(":feature:home"))
    implementation(project(":feature:scanner"))
    implementation(project(":feature:documents"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:profiles"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:models"))
    implementation(project(":feature:setup"))

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // Lifecycle
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)

    // App lock: BiometricPrompt (needs a FragmentActivity, which it brings in)
    implementation(libs.biometric)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.work)
    implementation(libs.work.runtime.ktx)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Core
    implementation(libs.core.ktx)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Debug
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
