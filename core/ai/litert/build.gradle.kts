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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
        }
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
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.reflect)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
}
