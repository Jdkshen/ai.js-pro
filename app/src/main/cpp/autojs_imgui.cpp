#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>

#include "imgui.h"
#include "imgui_impl_opengl3.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

constexpr char kLogTag[] = "AutoJsImGui";

ANativeWindow *gWindow = nullptr;
EGLDisplay gDisplay = EGL_NO_DISPLAY;
EGLSurface gSurface = EGL_NO_SURFACE;
EGLContext gContext = EGL_NO_CONTEXT;
std::recursive_mutex gRenderMutex;
std::mutex gInputMutex;
std::atomic<int> gPendingAction{0};
std::atomic<int> gSelectedBreadcrumbDepth{-1};
std::atomic<bool> gAccessibilityEnabled{false};
std::atomic<int> gRunningScriptCount{0};
std::string gFontPath;
struct ScriptEntry {
    std::string name;
    std::string path;
    bool directory;
    std::string typeLabel;
    std::string modifiedLabel;
    int iconKind;
};
struct SampleEntry {
    std::string name;
    std::string path;
    bool directory;
    std::string typeLabel;
    std::string modifiedLabel;
};
struct ResourceEntry {
    std::string name;
    std::string description;
    std::string metadata;
    std::string assetPath;
    bool imported;
};
struct PluginEntry {
    std::string name;
    std::string version;
    std::string packageName;
    bool installed;
};
struct TaskEntry {
    std::string name;
    std::string description;
};
std::string gScriptDirectoryLabel;
std::vector<ScriptEntry> gScriptEntries;
// 脚本/示例目录滚动位置缓存：按目录标签保存，返回父目录时恢复，进入新目录时滚顶。
// 版本号用于检测列表数据更新（setScriptEntries / setSampleEntries 回调）。
std::unordered_map<std::string, float> gScriptScrollCache;
int gScriptEntriesRevision = 0;
int gAppliedScriptRevision = -1;
std::string gSampleDirectoryLabel = u8"示例文件  >  中文";
std::vector<SampleEntry> gSampleEntries;
std::unordered_map<std::string, float> gSampleScrollCache;
int gSampleEntriesRevision = 0;
int gAppliedSampleRevision = -1;
std::vector<ResourceEntry> gResourceEntries;
std::vector<PluginEntry> gPluginEntries;
std::vector<TaskEntry> gRunningTasks;
std::vector<TaskEntry> gPendingTasks;
int gSelectedScript = -1;
int gRowMenuIndex = -1;
int gSelectedSample = -1;
int gSampleRowMenuIndex = -1;
int gSelectedResource = -1;
int gSelectedPlugin = -1;
int gSelectedTaskGroup = -1;
int gSelectedTaskIndex = -1;
bool gResourceImportedOnly = false;
bool gTaskGroupsExpanded[2] = {false, false};
float gUiScale = 1.0f;
bool gUiConfigured = false;
int gSection = 0;
bool gCreateMenuOpen = false;
bool gTouchActive = false;
bool gTouchScrolling = false;
bool gTouchPaging = false;
bool gTouchMoved = false;
float gTouchStartX = 0.0f;
float gTouchStartY = 0.0f;
float gTouchLastX = 0.0f;
float gTouchLastY = 0.0f;
float gTouchVelocityX = 0.0f;
float gTouchVelocityY = 0.0f;
std::chrono::steady_clock::time_point gTouchLastEvent = std::chrono::steady_clock::now();
float gPendingScrollPixels = 0.0f;
float gPendingFlingVelocity = 0.0f;
bool gFlingPending = false;
bool gCancelFlingPending = false;
bool gInputPageDragging = false;
float gInputPageDragPixels = 0.0f;
bool gPageReleasePending = false;
bool gPageReleaseCancelled = false;
float gPageReleasePixels = 0.0f;
float gPageReleaseVelocity = 0.0f;
float gTapX = 0.0f;
float gTapY = 0.0f;
int gTapPhase = 0;
int gSuppressInputFrames = 0;
bool gAllowActivationThisFrame = false;
int gAuthorizedTapFrames = 0;
float gFrameScrollPixels = 0.0f;
float gScrollVelocity = 0.0f;
float gPageOffset = 0.0f;
float gPageAnimationStart = 0.0f;
float gPageAnimationTarget = 0.0f;
float gPageAnimationElapsed = 0.0f;
float gPageAnimationDuration = 0.22f;
int gPageTargetSection = -1;
bool gPageAnimating = false;
std::atomic<float> gTouchScale{1.0f};
std::chrono::steady_clock::time_point gLastFrame = std::chrono::steady_clock::now();

void logError(const char *message) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s (EGL 0x%x)", message, eglGetError());
}

void queueAction(int action) {
    gPendingAction.store(action);
}

void applyPendingVerticalScroll() {
    if (std::abs(gFrameScrollPixels) < 0.01f) {
        return;
    }
    ImGui::SetScrollY(ImGui::GetScrollY() + gFrameScrollPixels);
    gFrameScrollPixels = 0.0f;
}

void applyTheme() {
    ImGuiStyle &style = ImGui::GetStyle();
    style.WindowRounding = 0.0f;
    style.ChildRounding = 12.0f * gUiScale;
    style.FrameRounding = 8.0f * gUiScale;
    style.PopupRounding = 10.0f * gUiScale;
    style.ScrollbarRounding = 8.0f * gUiScale;
    style.GrabRounding = 8.0f * gUiScale;
    style.WindowPadding = ImVec2(14.0f * gUiScale, 14.0f * gUiScale);
    style.FramePadding = ImVec2(12.0f * gUiScale, 9.0f * gUiScale);
    style.ItemSpacing = ImVec2(10.0f * gUiScale, 10.0f * gUiScale);
    style.ChildBorderSize = 1.0f * gUiScale;

    ImVec4 *colors = style.Colors;
    colors[ImGuiCol_WindowBg] = ImVec4(0.035f, 0.041f, 0.047f, 1.0f);
    colors[ImGuiCol_ChildBg] = ImVec4(0.065f, 0.073f, 0.080f, 1.0f);
    colors[ImGuiCol_PopupBg] = ImVec4(0.065f, 0.073f, 0.080f, 0.98f);
    colors[ImGuiCol_Border] = ImVec4(0.16f, 0.19f, 0.18f, 1.0f);
    colors[ImGuiCol_Text] = ImVec4(0.93f, 0.95f, 0.94f, 1.0f);
    colors[ImGuiCol_TextDisabled] = ImVec4(0.53f, 0.58f, 0.55f, 1.0f);
    colors[ImGuiCol_Button] = ImVec4(0.00f, 0.30f, 0.27f, 1.0f);
    colors[ImGuiCol_ButtonHovered] = ImVec4(0.00f, 0.42f, 0.38f, 1.0f);
    colors[ImGuiCol_ButtonActive] = ImVec4(0.00f, 0.52f, 0.47f, 1.0f);
    colors[ImGuiCol_Header] = ImVec4(0.00f, 0.34f, 0.31f, 1.0f);
    colors[ImGuiCol_HeaderHovered] = ImVec4(0.00f, 0.42f, 0.38f, 1.0f);
    colors[ImGuiCol_HeaderActive] = ImVec4(0.00f, 0.52f, 0.47f, 1.0f);
    colors[ImGuiCol_CheckMark] = ImVec4(0.00f, 0.59f, 0.53f, 1.0f);
    colors[ImGuiCol_SliderGrab] = ImVec4(0.00f, 0.59f, 0.53f, 1.0f);
}

bool initEgl(ANativeWindow *window) {
    gDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (gDisplay == EGL_NO_DISPLAY || !eglInitialize(gDisplay, nullptr, nullptr)) {
        logError("eglInitialize failed");
        return false;
    }

    const EGLint configAttributes[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_NONE};
    EGLConfig config = nullptr;
    EGLint configCount = 0;
    if (!eglChooseConfig(gDisplay, configAttributes, &config, 1, &configCount) || configCount == 0) {
        logError("eglChooseConfig failed");
        return false;
    }

    const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    gContext = eglCreateContext(gDisplay, config, EGL_NO_CONTEXT, contextAttributes);
    gSurface = eglCreateWindowSurface(gDisplay, config, window, nullptr);
    if (gContext == EGL_NO_CONTEXT || gSurface == EGL_NO_SURFACE) {
        logError("EGL context or surface creation failed");
        return false;
    }
    if (!eglMakeCurrent(gDisplay, gSurface, gSurface, gContext)) {
        logError("eglMakeCurrent failed");
        return false;
    }
    eglSwapInterval(gDisplay, 1);

    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    // A Surface recreation creates a fresh ImGui window tree whose scroll position starts at
    // zero. Force the first frame to restore the cached list positions before it is allowed to
    // write the new context's zero value back into the cache.
    gAppliedScriptRevision = -1;
    gAppliedSampleRevision = -1;
    ImGui::StyleColorsDark();
    if (!ImGui_ImplOpenGL3_Init("#version 100")) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "ImGui OpenGL backend initialization failed");
        return false;
    }

    // The Android UI thread creates the surface. Rendering owns the context afterwards.
    eglMakeCurrent(gDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    gLastFrame = std::chrono::steady_clock::now();
    return true;
}

