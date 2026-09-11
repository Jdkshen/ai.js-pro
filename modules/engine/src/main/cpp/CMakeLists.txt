cmake_minimum_required(VERSION 3.18.1)

project(ai_js_pro_quickjs LANGUAGES C CXX)

set(QUICKJS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/quickjs")
set(OPENCV_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/opencv/include")

if(NOT EXISTS "${OPENCV_JAVA_LIBRARY}")
    message(FATAL_ERROR "OPENCV_JAVA_LIBRARY must point to libopencv_java5.so for ${ANDROID_ABI}")
endif()

get_filename_component(OPENCV_JNI_DIR "${OPENCV_JAVA_LIBRARY}" DIRECTORY)
get_filename_component(OPENCV_JAVA_FILENAME "${OPENCV_JAVA_LIBRARY}" NAME)

# Copy all OpenCV .so files to a flat directory so the linker uses simple filenames
# (avoids embedding absolute build paths into ELF NEEDED entries)
set(OPENCV_FLAT_DIR "${CMAKE_BINARY_DIR}/opencv-flat")
file(MAKE_DIRECTORY "${OPENCV_FLAT_DIR}")
file(GLOB _opencv_sos "${OPENCV_JNI_DIR}/*.so")
foreach(_so ${_opencv_sos})
    get_filename_component(_so_name "${_so}" NAME)
    configure_file("${_so}" "${OPENCV_FLAT_DIR}/${_so_name}" COPYONLY)
endforeach()

# Import the monolithic library on 32-bit ABIs and any split component
# libraries present in the arm64 package.
set(OPENCV_LIBS opencv_java5)
set(OPENCV_OPTIONAL_LIBS opencv_imgproc opencv_core opencv_imgcodecs)
foreach(_lib ${OPENCV_OPTIONAL_LIBS})
    if(EXISTS "${OPENCV_FLAT_DIR}/lib${_lib}.so")
        list(APPEND OPENCV_LIBS ${_lib})
    endif()
endforeach()
foreach(_lib ${OPENCV_LIBS})
    add_library(${_lib} SHARED IMPORTED)
    set_target_properties(${_lib} PROPERTIES
        IMPORTED_LOCATION "${OPENCV_FLAT_DIR}/lib${_lib}.so"
        IMPORTED_NO_SONAME TRUE)
endforeach()

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
target_link_libraries(quickjs_jni PRIVATE quickjs ${OPENCV_LIBS} log)

# 脚本解密的原生实现：单独一个小 .so，不依赖 QuickJS/OpenCV，
# 这样 Rhino 工程的产物不必为了解密把整个引擎加载起来。
add_library(aijscrypto SHARED script_crypto.c)
target_compile_features(aijscrypto PRIVATE c_std_11)
target_compile_options(aijscrypto PRIVATE -fPIC -Wall -Wextra)
target_link_options(aijscrypto PRIVATE
        "-Wl,-z,max-page-size=16384"
        "$<$<CONFIG:Release>:-Wl,--strip-all>")
target_link_libraries(aijscrypto PRIVATE log dl)
