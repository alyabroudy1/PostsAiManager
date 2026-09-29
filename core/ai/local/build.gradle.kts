plugins {
    id("pam.test-conventions")
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Which accelerator backend to compile into pam_llama, alongside the CPU backend that
// always builds. `none` (default) is CPU-only. `vulkan` adds -DGGML_VULKAN=ON, which
// needs the NDK's Vulkan headers and a glslc to compile the shaders — try it with
// `./gradlew :core:ai:local:assembleDebug -Ppam.gpuBackend=vulkan`.
//
// Every other CMake `-D` this build needs lives in CMakeLists.txt, not here — see the
// comment there. This is the one switch that has to come from Gradle, since it is a
// per-build choice rather than a fixed project setting.
val gpuBackend = (project.findProperty("pam.gpuBackend") as String?) ?: "none"

// Optional override for the host prefix (Homebrew, typically) that the Vulkan build's
// CONFIG-mode find_package() calls (SPIRV-Headers, ...) search — see the comment in
// CMakeLists.txt. Only used when gpuBackend == "vulkan"; left unset, CMake falls back
// to $HOMEBREW_PREFIX, then `brew --prefix`.
val hostPrefixPath = project.findProperty("pam.hostPrefixPath") as String?

// glslc to compile the Vulkan shaders at build time (never ships to the device — it
// only runs on this machine). Prefer the one the NDK ships under shader-tools, so the
// toolchain is pinned the same way the rest of the NDK is; fall back to Homebrew's
// (`brew install shaderc`) when the NDK's is missing, e.g. on an older NDK.
fun findHostGlslc(): String? {
    val hostTag = when {
        org.gradle.internal.os.OperatingSystem.current().isMacOsX -> "darwin-x86_64"
        org.gradle.internal.os.OperatingSystem.current().isLinux -> "linux-x86_64"
        else -> "windows-x86_64"
    }
    val ndkCandidate = android.ndkDirectory.resolve("shader-tools/$hostTag/glslc")
    if (ndkCandidate.exists()) return ndkCandidate.absolutePath

    val whichCmd = if (org.gradle.internal.os.OperatingSystem.current().isWindows) "where" else "which"
    return runCatching {
        val process = ProcessBuilder(whichCmd, "glslc").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        output.takeIf { it.isNotEmpty() && process.exitValue() == 0 }?.lineSequence()?.firstOrNull()
    }.getOrNull()
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
                )
                if (gpuBackend == "vulkan") {
                    arguments += "-DGGML_VULKAN=ON"

                    // Host toolchain for the vulkan-shaders-gen ExternalProject
                    // sub-build — see the file's own header comment.
                    arguments += "-DGGML_VULKAN_SHADERS_GEN_TOOLCHAIN=" +
                        file("src/main/cpp/cmake/host-toolchain.cmake").absolutePath

                    // The same host-prefix override, forwarded to the *main*,
                    // Android-target configure too (see CMakeLists.txt).
                    hostPrefixPath?.let { arguments += "-DPAM_HOST_PREFIX_PATH=$it" }

                    // glslc compiles the shaders at build time; it never ships to the
                    // device. Fail fast with a clear message rather than letting CMake
                    // hunt for one and print a confusing "glslc not found".
                    val glslc = findHostGlslc()
                        ?: error(
                            "pam.gpuBackend=vulkan needs a glslc on this machine — " +
                                "none found under the NDK's shader-tools/ and none on " +
                                "PATH. Install one, e.g. `brew install shaderc`."
                        )
                    arguments += "-DVulkan_GLSLC_EXECUTABLE=$glslc"

                    // ggml-vulkan.cpp calls vkGetPhysicalDeviceFeatures2() (the
                    // Vulkan 1.1 *core* entry point, not the _KHR extension one)
                    // directly against libvulkan.so, rather than through its own
                    // runtime dispatcher. The NDK's per-API-level libvulkan.so link
                    // stub only exports that core symbol from API 29 on — minSdk 26's
                    // stub only has the KHR-suffixed one, so the vulkan build fails to
                    // link with `undefined symbol: vkGetPhysicalDeviceFeatures2`.
                    //
                    // Link this one native target against the API 29 stub instead.
                    // This does not raise the app's minSdk (26): a device below API 29
                    // may not even report Vulkan 1.1 support, but that is a runtime
                    // capability question the device checker already gates the GPU
                    // backend behind (see DeviceCapabilityChecker) — the CPU backend,
                    // and every other symbol this library imports, is unaffected by
                    // this and keeps resolving fine on API 26.
                    arguments += "-DANDROID_PLATFORM=29"
                }
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

    buildFeatures {
        aidl = true
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
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