void shutdownEgl() {
    if (gDisplay != EGL_NO_DISPLAY) {
        const bool current = gContext != EGL_NO_CONTEXT && gSurface != EGL_NO_SURFACE &&
                             eglMakeCurrent(gDisplay, gSurface, gSurface, gContext);
        if (ImGui::GetCurrentContext()) {
            ImGui_ImplOpenGL3_Shutdown();
            ImGui::DestroyContext();
        }
        if (current) {
            eglMakeCurrent(gDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        if (gContext != EGL_NO_CONTEXT) {
            eglDestroyContext(gDisplay, gContext);
        }
        if (gSurface != EGL_NO_SURFACE) {
            eglDestroySurface(gDisplay, gSurface);
        }
        eglTerminate(gDisplay);
    }
    if (gWindow) {
        ANativeWindow_release(gWindow);
        gWindow = nullptr;
    }
    gDisplay = EGL_NO_DISPLAY;
    gSurface = EGL_NO_SURFACE;
    gContext = EGL_NO_CONTEXT;
    gUiScale = 1.0f;
    gUiConfigured = false;
}

void configureUi(float density) {
    gUiScale = std::max(1.0f, density);
    gTouchScale.store(gUiScale, std::memory_order_relaxed);
    ImGuiIO &io = ImGui::GetIO();
    io.IniFilename = nullptr;
    io.LogFilename = nullptr;
    io.Fonts->Clear();

    ImFont *font = nullptr;
    if (!gFontPath.empty()) {
        ImFontConfig fontConfig;
        fontConfig.OversampleH = 2;
        fontConfig.OversampleV = 2;
        font = io.Fonts->AddFontFromFileTTF(gFontPath.c_str(), 17.0f * gUiScale,
                                           &fontConfig,
                                           io.Fonts->GetGlyphRangesChineseSimplifiedCommon());
    }
    if (!font) {
        io.Fonts->AddFontDefault();
    }
    applyTheme();
    gUiConfigured = true;
}

enum class ToolbarIcon {
    Menu,
    Code,
    File,
    Bookmark,
    Search,
    Filter,
    More
};

bool toolbarIconButton(const char *id, ToolbarIcon icon) {
    const float size = 40.0f * gUiScale;
    const bool clicked = ImGui::InvisibleButton(id, ImVec2(size, size)) &&
                         gAllowActivationThisFrame;
    const ImVec2 minimum = ImGui::GetItemRectMin();
    const ImVec2 maximum = ImGui::GetItemRectMax();
    const ImVec2 center((minimum.x + maximum.x) * 0.5f,
                        (minimum.y + maximum.y) * 0.5f);
    ImDrawList *drawList = ImGui::GetWindowDrawList();
    if (ImGui::IsItemActive()) {
        drawList->AddCircleFilled(center, 18.0f * gUiScale, IM_COL32(255, 255, 255, 34));
    }
    const ImU32 color = IM_COL32(255, 255, 255, 220);
    const float thickness = 2.2f * gUiScale;
    if (icon == ToolbarIcon::Menu) {
        for (int line = -1; line <= 1; ++line) {
            const float y = center.y + line * 6.0f * gUiScale;
            drawList->AddLine(ImVec2(center.x - 11.0f * gUiScale, y),
                              ImVec2(center.x + 11.0f * gUiScale, y), color, thickness);
        }
    } else if (icon == ToolbarIcon::Code) {
        drawList->AddLine(ImVec2(center.x - 11.0f * gUiScale, center.y),
                          ImVec2(center.x - 5.0f * gUiScale, center.y - 6.0f * gUiScale), color, thickness);
        drawList->AddLine(ImVec2(center.x - 11.0f * gUiScale, center.y),
                          ImVec2(center.x - 5.0f * gUiScale, center.y + 6.0f * gUiScale), color, thickness);
        drawList->AddLine(ImVec2(center.x + 11.0f * gUiScale, center.y),
                          ImVec2(center.x + 5.0f * gUiScale, center.y - 6.0f * gUiScale), color, thickness);
        drawList->AddLine(ImVec2(center.x + 11.0f * gUiScale, center.y),
                          ImVec2(center.x + 5.0f * gUiScale, center.y + 6.0f * gUiScale), color, thickness);
    } else if (icon == ToolbarIcon::File) {
        ImVec2 page[] = {
                ImVec2(center.x-9*gUiScale,center.y-12*gUiScale),
                ImVec2(center.x+2*gUiScale,center.y-12*gUiScale),
                ImVec2(center.x+9*gUiScale,center.y-5*gUiScale),
                ImVec2(center.x+9*gUiScale,center.y+12*gUiScale),
                ImVec2(center.x-9*gUiScale,center.y+12*gUiScale)
        };
        drawList->AddConvexPolyFilled(page,5,color);
        const ImU32 cutout=IM_COL32(16,17,18,255);
        for (int line = -1; line <= 1; ++line) {
            const float y = center.y + (line*4.5f+2.0f) * gUiScale;
            drawList->AddLine(ImVec2(center.x - 4.5f * gUiScale, y),
                              ImVec2(center.x + 5.0f * gUiScale, y), cutout, 1.5f*gUiScale);
        }
    } else if (icon == ToolbarIcon::Bookmark) {
        ImVec2 bookmark[] = {
                ImVec2(center.x-8*gUiScale,center.y-12*gUiScale),
                ImVec2(center.x+8*gUiScale,center.y-12*gUiScale),
                ImVec2(center.x+8*gUiScale,center.y+11*gUiScale),
                ImVec2(center.x,center.y+6*gUiScale),
                ImVec2(center.x-8*gUiScale,center.y+11*gUiScale)
        };
        drawList->AddConvexPolyFilled(bookmark,5,color);
    } else if (icon == ToolbarIcon::Search) {
        drawList->AddCircle(ImVec2(center.x - 3.0f * gUiScale, center.y - 3.0f * gUiScale),
                            8.0f * gUiScale, color, 24, thickness);
        drawList->AddLine(ImVec2(center.x + 3.0f * gUiScale, center.y + 3.0f * gUiScale),
                          ImVec2(center.x + 11.0f * gUiScale, center.y + 11.0f * gUiScale),
                          color, thickness);
    } else if (icon == ToolbarIcon::Filter) {
        ImVec2 funnel[] = {
                ImVec2(center.x - 11.0f * gUiScale, center.y - 10.0f * gUiScale),
                ImVec2(center.x + 11.0f * gUiScale, center.y - 10.0f * gUiScale),
                ImVec2(center.x + 4.0f * gUiScale, center.y - 1.0f * gUiScale),
                ImVec2(center.x + 2.0f * gUiScale, center.y + 10.0f * gUiScale),
                ImVec2(center.x - 2.0f * gUiScale, center.y + 12.0f * gUiScale),
                ImVec2(center.x - 2.0f * gUiScale, center.y - 1.0f * gUiScale)
        };
        drawList->AddPolyline(funnel, 6, color, ImDrawFlags_None, thickness);
    } else {
        for (int dot = -1; dot <= 1; ++dot) {
            drawList->AddCircleFilled(ImVec2(center.x, center.y + dot * 7.0f * gUiScale),
                                      2.0f * gUiScale, color);
        }
    }
    return clicked;
}

void drawTabGlyph(ImDrawList *drawList, const ImVec2 &center, int section, ImU32 color) {
    const float s = gUiScale;
    if (section == 0) {
        ImVec2 page[] = {{center.x-9*s,center.y-12*s},{center.x+3*s,center.y-12*s},
                         {center.x+9*s,center.y-6*s},{center.x+9*s,center.y+12*s},
                         {center.x-9*s,center.y+12*s}};
        drawList->AddConvexPolyFilled(page,5,color);
        drawList->AddTriangleFilled(ImVec2(center.x+3*s,center.y-12*s),
                                    ImVec2(center.x+3*s,center.y-6*s),
                                    ImVec2(center.x+9*s,center.y-6*s),IM_COL32(15,16,17,180));
    } else if (section == 1) {
        drawList->AddLine(ImVec2(center.x-10*s,center.y+8*s),ImVec2(center.x+8*s,center.y-10*s),color,4*s);
        drawList->AddCircle(ImVec2(center.x-6*s,center.y-6*s),5*s,color,20,2.2f*s);
        drawList->AddRect(ImVec2(center.x-1*s,center.y-8*s),ImVec2(center.x+10*s,center.y+4*s),color,1*s,0,2.2f*s);
        drawList->AddTriangleFilled(ImVec2(center.x-12*s,center.y+5*s),
                                    ImVec2(center.x-4*s,center.y+9*s),
                                    ImVec2(center.x-9*s,center.y+13*s),color);
    } else if (section == 2) {
        drawList->AddRectFilled(ImVec2(center.x-11*s,center.y-5*s),ImVec2(center.x+11*s,center.y+11*s),color,1*s);
        drawList->AddRectFilled(ImVec2(center.x-13*s,center.y-11*s),ImVec2(center.x+13*s,center.y-5*s),color,1*s);
        drawList->AddRectFilled(ImVec2(center.x-7*s,center.y+2*s),ImVec2(center.x-1*s,center.y+11*s),IM_COL32(16,17,18,190));
        drawList->AddRectFilled(ImVec2(center.x+3*s,center.y+1*s),ImVec2(center.x+8*s,center.y+5*s),IM_COL32(16,17,18,190));
    } else if (section == 3) {
        const ImU32 cutout=IM_COL32(16,17,18,255);
        drawList->AddRectFilled(ImVec2(center.x-10*s,center.y-10*s),ImVec2(center.x+10*s,center.y+10*s),color,2*s);
        drawList->AddCircleFilled(ImVec2(center.x,center.y-10*s),5*s,color,20);
        drawList->AddCircleFilled(ImVec2(center.x+10*s,center.y),5*s,color,20);
        drawList->AddCircleFilled(ImVec2(center.x,center.y+10*s),4.5f*s,cutout,20);
        drawList->AddCircleFilled(ImVec2(center.x-10*s,center.y),4.5f*s,cutout,20);
    } else {
        drawList->AddRectFilled(ImVec2(center.x-12*s,center.y-9*s),ImVec2(center.x+12*s,center.y+7*s),color,2*s);
        drawList->AddRectFilled(ImVec2(center.x-8*s,center.y-5*s),ImVec2(center.x+8*s,center.y+3*s),IM_COL32(16,17,18,210));
        drawList->AddRectFilled(ImVec2(center.x-2*s,center.y+7*s),ImVec2(center.x+2*s,center.y+11*s),color);
        drawList->AddRectFilled(ImVec2(center.x-7*s,center.y+11*s),ImVec2(center.x+7*s,center.y+13*s),color);
    }
}

void iconTab(const char *id, int section, float width, float visualSection) {
    const bool clicked = ImGui::InvisibleButton(id, ImVec2(width, 43.0f * gUiScale)) && gAllowActivationThisFrame;
    const ImVec2 minimum = ImGui::GetItemRectMin();
    const ImVec2 maximum = ImGui::GetItemRectMax();
    const float distance = std::abs(visualSection - static_cast<float>(section));
    const int alpha = static_cast<int>(255.0f * std::clamp(1.0f - distance * 0.45f, 0.48f, 1.0f));
    drawTabGlyph(ImGui::GetWindowDrawList(), ImVec2((minimum.x + maximum.x) * 0.5f,
                                                   (minimum.y + maximum.y) * 0.5f - 1.0f*gUiScale),
                 section, IM_COL32(238, 242, 241, alpha));
    if (clicked) {
        gSection = section;
        gPageOffset = 0.0f;
        gPageAnimating = false;
        gPageTargetSection = -1;
        gScrollVelocity = 0.0f;
        gRowMenuIndex = -1;
    }
}

void drawTopBar(float visualSection) {
    ImGui::PushStyleColor(ImGuiCol_ChildBg, ImVec4(0.018f, 0.020f, 0.022f, 1.0f));
    ImGui::PushStyleVar(ImGuiStyleVar_ChildRounding, 0.0f);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding,
                        ImVec2(14.0f * gUiScale, 8.0f * gUiScale));
    ImGui::BeginChild("autojs_app_bar", ImVec2(0.0f, 104.0f * gUiScale), false,
                      ImGuiWindowFlags_NoScrollbar | ImGuiWindowFlags_NoScrollWithMouse |
                      ImGuiWindowFlags_AlwaysUseWindowPadding);
    ImGui::PopStyleVar();

    const float toolbarY = ImGui::GetCursorPosY();
    if (toolbarIconButton("##navigation", ToolbarIcon::Menu)) {
        queueAction(22);
    }
    ImGui::SameLine(0.0f, 18.0f*gUiScale);
    ImGui::SetCursorPosY(toolbarY + 2.0f * gUiScale);
    ImGui::SetWindowFontScale(1.55f);
    ImGui::TextUnformatted("AI.js Pro");
    ImGui::GetWindowDrawList()->AddText(ImGui::GetFont(),ImGui::GetFontSize(),
                                        ImVec2(ImGui::GetItemRectMin().x+0.45f*gUiScale,
                                               ImGui::GetItemRectMin().y),
                                        IM_COL32(245,245,245,245),"AI.js Pro");
    ImGui::GetWindowDrawList()->AddText(ImGui::GetFont(),ImGui::GetFontSize(),
                                        ImVec2(ImGui::GetItemRectMin().x,
                                               ImGui::GetItemRectMin().y+0.45f*gUiScale),
                                        IM_COL32(245,245,245,210),"AI.js Pro");
    ImGui::SetWindowFontScale(1.0f);

    const float buttonWidth = 40.0f * gUiScale;
    const float gap = ImGui::GetStyle().ItemSpacing.x;
    const float rightStart = ImGui::GetWindowContentRegionMax().x - buttonWidth * 4.0f - gap * 3.0f;
    ImGui::SameLine(std::max(ImGui::GetCursorPosX() + gap, rightStart));
    ImGui::SetCursorPosY(toolbarY);
    if (toolbarIconButton("##code", ToolbarIcon::Code)) {
        queueAction(5);
    }
    ImGui::SameLine();
    if (toolbarIconButton("##file", ToolbarIcon::File)) {
        queueAction(3);
    }
    ImGui::SameLine();
    if (toolbarIconButton("##bookmark", ToolbarIcon::Bookmark)) {
        queueAction(21);
    }
    ImGui::SameLine();
    if (toolbarIconButton("##search", gSection == 2 ? ToolbarIcon::Filter : ToolbarIcon::Search)) {
        if (gSection == 0) queueAction(19);
        else if (gSection == 1) queueAction(27);
        else if (gSection == 2) queueAction(33);
        else if (gSection == 3) queueAction(41);
        else queueAction(55);
    }

    ImGui::SetCursorPosX(0.0f);
    const float tabGap = 0.0f;
    const float tabWidth = ImGui::GetWindowWidth() / 5.0f;
    ImGui::PushStyleVar(ImGuiStyleVar_ItemSpacing, ImVec2(0.0f, 0.0f));
    iconTab("##scripts_tab", 0, tabWidth, visualSection);
    const ImVec2 firstTabMin = ImGui::GetItemRectMin();
    const ImVec2 firstTabMax = ImGui::GetItemRectMax();
    ImGui::SameLine(0.0f, 0.0f);
    iconTab("##samples_tab", 1, tabWidth, visualSection);
    ImGui::SameLine(0.0f, 0.0f);
    iconTab("##resources_tab", 2, tabWidth, visualSection);
    ImGui::SameLine(0.0f, 0.0f);
    iconTab("##plugins_tab", 3, tabWidth, visualSection);
    ImGui::SameLine(0.0f, 0.0f);
    iconTab("##tasks_tab", 4, tabWidth, visualSection);
    ImGui::PopStyleVar();
    const float indicatorX = firstTabMin.x +
                             std::clamp(visualSection, 0.0f, 4.0f) * (tabWidth + tabGap);
    ImGui::GetWindowDrawList()->AddRectFilled(
            ImVec2(indicatorX, firstTabMax.y - 2.0f * gUiScale),
            ImVec2(indicatorX + tabWidth, firstTabMax.y), IM_COL32(58, 214, 188, 255));
    ImGui::EndChild();
    ImGui::PopStyleVar();
    ImGui::PopStyleColor();
}

void drawTaskRow(int group, int index, const TaskEntry &entry) {
    ImGui::PushID(group * 10000 + index);
    const float height = 58.0f * gUiScale;
    const bool clicked = ImGui::InvisibleButton("##task", ImVec2(ImGui::GetContentRegionAvail().x, height));
    const ImVec2 min = ImGui::GetItemRectMin(), max = ImGui::GetItemRectMax();
    ImDrawList *draw = ImGui::GetWindowDrawList();
    draw->AddRectFilled(min, max, IM_COL32(43,43,43,255));
    const ImVec2 badge(min.x + 28*gUiScale, min.y + height*.5f);
    draw->AddCircleFilled(badge, 18*gUiScale,
                          group == 0 ? IM_COL32(0,137,123,255) : IM_COL32(63,81,181,255), 28);
    const char *letter = entry.name.size() >= 2 && entry.name.substr(entry.name.size()-2) == "js" ? "J" : "A";
    const ImVec2 letterSize = ImGui::CalcTextSize(letter);
    draw->AddText(ImVec2(badge.x-letterSize.x*.5f,badge.y-letterSize.y*.5f),IM_COL32_WHITE,letter);
    const float tx=min.x+56*gUiScale;
    draw->PushClipRect(ImVec2(tx,min.y),ImVec2(max.x-58*gUiScale,max.y),true);
    draw->AddText(ImVec2(tx,min.y+9*gUiScale),IM_COL32(244,244,244,255),entry.name.c_str());
    draw->AddText(ImVec2(tx,min.y+33*gUiScale),IM_COL32(174,178,177,255),entry.description.c_str());
    draw->PopClipRect();
    const ImVec2 stop(max.x-28*gUiScale,min.y+height*.5f);
    draw->AddRect(ImVec2(stop.x-7*gUiScale,stop.y-7*gUiScale),ImVec2(stop.x+7*gUiScale,stop.y+7*gUiScale),
                  IM_COL32(225,225,225,240),1*gUiScale,0,1.7f*gUiScale);
    draw->AddLine(ImVec2(min.x+56*gUiScale,max.y),max,IM_COL32(255,255,255,22),.6f*gUiScale);
    if(clicked&&gAllowActivationThisFrame){
        gSelectedTaskGroup=group;
        gSelectedTaskIndex=index;
        if(ImGui::GetIO().MousePos.x>=max.x-58*gUiScale) queueAction(52);
        else queueAction(51);
    }
    ImGui::PopID();
}

void drawOverview() {
    const char *titles[]={u8"运行中任务",u8"任务"};
    ImGui::PushStyleVar(ImGuiStyleVar_ItemSpacing,ImVec2(0,1*gUiScale));
    for(int i=0;i<2;++i){
        const std::vector<TaskEntry> &entries=i==0?gRunningTasks:gPendingTasks;
        ImGui::PushID(i);
        const bool clicked=ImGui::InvisibleButton("##task_group",ImVec2(ImGui::GetContentRegionAvail().x,50*gUiScale))&&gAllowActivationThisFrame;
        const ImVec2 min=ImGui::GetItemRectMin(),max=ImGui::GetItemRectMax();
        ImDrawList *draw=ImGui::GetWindowDrawList();
        draw->AddRectFilled(min,max,IM_COL32(48,48,48,255));
        const ImVec2 icon(min.x+28*gUiScale,min.y+25*gUiScale);
        if(i==0){
            draw->AddCircle(icon,8*gUiScale,IM_COL32(220,220,220,255),20,1.7f*gUiScale);
            draw->AddTriangleFilled(ImVec2(icon.x-2*gUiScale,icon.y-4.5f*gUiScale),ImVec2(icon.x-2*gUiScale,icon.y+4.5f*gUiScale),ImVec2(icon.x+5*gUiScale,icon.y),IM_COL32(220,220,220,255));
        }else{
            draw->AddCircle(icon,8*gUiScale,IM_COL32(190,190,190,255),20,1.7f*gUiScale);
            draw->AddLine(icon,ImVec2(icon.x,icon.y-4*gUiScale),IM_COL32(190,190,190,255),1.7f*gUiScale);
            draw->AddLine(icon,ImVec2(icon.x+4*gUiScale,icon.y+2*gUiScale),IM_COL32(190,190,190,255),1.7f*gUiScale);
        }
        draw->AddText(ImGui::GetFont(),ImGui::GetFontSize()*1.12f,
                      ImVec2(min.x+60*gUiScale,min.y+14*gUiScale),IM_COL32(238,238,238,255),titles[i]);
        if(!entries.empty()){
            const std::string count=std::to_string(entries.size());
            draw->AddText(ImVec2(max.x-62*gUiScale,min.y+16*gUiScale),IM_COL32(75,210,183,255),count.c_str());
        }
        const ImVec2 arrow(max.x-21*gUiScale,min.y+25*gUiScale);
        if(gTaskGroupsExpanded[i]){
            draw->AddLine(ImVec2(arrow.x-5*gUiScale,arrow.y+3*gUiScale),ImVec2(arrow.x,arrow.y-2*gUiScale),IM_COL32(180,180,180,255),1.5f*gUiScale);
            draw->AddLine(ImVec2(arrow.x,arrow.y-2*gUiScale),ImVec2(arrow.x+5*gUiScale,arrow.y+3*gUiScale),IM_COL32(180,180,180,255),1.5f*gUiScale);
        }else{
            draw->AddLine(ImVec2(arrow.x-5*gUiScale,arrow.y-3*gUiScale),ImVec2(arrow.x,arrow.y+2*gUiScale),IM_COL32(180,180,180,255),1.5f*gUiScale);
            draw->AddLine(ImVec2(arrow.x,arrow.y+2*gUiScale),ImVec2(arrow.x+5*gUiScale,arrow.y-3*gUiScale),IM_COL32(180,180,180,255),1.5f*gUiScale);
        }
        draw->AddLine(ImVec2(min.x,max.y),max,IM_COL32(255,255,255,18),0.6f*gUiScale);
        if(clicked) gTaskGroupsExpanded[i]=!gTaskGroupsExpanded[i];
        ImGui::PopID();
        if(gTaskGroupsExpanded[i]){
            if(entries.empty()){
                ImGui::SetCursorPosX(56*gUiScale);
                ImGui::TextDisabled(i==0?u8"当前没有运行中的脚本":u8"当前没有定时任务");
            }else{
                for(int j=0;j<static_cast<int>(entries.size());++j) drawTaskRow(i,j,entries[static_cast<size_t>(j)]);
            }
        }
    }
    ImGui::PopStyleVar();
}

void drawEntryIcon(ImDrawList *drawList, const ImVec2 &minimum, int iconKind) {
    const ImVec2 center(minimum.x + 40.0f * gUiScale, minimum.y + 31.0f * gUiScale);
    const ImU32 background = iconKind == 1 ? IM_COL32(63, 81, 181, 255)
                             : iconKind == 0 ? IM_COL32(30, 126, 220, 255)
                             : iconKind == 3 ? IM_COL32(84, 110, 122, 255)
                                             : IM_COL32(92, 75, 180, 255);
    drawList->AddCircleFilled(center, 23.5f * gUiScale, background, 32);
    const ImU32 glyph = IM_COL32(242, 245, 247, 245);
    const float thickness = 1.8f * gUiScale;
    if (iconKind == 0) {
        drawList->AddRect(ImVec2(center.x - 11.0f*gUiScale, center.y - 6.0f*gUiScale),
                          ImVec2(center.x + 11.0f*gUiScale, center.y + 8.0f*gUiScale),
                          glyph, 2.0f*gUiScale, 0, thickness);
        drawList->AddLine(ImVec2(center.x - 9.0f*gUiScale, center.y - 6.0f*gUiScale),
                          ImVec2(center.x - 3.0f*gUiScale, center.y - 10.0f*gUiScale), glyph, thickness);
        drawList->AddLine(ImVec2(center.x - 3.0f*gUiScale, center.y - 10.0f*gUiScale),
                          ImVec2(center.x + 4.0f*gUiScale, center.y - 6.0f*gUiScale), glyph, thickness);
    } else if (iconKind == 1) {
        drawList->AddCircle(center, 3.0f*gUiScale, glyph, 18, thickness);
        drawList->AddLine(ImVec2(center.x,center.y-13.0f*gUiScale),
                          ImVec2(center.x,center.y-3.0f*gUiScale),glyph,thickness);
        drawList->AddLine(ImVec2(center.x-1.5f*gUiScale,center.y+2.5f*gUiScale),
                          ImVec2(center.x-7.0f*gUiScale,center.y+13.0f*gUiScale),glyph,thickness);
        drawList->AddLine(ImVec2(center.x+1.5f*gUiScale,center.y+2.5f*gUiScale),
                          ImVec2(center.x+7.0f*gUiScale,center.y+13.0f*gUiScale),glyph,thickness);
        drawList->AddLine(ImVec2(center.x-7.0f*gUiScale,center.y+13.0f*gUiScale),
                          ImVec2(center.x-8.5f*gUiScale,center.y+8.0f*gUiScale),glyph,thickness);
        drawList->AddLine(ImVec2(center.x+7.0f*gUiScale,center.y+13.0f*gUiScale),
                          ImVec2(center.x+8.5f*gUiScale,center.y+8.0f*gUiScale),glyph,thickness);
    } else if (iconKind == 2) {
        drawList->AddRect(ImVec2(center.x - 9.0f*gUiScale, center.y - 12.0f*gUiScale),
                          ImVec2(center.x + 9.0f*gUiScale, center.y + 12.0f*gUiScale),
                          glyph, 1.5f*gUiScale, 0, thickness);
        const ImVec2 jsSize = ImGui::CalcTextSize("JS");
        drawList->AddText(ImVec2(center.x - jsSize.x*0.5f, center.y - jsSize.y*0.3f), glyph, "JS");
    } else {
        drawList->AddRect(ImVec2(center.x - 9.0f*gUiScale, center.y - 12.0f*gUiScale),
                          ImVec2(center.x + 9.0f*gUiScale, center.y + 12.0f*gUiScale),
                          glyph, 1.5f*gUiScale, 0, thickness);
        drawList->AddLine(ImVec2(center.x - 5.0f*gUiScale, center.y - 4.0f*gUiScale),
                          ImVec2(center.x + 5.0f*gUiScale, center.y - 4.0f*gUiScale), glyph, thickness);
        drawList->AddLine(ImVec2(center.x - 5.0f*gUiScale, center.y + 2.0f*gUiScale),
                          ImVec2(center.x + 5.0f*gUiScale, center.y + 2.0f*gUiScale), glyph, thickness);
    }
}

void drawScriptEntryRow(int index) {
    const ScriptEntry &entry = gScriptEntries[static_cast<size_t>(index)];
    const float rowHeight = 62.0f * gUiScale;
    ImGui::PushID(index);
    const bool clicked = ImGui::InvisibleButton("##entry", ImVec2(ImGui::GetContentRegionAvail().x,
                                                                   rowHeight));
    const ImVec2 minimum = ImGui::GetItemRectMin();
    const ImVec2 maximum = ImGui::GetItemRectMax();
    ImDrawList *drawList = ImGui::GetWindowDrawList();
    if (ImGui::IsItemActive()) {
        drawList->AddRectFilled(minimum, maximum, IM_COL32(255, 255, 255, 14));
    }
    drawEntryIcon(drawList, minimum, entry.iconKind);
    const bool runnable = !entry.directory && entry.iconKind == 2;
    const float actionWidth = runnable ? 82.0f*gUiScale : 42.0f*gUiScale;
    const ImVec2 textPosition(minimum.x + 80.0f * gUiScale, minimum.y + 4.0f*gUiScale);
    drawList->PushClipRect(textPosition, ImVec2(maximum.x - actionWidth, maximum.y), true);
    drawList->AddText(ImGui::GetFont(), ImGui::GetFontSize()*1.12f, textPosition,
                      IM_COL32(247, 247, 247, 255), entry.name.c_str());
    drawList->AddText(ImVec2(textPosition.x, minimum.y + 27.0f*gUiScale),
                      IM_COL32(202, 202, 202, 255), entry.typeLabel.c_str());
    drawList->AddText(ImVec2(textPosition.x, minimum.y + 44.0f*gUiScale),
                      IM_COL32(184, 184, 184, 255), entry.modifiedLabel.c_str());
    drawList->PopClipRect();
    drawList->AddLine(ImVec2(minimum.x + 80.0f * gUiScale, maximum.y), maximum,
                      IM_COL32(255, 255, 255, 26), 0.65f * gUiScale);

    const ImVec2 moreCenter(maximum.x - 22.0f*gUiScale, minimum.y + rowHeight*0.5f);
    for (int dot = -1; dot <= 1; ++dot) {
        drawList->AddCircleFilled(ImVec2(moreCenter.x, moreCenter.y + dot*6.0f*gUiScale),
                                  2.1f*gUiScale, IM_COL32(205, 210, 208, 235));
    }
    ImVec2 runCenter(maximum.x - 62.0f*gUiScale, moreCenter.y);
    if (runnable) {
        drawList->AddTriangleFilled(ImVec2(runCenter.x - 6.0f*gUiScale, runCenter.y - 9.0f*gUiScale),
                                    ImVec2(runCenter.x - 6.0f*gUiScale, runCenter.y + 9.0f*gUiScale),
                                    ImVec2(runCenter.x + 9.0f*gUiScale, runCenter.y),
                                    IM_COL32(78, 209, 182, 255));
    }
    if (clicked && gAllowActivationThisFrame) {
        gSelectedScript = index;
        const ImVec2 tap = ImGui::GetIO().MousePos;
        if (tap.x >= maximum.x - 42.0f*gUiScale) {
            gRowMenuIndex = -1;
            queueAction(38);
        } else if (runnable && tap.x >= maximum.x - 82.0f*gUiScale) {
            gRowMenuIndex = -1;
            queueAction(13);
        } else {
            gRowMenuIndex = -1;
            queueAction(12);
        }
    }
    if (gRowMenuIndex == index) {
        ImGui::PushStyleVar(ImGuiStyleVar_ItemSpacing, ImVec2(6.0f*gUiScale, 4.0f*gUiScale));
        const float itemWidth = (ImGui::GetContentRegionAvail().x - 32.0f*gUiScale) / 3.0f;
        ImGui::SetCursorPosX(ImGui::GetCursorPosX() + 16.0f*gUiScale);
        if (ImGui::Button(entry.directory ? u8"打开" : u8"编辑", ImVec2(itemWidth, 34.0f*gUiScale)) && gAllowActivationThisFrame) {
            queueAction(12); gRowMenuIndex = -1;
        }
        ImGui::SameLine();
        if (ImGui::Button(u8"重命名", ImVec2(itemWidth, 34.0f*gUiScale)) && gAllowActivationThisFrame) {
            queueAction(17); gRowMenuIndex = -1;
        }
        ImGui::SameLine();
        ImGui::PushStyleColor(ImGuiCol_Button, ImVec4(0.38f, 0.10f, 0.12f, 1.0f));
        if (ImGui::Button(u8"删除", ImVec2(itemWidth, 34.0f*gUiScale)) && gAllowActivationThisFrame) {
            queueAction(18); gRowMenuIndex = -1;
        }
        ImGui::PopStyleColor();
        ImGui::PopStyleVar();
    }
    ImGui::PopID();
}

void drawMiniActionGlyph(const ImVec2 &center, int kind, ImU32 color) {
    ImDrawList *drawList = ImGui::GetWindowDrawList();
    const float s = gUiScale;
    const float t = 1.8f*s;
    if (kind == 0) {
        drawList->AddLine(ImVec2(center.x, center.y-9*s), ImVec2(center.x, center.y+6*s), color, t);
        drawList->AddLine(ImVec2(center.x, center.y-9*s), ImVec2(center.x-7*s, center.y-2*s), color, t);
        drawList->AddLine(ImVec2(center.x, center.y-9*s), ImVec2(center.x+7*s, center.y-2*s), color, t);
        drawList->AddLine(ImVec2(center.x-8*s,center.y+9*s),ImVec2(center.x+8*s,center.y+9*s),color,t);
    } else if (kind == 1) {
        drawList->AddLine(ImVec2(center.x-2*s, center.y-7*s), ImVec2(center.x+9*s, center.y-7*s), color, t);
        drawList->AddLine(ImVec2(center.x+1*s, center.y), ImVec2(center.x+9*s, center.y), color, t);
        drawList->AddLine(ImVec2(center.x+4*s, center.y+7*s), ImVec2(center.x+9*s, center.y+7*s), color, t);
        drawList->AddLine(ImVec2(center.x-7*s,center.y-9*s),ImVec2(center.x-7*s,center.y+8*s),color,t);
        drawList->AddTriangleFilled(ImVec2(center.x-11*s,center.y+4*s),ImVec2(center.x-3*s,center.y+4*s),ImVec2(center.x-7*s,center.y+10*s),color);
    } else {
        ImVec2 funnel[]={ImVec2(center.x-10*s,center.y-9*s),ImVec2(center.x+10*s,center.y-9*s),
                         ImVec2(center.x+3*s,center.y),ImVec2(center.x+3*s,center.y+9*s),
                         ImVec2(center.x-2*s,center.y+6*s),ImVec2(center.x-2*s,center.y)};
        drawList->AddPolyline(funnel,6,color,ImDrawFlags_None,t);
        drawList->AddLine(funnel[5],funnel[0],color,t);
    }
}

bool miniActionButton(const char *id, int glyph) {
    const bool clicked = ImGui::InvisibleButton(id, ImVec2(42.0f*gUiScale, 42.0f*gUiScale)) && gAllowActivationThisFrame;
    const ImVec2 min = ImGui::GetItemRectMin();
    const ImVec2 max = ImGui::GetItemRectMax();
    if (ImGui::IsItemActive()) ImGui::GetWindowDrawList()->AddCircleFilled(ImVec2((min.x+max.x)*.5f,(min.y+max.y)*.5f),18*gUiScale,IM_COL32(255,255,255,20));
    drawMiniActionGlyph(ImVec2((min.x+max.x)*.5f,(min.y+max.y)*.5f), glyph, IM_COL32(210,216,214,240));
    return clicked;
}

void drawClickableBreadcrumb(const std::string &label,float rowY,int action){
    ImDrawList *draw=ImGui::GetWindowDrawList();
    const float startX=ImGui::GetWindowPos().x+8*gUiScale;
    const float textY=ImGui::GetWindowPos().y+rowY+11*gUiScale;
    const size_t suffixAt=label.find(u8"  ·  ");
    const std::string pathPart=suffixAt==std::string::npos?label:label.substr(0,suffixAt);
    const std::string suffix=suffixAt==std::string::npos?"":label.substr(suffixAt);
    const size_t firstSlash=pathPart.find('/');
    const std::string root=firstSlash==std::string::npos?pathPart:pathPart.substr(0,firstSlash);
    const size_t divider=root.find('>');
    float x=startX;
    if(divider!=std::string::npos){
        const std::string parent=root.substr(0,divider+1);
        draw->AddText(ImVec2(x,textY),IM_COL32(145,145,145,255),parent.c_str());
        x+=ImGui::CalcTextSize(parent.c_str()).x+5*gUiScale;
        const std::string leaf=root.substr(divider+1);
        draw->AddText(ImVec2(x,textY),IM_COL32(245,245,245,255),leaf.c_str());
        x+=ImGui::CalcTextSize(leaf.c_str()).x;
    }else{
        draw->AddText(ImVec2(x,textY),IM_COL32(245,245,245,255),root.c_str());
        x+=ImGui::CalcTextSize(root.c_str()).x;
    }
    std::vector<std::pair<float,float>> hitRanges;
    hitRanges.emplace_back(startX,x);
    size_t cursor=firstSlash;
    while(cursor!=std::string::npos&&cursor<pathPart.size()){
        const size_t next=pathPart.find('/',cursor+1);
        const std::string part=pathPart.substr(cursor+1,next==std::string::npos?std::string::npos:next-cursor-1);
        const char *slash=" / ";
        draw->AddText(ImVec2(x,textY),IM_COL32(145,145,145,255),slash);
        x+=ImGui::CalcTextSize(slash).x;
        const float partStart=x;
        draw->AddText(ImVec2(x,textY),IM_COL32(245,245,245,255),part.c_str());
        x+=ImGui::CalcTextSize(part.c_str()).x;
        hitRanges.emplace_back(partStart,x);
        cursor=next;
    }
    if(!suffix.empty()) draw->AddText(ImVec2(x+6*gUiScale,textY),IM_COL32(115,190,178,255),suffix.c_str());
    if(gAllowActivationThisFrame&&ImGui::IsMouseClicked(0)){
        const ImVec2 tap=ImGui::GetIO().MousePos;
        if(tap.y>=textY-8*gUiScale&&tap.y<=textY+28*gUiScale){
            for(int depth=0;depth<static_cast<int>(hitRanges.size());++depth){
                if(tap.x>=hitRanges[static_cast<size_t>(depth)].first&&tap.x<=hitRanges[static_cast<size_t>(depth)].second){
                    gSelectedBreadcrumbDepth.store(depth);
                    queueAction(action);
                    break;
                }
            }
        }
    }
}

void drawScripts(bool applyScroll) {
    const float rowY = ImGui::GetCursorPosY();
    const std::string label = gScriptDirectoryLabel.empty() ? u8"内部存储  >  脚本" : gScriptDirectoryLabel;
    ImDrawList *barDraw = ImGui::GetWindowDrawList();
    barDraw->AddRectFilled(ImVec2(ImGui::GetWindowPos().x,ImGui::GetWindowPos().y+rowY),
                           ImVec2(ImGui::GetWindowPos().x+ImGui::GetWindowWidth(),ImGui::GetWindowPos().y+rowY+43*gUiScale),
                           IM_COL32(48,48,48,255));
    drawClickableBreadcrumb(label,rowY,35);
    ImGui::SetCursorPosX(std::max(ImGui::GetCursorPosX(), ImGui::GetWindowContentRegionMax().x - 126.0f*gUiScale));
    ImGui::SetCursorPosY(rowY);
    if (miniActionButton("##up", 0)) queueAction(11);
    ImGui::SameLine(0.0f, 0.0f);
    if (miniActionButton("##sort", 1)) { queueAction(28); gRowMenuIndex = -1; }
    ImGui::SameLine(0.0f, 0.0f);
    if (miniActionButton("##filter", 2)) queueAction(20);
    ImGui::SetCursorPosY(rowY + 42.0f*gUiScale);
    ImGui::Separator();
    ImGui::SetCursorPosY(rowY + 43.0f*gUiScale);

    ImGui::PushStyleVar(ImGuiStyleVar_ChildRounding, 0.0f);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(0.0f, 0.0f));
    ImGui::PushStyleColor(ImGuiCol_ChildBg, ImVec4(0.188f, 0.188f, 0.188f, 1.0f));
    ImGui::BeginChild("script_list", ImVec2(0.0f, 0.0f), false, ImGuiWindowFlags_NoScrollbar);
    ImGui::PopStyleColor();
    ImGui::PopStyleVar(2);
    if (applyScroll) {
        applyPendingVerticalScroll();
    }
    const bool restoreScriptScroll = gScriptEntriesRevision != gAppliedScriptRevision;
    float restoredScriptScroll = 0.0f;
    if (restoreScriptScroll) {
        auto cache = gScriptScrollCache.find(gScriptDirectoryLabel);
        if (cache != gScriptScrollCache.end()) restoredScriptScroll = cache->second;
    }
    if (gScriptEntries.empty()) {
        ImGui::SetCursorPos(ImVec2(18.0f*gUiScale, 24.0f*gUiScale));
        ImGui::TextDisabled(u8"当前目录没有脚本或子目录");
    } else {
        for (int i = 0; i < static_cast<int>(gScriptEntries.size()); ++i) drawScriptEntryRow(i);
    }
    // Restore only after all rows have established the child's content height. Calling
    // SetScrollY before drawing the rows makes a fresh ImGui context clamp the target to zero.
    if (restoreScriptScroll) {
        gAppliedScriptRevision = gScriptEntriesRevision;
        ImGui::SetScrollY(restoredScriptScroll);
    } else if (!gScriptEntries.empty()) {
        // Continuously retain the current directory position for editor/activity round trips.
        gScriptScrollCache[gScriptDirectoryLabel] = ImGui::GetScrollY();
    }
    ImGui::EndChild();
}

