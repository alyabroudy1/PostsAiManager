plugins {
    id("pam.test-conventions")
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.postsaimanager.core.ai.litert"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = libs.versions.jvmTarget.get()
        // LiteRT-LM 0.18.0 is compiled with Kotlin 2.4, whose metadata this project's Kotlin 2.1 compiler refuses by default.
        // The flag is set here and nowhere else, and no LiteRT-LM type appears in this module's public API, so no other module
        // ever compiles against that metadata. (Its minSdk is 24, below ours; it needs nothing newer from the toolchain.)
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }

    packaging {
        jniLibs {
            // liblitertlm_jni.so stays uncompressed and page-aligned inside the bundle, as the other native libraries do.
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:domain"))

    // The Gallery's engine. Its POM pulls Kotlin 2.4 reflect and coroutines 1.11, whose metadata the rest of this build cannot
    // read, so those two are replaced by this project's own versions (the AAR only uses their stable API).
    implementation(libs.litertlm.android) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-android")
    }
    implementation(libs.kotlin.reflect)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
}
