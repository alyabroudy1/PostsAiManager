plugins {
    id("pam.test-conventions")
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.postsaimanager.core.ai.online"
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
}

// ─────────────────────────────────────────────────────────────────────────────
//  ⚠️  THE TWO PRIVACY GUARANTEES LIVE IN THIS DEPENDENCY BLOCK.
//
//  1. NO `:core:data` — an online provider cannot read a document. There is no
//     DAO, DataStore or file handle on this classpath to inject.
//
//  2. NO `:core:domain` — an online provider cannot execute a tool. `ToolSpec`
//     (inert description) comes from `:core:model`; the executable `AiTool` type
//     lives in `:core:domain` and is deliberately absent here.
//
//  These are not conventions to remember — they are compile-time facts, and
//  Konsist re-checks them in CI (task 11.5.4/11.5.5).
//
//  Adding either dependency silently breaks the product's core promise.
//  See documentation/02-architecture.md §2.3.
// ─────────────────────────────────────────────────────────────────────────────
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:config"))
    // implementation(project(":core:data"))    ← NEVER
    // implementation(project(":core:domain"))  ← NEVER

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.logging)
}
