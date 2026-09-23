cmake_minimum_required(VERSION 3.18.1)

# Pikafish（中国象棋 NNUE 引擎）的 Android 构建：引擎源码来自 third-party/Pikafish
# （官方仓库浅克隆），本目录只放 JNI 胶水。构建入口 = modules/engine/src/main/cpp/build-pikafish.ps1，
# 产物 libpikafish.so 拷进 modules/engine/src/main/jniLibs/arm64-v8a 随包分发。
project(ai_js_pro_pikafish LANGUAGES CXX)

get_filename_component(PIKAFISH_SRC
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../third-party/Pikafish/src" ABSOLUTE)
if(NOT EXISTS "${PIKAFISH_SRC}/uci.cpp")
    message(FATAL_ERROR "Pikafish source tree is missing: ${PIKAFISH_SRC}")
endif()

file(GLOB_RECURSE PIKAFISH_ENGINE_SOURCES CONFIGURE_DEPENDS "${PIKAFISH_SRC}/*.cpp")
# universal/ 是「一份二进制多架构」专用入口（每次构建还会 awk 改段名），普通构建不带；
# main.cpp 里是命令行版 main()，App 进程里由 pikafish_jni.cpp 的 UCI 会话线程取代。
list(FILTER PIKAFISH_ENGINE_SOURCES EXCLUDE REGEX "/universal/")
list(FILTER PIKAFISH_ENGINE_SOURCES EXCLUDE REGEX "/main\\.cpp$")

add_library(pikafish SHARED
        pikafish_jni.cpp
        ${PIKAFISH_ENGINE_SOURCES})

target_include_directories(pikafish PRIVATE "${PIKAFISH_SRC}")

# 官方 Makefile 对应关系：
#   armv8            -> -DUSE_NEON=8（+ popcnt / bits=64 -> -DIS_64BIT）
#   armv8-dotprod    -> 追加 -march=armv8.2-a+dotprod -DUSE_NEON_DOTPROD
# 预取默认开启（只有定义了 NO_PREFETCH 才关）。
# dotprod 需要 Cortex-A76/Snapdragon 855 以上；默认关，保证老机型也能加载。
option(PIKAFISH_DOTPROD "Build with ARMv8.2 dot-product support (Cortex-A76+)" OFF)

# IS_64BIT 是硬要求：misc.h 里 `u128 = unsigned __int128` 只有在 IS_64BIT 下才定义，
# 而象棋盘是 90 格、Bitboard 必须是 128 位（types.h: using Bitboard = u128）。
target_compile_definitions(pikafish PRIVATE USE_NEON=8 USE_POPCNT IS_64BIT)
if(PIKAFISH_DOTPROD)
    target_compile_definitions(pikafish PRIVATE USE_NEON_DOTPROD)
    target_compile_options(pikafish PRIVATE -march=armv8.2-a+dotprod)
endif()

target_compile_options(pikafish PRIVATE
        -fPIC
        -O3
        -Wall
        -fno-exceptions)
target_compile_features(pikafish PRIVATE cxx_std_17)

target_link_options(pikafish PRIVATE
        "-Wl,-z,max-page-size=16384"
        "$<$<CONFIG:Release>:-Wl,--strip-all>")

target_link_libraries(pikafish PRIVATE log m)
