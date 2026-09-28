cmake_minimum_required(VERSION 3.22.1)

project("miotts" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "This module only builds for arm64-v8a (got '${ANDROID_ABI}'); see tts/build.gradle.kts abiFilters.")
endif()
set(GGML_SYSTEM_ARCH "ARM")

# Deterministic, CPU-only, no OpenMP — keep the NDK CMake toolchain build simple and match the
# flags upstream's own Android sample (examples/android/MioTTSCppDemoAndroid) uses. Most of these
# are also passed as -D... externalNativeBuild arguments from tts/build.gradle.kts; both places
# set them so a plain `cmake --build` from this directory (outside Gradle) still works.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)

set(THIRD_PARTY_DIR ${CMAKE_CURRENT_LIST_DIR}/third_party)
set(MIO_SRC ${THIRD_PARTY_DIR}/mio-tts-cpp)
set(LLAMA_SRC ${MIO_SRC}/llama.cpp)

if(NOT EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    message(FATAL_ERROR
        "third_party/mio-tts-cpp (+ its llama.cpp submodule) is missing. Run "
        "tts/src/main/cpp/fetch_deps.sh first (see tts/src/main/cpp/README.md).")
endif()

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_LLGUIDANCE OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_SRC} ${CMAKE_CURRENT_BINARY_DIR}/llama_cpp)

add_library(mio-tts-lib STATIC
    ${MIO_SRC}/src/mio-tts-lib.cpp
    ${MIO_SRC}/src/miocodec-decoder.cpp
    ${MIO_SRC}/src/wavlm-extractor.cpp)

target_compile_features(mio-tts-lib PRIVATE cxx_std_17)
target_include_directories(mio-tts-lib
    PUBLIC
        ${MIO_SRC}/src
    PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/vendor
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)
target_link_libraries(mio-tts-lib
    PUBLIC
        llama
        ggml
        ggml-base
        ggml-cpu
        android
        log)

add_library(miotts SHARED
    native_bridge.cpp)

target_compile_options(miotts PRIVATE $<$<CONFIG:Release>:-O3>)
target_compile_features(miotts PRIVATE cxx_std_17)
target_include_directories(miotts PRIVATE
    ${MIO_SRC}/src
    ${LLAMA_SRC}
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/vendor
    ${LLAMA_SRC}/ggml/include)
target_link_libraries(miotts PRIVATE
    mio-tts-lib
    llama
    ggml
    ggml-base
    ggml-cpu
    android
    log)