void drawSampleRow(int index) {
    const SampleEntry &entry=gSampleEntries[static_cast<size_t>(index)];
    ImGui::PushID(index);
    const float height = 62.0f*gUiScale;
    const bool clicked = ImGui::InvisibleButton("##sample", ImVec2(ImGui::GetContentRegionAvail().x, height));
    const ImVec2 min = ImGui::GetItemRectMin();
    const ImVec2 max = ImGui::GetItemRectMax();
    ImDrawList *drawList=ImGui::GetWindowDrawList();
    drawEntryIcon(drawList, min, entry.directory?0:2);
    const ImVec2 textPos(min.x+80*gUiScale,min.y+4*gUiScale);
    drawList->PushClipRect(textPos,ImVec2(max.x-(entry.directory?42:82)*gUiScale,max.y),true);
    drawList->AddText(ImGui::GetFont(),ImGui::GetFontSize()*1.12f,textPos,IM_COL32(247,247,247,255),entry.name.c_str());
    drawList->AddText(ImVec2(textPos.x,min.y+27*gUiScale),IM_COL32(202,202,202,255),entry.typeLabel.c_str());
    drawList->AddText(ImVec2(textPos.x,min.y+44*gUiScale),IM_COL32(184,184,184,255),entry.modifiedLabel.c_str());
    drawList->PopClipRect();
    const ImVec2 more(max.x-22*gUiScale,min.y+31*gUiScale);
    for(int dot=-1;dot<=1;++dot) drawList->AddCircleFilled(ImVec2(more.x,more.y+dot*6*gUiScale),2.1f*gUiScale,IM_COL32(235,235,235,240));
    if(!entry.directory){
        const ImVec2 run(max.x-62*gUiScale,min.y+31*gUiScale);
        drawList->AddTriangleFilled(ImVec2(run.x-6*gUiScale,run.y-9*gUiScale),ImVec2(run.x-6*gUiScale,run.y+9*gUiScale),ImVec2(run.x+9*gUiScale,run.y),IM_COL32(78,209,182,255));
    }
    drawList->AddLine(ImVec2(min.x+80*gUiScale,max.y),max,IM_COL32(255,255,255,26),0.65f*gUiScale);
    if(clicked&&gAllowActivationThisFrame){
        gSelectedSample=index;
        const float tapX=ImGui::GetIO().MousePos.x;
        if(tapX>=max.x-42*gUiScale) gSampleRowMenuIndex=gSampleRowMenuIndex==index?-1:index;
        else if(!entry.directory&&tapX>=max.x-82*gUiScale){gSampleRowMenuIndex=-1;queueAction(24);}
        else {gSampleRowMenuIndex=-1;queueAction(23);}
    }
    if(gSampleRowMenuIndex==index){
        ImGui::PushStyleVar(ImGuiStyleVar_ItemSpacing,ImVec2(6*gUiScale,4*gUiScale));
        const float itemWidth=(ImGui::GetContentRegionAvail().x-32*gUiScale)/3.0f;
        ImGui::SetCursorPosX(ImGui::GetCursorPosX()+16*gUiScale);
        if(ImGui::Button(entry.directory?u8"打开":u8"查看",ImVec2(itemWidth,34*gUiScale))&&gAllowActivationThisFrame){queueAction(23);gSampleRowMenuIndex=-1;}
        ImGui::SameLine();
        if(ImGui::Button(entry.directory?u8"导入目录":u8"运行",ImVec2(itemWidth,34*gUiScale))&&gAllowActivationThisFrame){queueAction(entry.directory?25:24);gSampleRowMenuIndex=-1;}
        ImGui::SameLine();
        if(ImGui::Button(u8"导入",ImVec2(itemWidth,34*gUiScale))&&gAllowActivationThisFrame){queueAction(25);gSampleRowMenuIndex=-1;}
        ImGui::PopStyleVar();
    }
    ImGui::PopID();
}

