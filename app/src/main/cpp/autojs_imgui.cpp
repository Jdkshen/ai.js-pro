#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>

#include "imgui.h"
#include "imgui_internal.h"
#include "imgui_impl_opengl3.h"
#include "imgui_accessibility_bridge.h"

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
ImGuiAccessibilityBridge gAccessibilityBridge;
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
float gUiScale = 1.0f;      // density-based: layout spacing, icons, touch targets
float gFontScale = 1.0f;     // density * fontScale: text rendering size
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

// ---- ImGui 左侧抽屉菜单（与 ImGuiWorkspaceActivity.java 的 ACTION_DRAWER_* 一一对应）----
enum {
    kDrawerActionAccessibility = 60,
    kDrawerActionToggleFloating = 61,
    kDrawerActionMoreServices = 62,
    kDrawerActionToggleDeveloper = 63,
    kDrawerActionTerminal = 64,
    kDrawerActionTheme = 65,
    kDrawerActionBlog = 66,
    kDrawerActionChannelForum = 67,
    kDrawerActionSettings = 68,
    kDrawerActionCheckUpdate = 69,
    kDrawerActionExit = 70,
    kActionOpenNativeDrawer = 71,
};

constexpr int kDrawerMenuActions[] = {
        kDrawerActionAccessibility,
        kDrawerActionToggleFloating,
        kDrawerActionMoreServices,
        kDrawerActionToggleDeveloper,
        kDrawerActionTerminal,
        kDrawerActionTheme,
        kDrawerActionBlog,
        kDrawerActionChannelForum,
        kDrawerActionSettings,
        kDrawerActionCheckUpdate,
        kDrawerActionExit,
};
constexpr int kDrawerMenuCount =
        static_cast<int>(sizeof(kDrawerMenuActions) / sizeof(kDrawerMenuActions[0]));

// 渲染线程状态：gDrawerProgress 为 atomic，touch 线程需读取（判断抽屉是否占用输入）。
bool gDrawerOpen = false;          // 完全打开（动画完成且目标为开）
bool gDrawerDragging = false;      // 正在拖动（渲染线程镜像）
std::atomic<float> gDrawerProgress{0.0f};  // 0=完全关闭，1=完全打开
float gDrawerTarget = -1.0f;       // -1 无目标，0=关闭，1=打开
float gDrawerAnimStart = 0.0f;
float gDrawerAnimElapsed = 0.0f;
constexpr float kDrawerAnimDuration = 0.20f;  // 180~240ms 推荐区间，ease-out

// 输入侧状态（gInputMutex 保护，仅 touch 线程写 / renderFrame 读）
bool gDrawerTouchActive = false;      // 本次触摸是否交给抽屉
bool gDrawerTouchDrag = false;        // 正在抽屉内拖动
bool gDrawerTouchScroll = false;      // 小屏/横屏时在抽屉内纵向滚动菜单
float gDrawerTouchDragProgress = 0.0f;
bool gDrawerTouchReleased = false;    // 等待渲染帧处理的手势释放
float gDrawerTouchReleaseProgress = 0.0f;
float gDrawerTouchReleaseVelocity = 0.0f;
bool gDrawerTouchCancelled = false;
bool gDrawerActiveForInput = false;   // 抽屉存在（>0 或动画中），背景输入必须禁用
float gDrawerPanelWidth = 0.0f;       // 当前帧抽屉面板宽度（供 touch 判断按下位置）

// 抽屉内点击由 Native 层直接命中分发（不经 ImGui 命中测试，避免 z-order 干扰）。
// gDrawerMenuAction: 0=无, -1=仅关闭抽屉, >0=关闭抽屉并执行对应 action。
int gDrawerMenuAction = 0;
int gDrawerActionAfterClose = 0;
std::atomic<float> gDrawerScrollY{0.0f};
std::atomic<float> gDrawerMaxScrollY{0.0f};

// 触摸线程局部（仅在 touch() 内使用）
float gDrawerDragStartX = 0.0f;
float gDrawerStartProgress = 0.0f;
float gDrawerDragWidth = 1.0f;
float gDrawerVelocityX = 0.0f;
float gDrawerTouchStartScrollY = 0.0f;

// Java setDrawerState 同步的菜单状态（避免每帧反调 Java）
std::atomic<bool> gDrawerAccessibilityEnabled{false};
std::atomic<bool> gDrawerFloatingShown{false};
std::atomic<bool> gDrawerDevConnected{false};

std::atomic<float> gTouchScale{1.0f};
std::chrono::steady_clock::time_point gLastFrame = std::chrono::steady_clock::now();

// ---- Unified ViewportMetrics (shared by rendering, touch, and layout) ----
struct ViewportMetrics {
    int viewWidthPx = 0;
    int viewHeightPx = 0;
    float density = 1.0f;
    float scaledDensity = 1.0f;
    float fontScale = 1.0f;
    int safeInsetLeftPx = 0;
    int safeInsetTopPx = 0;
    int safeInsetRightPx = 0;
    int safeInsetBottomPx = 0;
    int orientation = 1;  // 1=portrait, 2=landscape
};
std::mutex gViewportMutex;
ViewportMetrics gViewport;

// ---- Layout breakpoints (based on available width in dp) ----
enum class LayoutMode { Compact, Phone, Tablet, Expanded };
static constexpr float kBreakpointCompact = 360.0f;
static constexpr float kBreakpointTablet = 600.0f;
static constexpr float kBreakpointExpanded = 840.0f;

LayoutMode currentLayoutMode() {
    const float density = gViewport.density > 0.0f ? gViewport.density : 1.0f;
    const float widthDp = static_cast<float>(gViewport.viewWidthPx) / density;
    if (widthDp >= kBreakpointExpanded) return LayoutMode::Expanded;
    if (widthDp >= kBreakpointTablet) return LayoutMode::Tablet;
    if (widthDp >= kBreakpointCompact) return LayoutMode::Phone;
    return LayoutMode::Compact;
}

// ---- Unified Theme Palette (mirrors Java AppThemePalette field order) ----
struct ThemePalette {
    static constexpr int FIELD_COUNT = 29;
    bool isDark = true;
    ImU32 windowBackground = IM_COL32(9, 12, 18, 255);
    ImU32 surfacePrimary = IM_COL32(31, 33, 35, 255);
    ImU32 surfaceSecondary = IM_COL32(42, 44, 46, 255);
    ImU32 surfaceElevated = IM_COL32(51, 53, 55, 255);
    ImU32 toolbarBackground = IM_COL32(18, 20, 22, 255);
    ImU32 rowBackground = IM_COL32(38, 40, 42, 255);
    ImU32 rowPressed = IM_COL32(58, 60, 62, 255);
    ImU32 popupBackground = IM_COL32(31, 33, 35, 255);
    ImU32 scrim = IM_COL32(0, 0, 0, 120);
    ImU32 divider = IM_COL32(255, 255, 255, 26);
    ImU32 textPrimary = IM_COL32(241, 243, 244, 255);
    ImU32 textSecondary = IM_COL32(166, 171, 176, 255);
    ImU32 textDisabled = IM_COL32(97, 100, 103, 255);
    ImU32 iconPrimary = IM_COL32(221, 225, 228, 255);
    ImU32 accent = IM_COL32(0, 150, 136, 255);
    ImU32 accentPressed = IM_COL32(0, 121, 107, 255);
    ImU32 accentMuted = IM_COL32(0, 150, 136, 48);
    ImU32 danger = IM_COL32(239, 118, 118, 255);
    ImU32 statusBar = IM_COL32(9, 12, 18, 255);
    ImU32 navigationBar = IM_COL32(9, 12, 18, 255);
    ImU32 fabBackground = IM_COL32(0, 150, 136, 255);
    ImU32 fabForeground = IM_COL32(255, 255, 255, 255);
    ImU32 editorBackground = IM_COL32(30, 30, 30, 255);
    ImU32 editorToolbar = IM_COL32(24, 26, 26, 255);
    ImU32 editorTabActive = IM_COL32(48, 50, 50, 255);
    ImU32 editorTabInactive = IM_COL32(30, 30, 30, 255);
    ImU32 editorSelection = IM_COL32(38, 166, 154, 51);
    ImU32 terminalBackground = IM_COL32(0, 0, 0, 255);
    ImU32 terminalForeground = IM_COL32(255, 255, 255, 255);
};
ThemePalette gTheme;
// Render-thread snapshot. JNI writes gTheme; draw calls only read this copy.
ThemePalette gRenderTheme;
std::mutex gThemeMutex;
std::atomic<bool> gThemeDirty{true};

