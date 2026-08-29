cmake_minimum_required(VERSION 3.10.2)
project(autojs_imgui)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(IMGUI_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/imgui")

add_library(autojs_imgui SHARED
    autojs_imgui.cpp
    ${IMGUI_DIR}/imgui.cpp
    ${IMGUI_DIR}/imgui_draw.cpp
    ${IMGUI_DIR}/imgui_tables.cpp
    ${IMGUI_DIR}/imgui_widgets.cpp
    ${IMGUI_DIR}/imgui_impl_opengl3.cpp)

target_include_directories(autojs_imgui PRIVATE ${IMGUI_DIR})
target_compile_definitions(autojs_imgui PRIVATE IMGUI_IMPL_OPENGL_ES2)
target_compile_options(autojs_imgui PRIVATE -Wall -Wextra -Wno-unused-parameter)

find_library(ANDROID_LIBRARY android)
find_library(EGL_LIBRARY EGL)
find_library(GLES_LIBRARY GLESv2)
find_library(LOG_LIBRARY log)

target_link_libraries(autojs_imgui
    ${ANDROID_LIBRARY}
    ${EGL_LIBRARY}
    ${GLES_LIBRARY}
    ${LOG_LIBRARY})

# YOLO is intentionally arm64-only for the first release. The JavaScript API
# reports a clear unsupported-ABI message on the other application splits.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(NCNN_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/ncnn/arm64-v8a")
    set(NCNN_LIBRARY "${NCNN_ROOT}/lib/libncnn.a")
    set(ONNXRUNTIME_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/onnxruntime/arm64-v8a")
    set(ONNXRUNTIME_LIBRARY "${ONNXRUNTIME_ROOT}/lib/libonnxruntime.so")

    if(NOT EXISTS "${NCNN_LIBRARY}")
        message(FATAL_ERROR "The vendored arm64 ncnn library is missing: ${NCNN_LIBRARY}")
    endif()
    if(NOT EXISTS "${ONNXRUNTIME_LIBRARY}")
        message(FATAL_ERROR "The vendored arm64 ONNX Runtime library is missing: ${ONNXRUNTIME_LIBRARY}")
    endif()

    add_library(ncnn_autojs STATIC IMPORTED)
    set_target_properties(ncnn_autojs PROPERTIES
        IMPORTED_LOCATION "${NCNN_LIBRARY}"
        INTERFACE_INCLUDE_DIRECTORIES "${NCNN_ROOT}/include/ncnn")

    add_library(onnxruntime_autojs SHARED IMPORTED)
    set_target_properties(onnxruntime_autojs PROPERTIES
        IMPORTED_LOCATION "${ONNXRUNTIME_LIBRARY}"
        INTERFACE_INCLUDE_DIRECTORIES "${ONNXRUNTIME_ROOT}/include")

    add_library(autojs_yolo SHARED autojs_yolo.cpp)
    target_include_directories(autojs_yolo PRIVATE
        "${NCNN_ROOT}/include/ncnn"
        "${ONNXRUNTIME_ROOT}/include")
    target_compile_options(autojs_yolo PRIVATE
        -Wall -Wextra -Wno-unused-parameter
        $<$<OR:$<CONFIG:Release>,$<CONFIG:RelWithDebInfo>>:-O3;-DNDEBUG;-ffast-math;-fno-math-errno>)
    target_link_options(autojs_yolo PRIVATE
        -Wl,--gc-sections)
    target_link_libraries(autojs_yolo
        ncnn_autojs
        onnxruntime_autojs
        ${ANDROID_LIBRARY}
        ${LOG_LIBRARY}
        jnigraphics)
endif()