void drawSamples(bool applyScroll) {
    const float rowY=ImGui::GetCursorPosY();
    ImDrawList *drawList=ImGui::GetWindowDrawList();
    drawList->AddRectFilled(ImVec2(ImGui::GetWindowPos().x,ImGui::GetWindowPos().y+rowY),
                            ImVec2(ImGui::GetWindowPos().x+ImGui::GetWindowWidth(),ImGui::GetWindowPos().y+rowY+43*gUiScale),
                            IM_COL32(48,48,48,255));
    const std::string label=gSampleDirectoryLabel.empty()?u8"示例文件  >  中文":gSampleDirectoryLabel;
    drawClickableBreadcrumb(label,rowY,36);
    ImGui::SetCursorPosX(ImGui::GetWindowContentRegionMax().x-126*gUiScale);
    ImGui::SetCursorPosY(rowY);
    if(miniActionButton("##sample_up",0)) queueAction(26);
    ImGui::SameLine(0,0);
    if(miniActionButton("##sample_sort",1)) queueAction(37);
    ImGui::SameLine(0,0);
    if(miniActionButton("##sample_filter",2)) queueAction(29);
    ImGui::SetCursorPosY(rowY+42*gUiScale);
    ImGui::Separator();
    ImGui::SetCursorPosY(rowY+43*gUiScale);
    ImGui::PushStyleVar(ImGuiStyleVar_ChildRounding,0.0f);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding,ImVec2(0,0));
    ImGui::PushStyleColor(ImGuiCol_ChildBg,ImVec4(0.188f,0.188f,0.188f,1.0f));
    ImGui::BeginChild("sample_list",ImVec2(0,0),false,ImGuiWindowFlags_NoScrollbar);
    ImGui::PopStyleColor();
    ImGui::PopStyleVar(2);
    if(applyScroll) applyPendingVerticalScroll();
    const bool restoreSampleScroll = gSampleEntriesRevision != gAppliedSampleRevision;
    float restoredSampleScroll = 0.0f;
    if (restoreSampleScroll) {
        auto cache = gSampleScrollCache.find(gSampleDirectoryLabel);
        if (cache != gSampleScrollCache.end()) restoredSampleScroll = cache->second;
    }
    if (gSampleEntries.empty()) {
        ImGui::SetCursorPosX(16.0f*gUiScale);
        ImGui::TextDisabled(u8"暂无内置示例");
    } else {
        for (int i=0;i<static_cast<int>(gSampleEntries.size());++i) drawSampleRow(i);
    }
    if (restoreSampleScroll) {
        gAppliedSampleRevision = gSampleEntriesRevision;
        ImGui::SetScrollY(restoredSampleScroll);
    } else if (!gSampleEntries.empty()) {
        gSampleScrollCache[gSampleDirectoryLabel] = ImGui::GetScrollY();
    }
    ImGui::EndChild();
}