static ImU32 androidArgbToImGui(jint argb) {
    const auto value = static_cast<uint32_t>(argb);
    return IM_COL32((value >> 16) & 0xFF, (value >> 8) & 0xFF,
                    value & 0xFF, (value >> 24) & 0xFF);
}

static ImU32 packRgba(int red, int green, int blue, int alpha) {
    return (static_cast<ImU32>(red) & 0xFFu)
           | ((static_cast<ImU32>(green) & 0xFFu) << 8u)
           | ((static_cast<ImU32>(blue) & 0xFFu) << 16u)
           | ((static_cast<ImU32>(alpha) & 0xFFu) << 24u);
}

static ImU32 withAlpha(ImU32 color, int alpha) {
    return (color & 0x00FFFFFFu) | ((static_cast<ImU32>(alpha) & 0xFFu) << 24u);
}

/**
 * Maps the legacy dark-workspace draw colors onto semantic palette colors. This keeps
 * categorical artwork (blue folders, plugin covers, warnings) intact while allowing every
 * grayscale surface/text and teal action to follow light/dark mode immediately.
 */
static ImU32 themedLegacyColor(int red, int green, int blue, int alpha) {
    if (gRenderTheme.isDark) return packRgba(red, green, blue, alpha);
    if (alpha == 0) return 0;

    const int high = std::max(red, std::max(green, blue));
    const int low = std::min(red, std::min(green, blue));
    const int average = (red + green + blue) / 3;

    // Keep translucent black shadows/scrims black in light mode.
    if (alpha < 128 && high < 24) return packRgba(red, green, blue, alpha);

    // Existing workspace accent shades are teal/green-cyan.
    if (green >= red + 35 && green >= blue + 8 && blue >= red + 12) {
        return withAlpha(gRenderTheme.accent, alpha);
    }

    // Danger actions use red; preserve their role rather than a fixed shade.
    if (red >= green + 55 && red >= blue + 45) {
        return withAlpha(gRenderTheme.danger, alpha);
    }

    // Neutral colors make up workspace surfaces, dividers, labels and monochrome icons.
    if (high - low <= 16) {
        if (alpha < 96 && average > 180) return withAlpha(gRenderTheme.divider, alpha);
        if (average <= 22) return withAlpha(gRenderTheme.toolbarBackground, alpha);
        if (average <= 42) return withAlpha(gRenderTheme.surfacePrimary, alpha);
        if (average <= 72) return withAlpha(gRenderTheme.surfaceSecondary, alpha);
        if (average <= 125) return withAlpha(gRenderTheme.textDisabled, alpha);
        if (average <= 210) return withAlpha(gRenderTheme.textSecondary, alpha);
        return withAlpha(gRenderTheme.textPrimary, alpha);
    }

    return packRgba(red, green, blue, alpha);
}

// All IM_COL32 calls below this point belong to legacy workspace drawing code.
#undef IM_COL32
#define IM_COL32(R, G, B, A) themedLegacyColor((R), (G), (B), (A))

static ImVec4 colorToVec4(ImU32 c) {
    return ImVec4(
        static_cast<float>((c >> 0) & 0xFF) / 255.0f,
        static_cast<float>((c >> 8) & 0xFF) / 255.0f,
        static_cast<float>((c >> 16) & 0xFF) / 255.0f,
        static_cast<float>((c >> 24) & 0xFF) / 255.0f);
}

void applyThemeToStyle(ImGuiStyle &style) {
    style.Colors[ImGuiCol_WindowBg]         = colorToVec4(gRenderTheme.windowBackground);
    style.Colors[ImGuiCol_ChildBg]          = colorToVec4(gRenderTheme.surfacePrimary);
    style.Colors[ImGuiCol_PopupBg]          = colorToVec4(gRenderTheme.popupBackground);
    style.Colors[ImGuiCol_Border]           = colorToVec4(gRenderTheme.divider);
    style.Colors[ImGuiCol_Text]             = colorToVec4(gRenderTheme.textPrimary);
    style.Colors[ImGuiCol_TextDisabled]     = colorToVec4(gRenderTheme.textDisabled);
    style.Colors[ImGuiCol_ScrollbarBg]      = colorToVec4(gRenderTheme.surfaceSecondary);
    style.Colors[ImGuiCol_ScrollbarGrab]    = colorToVec4(gRenderTheme.textDisabled);
    style.Colors[ImGuiCol_Button]           = colorToVec4(gRenderTheme.accent);
    style.Colors[ImGuiCol_ButtonHovered]    = colorToVec4(gRenderTheme.accentPressed);
    style.Colors[ImGuiCol_ButtonActive]     = colorToVec4(gRenderTheme.accentPressed);
    style.Colors[ImGuiCol_FrameBg]          = colorToVec4(gRenderTheme.surfaceSecondary);
    style.Colors[ImGuiCol_FrameBgHovered]   = colorToVec4(gRenderTheme.surfaceElevated);
    style.Colors[ImGuiCol_FrameBgActive]    = colorToVec4(gRenderTheme.rowPressed);
    style.Colors[ImGuiCol_Header]           = colorToVec4(gRenderTheme.accentMuted);
    style.Colors[ImGuiCol_HeaderHovered]    = colorToVec4(gRenderTheme.accentMuted);
    style.Colors[ImGuiCol_HeaderActive]     = colorToVec4(gRenderTheme.accent);
    style.Colors[ImGuiCol_CheckMark]        = colorToVec4(gRenderTheme.accent);
    style.Colors[ImGuiCol_SliderGrab]       = colorToVec4(gRenderTheme.accent);
}

void logError(const char *message) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s (EGL 0x%x)", message, eglGetError());
}

void queueAction(int action) {
    gPendingAction.store(action);
}

enum AccessibilityNodeId {
    kA11yTitle = 90,
    kA11yNavigation = 100,
    kA11yEditor = 101,
    kA11yLog = 102,
    kA11yDocumentation = 103,
    kA11ySearch = 104,
    kA11yFirstTab = 200,
    kA11yScriptPath = 300,
    kA11yScriptUp = 301,
    kA11yScriptSort = 302,
    kA11yScriptFilter = 303,
    kA11yScriptList = 400,
    kA11yCreate = 500,
};

void addAccessibilityNode(int id, const char *text, ImGuiAccessibilityRole role, int flags,
                          ImGuiAccessibilityActionKind actionKind, int actionValue,
                          int targetIndex, const ImVec2 &minimum, const ImVec2 &maximum) {
    const ImVec2 display = ImGui::GetIO().DisplaySize;
    if (maximum.x <= 0.0f || maximum.y <= 0.0f || minimum.x >= display.x ||
        minimum.y >= display.y) {
        return;
    }
    ImGuiAccessibilityNode node;
    node.id = id;
    node.left = static_cast<int>(std::floor(std::max(0.0f, minimum.x)));
    node.top = static_cast<int>(std::floor(std::max(0.0f, minimum.y)));
    node.right = static_cast<int>(std::ceil(std::min(display.x, maximum.x)));
    node.bottom = static_cast<int>(std::ceil(std::min(display.y, maximum.y)));
    node.role = role;
    node.flags = flags;
    node.text = text ? text : "";
    node.actionKind = actionKind;
    node.actionValue = actionValue;
    node.targetIndex = targetIndex;
    gAccessibilityBridge.addNode(node);
}

