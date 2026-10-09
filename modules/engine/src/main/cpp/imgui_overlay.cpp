#include <jni.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <mutex>
#include <string>
#include <vector>
#include "imgui.h"
#include "imgui_internal.h"
#include "imgui_font.h"
#include "backends/imgui_impl_opengl3.h"

namespace {
// Dear ImGui's default current-context pointer is process global. Serialize
// contexts from separate script/GL threads and select the owner on every call.
std::mutex contextsMutex;
struct Overlay {
    ImGuiContext* context;
    std::chrono::steady_clock::time_point last = std::chrono::steady_clock::now();
    std::string binding;
};
std::string utf8(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
ImU32 color(jint argb) {
    return IM_COL32((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, (static_cast<uint32_t>(argb) >> 24) & 255);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_stardust_autojs_core_graphics_ImGuiDrawingOverlay_nativeInit(JNIEnv* env, jclass, jstring fontPath, jfloat fontSize) {
    std::lock_guard<std::mutex> lock(contextsMutex);
    auto* overlay = new Overlay{ImGui::CreateContext()};
    ImGui::SetCurrentContext(overlay->context);
    ImGuiIO& io = ImGui::GetIO();
    io.IniFilename = nullptr;
    io.LogFilename = nullptr;
    std::string font = utf8(env, fontPath);
    bool fallback = false;
    if (!buildImGuiFonts(io, font.c_str(), fontSize, &fallback)) {
        __android_log_print(ANDROID_LOG_ERROR, "ImGuiOverlay", "Font atlas initialization failed");
        ImGui::DestroyContext(overlay->context); delete overlay; return 0;
    }
    if (fallback) __android_log_print(ANDROID_LOG_WARN, "ImGuiOverlay", "CJK font rejected; using Latin fallback");
    // 字形数是「字体子集是否覆盖 ImGui 需要的全部码位」的客观信号：
    // 子集化过的 fonts/NotoSansCJKsc-Regular.otf 必须和上游完整字体给出同一个数
    // （工具与校验见 tools/imgui-font-subset.py）。
    // ⚠️ CJK 字体是用 MergeMode 合并进第 0 个字体的，不会新建 ImFont，所以数在 Fonts[0] 上。
    {
        const int fonts = io.Fonts->Fonts.Size;
        __android_log_print(ANDROID_LOG_INFO, "ImGuiOverlay",
                            "font atlas: fonts=%d glyphs0=%d size=%.1f",
                            fonts, fonts > 0 ? io.Fonts->Fonts[0]->Glyphs.Size : 0, fontSize);
    }
    if (!ImGui_ImplOpenGL3_Init("#version 300 es")) {
        ImGui::DestroyContext(overlay->context);
        delete overlay;
        return 0;
    }
    return reinterpret_cast<jlong>(overlay);
}

extern "C" JNIEXPORT void JNICALL
Java_com_stardust_autojs_core_graphics_ImGuiDrawingOverlay_nativeRender(
        JNIEnv* env, jclass, jlong handle, jint width, jint height, jint offsetX, jint offsetY, jint hudTop,
        jfloatArray dataArray, jintArray colorArray, jobjectArray labelArray, jstring statsValue) {
    if (!handle || width <= 0 || height <= 0) return;
    std::lock_guard<std::mutex> lock(contextsMutex);
    auto* overlay = reinterpret_cast<Overlay*>(handle);
    ImGui::SetCurrentContext(overlay->context);
    ImGuiIO& io = ImGui::GetIO();
    io.DisplaySize = ImVec2(static_cast<float>(width), static_cast<float>(height));
    auto now = std::chrono::steady_clock::now();
    io.DeltaTime = std::max(0.001f, std::min(0.1f, std::chrono::duration<float>(now - overlay->last).count()));
    overlay->last = now;
    const int count = std::min({env->GetArrayLength(dataArray) / 8,
                              env->GetArrayLength(colorArray), env->GetArrayLength(labelArray)});
    std::vector<float> data(count * 8);
    std::vector<jint> colors(count);
    if (count) {
        env->GetFloatArrayRegion(dataArray, 0, count * 8, data.data());
        env->GetIntArrayRegion(colorArray, 0, count, colors.data());
    }
    if (env->ExceptionCheck()) return;
    ImGui_ImplOpenGL3_NewFrame();
    ImGui::NewFrame();
    ImDrawList* draw = ImGui::GetForegroundDrawList();
    float hudX = 12, hudY = std::max(0, hudTop - offsetY), hudWidth = std::max(1, width - 24), hudHeight = height - hudY;
    for (int i = 0; i < count; i++) {
        const float* p = data.data() + i * 8;
        ImVec2 a(p[1] - offsetX, p[2] - offsetY), b(p[3] - offsetX, p[4] - offsetY);
        const ImU32 c = color(colors[i]);
        const float stroke = std::max(1.0f, p[6]);
        switch (static_cast<int>(p[0])) {
            case 1: draw->AddLine(a, b, c, stroke); break;
            case 2: draw->AddCircle(a, std::max(1.0f, p[5]), c, 0, stroke); break;
            case 3: break;
            case 4: {
                const float dx = b.x - a.x, dy = b.y - a.y;
                const float length = std::hypot(dx, dy);
                if (length < 1.0f) break;
                const float head = std::min(std::max(4.0f, p[5]), length * 0.4f);
                const float ux = dx / length, uy = dy / length;
                const ImVec2 base(b.x - ux * head, b.y - uy * head);
                draw->AddLine(a, base, c, stroke);
                draw->AddTriangleFilled(b, ImVec2(base.x - uy * head * 0.5f, base.y + ux * head * 0.5f),
                        ImVec2(base.x + uy * head * 0.5f, base.y - ux * head * 0.5f), c);
                break;
            }
            case 5:
                // A HUD rectangle constrains text to an unoccupied screen band.
                // Empty bounds explicitly disable the HUD without removing the surface.
                hudX = std::max(0.0f, a.x); hudY = std::max(0.0f, a.y);
                hudWidth = std::max(0.0f, std::min(b.x - a.x, width - hudX));
                hudHeight = std::max(0.0f, std::min(b.y - a.y, height - hudY));
                break;
            default: draw->AddRect(a, b, c, 0, 0, stroke); break;
        }
        auto text = static_cast<jstring>(env->GetObjectArrayElement(labelArray, i));
        std::string label = utf8(env, text);
        env->DeleteLocalRef(text);
        if (!label.empty()) draw->AddText(ImGui::GetFont(), std::max(8.0f, p[7]),
                ImVec2(a.x, static_cast<int>(p[0]) == 3 ? a.y : std::max(0.0f, a.y - p[7])), c, label.c_str());
    }
    std::string stats = utf8(env, statsValue);
    if (!stats.empty() && hudWidth > 0 && hudHeight > 0) {
        ImGui::SetNextWindowPos(ImVec2(hudX, hudY), ImGuiCond_Always);
        ImGui::SetNextWindowBgAlpha(0.7f);
        ImGui::SetNextWindowSizeConstraints(ImVec2(hudWidth, 0), ImVec2(hudWidth, hudHeight));
        ImGui::SetNextWindowSize(ImVec2(hudWidth, 0), ImGuiCond_Always);
        ImGui::Begin("Chess status", nullptr, ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_AlwaysAutoResize
                | ImGuiWindowFlags_NoInputs | ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoFocusOnAppearing);
        ImGui::PushTextWrapPos(ImGui::GetContentRegionAvail().x);
        ImGui::TextUnformatted(stats.c_str());
        ImGui::PopTextWrapPos();
        ImGui::End();
    }
    ImGui::Render();
    glViewport(0, 0, width, height);
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT);
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
}

extern "C" JNIEXPORT void JNICALL
Java_com_stardust_autojs_core_graphics_ImGuiDrawingOverlay_nativeShutdown(JNIEnv*, jclass, jlong handle) {
    if (!handle) return;
    std::lock_guard<std::mutex> lock(contextsMutex);
    auto* overlay = reinterpret_cast<Overlay*>(handle);
    ImGui::SetCurrentContext(overlay->context);
    ImGui_ImplOpenGL3_Shutdown();
    ImGui::DestroyContext(overlay->context);
    delete overlay;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_stardust_autojs_core_graphics_ImGuiControlPanel_nativeInit(JNIEnv* env, jclass cls, jstring font, jfloat size) {
    return Java_com_stardust_autojs_core_graphics_ImGuiDrawingOverlay_nativeInit(env, cls, font, size);
}

extern "C" JNIEXPORT void JNICALL
Java_com_stardust_autojs_core_graphics_ImGuiControlPanel_nativeShutdown(JNIEnv* env, jclass cls, jlong handle) {
    Java_com_stardust_autojs_core_graphics_ImGuiDrawingOverlay_nativeShutdown(env, cls, handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_stardust_autojs_core_graphics_ImGuiControlPanel_nativeRender(JNIEnv* env, jclass, jlong handle,
        jint width, jint height, jfloat density, jint columns, jstring titleValue, jstring bindingValue,
        jobjectArray idArray, jobjectArray labelArray, jbooleanArray enabledArray, jint action, jfloat x, jfloat y) {
    if (!handle || width <= 0 || height <= 0) return -1;
    std::lock_guard<std::mutex> lock(contextsMutex);
    auto* overlay = reinterpret_cast<Overlay*>(handle);
    ImGui::SetCurrentContext(overlay->context);
    ImGuiIO& io = ImGui::GetIO();
    std::string binding = utf8(env, bindingValue);
    if (binding != overlay->binding || action == -1) {
        ImGui::ClearActiveID(); io.ClearEventsQueue(); io.ClearInputMouse();
        io.AddMousePosEvent(-FLT_MAX, -FLT_MAX);
        overlay->binding = binding;
    }
    if (action >= 0) {
        io.AddMouseSourceEvent(ImGuiMouseSource_TouchScreen);
        io.AddMousePosEvent(x, y);
        if (action == 0 || action == 1) io.AddMouseButtonEvent(0, action == 0);
    }
    io.DisplaySize = ImVec2(static_cast<float>(width), static_cast<float>(height));
    auto now = std::chrono::steady_clock::now();
    io.DeltaTime = std::max(0.001f, std::min(0.1f, std::chrono::duration<float>(now - overlay->last).count()));
    overlay->last = now;
    ImGuiStyle& style = ImGui::GetStyle();
    style.WindowPadding = ImVec2(6 * density, 6 * density);
    style.ItemSpacing = ImVec2(4 * density, 4 * density);
    style.FrameRounding = 6 * density;
    style.WindowRounding = 8 * density;
    style.Colors[ImGuiCol_WindowBg] = ImVec4(0.06f, 0.09f, 0.12f, 0.96f);
    style.Colors[ImGuiCol_Button] = ImVec4(0.05f, 0.42f, 0.40f, 1);
    style.Colors[ImGuiCol_ButtonHovered] = ImVec4(0.07f, 0.58f, 0.54f, 1);
    style.Colors[ImGuiCol_ButtonActive] = ImVec4(0.10f, 0.68f, 0.62f, 1);
    int count = std::min({12, env->GetArrayLength(idArray), env->GetArrayLength(labelArray), env->GetArrayLength(enabledArray)});
    std::vector<jboolean> enabled(count);
    if (count) env->GetBooleanArrayRegion(enabledArray, 0, count, enabled.data());
    if (env->ExceptionCheck()) return -1;
    ImGui_ImplOpenGL3_NewFrame(); ImGui::NewFrame();
    ImGui::SetNextWindowPos(ImVec2(0, 0), ImGuiCond_Always);
    ImGui::SetNextWindowSize(io.DisplaySize, ImGuiCond_Always);
    ImGui::Begin("Controls", nullptr, ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove
            | ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoNav | ImGuiWindowFlags_NoScrollWithMouse);
    std::string title = utf8(env, titleValue);
    ImGui::TextUnformatted(title.c_str()); ImGui::Separator();
    columns = std::max(1, std::min(columns, count));
    const float buttonWidth = std::max(1.0f, (ImGui::GetContentRegionAvail().x - (columns - 1) * style.ItemSpacing.x) / columns);
    int clicked = -1;
    for (int i = 0; i < count; i++) {
        if (i % columns != 0) ImGui::SameLine();
        auto id = static_cast<jstring>(env->GetObjectArrayElement(idArray, i));
        auto label = static_cast<jstring>(env->GetObjectArrayElement(labelArray, i));
        std::string key = utf8(env, id), text = utf8(env, label);
        env->DeleteLocalRef(id); env->DeleteLocalRef(label);
        ImGui::PushID(key.c_str()); ImGui::BeginDisabled(!enabled[i]);
        if (ImGui::Button((text + "###button").c_str(), ImVec2(buttonWidth, 48 * density))) clicked = i;
        ImGui::EndDisabled(); ImGui::PopID();
    }
    ImGui::End(); ImGui::Render();
    glViewport(0, 0, width, height); glClearColor(0, 0, 0, 0); glClear(GL_COLOR_BUFFER_BIT);
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
    return clicked;
}