void drawDownloadIcon(ImDrawList *drawList, const ImVec2 &center, ImU32 color) {
    const float s=gUiScale;
    drawList->AddLine(ImVec2(center.x,center.y-9*s),ImVec2(center.x,center.y+5*s),color,2*s);
    drawList->AddLine(ImVec2(center.x,center.y+5*s),ImVec2(center.x-6*s,center.y-1*s),color,2*s);
    drawList->AddLine(ImVec2(center.x,center.y+5*s),ImVec2(center.x+6*s,center.y-1*s),color,2*s);
    drawList->AddLine(ImVec2(center.x-8*s,center.y+10*s),ImVec2(center.x+8*s,center.y+10*s),color,2*s);
}

void drawResources(bool applyScroll) {
    const float footerHeight=42*gUiScale;
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding,ImVec2(5*gUiScale,5*gUiScale));
    ImGui::PushStyleColor(ImGuiCol_ChildBg,ImVec4(0.188f,0.188f,0.188f,1.0f));
    ImGui::BeginChild("resource_list",ImVec2(0,-footerHeight),false,ImGuiWindowFlags_NoScrollbar);
    ImGui::PopStyleColor();
    ImGui::PopStyleVar();
    if(applyScroll) applyPendingVerticalScroll();
    ImGui::PushStyleVar(ImGuiStyleVar_ItemSpacing,ImVec2(5*gUiScale,3*gUiScale));
    int visibleCount=0;
    for(int i=0;i<static_cast<int>(gResourceEntries.size());++i){
        const ResourceEntry &entry=gResourceEntries[static_cast<size_t>(i)];
        if(gResourceImportedOnly&&entry.assetPath.rfind("file:",0)!=0) continue;
        ++visibleCount;
        ImGui::PushID(i);
        const float h=78*gUiScale;
        const bool clicked=ImGui::InvisibleButton("##resource",ImVec2(ImGui::GetContentRegionAvail().x,h));
        const ImVec2 min=ImGui::GetItemRectMin(),max=ImGui::GetItemRectMax();
        ImDrawList *draw=ImGui::GetWindowDrawList();
        draw->AddRectFilled(min,max,IM_COL32(62,62,62,255),2*gUiScale);
        const ImVec2 code(min.x+20*gUiScale,min.y+39*gUiScale);
        draw->AddLine(ImVec2(code.x-8*gUiScale,code.y),ImVec2(code.x-3*gUiScale,code.y-5*gUiScale),IM_COL32(218,218,218,255),1.8f*gUiScale);
        draw->AddLine(ImVec2(code.x-8*gUiScale,code.y),ImVec2(code.x-3*gUiScale,code.y+5*gUiScale),IM_COL32(218,218,218,255),1.8f*gUiScale);
        draw->AddLine(ImVec2(code.x+8*gUiScale,code.y),ImVec2(code.x+3*gUiScale,code.y-5*gUiScale),IM_COL32(218,218,218,255),1.8f*gUiScale);
        draw->AddLine(ImVec2(code.x+8*gUiScale,code.y),ImVec2(code.x+3*gUiScale,code.y+5*gUiScale),IM_COL32(218,218,218,255),1.8f*gUiScale);
        const float tx=min.x+42*gUiScale;
        draw->PushClipRect(ImVec2(tx,min.y),ImVec2(max.x-50*gUiScale,max.y),true);
        draw->AddText(ImGui::GetFont(),ImGui::GetFontSize()*1.08f,ImVec2(tx,min.y+6*gUiScale),IM_COL32(248,248,248,255),entry.name.c_str());
        draw->AddText(ImVec2(tx,min.y+30*gUiScale),IM_COL32(205,205,205,255),entry.description.c_str());
        const size_t first=entry.metadata.find('|');
        const size_t second=first==std::string::npos?std::string::npos:entry.metadata.find('|',first+1);
        const std::string source=first==std::string::npos?entry.metadata:entry.metadata.substr(0,first);
        const std::string category=second==std::string::npos?"":entry.metadata.substr(first+1,second-first-1);
        const std::string size=second==std::string::npos?"":entry.metadata.substr(second+1);
        draw->AddText(ImVec2(tx,min.y+54*gUiScale),IM_COL32(79,190,220,255),source.c_str());
        const float sizeWidth=ImGui::CalcTextSize(size.c_str()).x;
        const float sizeX=max.x-50*gUiScale-sizeWidth;
        const float categoryX=tx+62*gUiScale;
        if(!category.empty()&&sizeX>categoryX+10*gUiScale){
            draw->PushClipRect(ImVec2(categoryX,min.y+50*gUiScale),
                               ImVec2(sizeX-8*gUiScale,min.y+75*gUiScale),true);
            draw->AddText(ImVec2(categoryX,min.y+54*gUiScale),IM_COL32(165,169,168,255),category.c_str());
            draw->PopClipRect();
        }
        if(!size.empty()) draw->AddText(ImVec2(sizeX,min.y+54*gUiScale),IM_COL32(165,169,168,255),size.c_str());
        draw->PopClipRect();
        if(entry.imported){
            const ImVec2 c(max.x-24*gUiScale,min.y+39*gUiScale);
            draw->AddCircle(c,10*gUiScale,IM_COL32(80,205,176,255),24,1.8f*gUiScale);
            draw->AddLine(ImVec2(c.x-5*gUiScale,c.y),ImVec2(c.x-1*gUiScale,c.y+4*gUiScale),IM_COL32(80,205,176,255),1.8f*gUiScale);
            draw->AddLine(ImVec2(c.x-1*gUiScale,c.y+4*gUiScale),ImVec2(c.x+6*gUiScale,c.y-5*gUiScale),IM_COL32(80,205,176,255),1.8f*gUiScale);
        }else drawDownloadIcon(draw,ImVec2(max.x-24*gUiScale,min.y+39*gUiScale),IM_COL32(244,244,244,255));
        if(clicked&&gAllowActivationThisFrame){
            gSelectedResource=i;
            if(ImGui::GetIO().MousePos.x>=max.x-50*gUiScale) queueAction(entry.imported?31:30);
            else queueAction(34);
        }
        ImGui::PopID();
    }
    if(visibleCount==0){
        ImGui::SetCursorPos(ImVec2(16*gUiScale,22*gUiScale));
        ImGui::TextDisabled(gResourceImportedOnly?u8"还没有导入的资源":u8"没有可用资源");
    }
    ImGui::PopStyleVar();
    ImGui::EndChild();
    const float half=ImGui::GetContentRegionAvail().x*.5f;
    const bool allClicked=ImGui::InvisibleButton("##resource_all",ImVec2(half,footerHeight))&&gAllowActivationThisFrame;
    const ImVec2 allMin=ImGui::GetItemRectMin();
    ImGui::SameLine(0,0);
    const bool mineClicked=ImGui::InvisibleButton("##resource_mine",ImVec2(half,footerHeight))&&gAllowActivationThisFrame;
    const ImVec2 mineMin=ImGui::GetItemRectMin(),mineMax=ImGui::GetItemRectMax();
    if(allClicked) gResourceImportedOnly=false;
    if(mineClicked) gResourceImportedOnly=true;
    ImDrawList *draw=ImGui::GetWindowDrawList();
    draw->AddRectFilled(allMin,mineMax,IM_COL32(28,30,31,255));
    const ImU32 allColor=gResourceImportedOnly?IM_COL32(190,190,190,255):IM_COL32(72,210,183,255);
    const ImU32 mineColor=gResourceImportedOnly?IM_COL32(72,210,183,255):IM_COL32(190,190,190,255);
    draw->AddText(ImVec2(allMin.x+18*gUiScale,allMin.y+12*gUiScale),allColor,u8"全部");
    draw->AddTriangleFilled(ImVec2(allMin.x+58*gUiScale,allMin.y+18*gUiScale),
                            ImVec2(allMin.x+68*gUiScale,allMin.y+18*gUiScale),
                            ImVec2(allMin.x+63*gUiScale,allMin.y+24*gUiScale),allColor);
    const ImVec2 person(mineMin.x+21*gUiScale,mineMin.y+19*gUiScale);
    draw->AddCircleFilled(ImVec2(person.x,person.y-4*gUiScale),4*gUiScale,mineColor,18);
    draw->AddCircle(ImVec2(person.x,person.y+7*gUiScale),7*gUiScale,mineColor,18,2*gUiScale);
    draw->AddText(ImVec2(mineMin.x+38*gUiScale,mineMin.y+12*gUiScale),mineColor,u8"我的");
}

void drawPluginCover(ImDrawList *draw,const ImVec2 &min,const ImVec2 &max,int kind){
    const ImU32 covers[]={IM_COL32(242,242,238,255),IM_COL32(58,73,232,255),IM_COL32(55,199,174,255),IM_COL32(15,15,15,255),IM_COL32(232,219,196,255)};
    draw->AddRectFilled(min,max,covers[kind],4*gUiScale,ImDrawFlags_RoundCornersTop);
    const float big=ImGui::GetFontSize()*1.55f;
    if(kind==0){
        draw->AddText(ImGui::GetFont(),big,ImVec2(min.x+10*gUiScale,min.y+12*gUiScale),IM_COL32(30,30,30,255),"YOLOv11");
        draw->AddRectFilled(ImVec2(min.x+10*gUiScale,min.y+48*gUiScale),ImVec2(max.x-10*gUiScale,max.y-10*gUiScale),IM_COL32(22,22,22,255));
        draw->AddText(ImVec2(min.x+18*gUiScale,min.y+61*gUiScale),IM_COL32_WHITE,"YOLO");
    }else if(kind==1){
        draw->AddText(ImGui::GetFont(),big,ImVec2(min.x+12*gUiScale,min.y+14*gUiScale),IM_COL32_WHITE,"PaddleOCR");
        draw->AddText(ImGui::GetFont(),ImGui::GetFontSize()*2.2f,ImVec2(min.x+25*gUiScale,min.y+52*gUiScale),IM_COL32(245,245,255,255),"P");
    }else if(kind==2){
        draw->AddRectFilled(ImVec2(min.x+35*gUiScale,min.y+12*gUiScale),ImVec2(max.x-30*gUiScale,max.y-12*gUiScale),IM_COL32(245,248,248,255),3*gUiScale);
        for(int l=0;l<4;++l) draw->AddLine(ImVec2(min.x+48*gUiScale,min.y+(30+l*14)*gUiScale),ImVec2(max.x-43*gUiScale,min.y+(30+l*14)*gUiScale),IM_COL32(72,118,160,255),2*gUiScale);
        draw->AddCircle(ImVec2(max.x-42*gUiScale,max.y-33*gUiScale),22*gUiScale,IM_COL32(40,116,187,255),28,4*gUiScale);
    }else if(kind==3){
        draw->AddText(ImGui::GetFont(),ImGui::GetFontSize()*3.0f,ImVec2(min.x+16*gUiScale,min.y+24*gUiScale),IM_COL32_WHITE,"7Z");
        draw->AddLine(ImVec2((min.x+max.x)*.5f,min.y),ImVec2((min.x+max.x)*.5f,max.y),IM_COL32(90,90,90,255),1*gUiScale);
    }else{
        draw->AddText(ImGui::GetFont(),big,ImVec2(min.x+12*gUiScale,min.y+12*gUiScale),IM_COL32(45,35,35,255),"PP-OCR");
        draw->AddText(ImGui::GetFont(),ImGui::GetFontSize()*2.0f,ImVec2(min.x+35*gUiScale,min.y+50*gUiScale),IM_COL32(70,35,35,255),"V3");
    }
}

void drawPlugins(bool applyScroll) {
    const float margin=10*gUiScale;
    const float cardWidth=(ImGui::GetContentRegionAvail().x-margin*3)/2.0f;
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding,ImVec2(0,10*gUiScale));
    ImGui::PushStyleColor(ImGuiCol_ChildBg,ImVec4(0.188f,0.188f,0.188f,1.0f));
    ImGui::BeginChild("plugin_grid",ImVec2(0,0),false,ImGuiWindowFlags_NoScrollbar);
    ImGui::PopStyleColor();
    ImGui::PopStyleVar();
    if(applyScroll) applyPendingVerticalScroll();
    ImGui::PushStyleVar(ImGuiStyleVar_ItemSpacing,ImVec2(margin,10*gUiScale));
    for(int i=0;i<static_cast<int>(gPluginEntries.size());++i){
        const PluginEntry &entry=gPluginEntries[static_cast<size_t>(i)];
        if(i%2==0) ImGui::SetCursorPosX(margin); else ImGui::SameLine(0.0f,margin);
        ImGui::PushID(i);
        const bool clicked=ImGui::InvisibleButton("##plugin",ImVec2(cardWidth,224*gUiScale));
        const ImVec2 min=ImGui::GetItemRectMin(),max=ImGui::GetItemRectMax();
        ImDrawList *draw=ImGui::GetWindowDrawList();
        draw->AddRectFilled(min,max,IM_COL32(31,33,33,255),5*gUiScale);
        const ImVec2 coverMax(max.x,min.y+150*gUiScale);
        if(entry.installed){
            drawPluginCover(draw,min,coverMax,i%5);
        }else{
            draw->AddRectFilled(min,coverMax,IM_COL32(44,61,72,255),4*gUiScale,ImDrawFlags_RoundCornersTop);
            const char *apk="APK";
            const ImVec2 apkSize=ImGui::CalcTextSize(apk);
            draw->AddText(ImGui::GetFont(),ImGui::GetFontSize()*2.0f,
                          ImVec2((min.x+coverMax.x-apkSize.x*2.0f)*.5f,min.y+47*gUiScale),
                          IM_COL32(232,238,240,255),apk);
            drawDownloadIcon(draw,ImVec2((min.x+max.x)*.5f,min.y+111*gUiScale),IM_COL32(96,205,184,255));
        }
        draw->PushClipRect(ImVec2(min.x+10*gUiScale,min.y+150*gUiScale),max,true);
        draw->AddText(ImGui::GetFont(),ImGui::GetFontSize()*1.06f,ImVec2(min.x+10*gUiScale,min.y+160*gUiScale),IM_COL32(239,242,241,255),entry.name.c_str());
        draw->AddText(ImVec2(min.x+10*gUiScale,min.y+190*gUiScale),entry.installed?IM_COL32(91,203,177,255):IM_COL32(155,160,158,255),entry.version.c_str());
        draw->PopClipRect();
        if(clicked&&gAllowActivationThisFrame){gSelectedPlugin=i;queueAction(40);}
        ImGui::PopID();
    }
    if(gPluginEntries.empty()){
        ImGui::SetCursorPos(ImVec2(18*gUiScale,24*gUiScale));
        ImGui::TextDisabled(u8"没有检测到 AI.js Pro 插件");
    }
    ImGui::PopStyleVar();
    ImGui::EndChild();
}

