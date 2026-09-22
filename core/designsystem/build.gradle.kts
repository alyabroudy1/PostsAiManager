plugins {
    id("pam.test-conventions")
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.postsaimanager.core.designsystem"
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
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // The generic ConfigSpec-driven controls (Settings' "On-device AI" section, the chat
    // header's model sheet) render `com.postsaimanager.core.model.ConfigSpec` and friends
    // directly, so every consumer of this module gets them for free.
    api(project(":core:model"))

    // Compose
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.material3)
    api(libs.compose.material.icons)
    api(libs.compose.foundation)
    api(libs.compose.runtime)
    api(libs.compose.animation)
    api(libs.compose.ui.tooling.preview)

    // Coil
    api(libs.coil.compose)

    // Lottie
    api(libs.lottie.compose)

    // Markdown rendering for assistant chat replies (bold, lists, headings, code fences).
    api(libs.compose.markdown)

    // Debug
    debugApi(libs.compose.ui.tooling)
}
