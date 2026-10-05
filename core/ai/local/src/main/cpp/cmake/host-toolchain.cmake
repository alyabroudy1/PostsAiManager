# Host toolchain for llama.cpp's `vulkan-shaders-gen` ExternalProject sub-build.
#
# `vulkan-shaders-gen` is a build-time-only HOST tool: it never ships to the phone,
# it just emits the compiled SPIR-V shader header that `pam_llama` links against.
# ggml-vulkan/CMakeLists.txt builds it with `ExternalProject_Add`, and since the
# outer configure is cross-compiling for Android, that sub-build needs its own,
# purely-native toolchain — this file, passed in as
# `-DGGML_VULKAN_SHADERS_GEN_TOOLCHAIN=<this file>` from build.gradle.kts.
#
# Without this file, ggml-vulkan/CMakeLists.txt falls back to `detect_host_compiler()`
# (find_program(... NO_CMAKE_FIND_ROOT_PATH)), which also works — this file exists so
# the choice is explicit and versioned, and so this sub-build's CONFIG-mode
# find_package() calls (it currently only needs find_package(Threads), which is not
# CONFIG-mode, but keep this consistent with the relaxation in ../CMakeLists.txt) can
# see Homebrew too, in case a future llama.cpp bump adds one.
#
# No cross-compiling settings here on purpose: this is a plain native host build.
set(CMAKE_BUILD_TYPE Release CACHE STRING "")

# The sub-build inherits CMAKE_GENERATOR (Ninja) from the outer, Android-target
# configure, but that is Android Studio's bundled `cmake`/`ninja` pair — `ninja`
# usually is not on PATH by itself, and the outer configure's C/C++ compiler is the
# NDK's clang, which cannot produce a host binary. Both need a host-appropriate
# value, found rather than hardcoded so this keeps working on any machine/CI agent:
#  - CMAKE_MAKE_PROGRAM: ninja ships right next to the `cmake` binary that is running
#    this toolchain file (CMAKE_COMMAND) — reuse that one instead of requiring a
#    separately-installed host ninja.
#  - CMAKE_C_COMPILER / CMAKE_CXX_COMPILER: same lookup ggml-vulkan/CMakeLists.txt's
#    own detect_host_compiler() fallback uses, kept here so it is explicit/versioned.
if(NOT CMAKE_MAKE_PROGRAM)
    get_filename_component(_pam_host_cmake_bin_dir "${CMAKE_COMMAND}" DIRECTORY)
    find_program(CMAKE_MAKE_PROGRAM
        NAMES ninja
        PATHS "${_pam_host_cmake_bin_dir}"
        NO_DEFAULT_PATH
    )
    if(NOT CMAKE_MAKE_PROGRAM)
        find_program(CMAKE_MAKE_PROGRAM NAMES ninja NO_CMAKE_FIND_ROOT_PATH)
    endif()
    unset(_pam_host_cmake_bin_dir)
endif()

if(NOT CMAKE_C_COMPILER)
    find_program(CMAKE_C_COMPILER NAMES clang cc gcc NO_CMAKE_FIND_ROOT_PATH)
endif()
if(NOT CMAKE_CXX_COMPILER)
    find_program(CMAKE_CXX_COMPILER NAMES clang++ c++ g++ NO_CMAKE_FIND_ROOT_PATH)
endif()

# The sub-build's own project() call runs after this file, so nothing here needs a
# cross-compiling sysroot: search the host normally everywhere...
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE NEVER)
# ...and let a CONFIG-mode find_package() see Homebrew (or another host prefix) on
# top of the usual system locations, the same relaxation ../CMakeLists.txt applies to
# the main, Android-target configure for the same reason (SPIRV-Headers etc. only
# ship a Homebrew/system CMake package, never one inside the NDK sysroot).
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)

if(DEFINED PAM_HOST_PREFIX_PATH AND NOT PAM_HOST_PREFIX_PATH STREQUAL "")
    list(APPEND CMAKE_PREFIX_PATH "${PAM_HOST_PREFIX_PATH}")
elseif(DEFINED ENV{HOMEBREW_PREFIX})
    list(APPEND CMAKE_PREFIX_PATH "$ENV{HOMEBREW_PREFIX}")
else()
    find_program(_pam_host_brew_exe brew)
    if(_pam_host_brew_exe)
        execute_process(
            COMMAND ${_pam_host_brew_exe} --prefix
            OUTPUT_VARIABLE _pam_host_brew_prefix
            OUTPUT_STRIP_TRAILING_WHITESPACE
        )
        if(_pam_host_brew_prefix)
            list(APPEND CMAKE_PREFIX_PATH "${_pam_host_brew_prefix}")
        endif()
    endif()
    unset(_pam_host_brew_exe CACHE)
endif()
