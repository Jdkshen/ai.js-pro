cmake_minimum_required(VERSION 3.18.1)

# 象棋插件 APK 的原生引擎：与宿主随包的 libpikafish.so 同源（third-party/Pikafish +
# modules/engine/src/main/cpp/pikafish/pikafish_jni.cpp），只是换个库名、换 JNI 类名，
# 这样插件在任意宿主里都自带一份引擎，而不依赖宿主的 $pikafish。
# 构建入口 = 本目录 build-native.ps1，产物落到 src/main/jniLibs/arm64-v8a/libxiangqi_engine.so。
project(ai_js_pro_xiangqi_plugin_engine LANGUAGES CXX)

get_filename_component(PIKAFISH_SRC
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third-party/Pikafish/src" ABSOLUTE)
get_filename_component(PIKAFISH_JNI_DIR
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../modules/engine/src/main/cpp/pikafish" ABSOLUTE)
if(NOT EXISTS "${PIKAFISH_SRC}/uci.cpp")
    message(FATAL_ERROR "Pikafish source tree is missing: ${PIKAFISH_SRC}")
endif()
if(NOT EXISTS "${PIKAFISH_JNI_DIR}/pikafish_jni.cpp")
    message(FATAL_ERROR "Shared Pikafish JNI glue is missing: ${PIKAFISH_JNI_DIR}")
endif()

file(GLOB_RECURSE PIKAFISH_ENGINE_SOURCES CONFIGURE_DEPENDS "${PIKAFISH_SRC}/*.cpp")
# universal/ 是「一份二进制多架构」专用入口；main.cpp 是命令行版 main()，
# App 进程里由 pikafish_jni.cpp 的 UCI 会话线程取代（与宿主那份构建保持一致）。
list(FILTER PIKAFISH_ENGINE_SOURCES EXCLUDE REGEX "/universal/")
list(FILTER PIKAFISH_ENGINE_SOURCES EXCLUDE REGEX "/main\\.cpp$")

add_library(xiangqi_engine SHARED
        device_features.cpp
        ${PIKAFISH_JNI_DIR}/pikafish_jni.cpp
        ${PIKAFISH_ENGINE_SOURCES})

target_include_directories(xiangqi_engine PRIVATE "${PIKAFISH_SRC}")

# JNI 导出名指向插件自己的桥接类（插件与宿主是两个 APK，不能共用一份 .so：
# 同名库会被链接器按 SONAME 复用，导致插件拿到宿主那份）。
target_compile_definitions(xiangqi_engine PRIVATE
        PIKAFISH_JNI_CLASS=Java_com_jdkshen_xiangqi_plugin_NativeEngine
        USE_NEON=8
        USE_POPCNT
        IS_64BIT)

# dotprod 需要 Cortex-A76/Snapdragon 855 以上；默认关，保证老机型也能加载。
option(PIKAFISH_DOTPROD "Build with ARMv8.2 dot-product support (Cortex-A76+)" OFF)
if(PIKAFISH_DOTPROD)
    target_compile_definitions(xiangqi_engine PRIVATE USE_NEON_DOTPROD)
    target_compile_options(xiangqi_engine PRIVATE -march=armv8.2-a+dotprod)
endif()

target_compile_options(xiangqi_engine PRIVATE
        -fPIC
        -O3
        -Wall
        -fno-exceptions)
target_compile_features(xiangqi_engine PRIVATE cxx_std_17)

target_link_options(xiangqi_engine PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
        "$<$<CONFIG:Release>:-Wl,--strip-all>")

target_link_libraries(xiangqi_engine PRIVATE log m)

# Install the PIE command-line engine through Android's native-library directory.
# Node spawns it as a process (never dlopen); no executable is copied to writable
# app data. Baseline ARMv8 NEON keeps this usable without dot-product extensions.
add_executable(xiangqi_uci ${PIKAFISH_ENGINE_SOURCES} "${PIKAFISH_SRC}/main.cpp")
set_target_properties(xiangqi_uci PROPERTIES OUTPUT_NAME "libxiangqi_uci" SUFFIX ".so")
target_include_directories(xiangqi_uci PRIVATE "${PIKAFISH_SRC}")
target_compile_features(xiangqi_uci PRIVATE cxx_std_17)
target_compile_definitions(xiangqi_uci PRIVATE USE_NEON=8 USE_POPCNT IS_64BIT NNUE_EMBEDDING_OFF)
target_compile_options(xiangqi_uci PRIVATE -O3 -fno-exceptions -march=armv8-a)
target_link_options(xiangqi_uci PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384"
    "$<$<CONFIG:Release>:-Wl,--strip-all>")
target_link_libraries(xiangqi_uci PRIVATE log m)

add_executable(xiangqi_uci_dotprod ${PIKAFISH_ENGINE_SOURCES} "${PIKAFISH_SRC}/main.cpp")
set_target_properties(xiangqi_uci_dotprod PROPERTIES OUTPUT_NAME "libxiangqi_uci_dotprod" SUFFIX ".so")
target_include_directories(xiangqi_uci_dotprod PRIVATE "${PIKAFISH_SRC}")
target_compile_features(xiangqi_uci_dotprod PRIVATE cxx_std_17)
target_compile_definitions(xiangqi_uci_dotprod PRIVATE USE_NEON=8 USE_NEON_DOTPROD USE_POPCNT IS_64BIT NNUE_EMBEDDING_OFF)
target_compile_options(xiangqi_uci_dotprod PRIVATE -O3 -fno-exceptions -march=armv8.2-a+dotprod)
target_link_options(xiangqi_uci_dotprod PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384"
    "$<$<CONFIG:Release>:-Wl,--strip-all>")
target_link_libraries(xiangqi_uci_dotprod PRIVATE log m)