void addLastItemAccessibilityNode(int id, const char *text, ImGuiAccessibilityRole role,
                                  int flags, ImGuiAccessibilityActionKind actionKind,
                                  int actionValue = 0, int targetIndex = -1) {
    addAccessibilityNode(id, text, role, flags, actionKind, actionValue, targetIndex,
                         ImGui::GetItemRectMin(), ImGui::GetItemRectMax());
}

float drawerPanelWidthFor(int width) {
    // 保留旧 ImGui 抽屉的兜底尺寸；主工作台现由 Android 原生 DrawerLayout 展示侧栏。
    return std::min(static_cast<float>(width) * 0.78f, 480.0f * gUiScale);
}

void startDrawerOpen() {
    if (gDrawerDragging) {
        return;
    }
    // 打开抽屉时取消尚未结束的列表惯性滚动与分页拖动/动画，防止背景继续移动。
    gScrollVelocity = 0.0f;
    gPageAnimating = false;
    gPageTargetSection = -1;
    gPageAnimationTarget = 0.0f;
    gPageOffset = 0.0f;
    gRowMenuIndex = -1;
    gDrawerActionAfterClose = 0;
    gDrawerAnimStart = gDrawerProgress.load();
    gDrawerAnimElapsed = 0.0f;
    gDrawerTarget = 1.0f;
}

void startDrawerClose() {
    if (gDrawerDragging) {
        return;
    }
    gDrawerAnimStart = gDrawerProgress.load();
    gDrawerAnimElapsed = 0.0f;
    gDrawerTarget = 0.0f;
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

    applyThemeToStyle(style);
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
    // Font scale = density * system fontScale; clamped to avoid extreme sizes.
    float userFontScale = 1.0f;
    {
        std::lock_guard<std::mutex> lock(gViewportMutex);
        userFontScale = gViewport.fontScale > 0.0f ? gViewport.fontScale : 1.0f;
    }
    gFontScale = gUiScale * std::clamp(userFontScale, 0.85f, 1.8f);
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
        // Font size uses gFontScale so text respects system font scaling.
        font = io.Fonts->AddFontFromFileTTF(gFontPath.c_str(), 17.0f * gFontScale,
                                           &fontConfig,
                                           io.Fonts->GetGlyphRangesChineseSimplifiedCommon());
    }
    if (!font) {
        io.Fonts->AddFontDefault();
    }
    applyTheme();
    gUiConfigured = true;
}

