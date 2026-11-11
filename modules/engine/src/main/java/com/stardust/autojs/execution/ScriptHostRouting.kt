package com.stardust.autojs.execution

/**
 * task-78：**「这个脚本要不要用 Activity 宿主来跑」的纯判据**（唯一来源，打包运行时与单测共用）。
 *
 * <p>背景（device4 打包版真机探针）：打包版里 `activity` 全局**未定义**（`ReferenceError`），
 * 而 UI 脚本 `activity.getEventEmitter()/startActivityForResult()/runOnUiThread()` 是最常见写法。
 * 根因：`activity` 由 `ScriptExecuteActivity.prepare()` 注入（`put("activity", this)`），
 * 而 inrt 的启动路由只把「正文里有 `ui.layout/ui.layoutFile/ui.inflate`」的 UI 脚本交给它，
 * 于是「`"ui";` + `activity.*`」这类脚本被判成 headless（无宿主 Activity）⇒ 没有 `activity`。
 *
 * <p>这里把判据抽成纯函数，让 inrt（`AssetsProjectLauncher`）与单测共用；ImGui/浮窗项目仍然 headless
 * （它们同样写 `"ui";`，但必须留在 `:script` 脚本线程里跑，否则 native 上下文跨线程）。
 */
object ScriptHostRouting {

    /** UI 宿主判据：UI 模式 + （有 ui.layout 家族调用 或 用了 activity.*），且不是 ImGui/浮窗项目。 */
    @JvmStatic
    fun needsActivityHost(isUiMode: Boolean,
                          hasUiLayoutCall: Boolean,
                          usesActivityApi: Boolean,
                          hasImGuiMarker: Boolean): Boolean {
        if (!isUiMode) {
            return false
        }
        if (hasUiLayoutCall) {
            return true
        }
        if (!usesActivityApi) {
            return false
        }
        // `"ui";` + activity.* ：需要 Activity 才能定义 activity 全局；但 ImGui/浮窗项目必须留 headless。
        return !hasImGuiMarker
    }

    /** 是否能从脚本文本里看出「用了 activity.*」（`activity.` 属性访问；用于 inrt 的文本判据）。 */
    @JvmStatic
    fun textUsesActivityApi(text: String?): Boolean {
        if (text == null) {
            return false
        }
        return ACTIVITY_API_PATTERN.containsMatchIn(text)
    }

    /** `ui.layout` / `ui.layoutFile` / `ui.inflate`（与 inrt 既有 UI_LAYOUT_PATTERN 同义）。 */
    @JvmStatic
    fun textUsesUiLayout(text: String?): Boolean {
        if (text == null) {
            return false
        }
        return UI_LAYOUT_PATTERN.containsMatchIn(text)
    }


    /**
     * 「是否需要「显示在其他应用上层」权限」的纯判据（task-150）。
     *
     * <p>**只有确定会用 overlay 窗口时才为真**：UI 模式 且（用了 `floaty.*` 或 ImGui/浮窗项目；
     * 或 Node 引擎的 UI 项目 —— 真机证据：Node UI 产物 inflate 时 `BadTokenException … window type 2038`）。
     * 纯 `ui.layout` 走的是 ScriptExecuteActivity 普通窗口，**不需要**该权限 ⇒ 不为真（避免对所有 UI 工程误弹）。
     */
    @JvmStatic
    fun needsOverlayPermission(isUiMode: Boolean,
                              hasOverlayApi: Boolean,
                              hasImGuiMarker: Boolean,
                              isNodeUiProject: Boolean): Boolean {
        if (!isUiMode) {
            return false
        }
        return hasOverlayApi || hasImGuiMarker || isNodeUiProject
    }

    /** 正文是否用了浮窗/overlay 类 API（`floaty.*` 或 `$floaty`）。 */
    @JvmStatic
    fun textUsesOverlayApi(text: String?): Boolean {
        if (text == null) {
            return false
        }
        return text.contains("floaty.") || text.contains("\$floaty") || text.contains("rawWindow(")
    }

    /** 正文是否含 ImGui 标记（与 inrt 既有口径同义：`libImGuiBridge` / `ImGuiBridge` / `imgui`）。 */
    @JvmStatic
    fun textUsesImGui(text: String?): Boolean {
        if (text == null) {
            return false
        }
        val lower = text.lowercase()
        return lower.contains("imgui") || text.contains("libImGuiBridge") || text.contains("ImGuiBridge")
    }
    private val ACTIVITY_API_PATTERN = Regex("""\bactivity\s*\.""")
    private val UI_LAYOUT_PATTERN = Regex("""\bui\s*\.\s*(layout|layoutFile|inflate)\s*\(""")
}