void drawPluginHelpFab(int width,int height,float offsetX){
    const float radius=25*gUiScale;
    const ImVec2 center(static_cast<float>(width)-14*gUiScale-radius+offsetX,
                        static_cast<float>(height)-14*gUiScale-radius);
    ImDrawList *draw=ImGui::GetForegroundDrawList();
    ImGuiIO &io=ImGui::GetIO();
    const float dx=io.MousePos.x-center.x,dy=io.MousePos.y-center.y;
    if(gAllowActivationThisFrame&&ImGui::IsMouseClicked(0)&&dx*dx+dy*dy<=radius*radius){
        queueAction(43);
        io.MousePos=ImVec2(-100000,-100000);
    }
    draw->AddCircleFilled(ImVec2(center.x+1.5f*gUiScale,center.y+2.5f*gUiScale),radius,IM_COL32(0,0,0,48),36);
    draw->AddCircleFilled(center,radius,IM_COL32(0,150,136,255),36);
    const char *question="?";
    const ImVec2 size=ImGui::CalcTextSize(question);
    draw->AddText(ImGui::GetFont(),ImGui::GetFontSize()*1.55f,
                  ImVec2(center.x-size.x*.5f,center.y-size.y*.72f),IM_COL32_WHITE,question);
}

void drawResourceUploadFab(int width,int height,float offsetX){
    const float radius=25*gUiScale;
    const ImVec2 center(static_cast<float>(width)*.5f+offsetX,
                        static_cast<float>(height)-18*gUiScale-42*gUiScale-radius);
    ImDrawList *draw=ImGui::GetForegroundDrawList();
    ImGuiIO &io=ImGui::GetIO();
    const float dx=io.MousePos.x-center.x,dy=io.MousePos.y-center.y;
    if(gAllowActivationThisFrame&&ImGui::IsMouseClicked(0)&&dx*dx+dy*dy<=radius*radius){
        queueAction(32);
        io.MousePos=ImVec2(-100000,-100000);
    }
    draw->AddCircleFilled(ImVec2(center.x+1.5f*gUiScale,center.y+2.5f*gUiScale),radius,IM_COL32(0,0,0,48),36);
    draw->AddCircleFilled(center,radius,IM_COL32(0,150,136,255),36);
    const ImU32 white=IM_COL32_WHITE;
    draw->AddLine(ImVec2(center.x,center.y+7*gUiScale),ImVec2(center.x,center.y-10*gUiScale),white,2.2f*gUiScale);
    draw->AddLine(ImVec2(center.x,center.y-10*gUiScale),ImVec2(center.x-6*gUiScale,center.y-4*gUiScale),white,2.2f*gUiScale);
    draw->AddLine(ImVec2(center.x,center.y-10*gUiScale),ImVec2(center.x+6*gUiScale,center.y-4*gUiScale),white,2.2f*gUiScale);
    draw->AddLine(ImVec2(center.x-9*gUiScale,center.y+10*gUiScale),ImVec2(center.x+9*gUiScale,center.y+10*gUiScale),white,2.2f*gUiScale);
}

void drawCreateFab(int width, int height, float offsetX) {
    const float margin = 14.0f * gUiScale;
    const float fabSize = 54.0f * gUiScale;
    const float radius = fabSize * 0.5f;
    const float menuWidth = 150.0f * gUiScale;
    const float menuHeight = 42.0f * gUiScale;
    const ImVec2 center(static_cast<float>(width) - margin - radius + offsetX,
                        static_cast<float>(height) - margin - radius);
    ImDrawList *drawList = ImGui::GetForegroundDrawList();
    ImGuiIO &io = ImGui::GetIO();
    bool consumed = false;

    auto inside = [](const ImVec2 &point, const ImVec2 &minimum, const ImVec2 &maximum) {
        return point.x >= minimum.x && point.y >= minimum.y &&
               point.x <= maximum.x && point.y <= maximum.y;
    };
    if (gCreateMenuOpen) {
        const ImVec2 folderMin(static_cast<float>(width) - margin - menuWidth + offsetX,
                               center.y - radius - 102.0f * gUiScale);
        const ImVec2 folderMax(folderMin.x + menuWidth, folderMin.y + menuHeight);
        const ImVec2 scriptMin(folderMin.x, folderMax.y + 8.0f * gUiScale);
        const ImVec2 scriptMax(scriptMin.x + menuWidth, scriptMin.y + menuHeight);
        drawList->AddRectFilled(folderMin, folderMax, IM_COL32(0, 105, 96, 255),
                                8.0f * gUiScale);
        drawList->AddRectFilled(scriptMin, scriptMax, IM_COL32(0, 105, 96, 255),
                                8.0f * gUiScale);
        const ImVec2 folderText = ImGui::CalcTextSize(u8"新建文件夹");
        const ImVec2 scriptText = ImGui::CalcTextSize(u8"新建脚本");
        drawList->AddText(ImVec2(folderMin.x + (menuWidth - folderText.x) * 0.5f,
                                 folderMin.y + (menuHeight - folderText.y) * 0.5f),
                          IM_COL32_WHITE, u8"新建文件夹");
        drawList->AddText(ImVec2(scriptMin.x + (menuWidth - scriptText.x) * 0.5f,
                                 scriptMin.y + (menuHeight - scriptText.y) * 0.5f),
                          IM_COL32_WHITE, u8"新建脚本");
        if (gAllowActivationThisFrame && ImGui::IsMouseClicked(0) &&
            inside(io.MousePos, folderMin, folderMax)) {
            gCreateMenuOpen = false;
            queueAction(16);
            consumed = true;
        }
        if (gAllowActivationThisFrame && ImGui::IsMouseClicked(0) &&
            inside(io.MousePos, scriptMin, scriptMax)) {
            gCreateMenuOpen = false;
            queueAction(15);
            consumed = true;
        }
    }

    const float dx = io.MousePos.x - center.x;
    const float dy = io.MousePos.y - center.y;
    if (gAllowActivationThisFrame && ImGui::IsMouseClicked(0) &&
        dx * dx + dy * dy <= radius * radius) {
        gCreateMenuOpen = !gCreateMenuOpen;
        consumed = true;
    }
    if (consumed) {
        io.MousePos = ImVec2(-100000.0f, -100000.0f);
    }

    drawList->AddCircleFilled(ImVec2(center.x + 1.5f * gUiScale,
                                     center.y + 2.5f * gUiScale),
                              radius, IM_COL32(0, 0, 0, 48), 40);
    drawList->AddCircleFilled(center, radius, IM_COL32(0, 150, 136, 255), 40);
    const ImU32 iconColor = IM_COL32_WHITE;
    const float iconRadius = 8.5f * gUiScale;
    if (gCreateMenuOpen) {
        drawList->AddLine(ImVec2(center.x - iconRadius, center.y - iconRadius),
                          ImVec2(center.x + iconRadius, center.y + iconRadius),
                          iconColor, 2.5f * gUiScale);
        drawList->AddLine(ImVec2(center.x + iconRadius, center.y - iconRadius),
                          ImVec2(center.x - iconRadius, center.y + iconRadius),
                          iconColor, 2.5f * gUiScale);
    } else {
        for (int line = -1; line <= 1; ++line) {
            const float y = center.y + line * 6.0f*gUiScale;
            drawList->AddLine(ImVec2(center.x - iconRadius, y),
                              ImVec2(center.x + iconRadius, y), iconColor, 2.0f * gUiScale);
        }
    }
}

void drawTaskFab(int width,int height,float offsetX){
    const float radius=27*gUiScale;
    const ImVec2 center(static_cast<float>(width)-14*gUiScale-radius+offsetX,
                        static_cast<float>(height)-14*gUiScale-radius);
    ImDrawList *draw=ImGui::GetForegroundDrawList();
    ImGuiIO &io=ImGui::GetIO();
    const float dx=io.MousePos.x-center.x,dy=io.MousePos.y-center.y;
    if(gAllowActivationThisFrame&&ImGui::IsMouseClicked(0)&&dx*dx+dy*dy<=radius*radius){
        queueAction(53);
        io.MousePos=ImVec2(-100000,-100000);
    }
    draw->AddCircleFilled(ImVec2(center.x+1.5f*gUiScale,center.y+2.5f*gUiScale),radius,IM_COL32(0,0,0,48),36);
    draw->AddCircleFilled(center,radius,IM_COL32(0,150,136,255),36);
    draw->AddLine(ImVec2(center.x-9*gUiScale,center.y),ImVec2(center.x+9*gUiScale,center.y),IM_COL32_WHITE,2*gUiScale);
    draw->AddLine(ImVec2(center.x,center.y-9*gUiScale),ImVec2(center.x,center.y+9*gUiScale),IM_COL32_WHITE,2*gUiScale);
    if(gRunningScriptCount.load()>0){
        const ImVec2 stopCenter(center.x-66*gUiScale,center.y);
        const float sx=io.MousePos.x-stopCenter.x,sy=io.MousePos.y-stopCenter.y;
        if(gAllowActivationThisFrame&&ImGui::IsMouseClicked(0)&&sx*sx+sy*sy<=radius*radius){
            queueAction(14);
            io.MousePos=ImVec2(-100000,-100000);
        }
        draw->AddCircleFilled(ImVec2(stopCenter.x+1.5f*gUiScale,stopCenter.y+2.5f*gUiScale),
                              radius,IM_COL32(0,0,0,48),36);
        draw->AddCircleFilled(stopCenter,radius,IM_COL32(183,48,58,255),36);
        draw->AddLine(ImVec2(stopCenter.x-8*gUiScale,stopCenter.y-8*gUiScale),
                      ImVec2(stopCenter.x+8*gUiScale,stopCenter.y+8*gUiScale),IM_COL32_WHITE,2.4f*gUiScale);
        draw->AddLine(ImVec2(stopCenter.x+8*gUiScale,stopCenter.y-8*gUiScale),
                      ImVec2(stopCenter.x-8*gUiScale,stopCenter.y+8*gUiScale),IM_COL32_WHITE,2.4f*gUiScale);
    }
}

void drawContentPage(int section, float offsetX, const ImVec2 &origin,
                     const ImVec2 &size, bool applyScroll, int width, int height) {
    ImGui::SetCursorScreenPos(ImVec2(origin.x + offsetX, origin.y));
    ImGui::PushID(section);
    ImGui::PushStyleColor(ImGuiCol_ChildBg, ImVec4(0.188f, 0.188f, 0.188f, 1.0f));
    ImGui::PushStyleVar(ImGuiStyleVar_ChildRounding, 0.0f);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(0.0f, 0.0f));
    ImGui::BeginChild("content_page", size, false,
                      ImGuiWindowFlags_AlwaysUseWindowPadding |
                      ImGuiWindowFlags_NoNavFocus | ImGuiWindowFlags_NoScrollbar);
    ImGui::PopStyleVar(2);
    ImGui::PopStyleColor();

    if (section == 0) {
        drawScripts(applyScroll);
    } else {
        if (section == 1) {
            drawSamples(applyScroll);
        } else if (section == 2) {
            drawResources(applyScroll);
        } else if (section == 3) {
            drawPlugins(applyScroll);
        } else {
            if(applyScroll) applyPendingVerticalScroll();
            drawOverview();
        }
    }
    ImGui::EndChild();
    if (section == 0) {
        drawCreateFab(width, height, offsetX);
    } else if(section==2){
        drawResourceUploadFab(width,height,offsetX);
    } else if(section==3){
        drawPluginHelpFab(width,height,offsetX);
    } else if(section==4){
        drawTaskFab(width,height,offsetX);
    }
    ImGui::PopID();
}