// Row height helper: base height scales partially with font scale.
// At fontScale=1.0 this returns basePx * gUiScale; at fontScale=1.5 it grows ~25%.
static float rowHeight(float basePx) {
    const float fontFactor = 1.0f + (gFontScale / std::max(1.0f, gUiScale) - 1.0f) * 0.35f;
    return basePx * gUiScale * std::clamp(fontFactor, 0.9f, 1.6f);
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
    static const char *labels[] = {
            u8"文件管理", u8"示例", u8"资源", u8"插件", u8"任务"
    };
    addAccessibilityNode(kA11yFirstTab + section, labels[section],
                         ImGuiAccessibilityRole::Tab,
                         ImGuiAccessibilityClickable |
                                 (gSection == section ? ImGuiAccessibilitySelected : 0),
                         ImGuiAccessibilityActionKind::SelectSection, section, -1,
                         minimum, maximum);
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
    ImGui::PushStyleColor(ImGuiCol_ChildBg, colorToVec4(gRenderTheme.toolbarBackground));
    ImGui::PushStyleVar(ImGuiStyleVar_ChildRounding, 0.0f);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding,
                        ImVec2(14.0f * gUiScale, 8.0f * gUiScale));
    // Responsive top bar height: Compact uses less vertical space; scale with font
    const LayoutMode layout = currentLayoutMode();
    const float baseTopBarPx = (layout == LayoutMode::Compact) ? 88.0f : 104.0f;
    const float topBarHeight = rowHeight(baseTopBarPx);
    ImGui::BeginChild("autojs_app_bar", ImVec2(0.0f, topBarHeight), false,
                      ImGuiWindowFlags_NoScrollbar | ImGuiWindowFlags_NoScrollWithMouse |
                      ImGuiWindowFlags_AlwaysUseWindowPadding);
    ImGui::PopStyleVar();

    const float toolbarY = ImGui::GetCursorPosY();
    // The navigation button is a native Android ImageView overlay (56dp touch target).
    // This InvisibleButton is a click-only fallback: same 56dp size, no icon drawing.
    // The native ImageView provides the visual; this catches touches it misses.
    {
        ImVec2 btnSize(56.0f * gUiScale, 40.0f * gUiScale);
        const bool navClicked = ImGui::InvisibleButton("##navigation", btnSize);
        if (navClicked && gAllowActivationThisFrame) {
            queueAction(kActionOpenNativeDrawer);
        }
    }
    ImGui::SameLine(0.0f, 18.0f*gUiScale);
    ImGui::SetCursorPosY(toolbarY + 2.0f * gUiScale);
    ImGui::SetWindowFontScale(1.55f);
    // Responsive title: Compact shows short title, others show full title
    const char *titleText = (layout == LayoutMode::Compact) ? "AI.js" : "AI.js Pro";
    ImGui::TextUnformatted(titleText);
    addLastItemAccessibilityNode(kA11yTitle, titleText, ImGuiAccessibilityRole::Text,
                                 ImGuiAccessibilityNone,
                                 ImGuiAccessibilityActionKind::None);
    ImGui::GetWindowDrawList()->AddText(ImGui::GetFont(),ImGui::GetFontSize(),
                                        ImVec2(ImGui::GetItemRectMin().x+0.45f*gUiScale,
                                               ImGui::GetItemRectMin().y),
                                        IM_COL32(245,245,245,245), titleText);
    ImGui::GetWindowDrawList()->AddText(ImGui::GetFont(),ImGui::GetFontSize(),
                                        ImVec2(ImGui::GetItemRectMin().x,
                                               ImGui::GetItemRectMin().y+0.45f*gUiScale),
                                        IM_COL32(245,245,245,210), titleText);
    ImGui::SetWindowFontScale(1.0f);

    // Responsive toolbar buttons: Compact shows fewer buttons
    const float buttonWidth = 40.0f * gUiScale;
    const float gap = ImGui::GetStyle().ItemSpacing.x;
    const int maxButtons = (layout == LayoutMode::Compact) ? 2 : 4;
    const float rightStart = ImGui::GetWindowContentRegionMax().x
            - buttonWidth * static_cast<float>(maxButtons) - gap * static_cast<float>(maxButtons - 1);
    ImGui::SameLine(std::max(ImGui::GetCursorPosX() + gap, rightStart));
    ImGui::SetCursorPosY(toolbarY);
    if (toolbarIconButton("##code", ToolbarIcon::Code)) {
        queueAction(5);
    }
    addLastItemAccessibilityNode(kA11yEditor, u8"打开编辑器",
                                 ImGuiAccessibilityRole::Button,
                                 ImGuiAccessibilityClickable,
                                 ImGuiAccessibilityActionKind::QueueAction, 5);
    if (layout != LayoutMode::Compact) {
        ImGui::SameLine();
        if (toolbarIconButton("##file", ToolbarIcon::File)) {
            queueAction(3);
        }
        addLastItemAccessibilityNode(kA11yLog, u8"打开日志",
                                     ImGuiAccessibilityRole::Button,
                                     ImGuiAccessibilityClickable,
                                     ImGuiAccessibilityActionKind::QueueAction, 3);
        ImGui::SameLine();
        if (toolbarIconButton("##bookmark", ToolbarIcon::Bookmark)) {
            queueAction(21);
        }
        addLastItemAccessibilityNode(kA11yDocumentation, u8"打开文档",
                                     ImGuiAccessibilityRole::Button,
                                     ImGuiAccessibilityClickable,
                                     ImGuiAccessibilityActionKind::QueueAction, 21);
    }
    ImGui::SameLine();
    if (toolbarIconButton("##search", gSection == 2 ? ToolbarIcon::Filter : ToolbarIcon::Search)) {
        if (gSection == 0) queueAction(19);
        else if (gSection == 1) queueAction(27);
        else if (gSection == 2) queueAction(33);
        else if (gSection == 3) queueAction(41);
        else queueAction(55);
    }
    const int searchActions[] = {19, 27, 33, 41, 55};
    const char *searchLabels[] = {
            u8"搜索文件", u8"搜索示例", u8"筛选资源", u8"搜索插件", u8"搜索任务"
    };
    addLastItemAccessibilityNode(kA11ySearch, searchLabels[gSection],
                                 ImGuiAccessibilityRole::Button,
                                 ImGuiAccessibilityClickable,
                                 ImGuiAccessibilityActionKind::QueueAction,
                                 searchActions[gSection]);

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
    const float taskRowH = rowHeight(58.0f);
    const bool clicked = ImGui::InvisibleButton("##task", ImVec2(ImGui::GetContentRegionAvail().x, taskRowH));
    const ImVec2 min = ImGui::GetItemRectMin(), max = ImGui::GetItemRectMax();
    ImDrawList *draw = ImGui::GetWindowDrawList();
    draw->AddRectFilled(min, max, IM_COL32(43,43,43,255));
    const ImVec2 badge(min.x + 28*gUiScale, min.y + taskRowH*.5f);
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
    const ImVec2 stop(max.x-28*gUiScale,min.y+taskRowH*.5f);
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
    const float rowH = rowHeight(62.0f);
    ImGui::PushID(index);
    const bool clicked = ImGui::InvisibleButton("##entry", ImVec2(ImGui::GetContentRegionAvail().x,
                                                                   rowH));
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

    const ImVec2 moreCenter(maximum.x - 22.0f*gUiScale, minimum.y + rowH*0.5f);
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
    if (ImGui::IsRectVisible(minimum, maximum)) {
        const std::string stableKey = entry.path.empty() ? entry.name : entry.path;
        const int selectedFlag = gSelectedScript == index ? ImGuiAccessibilitySelected : 0;
        addAccessibilityNode(stableAccessibilityId(stableKey, 0), entry.name.c_str(),
                             ImGuiAccessibilityRole::ListItem,
                             ImGuiAccessibilityClickable | selectedFlag,
                             ImGuiAccessibilityActionKind::SelectScript, 12, index,
                             minimum, ImVec2(maximum.x - actionWidth, maximum.y));
        if (runnable) {
            addAccessibilityNode(stableAccessibilityId(stableKey, 1),
                                 (entry.name + u8" 运行").c_str(),
                                 ImGuiAccessibilityRole::Button,
                                 ImGuiAccessibilityClickable,
                                 ImGuiAccessibilityActionKind::SelectScript, 13, index,
                                 ImVec2(maximum.x - 82.0f*gUiScale, minimum.y),
                                 ImVec2(maximum.x - 42.0f*gUiScale, maximum.y));
        }
        addAccessibilityNode(stableAccessibilityId(stableKey, 2),
                             (entry.name + u8" 更多操作").c_str(),
                             ImGuiAccessibilityRole::Button,
                             ImGuiAccessibilityClickable,
                             ImGuiAccessibilityActionKind::SelectScript, 38, index,
                             ImVec2(maximum.x - 42.0f*gUiScale, minimum.y), maximum);
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
        ImGui::PushStyleColor(ImGuiCol_Button, colorToVec4(gRenderTheme.danger));
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
    addAccessibilityNode(kA11yScriptPath, label.c_str(), ImGuiAccessibilityRole::Text,
                         ImGuiAccessibilityNone, ImGuiAccessibilityActionKind::None, 0, -1,
                         ImVec2(ImGui::GetWindowPos().x,
                                ImGui::GetWindowPos().y + rowY),
                         ImVec2(ImGui::GetWindowPos().x + ImGui::GetWindowWidth() -
                                        126.0f*gUiScale,
                                ImGui::GetWindowPos().y + rowY + 43.0f*gUiScale));
    ImGui::SetCursorPosX(std::max(ImGui::GetCursorPosX(), ImGui::GetWindowContentRegionMax().x - 126.0f*gUiScale));
    ImGui::SetCursorPosY(rowY);
    if (miniActionButton("##up", 0)) queueAction(11);
    addLastItemAccessibilityNode(kA11yScriptUp, u8"返回上级目录",
                                 ImGuiAccessibilityRole::Button,
                                 ImGuiAccessibilityClickable,
                                 ImGuiAccessibilityActionKind::QueueAction, 11);
    ImGui::SameLine(0.0f, 0.0f);
    if (miniActionButton("##sort", 1)) { queueAction(28); gRowMenuIndex = -1; }
    addLastItemAccessibilityNode(kA11yScriptSort, u8"文件排序",
                                 ImGuiAccessibilityRole::Button,
                                 ImGuiAccessibilityClickable,
                                 ImGuiAccessibilityActionKind::QueueAction, 28);
    ImGui::SameLine(0.0f, 0.0f);
    if (miniActionButton("##filter", 2)) queueAction(20);
    addLastItemAccessibilityNode(kA11yScriptFilter, u8"文件筛选",
                                 ImGuiAccessibilityRole::Button,
                                 ImGuiAccessibilityClickable,
                                 ImGuiAccessibilityActionKind::QueueAction, 20);
    ImGui::SetCursorPosY(rowY + 42.0f*gUiScale);
    ImGui::Separator();
    ImGui::SetCursorPosY(rowY + 43.0f*gUiScale);

    ImGui::PushStyleVar(ImGuiStyleVar_ChildRounding, 0.0f);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(0.0f, 0.0f));
    ImGui::PushStyleColor(ImGuiCol_ChildBg, colorToVec4(gRenderTheme.rowBackground));
    ImGui::BeginChild("script_list", ImVec2(0.0f, 0.0f), false, ImGuiWindowFlags_NoScrollbar);
    ImGui::PopStyleColor();
    ImGui::PopStyleVar(2);
    const ImVec2 listMinimum = ImGui::GetWindowPos();
    const ImVec2 listSize = ImGui::GetWindowSize();
    const ImVec2 listMaximum(listMinimum.x + listSize.x, listMinimum.y + listSize.y);
    addAccessibilityNode(kA11yScriptList, u8"文件列表", ImGuiAccessibilityRole::List,
                         ImGuiAccessibilityScrollable,
                         ImGuiAccessibilityActionKind::ScrollScripts, 0, -1,
                         listMinimum, listMaximum);
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
        addLastItemAccessibilityNode(kA11yScriptList + 1,
                                     u8"当前目录没有脚本或子目录",
                                     ImGuiAccessibilityRole::Text,
                                     ImGuiAccessibilityNone,
                                     ImGuiAccessibilityActionKind::None);
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
    const float sampleRowH = rowHeight(62.0f);
    const bool clicked = ImGui::InvisibleButton("##sample", ImVec2(ImGui::GetContentRegionAvail().x, sampleRowH));
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
    ImGui::PushStyleColor(ImGuiCol_ChildBg, colorToVec4(gRenderTheme.rowBackground));
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
    ImGui::PushStyleColor(ImGuiCol_ChildBg, colorToVec4(gRenderTheme.rowBackground));
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
        const float resH=rowHeight(78.0f);
        const bool clicked=ImGui::InvisibleButton("##resource",ImVec2(ImGui::GetContentRegionAvail().x,resH));
        const ImVec2 min=ImGui::GetItemRectMin(),max=ImGui::GetItemRectMax();
        ImDrawList *draw=ImGui::GetWindowDrawList();
        draw->AddRectFilled(min,max,IM_COL32(62,62,62,255),2*gUiScale);
        const ImVec2 code(min.x+20*gUiScale,min.y+resH*0.5f);
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
    // Responsive column count based on layout breakpoint
    const LayoutMode layout = currentLayoutMode();
    int columns;
    switch (layout) {
        case LayoutMode::Compact:  columns = 1; break;
        case LayoutMode::Phone:    columns = 2; break;
        case LayoutMode::Tablet:   columns = 3; break;
        case LayoutMode::Expanded: columns = 4; break;
    }
    const float availableWidth = ImGui::GetContentRegionAvail().x;
    const float cardWidth = (availableWidth - margin * static_cast<float>(columns + 1))
                            / static_cast<float>(columns);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding,ImVec2(0,10*gUiScale));
    ImGui::PushStyleColor(ImGuiCol_ChildBg, colorToVec4(gRenderTheme.rowBackground));
    ImGui::BeginChild("plugin_grid",ImVec2(0,0),false,ImGuiWindowFlags_NoScrollbar);
    ImGui::PopStyleColor();
    ImGui::PopStyleVar();
    if(applyScroll) applyPendingVerticalScroll();
    ImGui::PushStyleVar(ImGuiStyleVar_ItemSpacing,ImVec2(margin,10*gUiScale));
    for(int i=0;i<static_cast<int>(gPluginEntries.size());++i){
        const PluginEntry &entry=gPluginEntries[static_cast<size_t>(i)];
        // Position in grid: wrap to new row after 'columns' items
        if(i % columns == 0) ImGui::SetCursorPosX(margin);
        else ImGui::SameLine(0.0f, margin);
        ImGui::PushID(i);
        const float pluginCardH = rowHeight(224.0f);
        const bool clicked=ImGui::InvisibleButton("##plugin",ImVec2(cardWidth, pluginCardH));
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
    float safeB=0.0f, safeR=0.0f;
    { std::lock_guard<std::mutex> lk(gViewportMutex); safeB=gViewport.safeInsetBottomPx; safeR=gViewport.safeInsetRightPx; }
    const ImVec2 center(static_cast<float>(width)-14*gUiScale-radius-safeR+offsetX,
                        static_cast<float>(height)-14*gUiScale-radius-safeB);
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
    float safeB=0.0f;
    { std::lock_guard<std::mutex> lk(gViewportMutex); safeB=gViewport.safeInsetBottomPx; }
    const ImVec2 center(static_cast<float>(width)*.5f+offsetX,
                        static_cast<float>(height)-18*gUiScale-42*gUiScale-radius-safeB);
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
    // Respect safe insets (bottom and right)
    float safeBottom = 0.0f, safeRight = 0.0f;
    {
        std::lock_guard<std::mutex> lock(gViewportMutex);
        safeBottom = gViewport.safeInsetBottomPx;
        safeRight = gViewport.safeInsetRightPx;
    }
    const ImVec2 center(static_cast<float>(width) - margin - radius - safeRight + offsetX,
                        static_cast<float>(height) - margin - radius - safeBottom);
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
        addAccessibilityNode(kA11yCreate + 1, u8"新建文件夹",
                             ImGuiAccessibilityRole::Button,
                             ImGuiAccessibilityClickable,
                             ImGuiAccessibilityActionKind::QueueAction, 16, -1,
                             folderMin, folderMax);
        addAccessibilityNode(kA11yCreate + 2, u8"新建脚本",
                             ImGuiAccessibilityRole::Button,
                             ImGuiAccessibilityClickable,
                             ImGuiAccessibilityActionKind::QueueAction, 15, -1,
                             scriptMin, scriptMax);
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
    addAccessibilityNode(kA11yCreate,
                         gCreateMenuOpen ? u8"关闭新建菜单" : u8"打开新建菜单",
                         ImGuiAccessibilityRole::Button,
                         ImGuiAccessibilityClickable |
                                 (gCreateMenuOpen ? ImGuiAccessibilitySelected : 0),
                         ImGuiAccessibilityActionKind::ToggleCreateMenu, 0, -1,
                         ImVec2(center.x - radius, center.y - radius),
                         ImVec2(center.x + radius, center.y + radius));
}

