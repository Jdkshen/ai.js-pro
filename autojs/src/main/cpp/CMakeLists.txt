cmake_minimum_required(VERSION 3.18.1)

project(ai_js_pro_quickjs LANGUAGES C CXX)

set(QUICKJS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/quickjs")
set(OPENCV_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/opencv/include")

if(NOT EXISTS "${OPENCV_JAVA_LIBRARY}")
    message(FATAL_ERROR "OPENCV_JAVA_LIBRARY must point to libopencv_java5.so for ${ANDROID_ABI}")
endif()

add_library(opencv_java5 SHARED IMPORTED)
set_target_properties(opencv_java5 PROPERTIES IMPORTED_LOCATION "${OPENCV_JAVA_LIBRARY}")

add_library(quickjs SHARED
        ${QUICKJS_DIR}/quickjs.c
        ${QUICKJS_DIR}/dtoa.c
        ${QUICKJS_DIR}/libregexp.c
        ${QUICKJS_DIR}/libunicode.c
        ${QUICKJS_DIR}/cutils.c)

target_include_directories(quickjs PUBLIC ${QUICKJS_DIR})
target_compile_features(quickjs PRIVATE c_std_11)
target_compile_definitions(quickjs PRIVATE
        _GNU_SOURCE
        CONFIG_VERSION="2026-06-04")
target_compile_options(quickjs PRIVATE
        -fPIC
        -funsigned-char
        -Wno-sign-compare
        -Wno-missing-field-initializers)
target_link_options(quickjs PRIVATE
        "-Wl,-z,max-page-size=16384"
        "$<$<CONFIG:Release>:-Wl,--strip-all>")
target_link_libraries(quickjs PRIVATE log m)

add_library(quickjs_jni SHARED
        quickjs_jni.cpp
        native_frame_store.cpp)
target_compile_features(quickjs_jni PRIVATE cxx_std_17)
target_include_directories(quickjs_jni PRIVATE
        ${QUICKJS_DIR}
        ${OPENCV_INCLUDE_DIR})
target_compile_definitions(quickjs_jni PRIVATE CONFIG_VERSION="2026-06-04")
target_compile_options(quickjs_jni PRIVATE -fPIC -Wall -Wextra)
target_link_options(quickjs_jni PRIVATE
        "-Wl,-z,max-page-size=16384"
        "$<$<CONFIG:Release>:-Wl,--strip-all>")
target_link_libraries(quickjs_jni PRIVATE quickjs opencv_java5 log)
