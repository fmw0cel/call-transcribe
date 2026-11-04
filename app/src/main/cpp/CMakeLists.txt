cmake_minimum_required(VERSION 3.22.1)
project(localtranscribe C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# whisper.cpp (vendored in <repo>/whisper.cpp, pinned to a release tag)
# ---------------------------------------------------------------------------
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)

set(GGML_OPENMP   OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE   OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../whisper.cpp
                 ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp)

# ---------------------------------------------------------------------------
# JNI bridge
# ---------------------------------------------------------------------------
add_library(whisper_jni SHARED whisper_jni.cpp)

target_include_directories(whisper_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../whisper.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../whisper.cpp/ggml/include)

target_link_libraries(whisper_jni PRIVATE whisper android log)

target_compile_options(whisper_jni PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(whisper_jni PRIVATE -Wl,--gc-sections)
