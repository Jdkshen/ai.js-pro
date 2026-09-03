cmake_minimum_required(VERSION 3.10.2)
project(autojs_imgui)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(IMGUI_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/imgui")

add_library(autojs_imgui SHARED
    autojs_imgui.cpp
    imgui_accessibility_bridge.cpp
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
