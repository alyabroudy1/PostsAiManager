cmake_minimum_required(VERSION 3.22.1)
project(pam_llama CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── llama.cpp ────────────────────────────────────────────────────────────────
# Vendored as a git submodule at core/ai/local/src/main/cpp/llama.cpp so the exact
# revision is pinned in this repository. Fetching at build time would make builds
# non-reproducible and break offline/CI builds.
#
#   git submodule add https://github.com/ggml-org/llama.cpp \
#       core/ai/local/src/main/cpp/llama.cpp
#   cd core/ai/local/src/main/cpp/llama.cpp && git checkout <known-good-tag>
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

add_subdirectory(${LLAMA_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp EXCLUDE_FROM_ALL)

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
