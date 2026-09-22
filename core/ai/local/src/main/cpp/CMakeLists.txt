cmake_minimum_required(VERSION 3.22.1)
project(pam_llama CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── llama.cpp ────────────────────────────────────────────────────────────────
# Vendored as a git submodule at core/ai/local/src/main/cpp/llama.cpp (see
# .gitmodules), pinned to tag b10299. The pinned commit lives in this repository, so
# every checkout builds the same revision; fetching at build time would make builds
# non-reproducible and break offline/CI builds.
#
# Fresh clone:      git clone --recurse-submodules <repo>
# Existing clone:   git submodule update --init --recursive
# Bump the pin:     cd core/ai/local/src/main/cpp/llama.cpp
#                   git fetch --depth 1 origin tag <tag> && git checkout <tag>
#                   then commit the new gitlink from the repo root.
#
# `shallow = true` in .gitmodules keeps the clone to the pinned commit only; the full
# llama.cpp history is several hundred MB nobody needs on a phone-app checkout.
#
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)

if(NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "llama.cpp submodule is missing. Run:\n"
        "  git submodule update --init --recursive\n"
        "See documentation/06-llama-spike.md")
endif()

# Trim everything not needed on a phone. Each of these otherwise pulls in tools,
# tests or a CURL dependency that will not link for Android.
#
# These are fixed project settings, not per-build choices, so they live here and only
# here — build.gradle.kts used to pass the same five flags a second time as `-D`
# arguments, which did nothing (CACHE ... FORCE always wins over a command-line `-D`)
# except invite the two copies to drift. The one flag that genuinely is a per-build
# choice — which accelerator backend to compile in — stays in build.gradle.kts as the
# `pam.gpuBackend` Gradle property, forwarded as a plain `-D` with no FORCE here.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)

# arm64-v8a CPU features. Runtime dispatch is preferred over baseline -march bumps
# so one binary serves every arm64 device rather than crashing on older cores.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
endif()

# ── Vulkan backend: let CONFIG-mode find_package() see host packages ───────────
# ggml-vulkan/CMakeLists.txt calls find_package(SPIRV-Headers CONFIG REQUIRED) (and
# find_package(Vulkan COMPONENTS glslc REQUIRED)) directly in *this*, Android-target
# configure — not only inside the vulkan-shaders-gen host sub-build. The NDK
# toolchain file sets CMAKE_FIND_ROOT_PATH_MODE_PACKAGE to ONLY, which restricts a
# CONFIG-mode package search to the NDK sysroot. The sysroot has vulkan/vulkan.h and
# libvulkan.so (so plain MODULE-mode find_package(Vulkan) already succeeds), but it
# never ships a SPIRV-Headers CMake package, hence:
#   Could not find a package configuration file provided by "SPIRV-Headers"
#
# Fix: also let CONFIG-mode lookups search a host prefix (Homebrew by default, or
# -DPAM_HOST_PREFIX_PATH=<prefix> / $ENV{HOMEBREW_PREFIX} as overrides). This only
# relaxes *package config* discovery — CMAKE_FIND_ROOT_PATH_MODE_LIBRARY and _INCLUDE
# are left at their NDK-toolchain default (ONLY), so the actual Vulkan::Vulkan target
# still resolves to the NDK's arm64 libvulkan.so / vulkan.h, never a host binary.
if(GGML_VULKAN)
    if(DEFINED PAM_HOST_PREFIX_PATH AND NOT PAM_HOST_PREFIX_PATH STREQUAL "")
        set(PAM_VULKAN_HOST_PREFIX "${PAM_HOST_PREFIX_PATH}")
    elseif(DEFINED ENV{HOMEBREW_PREFIX})
        set(PAM_VULKAN_HOST_PREFIX "$ENV{HOMEBREW_PREFIX}")
    else()
        find_program(_pam_brew_exe brew)
        if(_pam_brew_exe)
            execute_process(
                COMMAND ${_pam_brew_exe} --prefix
                OUTPUT_VARIABLE _pam_brew_prefix
                OUTPUT_STRIP_TRAILING_WHITESPACE
            )
            if(_pam_brew_prefix)
                set(PAM_VULKAN_HOST_PREFIX "${_pam_brew_prefix}")
            endif()
        endif()
        unset(_pam_brew_exe CACHE)
    endif()

    if(PAM_VULKAN_HOST_PREFIX)
        list(APPEND CMAKE_PREFIX_PATH "${PAM_VULKAN_HOST_PREFIX}")
    endif()

    set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
endif()

add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp EXCLUDE_FROM_ALL)

# ggml-vulkan.cpp includes <vulkan/vulkan.hpp>, the Vulkan-Hpp C++ bindings. The NDK
# sysroot only ships the plain C header (vulkan/vulkan.h) — vulkan.hpp is header-only
# and pulls in nothing but vulkan.h itself, so it is safe to take it from the host
# Vulkan-Headers install (the same one that satisfied find_package(SPIRV-Headers)
# above) even though the rest of this target compiles for the NDK sysroot.
if(GGML_VULKAN AND TARGET ggml-vulkan AND PAM_VULKAN_HOST_PREFIX)
    target_include_directories(ggml-vulkan PRIVATE "${PAM_VULKAN_HOST_PREFIX}/include")
endif()

# ── JNI bridge ───────────────────────────────────────────────────────────────
add_library(pam_llama SHARED llama_jni.cpp)

target_include_directories(pam_llama PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)

find_library(log-lib log)

target_link_libraries(pam_llama
    llama
    ${log-lib}
)
