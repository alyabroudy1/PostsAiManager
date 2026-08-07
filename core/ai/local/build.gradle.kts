plugins {
    id("pam.test-conventions")
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.postsaimanager.core.ai.local"
    compileSdk = libs.versions.compileSdk.get().toInt()

    // Pin the NDK so every machine and CI agent produces the same binary.
    // Bump deliberately, never implicitly.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                // -O3 and 16 KB page alignment: Android 15+ devices may use 16 KB pages,
                // and a native lib aligned to 4 KB will refuse to load on them.
                cppFlags += listOf("-O3", "-fexceptions", "-frtti")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DGGML_OPENMP=OFF",
                    "-DLLAMA_CURL=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                )
            }
        }

        ndk {
            // Matches the app-level abiFilters. 32-bit cannot host a useful LLM
            // (~3 GB usable address space) and x86 has no modern device.
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // Required for 16 KB page-size devices (Android 15+).
            useLegacyPackaging = false
        }
        resources {
            // :core:testing exposes the JUnit 5 stack via `api`, and several of those jars
            // ship their own META-INF licence files. Harmless duplicates, but the packager
            // treats a collision as fatal.
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/{AL2.0,LGPL2.1}",
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
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:ai:core"))
    implementation(project(":core:domain"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Instrumented spike tests (JUnit4 — the instrumentation runner is JUnit4-based,
    // independent of the JUnit 5 platform used for unit tests).
    androidTestImplementation(project(":core:testing"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
