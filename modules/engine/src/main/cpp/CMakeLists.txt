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
set(OPENCV_OPTIONAL_LIBS opencv_imgproc opencv_core opencv_imgcodecs opencv_features opencv_flann opencv_geometry)
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
        ${QUICKJS_DIR}/libunicode.c)
        # 2026-09-24 版 quickjs-ng：cutils.c 已上游合并（头文件改 static inline），不再单独编译。
target_include_directories(quickjs PUBLIC ${QUICKJS_DIR})
target_compile_features(quickjs PRIVATE c_std_11)
target_compile_definitions(quickjs PRIVATE
        _GNU_SOURCE
        CONFIG_VERSION="2026-09-24")
target_compile_options(quickjs PRIVATE
        -fPIC
        -funsigned-char
        -Wno-sign-compare
        -Wno-missing-field-initializers)
target_link_options(quickjs PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
        "$<$<CONFIG:Release>:-Wl,--strip-all>")
target_link_libraries(quickjs PRIVATE log m)

add_library(quickjs_jni SHARED
        quickjs_jni.cpp
        native_frame_store.cpp
        sift_matcher.cpp
        xiangqi_board_detector.cpp
        imgui_overlay.cpp
        third_party/imgui/imgui.cpp
        third_party/imgui/imgui_draw.cpp
        third_party/imgui/imgui_tables.cpp
        third_party/imgui/imgui_widgets.cpp
        third_party/imgui/backends/imgui_impl_opengl3.cpp)
target_compile_features(quickjs_jni PRIVATE cxx_std_17)
target_include_directories(quickjs_jni PRIVATE
        ${QUICKJS_DIR}
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party/imgui
        ${OPENCV_INCLUDE_DIR})
target_compile_definitions(quickjs_jni PRIVATE CONFIG_VERSION="2026-09-24")
target_compile_definitions(quickjs_jni PRIVATE IMGUI_IMPL_OPENGL_ES3)
if(TARGET opencv_features AND TARGET opencv_geometry AND TARGET opencv_flann)
    target_compile_definitions(quickjs_jni PRIVATE AIJS_HAS_SIFT=1)
endif()
# ImGui 的 C++ 符号（如 ImGui::Button）默认是 default visibility。第三方脚本自带的 native 库
# 常通过 dlsym(RTLD_DEFAULT) 按符号名找 ImGui 函数；若本库先加载，它们会解析到我们这版
# （另一套 ImGuiContext），调用时直接 SIGSEGV。JNI 入口都带 JNIEXPORT（visibility default），
# 所以这里把其余符号默认隐藏，避免污染进程全局符号表。
target_compile_options(quickjs_jni PRIVATE
        -fPIC -Wall -Wextra
        -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_options(quickjs_jni PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
        "$<$<CONFIG:Release>:-Wl,--strip-all>")
target_link_libraries(quickjs_jni PRIVATE quickjs ${OPENCV_LIBS} log dl GLESv3)

# 脚本解密的原生实现：单独一个小 .so，不依赖 QuickJS/OpenCV，
# 这样 Rhino 工程的产物不必为了解密把整个引擎加载起来。
add_library(aijscrypto SHARED script_crypto.c)
target_compile_features(aijscrypto PRIVATE c_std_11)
target_compile_options(aijscrypto PRIVATE -fPIC -Wall -Wextra)
target_link_options(aijscrypto PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
        "$<$<CONFIG:Release>:-Wl,--strip-all>")
target_link_libraries(aijscrypto PRIVATE log dl)

# PP-OCR（ncnn）文字识别引擎：单独一个 .so，不依赖 QuickJS，Rhino/QuickJS 两个引擎都能用。
# 只在 arm64-v8a 提供（仓库只 vendor 了 arm64 的 ncnn 静态库）；其余 ABI 没有这个 .so，
# Java 侧会检测不到并回退到 ML Kit。
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(NCNN_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/ncnn/${ANDROID_ABI}")
    if(NOT EXISTS "${NCNN_ROOT}/lib/libncnn.a")
        message(FATAL_ERROR "vendored ncnn static library is missing: ${NCNN_ROOT}/lib/libncnn.a")
    endif()
    if(NOT EXISTS "${NCNN_ROOT}/include/ncnn/net.h")
        message(FATAL_ERROR "vendored ncnn headers are missing: ${NCNN_ROOT}/include/ncnn")
    endif()

    add_library(ncnn STATIC IMPORTED)
    set_target_properties(ncnn PROPERTIES
            IMPORTED_LOCATION "${NCNN_ROOT}/lib/libncnn.a")

    # OpenCV 5 把 boundingRect / minAreaRect / getAffineTransform 挪到了 geometry 模块，
    # PP-OCR 的检测框与透视裁剪要用（QuickJS 那条链不需要，所以只给 ppocr 加）。
    set(PPOCR_OPENCV_LIBS ${OPENCV_LIBS})
    if(EXISTS "${OPENCV_FLAT_DIR}/libopencv_geometry.so")
        if(NOT TARGET opencv_geometry)
        add_library(opencv_geometry SHARED IMPORTED)
        set_target_properties(opencv_geometry PROPERTIES
                IMPORTED_LOCATION "${OPENCV_FLAT_DIR}/libopencv_geometry.so"
                IMPORTED_NO_SONAME TRUE)
        endif()
        list(APPEND PPOCR_OPENCV_LIBS opencv_geometry)
    else()
        message(FATAL_ERROR "PP-OCR 需要 OpenCV geometry 模块：${OPENCV_FLAT_DIR}/libopencv_geometry.so 不存在")
    endif()

    add_library(ppocr SHARED
            ppocr/ppocr_jni.cpp
            ppocr/ppocr_engine.cpp)
    target_compile_features(ppocr PRIVATE cxx_std_17)
    target_include_directories(ppocr PRIVATE
            "${CMAKE_CURRENT_SOURCE_DIR}/ppocr"
            "${NCNN_ROOT}/include/ncnn"
            ${OPENCV_INCLUDE_DIR})
    target_compile_options(ppocr PRIVATE -fPIC -Wall -Wextra)
    # PP-OCR 的识别阶段会开工作线程，ncnn 内部单线程；这里沿用仓库统一的 16KB page 与 strip 约定。
    target_link_options(ppocr PRIVATE
            "-Wl,-z,max-page-size=16384"
            "-Wl,-z,common-page-size=16384"
            "$<$<CONFIG:Release>:-Wl,--strip-all>")
    target_link_libraries(ppocr PRIVATE ncnn ${PPOCR_OPENCV_LIBS} jnigraphics android log dl z m)
endif()
