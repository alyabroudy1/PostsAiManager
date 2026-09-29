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
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ONNX Runtime ships native libs for 4 ABIs. Without a filter every install
        // carries all of them (~160 MB of lib/, ~120 MB of it unusable on any given
        // device). Release ships arm64-v8a only — 32-bit cannot host an LLM, and x86
        // has no modern device. Debug adds x86_64 for the emulator.
        ndk {
            abiFilters += listOf("arm64-v8a")
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

    kotlinOptions {
        jvmTarget = libs.versions.jvmTarget.get()
    }

    buildFeatures {
        compose = true
        buildConfig = true
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

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.work)
    implementation(libs.work.runtime.ktx)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Core
    implementation(libs.core.ktx)

    // Debug
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