void drawWorkspace(int width, int height) {
    ImGui::SetNextWindowPos(ImVec2(0.0f, 0.0f));
    ImGui::SetNextWindowSize(ImVec2(static_cast<float>(width), static_cast<float>(height)));
    const ImGuiWindowFlags flags = ImGuiWindowFlags_NoDecoration |
                                   ImGuiWindowFlags_NoMove |
                                   ImGuiWindowFlags_NoSavedSettings |
                                   ImGuiWindowFlags_NoBringToFrontOnFocus;
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(0.0f, 0.0f));
    ImGui::Begin("AI.js Pro ImGui Workspace", nullptr, flags);
    ImGui::PopStyleVar();

    const float pageWidth = static_cast<float>(width);
    const float visualSection = static_cast<float>(gSection) -
                                (pageWidth > 0.0f ? gPageOffset / pageWidth : 0.0f);
    drawTopBar(visualSection);
    const ImVec2 contentOrigin = ImGui::GetCursorScreenPos();
    const ImVec2 contentSize(pageWidth,
                             std::max(1.0f, static_cast<float>(height) - contentOrigin.y));

    drawContentPage(gSection, gPageOffset, contentOrigin, contentSize,
                    true, width, height);
    if (gPageOffset < -0.5f && gSection < 4) {
        drawContentPage(gSection + 1, gPageOffset + pageWidth, contentOrigin, contentSize,
                        false, width, height);
    } else if (gPageOffset > 0.5f && gSection > 0) {
        drawContentPage(gSection - 1, gPageOffset - pageWidth, contentOrigin, contentSize,
                        false, width, height);
    }
    ImGui::End();
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_attachSurface(
        JNIEnv *env, jclass, jobject javaSurface) {
    std::lock_guard<std::recursive_mutex> lock(gRenderMutex);
    shutdownEgl();
    gWindow = ANativeWindow_fromSurface(env, javaSurface);
    if (!gWindow) {
        return JNI_FALSE;
    }
    if (!initEgl(gWindow)) {
        shutdownEgl();
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_detachSurface(JNIEnv *, jclass) {
    std::lock_guard<std::recursive_mutex> lock(gRenderMutex);
    shutdownEgl();
}

extern "C" JNIEXPORT void JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_setUiFontPath(
        JNIEnv *env, jclass, jstring path) {
    std::lock_guard<std::recursive_mutex> lock(gRenderMutex);
    if (!path) {
        gFontPath.clear();
        return;
    }
    const char *characters = env->GetStringUTFChars(path, nullptr);
    gFontPath = characters ? characters : "";
    if (characters) {
        env->ReleaseStringUTFChars(path, characters);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_setWorkspaceState(
        JNIEnv *, jclass, jboolean accessibilityEnabled, jint runningScriptCount) {
    gAccessibilityEnabled.store(accessibilityEnabled == JNI_TRUE);
    gRunningScriptCount.store(std::max(0, static_cast<int>(runningScriptCount)));
}

extern "C" JNIEXPORT void JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_setScriptEntries(
        JNIEnv *env, jclass, jstring directoryLabel, jobjectArray names,
        jobjectArray paths, jbooleanArray directories, jobjectArray types,
        jobjectArray modifiedTimes, jintArray iconKinds) {
    std::lock_guard<std::recursive_mutex> lock(gRenderMutex);

    std::string previousPath;
    if (gSelectedScript >= 0 && gSelectedScript < static_cast<int>(gScriptEntries.size())) {
        previousPath = gScriptEntries[static_cast<size_t>(gSelectedScript)].path;
    }

    const char *labelCharacters = directoryLabel
                                  ? env->GetStringUTFChars(directoryLabel, nullptr)
                                  : nullptr;
    gScriptDirectoryLabel = labelCharacters ? labelCharacters : "";
    if (labelCharacters) {
        env->ReleaseStringUTFChars(directoryLabel, labelCharacters);
    }

    gScriptEntries.clear();
    gSelectedScript = -1;
    if (!names || !paths || !directories || !types || !modifiedTimes || !iconKinds) {
        return;
    }

    const jsize count = std::min(env->GetArrayLength(names),
                                 std::min(env->GetArrayLength(paths),
                                 std::min(env->GetArrayLength(directories),
                                 std::min(env->GetArrayLength(types),
                                 std::min(env->GetArrayLength(modifiedTimes),
                                          env->GetArrayLength(iconKinds))))));
    std::vector<jboolean> directoryValues(static_cast<size_t>(count));
    if (count > 0) {
        env->GetBooleanArrayRegion(directories, 0, count, directoryValues.data());
    }
    std::vector<jint> iconValues(static_cast<size_t>(count));
    if (count > 0) {
        env->GetIntArrayRegion(iconKinds, 0, count, iconValues.data());
    }
    gScriptEntries.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto name = static_cast<jstring>(env->GetObjectArrayElement(names, i));
        auto path = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
        auto type = static_cast<jstring>(env->GetObjectArrayElement(types, i));
        auto modified = static_cast<jstring>(env->GetObjectArrayElement(modifiedTimes, i));
        const char *nameCharacters = name ? env->GetStringUTFChars(name, nullptr) : nullptr;
        const char *pathCharacters = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
        const char *typeCharacters = type ? env->GetStringUTFChars(type, nullptr) : nullptr;
        const char *modifiedCharacters = modified ? env->GetStringUTFChars(modified, nullptr) : nullptr;
        ScriptEntry entry{
                nameCharacters ? nameCharacters : "",
                pathCharacters ? pathCharacters : "",
                directoryValues[static_cast<size_t>(i)] == JNI_TRUE,
                typeCharacters ? typeCharacters : "",
                modifiedCharacters ? modifiedCharacters : "",
                static_cast<int>(iconValues[static_cast<size_t>(i)])};
        if (nameCharacters) {
            env->ReleaseStringUTFChars(name, nameCharacters);
        }
        if (pathCharacters) {
            env->ReleaseStringUTFChars(path, pathCharacters);
        }
        if (typeCharacters) {
            env->ReleaseStringUTFChars(type, typeCharacters);
        }
        if (modifiedCharacters) {
            env->ReleaseStringUTFChars(modified, modifiedCharacters);
        }
        if (name) {
            env->DeleteLocalRef(name);
        }
        if (path) {
            env->DeleteLocalRef(path);
        }
        if (type) {
            env->DeleteLocalRef(type);
        }
        if (modified) {
            env->DeleteLocalRef(modified);
        }
        if (!previousPath.empty() && entry.path == previousPath) {
            gSelectedScript = static_cast<int>(gScriptEntries.size());
        }
        gScriptEntries.push_back(std::move(entry));
    }
    gScriptEntriesRevision++;
}

extern "C" JNIEXPORT void JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_setSampleEntries(
        JNIEnv *env, jclass, jstring directoryLabel, jobjectArray names,
        jobjectArray paths, jbooleanArray directories, jobjectArray types,
        jobjectArray modifiedTimes) {
    std::lock_guard<std::recursive_mutex> lock(gRenderMutex);
    std::string previousPath;
    if(gSelectedSample>=0&&gSelectedSample<static_cast<int>(gSampleEntries.size()))
        previousPath=gSampleEntries[static_cast<size_t>(gSelectedSample)].path;
    const char *label=directoryLabel?env->GetStringUTFChars(directoryLabel,nullptr):nullptr;
    gSampleDirectoryLabel=label?label:u8"示例文件  >  中文";
    if(label) env->ReleaseStringUTFChars(directoryLabel,label);
    gSampleEntries.clear();
    gSelectedSample=-1;
    if(!names||!paths||!directories||!types||!modifiedTimes) return;
    const jsize count=std::min(env->GetArrayLength(names),std::min(env->GetArrayLength(paths),
                      std::min(env->GetArrayLength(directories),std::min(env->GetArrayLength(types),env->GetArrayLength(modifiedTimes)))));
    std::vector<jboolean> directoryValues(static_cast<size_t>(count));
    if(count>0) env->GetBooleanArrayRegion(directories,0,count,directoryValues.data());
    gSampleEntries.reserve(static_cast<size_t>(count));
    for(jsize i=0;i<count;++i){
        auto name=static_cast<jstring>(env->GetObjectArrayElement(names,i));
        auto path=static_cast<jstring>(env->GetObjectArrayElement(paths,i));
        auto type=static_cast<jstring>(env->GetObjectArrayElement(types,i));
        auto modified=static_cast<jstring>(env->GetObjectArrayElement(modifiedTimes,i));
        const char *n=name?env->GetStringUTFChars(name,nullptr):nullptr;
        const char *p=path?env->GetStringUTFChars(path,nullptr):nullptr;
        const char *t=type?env->GetStringUTFChars(type,nullptr):nullptr;
        const char *m=modified?env->GetStringUTFChars(modified,nullptr):nullptr;
        SampleEntry entry{n?n:"",p?p:"",directoryValues[static_cast<size_t>(i)]==JNI_TRUE,t?t:"",m?m:""};
        if(n) env->ReleaseStringUTFChars(name,n); if(p) env->ReleaseStringUTFChars(path,p);
        if(t) env->ReleaseStringUTFChars(type,t); if(m) env->ReleaseStringUTFChars(modified,m);
        if(name) env->DeleteLocalRef(name); if(path) env->DeleteLocalRef(path);
        if(type) env->DeleteLocalRef(type); if(modified) env->DeleteLocalRef(modified);
        if(!previousPath.empty()&&entry.path==previousPath) gSelectedSample=static_cast<int>(gSampleEntries.size());
        gSampleEntries.push_back(std::move(entry));
    }
    gSampleEntriesRevision++;
}

extern "C" JNIEXPORT void JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_setResourceEntries(
        JNIEnv *env,jclass,jobjectArray names,jobjectArray descriptions,jobjectArray metadata,
        jobjectArray assetPaths,jbooleanArray imported){
    std::lock_guard<std::recursive_mutex> lock(gRenderMutex);
    gResourceEntries.clear(); gSelectedResource=-1;
    if(!names||!descriptions||!metadata||!assetPaths||!imported) return;
    const jsize count=std::min(env->GetArrayLength(names),std::min(env->GetArrayLength(descriptions),
                      std::min(env->GetArrayLength(metadata),std::min(env->GetArrayLength(assetPaths),env->GetArrayLength(imported)))));
    std::vector<jboolean> owned(static_cast<size_t>(count));
    if(count>0) env->GetBooleanArrayRegion(imported,0,count,owned.data());
    gResourceEntries.reserve(static_cast<size_t>(count));
    for(jsize i=0;i<count;++i){
        auto name=static_cast<jstring>(env->GetObjectArrayElement(names,i));
        auto desc=static_cast<jstring>(env->GetObjectArrayElement(descriptions,i));
        auto meta=static_cast<jstring>(env->GetObjectArrayElement(metadata,i));
        auto path=static_cast<jstring>(env->GetObjectArrayElement(assetPaths,i));
        const char *n=name?env->GetStringUTFChars(name,nullptr):nullptr;
        const char *d=desc?env->GetStringUTFChars(desc,nullptr):nullptr;
        const char *m=meta?env->GetStringUTFChars(meta,nullptr):nullptr;
        const char *p=path?env->GetStringUTFChars(path,nullptr):nullptr;
        gResourceEntries.push_back({n?n:"",d?d:"",m?m:"",p?p:"",owned[static_cast<size_t>(i)]==JNI_TRUE});
        if(n) env->ReleaseStringUTFChars(name,n); if(d) env->ReleaseStringUTFChars(desc,d);
        if(m) env->ReleaseStringUTFChars(meta,m); if(p) env->ReleaseStringUTFChars(path,p);
        if(name) env->DeleteLocalRef(name); if(desc) env->DeleteLocalRef(desc);
        if(meta) env->DeleteLocalRef(meta); if(path) env->DeleteLocalRef(path);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_setPluginEntries(
        JNIEnv *env,jclass,jobjectArray names,jobjectArray versions,jobjectArray packages,jbooleanArray installed){
    std::lock_guard<std::recursive_mutex> lock(gRenderMutex);
    gPluginEntries.clear(); gSelectedPlugin=-1;
    if(!names||!versions||!packages||!installed) return;
    const jsize count=std::min(env->GetArrayLength(names),std::min(env->GetArrayLength(versions),
                      std::min(env->GetArrayLength(packages),env->GetArrayLength(installed))));
    std::vector<jboolean> installedValues(static_cast<size_t>(count));
    if(count>0) env->GetBooleanArrayRegion(installed,0,count,installedValues.data());
    gPluginEntries.reserve(static_cast<size_t>(count));
    for(jsize i=0;i<count;++i){
        auto name=static_cast<jstring>(env->GetObjectArrayElement(names,i));
        auto version=static_cast<jstring>(env->GetObjectArrayElement(versions,i));
        auto packageName=static_cast<jstring>(env->GetObjectArrayElement(packages,i));
        const char *n=name?env->GetStringUTFChars(name,nullptr):nullptr;
        const char *v=version?env->GetStringUTFChars(version,nullptr):nullptr;
        const char *p=packageName?env->GetStringUTFChars(packageName,nullptr):nullptr;
        gPluginEntries.push_back({n?n:"",v?v:"",p?p:"",installedValues[static_cast<size_t>(i)]==JNI_TRUE});
        if(n) env->ReleaseStringUTFChars(name,n); if(v) env->ReleaseStringUTFChars(version,v);
        if(p) env->ReleaseStringUTFChars(packageName,p);
        if(name) env->DeleteLocalRef(name); if(version) env->DeleteLocalRef(version);
        if(packageName) env->DeleteLocalRef(packageName);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_setTaskEntries(
        JNIEnv *env,jclass,jobjectArray runningNames,jobjectArray runningDescriptions,
        jobjectArray pendingNames,jobjectArray pendingDescriptions){
    std::lock_guard<std::recursive_mutex> lock(gRenderMutex);
    auto fill=[&](jobjectArray names,jobjectArray descriptions,std::vector<TaskEntry> &target){
        target.clear();
        if(!names||!descriptions) return;
        const jsize count=std::min(env->GetArrayLength(names),env->GetArrayLength(descriptions));
        target.reserve(static_cast<size_t>(count));
        for(jsize i=0;i<count;++i){
            auto name=static_cast<jstring>(env->GetObjectArrayElement(names,i));
            auto description=static_cast<jstring>(env->GetObjectArrayElement(descriptions,i));
            const char *n=name?env->GetStringUTFChars(name,nullptr):nullptr;
            const char *d=description?env->GetStringUTFChars(description,nullptr):nullptr;
            target.push_back({n?n:"",d?d:""});
            if(n) env->ReleaseStringUTFChars(name,n); if(d) env->ReleaseStringUTFChars(description,d);
            if(name) env->DeleteLocalRef(name); if(description) env->DeleteLocalRef(description);
        }
    };
    fill(runningNames,runningDescriptions,gRunningTasks);
    fill(pendingNames,pendingDescriptions,gPendingTasks);
    if(gSelectedTaskGroup==0&&gSelectedTaskIndex>=static_cast<int>(gRunningTasks.size())){gSelectedTaskGroup=-1;gSelectedTaskIndex=-1;}
    if(gSelectedTaskGroup==1&&gSelectedTaskIndex>=static_cast<int>(gPendingTasks.size())){gSelectedTaskGroup=-1;gSelectedTaskIndex=-1;}
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_getSelectedScriptPath(
        JNIEnv *env, jclass) {
    std::lock_guard<std::recursive_mutex> lock(gRenderMutex);
    if (gSelectedScript < 0 || gSelectedScript >= static_cast<int>(gScriptEntries.size())) {
        return nullptr;
    }
    return env->NewStringUTF(gScriptEntries[static_cast<size_t>(gSelectedScript)].path.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_getSelectedSamplePath(JNIEnv *env,jclass){
    std::lock_guard<std::recursive_mutex> lock(gRenderMutex);
    if(gSelectedSample<0||gSelectedSample>=static_cast<int>(gSampleEntries.size())) return nullptr;
    return env->NewStringUTF(gSampleEntries[static_cast<size_t>(gSelectedSample)].path.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_getSelectedResourcePath(JNIEnv *env,jclass){
    std::lock_guard<std::recursive_mutex> lock(gRenderMutex);
    if(gSelectedResource<0||gSelectedResource>=static_cast<int>(gResourceEntries.size())) return nullptr;
    return env->NewStringUTF(gResourceEntries[static_cast<size_t>(gSelectedResource)].assetPath.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_getSelectedPluginPackage(JNIEnv *env,jclass){
    std::lock_guard<std::recursive_mutex> lock(gRenderMutex);
    if(gSelectedPlugin<0||gSelectedPlugin>=static_cast<int>(gPluginEntries.size())) return nullptr;
    return env->NewStringUTF(gPluginEntries[static_cast<size_t>(gSelectedPlugin)].packageName.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_getSelectedTaskKey(JNIEnv *,jclass){
    std::lock_guard<std::recursive_mutex> lock(gRenderMutex);
    if(gSelectedTaskGroup<0||gSelectedTaskIndex<0) return -1;
    return gSelectedTaskGroup*10000+gSelectedTaskIndex;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_getSelectedBreadcrumbDepth(JNIEnv *,jclass){
    return gSelectedBreadcrumbDepth.load();
}

extern "C" JNIEXPORT jint JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_getCurrentSection(JNIEnv *, jclass) {
    std::lock_guard<std::recursive_mutex> lock(gRenderMutex);
    return gSection;
}

extern "C" JNIEXPORT void JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_renderFrame(
        JNIEnv *, jclass, jint width, jint height, jfloat density) {
    std::lock_guard<std::recursive_mutex> lock(gRenderMutex);
    if (gDisplay == EGL_NO_DISPLAY || gSurface == EGL_NO_SURFACE ||
        gContext == EGL_NO_CONTEXT || width <= 0 || height <= 0) {
        return;
    }
    if (eglGetCurrentContext() != gContext &&
        !eglMakeCurrent(gDisplay, gSurface, gSurface, gContext)) {
        return;
    }

    ImGuiIO &io = ImGui::GetIO();
    io.DisplaySize = ImVec2(static_cast<float>(width), static_cast<float>(height));
    const auto now = std::chrono::steady_clock::now();
    io.DeltaTime = std::clamp(std::chrono::duration<float>(now - gLastFrame).count(),
                              1.0f / 240.0f, 1.0f / 15.0f);
    gLastFrame = now;

    if (!gUiConfigured) {
        configureUi(density);
    }

    bool pageDragging = false;
    float pageDragPixels = 0.0f;
    bool pageReleased = false;
    bool pageReleaseCancelled = false;
    float pageReleasePixels = 0.0f;
    float pageReleaseVelocity = 0.0f;
    bool scrollDragging = false;
    bool flingReleased = false;
    float releasedFlingVelocity = 0.0f;
    bool cancelFling = false;
    {
        std::lock_guard<std::mutex> inputLock(gInputMutex);
        gFrameScrollPixels = gPendingScrollPixels;
        gPendingScrollPixels = 0.0f;
        pageDragging = gInputPageDragging;
        pageDragPixels = gInputPageDragPixels;
        pageReleased = gPageReleasePending;
        pageReleaseCancelled = gPageReleaseCancelled;
        pageReleasePixels = gPageReleasePixels;
        pageReleaseVelocity = gPageReleaseVelocity;
        gPageReleasePending = false;
        gPageReleaseCancelled = false;
        scrollDragging = gTouchScrolling;
        flingReleased = gFlingPending;
        releasedFlingVelocity = gPendingFlingVelocity;
        gFlingPending = false;
        cancelFling = gCancelFlingPending;
        gCancelFlingPending = false;

        gAllowActivationThisFrame = gAuthorizedTapFrames > 0;
        if (gSuppressInputFrames > 0) {
            io.AddMousePosEvent(-100000.0f, -100000.0f);
            io.AddMouseButtonEvent(0, false);
            gTapPhase = 0;
            gAuthorizedTapFrames = 0;
            gAllowActivationThisFrame = false;
            --gSuppressInputFrames;
        } else if (gTapPhase == 1) {
            io.AddMouseSourceEvent(ImGuiMouseSource_TouchScreen);
            io.AddMousePosEvent(gTapX, gTapY);
            io.AddMouseButtonEvent(0, true);
            gTapPhase = 2;
        } else if (gTapPhase == 2) {
            io.AddMouseSourceEvent(ImGuiMouseSource_TouchScreen);
            io.AddMousePosEvent(gTapX, gTapY);
            io.AddMouseButtonEvent(0, false);
            gTapPhase = 3;
        } else if (gTapPhase == 3) {
            io.AddMousePosEvent(-100000.0f, -100000.0f);
            gTapPhase = 0;
        }
        if (gAuthorizedTapFrames > 0) {
            --gAuthorizedTapFrames;
        }
    }

    if (cancelFling || scrollDragging || pageDragging) {
        gScrollVelocity = 0.0f;
    }
    if (flingReleased) {
        gScrollVelocity = std::clamp(releasedFlingVelocity, -6500.0f, 6500.0f);
    }
    if (!scrollDragging && std::abs(gScrollVelocity) > 8.0f) {
        gFrameScrollPixels += gScrollVelocity * io.DeltaTime;
        gScrollVelocity *= std::exp(-4.8f * io.DeltaTime);
    } else if (!scrollDragging) {
        gScrollVelocity = 0.0f;
    }

    const float pageWidth = static_cast<float>(width);
    auto resistedPageOffset = [&](float offset) {
        if ((gSection == 0 && offset > 0.0f) || (gSection == 4 && offset < 0.0f)) {
            return offset * 0.24f;
        }
        return std::clamp(offset, -pageWidth, pageWidth);
    };
    if (pageDragging) {
        gPageAnimating = false;
        gPageTargetSection = -1;
        gPageOffset = resistedPageOffset(pageDragPixels);
    } else if (pageReleased) {
        gPageOffset = resistedPageOffset(pageReleasePixels);
        const bool movingLeft = pageReleasePixels < 0.0f;
        const bool targetAvailable = movingLeft ? gSection < 4 : gSection > 0;
        const bool distanceCommit = std::abs(pageReleasePixels) >= pageWidth * 0.28f;
        const bool velocityCommit = std::abs(pageReleasePixels) >= pageWidth * 0.10f &&
                                    std::abs(pageReleaseVelocity) >= 1100.0f &&
                                    ((movingLeft && pageReleaseVelocity < 0.0f) ||
                                     (!movingLeft && pageReleaseVelocity > 0.0f));
        const bool commit = !pageReleaseCancelled && targetAvailable &&
                            (distanceCommit || velocityCommit);
        gPageAnimationStart = gPageOffset;
        gPageAnimationTarget = commit ? (movingLeft ? -pageWidth : pageWidth) : 0.0f;
        gPageTargetSection = commit ? gSection + (movingLeft ? 1 : -1) : -1;
        gPageAnimationElapsed = 0.0f;
        const float remaining = std::abs(gPageAnimationTarget - gPageAnimationStart) /
                                std::max(1.0f, pageWidth);
        gPageAnimationDuration = std::clamp(0.12f + remaining * 0.15f, 0.14f, 0.27f);
        gPageAnimating = true;
    }
    if (!pageDragging && gPageAnimating) {
        gPageAnimationElapsed += io.DeltaTime;
        const float progress = std::clamp(gPageAnimationElapsed / gPageAnimationDuration,
                                          0.0f, 1.0f);
        const float eased = 1.0f - std::pow(1.0f - progress, 3.0f);
        gPageOffset = gPageAnimationStart +
                      (gPageAnimationTarget - gPageAnimationStart) * eased;
        if (progress >= 1.0f) {
            if (gPageTargetSection >= 0) {
                gSection = gPageTargetSection;
                gCreateMenuOpen = false;
            }
            gPageOffset = 0.0f;
            gPageTargetSection = -1;
            gPageAnimating = false;
        }
    }

    ImGui_ImplOpenGL3_NewFrame();
    ImGui::NewFrame();
    drawWorkspace(width, height);
    ImGui::Render();

    glViewport(0, 0, width, height);
    glClearColor(0.035f, 0.047f, 0.071f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
    eglSwapBuffers(gDisplay, gSurface);
}

extern "C" JNIEXPORT void JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_touch(
        JNIEnv *, jclass, jint action, jfloat x, jfloat y) {
    std::lock_guard<std::mutex> lock(gInputMutex);
    const auto now = std::chrono::steady_clock::now();
    if (action == 0) {
        gAuthorizedTapFrames = 0;
        gCancelFlingPending = true;
        gFlingPending = false;
        gTouchActive = true;
        gTouchScrolling = false;
        gTouchPaging = false;
        gTouchMoved = false;
        gTouchStartX = x;
        gTouchStartY = y;
        gTouchLastX = x;
        gTouchLastY = y;
        gTouchVelocityX = 0.0f;
        gTouchVelocityY = 0.0f;
        gTouchLastEvent = now;
        gInputPageDragging = false;
        gInputPageDragPixels = 0.0f;
    } else if (action == 2 && gTouchActive) {
        const float totalX = x - gTouchStartX;
        const float totalY = y - gTouchStartY;
        const float scale = gTouchScale.load(std::memory_order_relaxed);
        const float touchSlop = std::max(24.0f, 12.0f * scale);
        const float pageIntentSlop = std::max(48.0f, 20.0f * scale);
        const float absX = std::abs(totalX);
        const float absY = std::abs(totalY);
        const float elapsed = std::max(
                0.001f, std::chrono::duration<float>(now - gTouchLastEvent).count());
        const float instantVelocityX = (x - gTouchLastX) / elapsed;
        const float instantVelocityY = (gTouchLastY - y) / elapsed;
        gTouchVelocityX = gTouchVelocityX * 0.62f + instantVelocityX * 0.38f;
        gTouchVelocityY = gTouchVelocityY * 0.62f + instantVelocityY * 0.38f;
        if (absX > touchSlop || absY > touchSlop) {
            gTouchMoved = true;
        }
        // Match Pro's direction lock: scrolling gets first refusal, while paging requires a
        // longer, clearly horizontal gesture. Ambiguous diagonal motion remains undecided until
        // its intent is clear instead of immediately stealing the list's vertical scroll.
        if (!gTouchScrolling && !gTouchPaging && absY > touchSlop &&
            absX < absY * 1.55f) {
            gTouchScrolling = true;
            gSuppressInputFrames = 4;
            gAuthorizedTapFrames = 0;
            gTapPhase = 0;
            gPendingScrollPixels += gTouchStartY - y;
        } else if (!gTouchScrolling && !gTouchPaging && absX > pageIntentSlop &&
                   absX >= absY * 1.55f) {
            gTouchPaging = true;
            gSuppressInputFrames = 4;
            gAuthorizedTapFrames = 0;
            gTapPhase = 0;
            gInputPageDragging = true;
            gInputPageDragPixels = std::copysign(absX - pageIntentSlop, totalX);
        } else if (gTouchScrolling) {
            gSuppressInputFrames = 4;
            gAuthorizedTapFrames = 0;
            gPendingScrollPixels += gTouchLastY - y;
        } else if (gTouchPaging) {
            gSuppressInputFrames = 4;
            gAuthorizedTapFrames = 0;
            gInputPageDragging = true;
            gInputPageDragPixels = std::copysign(
                    std::max(0.0f, absX - pageIntentSlop), totalX);
        }
        gTouchLastX = x;
        gTouchLastY = y;
        gTouchLastEvent = now;
    } else if (action == 1 || action == 3 || action == 6) {
        const float idleSeconds = std::chrono::duration<float>(now - gTouchLastEvent).count();
        if (idleSeconds > 0.14f) {
            gTouchVelocityX = 0.0f;
            gTouchVelocityY = 0.0f;
        }
        if (gTouchActive && gTouchPaging) {
            const float releasedPagePixels = gInputPageDragPixels;
            gInputPageDragging = false;
            gPageReleasePending = true;
            gPageReleaseCancelled = action != 1;
            gPageReleasePixels = releasedPagePixels;
            gPageReleaseVelocity = action == 1 ? gTouchVelocityX : 0.0f;
            gPendingScrollPixels = 0.0f;
            gSuppressInputFrames = 4;
        } else if (gTouchActive && gTouchScrolling) {
            if (action == 1 && std::abs(gTouchVelocityY) > 40.0f) {
                gPendingFlingVelocity = gTouchVelocityY;
                gFlingPending = true;
            }
        } else if (gTouchActive && action == 1 && !gTouchMoved &&
                   !gTouchScrolling && !gTouchPaging) {
            gTapX = x;
            gTapY = y;
            gTapPhase = 1;
            gAuthorizedTapFrames = 6;
        }
        gTouchActive = false;
        gTouchScrolling = false;
        gTouchPaging = false;
        gTouchMoved = false;
        gInputPageDragging = false;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_pollAction(JNIEnv *, jclass) {
    return gPendingAction.exchange(0);
}
