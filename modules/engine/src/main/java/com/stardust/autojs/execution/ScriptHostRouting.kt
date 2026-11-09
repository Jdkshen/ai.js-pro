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

    private val ACTIVITY_API_PATTERN = Regex("""\bactivity\s*\.""")
    private val UI_LAYOUT_PATTERN = Regex("""\bui\s*\.\s*(layout|layoutFile|inflate)\s*\(""")
}