void drawTaskFab(int width,int height,float offsetX){
    const float radius=27*gUiScale;
    float safeB=0.0f, safeR=0.0f;
    { std::lock_guard<std::mutex> lk(gViewportMutex); safeB=gViewport.safeInsetBottomPx; safeR=gViewport.safeInsetRightPx; }
    const ImVec2 center(static_cast<float>(width)-14*gUiScale-radius-safeR+offsetX,
                        static_cast<float>(height)-14*gUiScale-radius-safeB);
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
    ImGui::PushStyleColor(ImGuiCol_ChildBg, colorToVec4(gRenderTheme.rowBackground));
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

// 抽屉布局常量（单位随 gUiScale 缩放；绘制与触摸分发共用）。
// 基于 ddpi 界面实测：item1 顶部约 96*gUiScale，行高 52*gUiScale，行距 10*gUiScale。
constexpr float kDrawerNavSize = 40.0f;      // 顶部按钮
constexpr float kDrawerHeader = 96.0f;       // 顶部(按钮+标题+分隔)高度
constexpr float kDrawerItemHeight = 52.0f;
constexpr float kDrawerItemGap = 10.0f;

float drawerItemTopY(int index, float scrollY) {
    return (kDrawerHeader + index * (kDrawerItemHeight + kDrawerItemGap)) * gUiScale -
           scrollY;
}

void drawWorkspaceDrawerItem(const char *label, const char *status, int index, int action,
                             float panelX, float panelWidth, float scrollY,
                             ImDrawList *drawList) {
    const float s = gUiScale;
    const float itemTop = drawerItemTopY(index, scrollY);
    const float itemBottom = itemTop + kDrawerItemHeight * s;
    const float itemLeft = panelX + 20.0f * s;
    const float itemRight = panelX + panelWidth - 20.0f * s;
    (void) action;
    // 行背景与分隔线。
    drawList->AddRectFilled(ImVec2(itemLeft, itemTop), ImVec2(itemRight, itemBottom),
                            IM_COL32(0, 0, 0, 0), 10.0f * s);
    drawList->AddText(ImGui::GetFont(), ImGui::GetFontSize() * 1.05f,
                      ImVec2(itemLeft + 14.0f * s, itemTop + 14.0f * s),
                      IM_COL32(238, 242, 241, 255), label);
    if (status && status[0] != '\0') {
        const ImVec2 textSize = ImGui::CalcTextSize(status);
        drawList->AddText(ImVec2(itemRight - textSize.x - 14.0f * s, itemTop + 17.0f * s),
                          IM_COL32(78, 209, 182, 225), status);
    }
    drawList->AddLine(ImVec2(itemLeft + 14.0f * s, itemBottom),
                      ImVec2(itemRight - 14.0f * s, itemBottom),
                      IM_COL32(255, 255, 255, 18), 0.6f * s);
}

void drawWorkspaceDrawer(int width, int height) {
    const float progress = gDrawerProgress.load();
    if (progress <= 0.0005f && gDrawerTarget < 0.0f) {
        return;
    }
    const float s = gUiScale;
    const float drawerWidth = drawerPanelWidthFor(width);
    const float drawerX = -drawerWidth * (1.0f - progress);
    // Foreground DrawList is rendered after every Child Window. Drawing on the parent window's
    // list makes the drawer disappear behind script/sample/resource children.
    ImDrawList *drawList = ImGui::GetForegroundDrawList();

    // 遮罩：仅覆盖抽屉右侧区域；透明度随进度变化（完全打开约 48% 黑）。
    drawList->AddRectFilled(ImVec2(drawerX + drawerWidth, 0.0f),
                            ImVec2(static_cast<float>(width), static_cast<float>(height)),
                            IM_COL32(0, 0, 0, static_cast<int>(255.0f * 0.48f * progress)));

    // 面板背景（跟随主题的深色）。
    drawList->AddRectFilled(ImVec2(drawerX, 0.0f),
                            ImVec2(drawerX + drawerWidth, static_cast<float>(height)),
                            IM_COL32(13, 15, 17, 255));

    // 顶部：三横线按钮（点击关闭，命中区域由 Native touch 层分发）+ 标题。
    const float navTop = 0.0f;
    const float navBottom = kDrawerNavSize * s;
    const float navCenterX = drawerX + kDrawerNavSize * s * 0.5f;
    const float navCenterY = navTop + navBottom * 0.5f;
    const float navThickness = 2.2f * s;
    for (int line = -1; line <= 1; ++line) {
        const float y = navCenterY + line * 6.0f * s;
        drawList->AddLine(ImVec2(navCenterX - 11.0f * s, y),
                          ImVec2(navCenterX + 11.0f * s, y),
                          IM_COL32(255, 255, 255, 220), navThickness);
    }
    ImFont *font = ImGui::GetFont();
    drawList->AddText(font, ImGui::GetFontSize() * 1.5f,
                      ImVec2(drawerX + kDrawerNavSize * s + 14.0f * s,
                             navCenterY - ImGui::GetFontSize() * 0.75f),
                      IM_COL32(245, 245, 245, 245), u8"AI.js Pro");
    drawList->AddLine(ImVec2(drawerX + 20.0f * s, kDrawerHeader * s - 12.0f * s),
                      ImVec2(drawerX + drawerWidth - 20.0f * s, kDrawerHeader * s - 12.0f * s),
                      IM_COL32(255, 255, 255, 26), 0.6f * s);

    // 菜单项（纯绘制；点击关闭+执行由 Native touch 层分发）。
    const bool accessibility = gDrawerAccessibilityEnabled.load();
    const bool floating = gDrawerFloatingShown.load();
    const bool devConnected = gDrawerDevConnected.load();
    struct DrawerMenuItem {
        const char *label;
        const char *status;
        int action;
    };
    const DrawerMenuItem items[] = {
            {u8"无障碍服务", accessibility ? u8"已开启" : u8"未开启", kDrawerActionAccessibility},
            {floating ? u8"关闭悬浮窗" : u8"开启悬浮窗", floating ? u8"已开启" : u8"已关闭",
             kDrawerActionToggleFloating},
            {u8"更多服务", nullptr, kDrawerActionMoreServices},
            {devConnected ? u8"断开开发者调试" : u8"开发者调试", devConnected ? u8"已连接" : u8"未连接",
             kDrawerActionToggleDeveloper},
            {u8"终端", u8"当前脚本目录", kDrawerActionTerminal},
            {u8"主题", nullptr, kDrawerActionTheme},
            {u8"官方博客", nullptr, kDrawerActionBlog},
            {u8"官方频道 / 论坛", nullptr, kDrawerActionChannelForum},
            {u8"设置", nullptr, kDrawerActionSettings},
            {u8"检查更新", nullptr, kDrawerActionCheckUpdate},
            {u8"退出", nullptr, kDrawerActionExit},
    };
    const int itemCount = static_cast<int>(sizeof(items) / sizeof(items[0]));
    static_assert(itemCount == kDrawerMenuCount, "drawer labels/actions out of sync");
    const float contentBottom = (kDrawerHeader + itemCount * kDrawerItemHeight +
                                 (itemCount - 1) * kDrawerItemGap + 12.0f) * s;
    const float maxScrollY = std::max(0.0f, contentBottom - static_cast<float>(height));
    gDrawerMaxScrollY.store(maxScrollY, std::memory_order_relaxed);
    const float scrollY = std::clamp(gDrawerScrollY.load(std::memory_order_relaxed),
                                     0.0f, maxScrollY);
    gDrawerScrollY.store(scrollY, std::memory_order_relaxed);
    drawList->PushClipRect(ImVec2(std::max(0.0f, drawerX), kDrawerHeader * s - 10.0f * s),
                           ImVec2(drawerX + drawerWidth, static_cast<float>(height)), true);
    for (int i = 0; i < itemCount; ++i) {
        drawWorkspaceDrawerItem(items[i].label, items[i].status, i, items[i].action,
                                drawerX, drawerWidth, scrollY, drawList);
    }
    drawList->PopClipRect();
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
    // 抽屉使用 Foreground DrawList 覆盖所有 Child Window；输入由 touch() 独占拦截。
    drawWorkspaceDrawer(width, height);
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
    if (gThemeDirty.exchange(false, std::memory_order_acq_rel)) {
        {
            std::lock_guard<std::mutex> lock(gThemeMutex);
            gRenderTheme = gTheme;
        }
        applyThemeToStyle(ImGui::GetStyle());
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
    bool drawerDragging = false;
    float drawerDragProgress = 0.0f;
    bool drawerReleased = false;
    float drawerReleaseProgress = 0.0f;
    float drawerReleaseVelocity = 0.0f;
    bool drawerCancelled = false;
    int pendingDrawerMenuAction = 0;
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

        drawerDragging = gDrawerTouchDrag;
        drawerDragProgress = gDrawerTouchDragProgress;
        drawerReleased = gDrawerTouchReleased;
        drawerReleaseProgress = gDrawerTouchReleaseProgress;
        drawerReleaseVelocity = gDrawerTouchReleaseVelocity;
        drawerCancelled = gDrawerTouchCancelled;
        gDrawerTouchReleased = false;
        gDrawerTouchCancelled = false;
        pendingDrawerMenuAction = gDrawerMenuAction;
        gDrawerMenuAction = 0;
        // 供 touch 线程判断：抽屉存在时横向手势只控制抽屉，背景滚动/分页被禁用。
        gDrawerPanelWidth = drawerPanelWidthFor(width);
        gDrawerActiveForInput = gDrawerProgress.load() > 0.005f ||
                                gDrawerTarget > 0.5f || drawerDragging;

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

    // ---- 抽屉动画：按帧时间更新（非阻塞）；拖动时跟手，松手按距离/速度决定开合 ----
    if (pendingDrawerMenuAction != 0) {
        // 抽屉内点击分发结果：先关闭抽屉，再（可选）执行菜单 action。
        if (pendingDrawerMenuAction < 0) {
            startDrawerClose();
        } else {
            gDrawerActionAfterClose = pendingDrawerMenuAction;
            startDrawerClose();
        }
    }
    if (drawerDragging) {
        gDrawerDragging = true;
        gDrawerTarget = -1.0f;
        gDrawerProgress.store(std::clamp(drawerDragProgress, 0.0f, 1.0f));
        gScrollVelocity = 0.0f;
    } else if (drawerReleased) {
        gDrawerDragging = false;
        gDrawerAnimStart = std::clamp(drawerReleaseProgress, 0.0f, 1.0f);
        gDrawerAnimElapsed = 0.0f;
        if (drawerCancelled) {
            // ACTION_CANCEL 安全收尾：恢复到完全打开或完全关闭，不停在半开状态。
            gDrawerTarget = gDrawerAnimStart >= 0.5f ? 1.0f : 0.0f;
        } else {
            const bool fastLeftFling = drawerReleaseVelocity < -1100.0f;
            const bool slowLeftFling = drawerReleaseVelocity < -450.0f;
            const bool close = gDrawerAnimStart < 0.45f || fastLeftFling ||
                               (slowLeftFling && gDrawerAnimStart < 0.80f);
            gDrawerTarget = close ? 0.0f : 1.0f;
        }
        if (gDrawerAnimStart <= 0.001f && gDrawerTarget == 0.0f) {
            gDrawerProgress.store(0.0f);
            gDrawerTarget = -1.0f;
            gDrawerOpen = false;
            if (gDrawerActionAfterClose != 0) {
                queueAction(gDrawerActionAfterClose);
                gDrawerActionAfterClose = 0;
            }
        }
    } else if (gDrawerTarget >= 0.0f && !gDrawerDragging) {
        gDrawerAnimElapsed += io.DeltaTime;
        const float t = std::clamp(gDrawerAnimElapsed / kDrawerAnimDuration, 0.0f, 1.0f);
        const float eased = 1.0f - std::pow(1.0f - t, 3.0f);
        gDrawerProgress.store(gDrawerAnimStart +
                              (gDrawerTarget - gDrawerAnimStart) * eased);
        if (t >= 1.0f) {
            gDrawerProgress.store(gDrawerTarget);
            gDrawerOpen = gDrawerTarget > 0.5f;
            const bool closed = gDrawerTarget < 0.5f;
            gDrawerTarget = -1.0f;
            if (closed && gDrawerActionAfterClose != 0) {
                queueAction(gDrawerActionAfterClose);
                gDrawerActionAfterClose = 0;
            }
        }
    } else {
        gDrawerDragging = false;
        if (gDrawerTarget < 0.0f) {
            gDrawerOpen = gDrawerProgress.load() > 0.999f;
        }
    }
    // 抽屉存在期间禁止背景惯性滚动与分页动画继续改变滚动位置。
    if (gDrawerProgress.load() > 0.005f || gDrawerTarget > 0.5f || drawerDragging) {
        gScrollVelocity = 0.0f;
        if (!pageDragging) {
            gPageAnimating = false;
            gPageTargetSection = -1;
        }
    }

    ImGui_ImplOpenGL3_NewFrame();
    ImGui::NewFrame();
    gAccessibilityBridge.beginFrame();
    drawWorkspace(width, height);
    gAccessibilityBridge.finishFrame();
    ImGui::Render();

    glViewport(0, 0, width, height);
    const ImVec4 clearColor = colorToVec4(gRenderTheme.windowBackground);
    glClearColor(clearColor.x, clearColor.y, clearColor.z, clearColor.w);
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
        // 抽屉打开或动画/拖动中：横向手势只控制抽屉，背景滚动/分页被禁用。
        if (gDrawerActiveForInput) {
            gDrawerTouchActive = true;
            gDrawerTouchDrag = false;
            gDrawerTouchScroll = false;
            gDrawerTouchReleaseProgress = 0.0f;
            gDrawerTouchReleaseVelocity = 0.0f;
            gDrawerDragStartX = x;
            gDrawerStartProgress = gDrawerProgress.load(std::memory_order_relaxed);
            gDrawerDragWidth = std::max(1.0f, gDrawerPanelWidth);
            gDrawerVelocityX = 0.0f;
            gDrawerTouchDragProgress = gDrawerStartProgress;
            gDrawerTouchStartScrollY = gDrawerScrollY.load(std::memory_order_relaxed);
            // 遮罩区按下仅用于点击关闭；
            // 面板内按下先保持点击，移动超过阈值后由 MOVE 开始跟手拖动。
        }
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
        // 抽屉手势优先级最高：拖动距离实时映射到进度，不进入 gTouchPaging / gTouchScrolling。
        if (gDrawerTouchActive) {
            gDrawerVelocityX = gTouchVelocityX;
            const float visiblePanelRight = gDrawerPanelWidth *
                    gDrawerProgress.load(std::memory_order_relaxed);
            const bool startedInsidePanel = gTouchStartX < visiblePanelRight;
            if (!gDrawerTouchDrag && !gDrawerTouchScroll && gTouchMoved &&
                startedInsidePanel && absX > touchSlop && absX >= absY * 1.15f) {
                // 面板内按下后移动超过阈值才开始跟手拖动；未移动保持为点击（菜单项）。
                gDrawerTouchDrag = true;
                gSuppressInputFrames = 4;
                gAuthorizedTapFrames = 0;
                gTapPhase = 0;
            } else if (!gDrawerTouchDrag && !gDrawerTouchScroll && gTouchMoved &&
                       startedInsidePanel && absY > touchSlop && absY > absX * 1.15f &&
                       gDrawerMaxScrollY.load(std::memory_order_relaxed) > 0.0f) {
                // 小屏或横屏菜单允许纵向滚动，且不会误触发工作台列表滚动。
                gDrawerTouchScroll = true;
            }
            if (gDrawerTouchDrag) {
                const float delta = (x - gDrawerDragStartX) / gDrawerDragWidth;
                gDrawerTouchDragProgress = std::clamp(
                        gDrawerStartProgress + delta, 0.0f, 1.0f);
            } else if (gDrawerTouchScroll) {
                const float maxScroll = gDrawerMaxScrollY.load(std::memory_order_relaxed);
                gDrawerScrollY.store(std::clamp(gDrawerTouchStartScrollY - totalY,
                                                0.0f, maxScroll),
                                     std::memory_order_relaxed);
            }
            gTouchLastX = x;
            gTouchLastY = y;
            gTouchLastEvent = now;
            return;
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
        if (gDrawerTouchActive) {
            // 抽屉手势收尾：按距离和速度决定回弹目标；ACTION_CANCEL 安全收尾。
            if (gDrawerTouchDrag) {
                gDrawerTouchDrag = false;
                gDrawerTouchReleased = true;
                gDrawerTouchReleaseProgress = gDrawerTouchDragProgress;
                gDrawerTouchReleaseVelocity = action == 1 ? gDrawerVelocityX : 0.0f;
                gDrawerTouchCancelled = action != 1;
            } else if (gDrawerTouchScroll) {
                // 菜单纵向滚动已经实时提交，不产生点击，也不传给背景列表。
                gDrawerTouchScroll = false;
            } else if (action == 1 && !gTouchMoved && !gTouchScrolling && !gTouchPaging) {
                // 抽屉内点击：Native 层直接命中分发，
                // 遮罩点击关闭 / 面板顶部按钮关闭 / 菜单项关闭并执行对应 action。
                const float progress = gDrawerProgress.load(std::memory_order_relaxed);
                const float panelRight = gDrawerPanelWidth * progress;
                const float panelX = panelRight - gDrawerPanelWidth;
                const float navRight = panelX + kDrawerNavSize *
                        gTouchScale.load(std::memory_order_relaxed);
                if (x >= panelRight) {
                    gDrawerMenuAction = -1;  // 遮罩：仅关闭
                } else if (y >= 0.0f &&
                           y <= kDrawerNavSize * gTouchScale.load(std::memory_order_relaxed) &&
                           x <= navRight) {
                    gDrawerMenuAction = -1;  // 顶部三横线：仅关闭
                } else {
                    int hitAction = 0;
                    const float scrollY = gDrawerScrollY.load(std::memory_order_relaxed);
                    const float scale = gTouchScale.load(std::memory_order_relaxed);
                    const float listTop = (kDrawerHeader - 10.0f) * scale;
                    for (int i = 0; i < kDrawerMenuCount; ++i) {
                        const float itemTop = drawerItemTopY(i, scrollY);
                        const float itemBottom = itemTop + kDrawerItemHeight * scale;
                        if (y >= listTop && y >= itemTop && y <= itemBottom) {
                            hitAction = kDrawerMenuActions[i];
                            break;
                        }
                    }
                    gDrawerMenuAction = hitAction;  // 0=空白区无操作
                }
            }
            gTouchActive = false;
            gTouchScrolling = false;
            gTouchPaging = false;
            gTouchMoved = false;
            gInputPageDragging = false;
            gDrawerTouchActive = false;
            gDrawerTouchScroll = false;
            return;
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

extern "C" JNIEXPORT void JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_setThemePalette(
        JNIEnv *env, jclass, jintArray jColors) {
    if (jColors == nullptr) return;
    jint len = env->GetArrayLength(jColors);
    if (len < 1 + ThemePalette::FIELD_COUNT) return;
    jint *colors = env->GetIntArrayElements(jColors, nullptr);
    if (colors == nullptr) return;

    ThemePalette t;
    t.isDark = (colors[0] != 0);
    t.windowBackground    = androidArgbToImGui(colors[1]);
    t.surfacePrimary      = androidArgbToImGui(colors[2]);
    t.surfaceSecondary    = androidArgbToImGui(colors[3]);
    t.surfaceElevated     = androidArgbToImGui(colors[4]);
    t.toolbarBackground   = androidArgbToImGui(colors[5]);
    t.rowBackground       = androidArgbToImGui(colors[6]);
    t.rowPressed          = androidArgbToImGui(colors[7]);
    t.popupBackground     = androidArgbToImGui(colors[8]);
    t.scrim               = androidArgbToImGui(colors[9]);
    t.divider             = androidArgbToImGui(colors[10]);
    t.textPrimary         = androidArgbToImGui(colors[11]);
    t.textSecondary       = androidArgbToImGui(colors[12]);
    t.textDisabled        = androidArgbToImGui(colors[13]);
    t.iconPrimary         = androidArgbToImGui(colors[14]);
    t.accent              = androidArgbToImGui(colors[15]);
    t.accentPressed       = androidArgbToImGui(colors[16]);
    t.accentMuted         = androidArgbToImGui(colors[17]);
    t.danger              = androidArgbToImGui(colors[18]);
    t.statusBar           = androidArgbToImGui(colors[19]);
    t.navigationBar       = androidArgbToImGui(colors[20]);
    t.fabBackground       = androidArgbToImGui(colors[21]);
    t.fabForeground       = androidArgbToImGui(colors[22]);
    t.editorBackground    = androidArgbToImGui(colors[23]);
    t.editorToolbar       = androidArgbToImGui(colors[24]);
    t.editorTabActive     = androidArgbToImGui(colors[25]);
    t.editorTabInactive   = androidArgbToImGui(colors[26]);
    t.editorSelection     = androidArgbToImGui(colors[27]);
    t.terminalBackground  = androidArgbToImGui(colors[28]);
    t.terminalForeground  = androidArgbToImGui(colors[29]);

    env->ReleaseIntArrayElements(jColors, colors, JNI_ABORT);

    {
        std::lock_guard<std::mutex> lock(gThemeMutex);
        gTheme = t;
    }
    gThemeDirty.store(true, std::memory_order_release);
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "Theme palette applied: %s",
                        t.isDark ? "dark" : "light");
}

extern "C" JNIEXPORT jint JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_pollAction(JNIEnv *, jclass) {
    return gPendingAction.exchange(0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_closeWorkspaceDrawer(JNIEnv *, jclass) {
    std::lock_guard<std::recursive_mutex> lock(gRenderMutex);
    const float progress = gDrawerProgress.load();
    if (progress <= 0.005f && gDrawerTarget < 0.5f) {
        // 完全关闭：交由 Java 执行目录返回/退出确认。
        return JNI_FALSE;
    }
    // 抽屉打开或正在动画/拖动：设置目标关闭并返回 true，拦截返回键的目录返回。
    gDrawerTarget = 0.0f;
    gDrawerAnimStart = progress;
    gDrawerAnimElapsed = 0.0f;
    gDrawerDragging = false;
    {
        std::lock_guard<std::mutex> inputLock(gInputMutex);
        gDrawerTouchActive = false;
        gDrawerTouchDrag = false;
        gDrawerTouchScroll = false;
        gDrawerTouchReleased = false;
        gDrawerTouchCancelled = false;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_setDrawerState(
        JNIEnv *, jclass, jboolean accessibilityEnabled, jboolean floatingShown,
        jboolean devConnected) {
    gDrawerAccessibilityEnabled.store(accessibilityEnabled == JNI_TRUE);
    gDrawerFloatingShown.store(floatingShown == JNI_TRUE);
    gDrawerDevConnected.store(devConnected == JNI_TRUE);
}

extern "C" JNIEXPORT jint JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_getAccessibilityRevision(
        JNIEnv *, jclass) {
    std::lock_guard<std::recursive_mutex> lock(gRenderMutex);
    return gAccessibilityBridge.revision();
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_getAccessibilitySnapshot(
        JNIEnv *env, jclass) {
    std::lock_guard<std::recursive_mutex> lock(gRenderMutex);
    const auto &nodes = gAccessibilityBridge.nodes();
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(nodes.size()),
                                              stringClass, nullptr);
    for (size_t index = 0; index < nodes.size(); ++index) {
        const ImGuiAccessibilityNode &node = nodes[index];
        std::string label = node.text;
        for (char &character : label) {
            if (character == '\x1f' || character == '\n' || character == '\r') character = ' ';
        }
        std::string value = std::to_string(node.id) + '\x1f' +
                            std::to_string(node.left) + '\x1f' +
                            std::to_string(node.top) + '\x1f' +
                            std::to_string(node.right) + '\x1f' +
                            std::to_string(node.bottom) + '\x1f' +
                            std::to_string(static_cast<int>(node.role)) + '\x1f' +
                            std::to_string(node.flags) + '\x1f' + label;
        jstring javaValue = env->NewStringUTF(value.c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(index), javaValue);
        env->DeleteLocalRef(javaValue);
    }
    env->DeleteLocalRef(stringClass);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_performAccessibilityAction(
        JNIEnv *, jclass, jint virtualViewId, jint androidAction) {
    constexpr int kAndroidActionClick = 16;
    constexpr int kAndroidActionScrollForward = 4096;
    constexpr int kAndroidActionScrollBackward = 8192;
    std::lock_guard<std::recursive_mutex> lock(gRenderMutex);
    const ImGuiAccessibilityNode *node = gAccessibilityBridge.findNode(virtualViewId);
    if (!node) return JNI_FALSE;

    if ((androidAction == kAndroidActionScrollForward ||
         androidAction == kAndroidActionScrollBackward) &&
        node->actionKind == ImGuiAccessibilityActionKind::ScrollScripts) {
        const float direction = androidAction == kAndroidActionScrollForward ? 1.0f : -1.0f;
        const float distance = std::max(120.0f,
                                        static_cast<float>(node->bottom - node->top) * 0.72f);
        std::lock_guard<std::mutex> inputLock(gInputMutex);
        gPendingScrollPixels += direction * distance;
        gCancelFlingPending = true;
        return JNI_TRUE;
    }
    if (androidAction != kAndroidActionClick ||
        (node->flags & ImGuiAccessibilityClickable) == 0) {
        return JNI_FALSE;
    }

    switch (node->actionKind) {
        case ImGuiAccessibilityActionKind::QueueAction:
            if (node->actionValue == 15 || node->actionValue == 16) gCreateMenuOpen = false;
            queueAction(node->actionValue);
            return JNI_TRUE;
        case ImGuiAccessibilityActionKind::SelectScript:
            if (node->targetIndex < 0 ||
                node->targetIndex >= static_cast<int>(gScriptEntries.size())) {
                return JNI_FALSE;
            }
            gSelectedScript = node->targetIndex;
            gRowMenuIndex = -1;
            queueAction(node->actionValue);
            return JNI_TRUE;
        case ImGuiAccessibilityActionKind::SelectBreadcrumb:
            gSelectedBreadcrumbDepth.store(node->targetIndex);
            queueAction(node->actionValue);
            return JNI_TRUE;
        case ImGuiAccessibilityActionKind::SelectSection:
            if (node->actionValue < 0 || node->actionValue > 4) return JNI_FALSE;
            gSection = node->actionValue;
            gPageOffset = 0.0f;
            gPageAnimating = false;
            gPageTargetSection = -1;
            gScrollVelocity = 0.0f;
            gCreateMenuOpen = false;
            return JNI_TRUE;
        case ImGuiAccessibilityActionKind::ToggleCreateMenu:
            gCreateMenuOpen = !gCreateMenuOpen;
            return JNI_TRUE;
        default:
            return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_autojs_autojs_ui_imgui_ImGuiNativeBridge_setViewportMetrics(
        JNIEnv *, jclass, jint viewWidthPx, jint viewHeightPx,
        jfloat density, jfloat scaledDensity, jfloat fontScale,
        jint safeInsetLeftPx, jint safeInsetTopPx,
        jint safeInsetRightPx, jint safeInsetBottomPx,
        jint orientation) {
    std::lock_guard<std::mutex> lock(gViewportMutex);
    gViewport.viewWidthPx = viewWidthPx;
    gViewport.viewHeightPx = viewHeightPx;
    gViewport.density = density;
    gViewport.scaledDensity = scaledDensity;
    gViewport.fontScale = fontScale;
    gViewport.safeInsetLeftPx = safeInsetLeftPx;
    gViewport.safeInsetTopPx = safeInsetTopPx;
    gViewport.safeInsetRightPx = safeInsetRightPx;
    gViewport.safeInsetBottomPx = safeInsetBottomPx;
    gViewport.orientation = orientation;
    // Update density-derived scale used by UI elements.
    // Update density-derived scale used by UI elements.
    gUiScale = density;
    gTouchScale.store(density, std::memory_order_relaxed);
    // Reconfigure UI (font, theme) on next render frame.
    gUiConfigured = false;
}
