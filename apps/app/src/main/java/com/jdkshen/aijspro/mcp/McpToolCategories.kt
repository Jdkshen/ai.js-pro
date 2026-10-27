package com.jdkshen.aijspro.mcp

/** Visible grouping for the flat MCP tool catalog; does not change tool names or semantics. */
internal data class McpToolCategory(
    val id: String,
    val title: String,
    val summary: String,
    val order: Int,
)

internal object McpToolCategories {
    val all = listOf(
        McpToolCategory("status", "状态与诊断", "MCP/设备/日志/运行状态", 1),
        McpToolCategory("script", "脚本与执行", "脚本读写、运行、任务与语法检查", 2),
        McpToolCategory("workspace", "工作区", "脚本工作区编辑、Diff 与落盘", 3),
        McpToolCategory("engine", "引擎与 API", "三引擎 API 枚举、探测与调用", 4),
        McpToolCategory("device", "设备与无障碍", "UI 读取/点击/输入、应用与安装入口", 5),
        McpToolCategory("terminal", "终端与构建", "MCP 终端任务与 PATH/D8/Smali 检测", 6),
        McpToolCategory("overlay", "悬浮窗", "Node/脚本悬浮窗查询与关闭", 7),
        McpToolCategory("asset", "示例与帮助", "API 文档检索、内置示例与使用说明", 8),
    )

    private val byId = all.associateBy { it.id }

    private val toolToCategory: Map<String, String> = buildMap {
        listOf(
            "get_status", "read_apk_logs", "get_mcp_log",
        ).forEach { put(it, "status") }
        listOf(
            "list_scripts", "read_script", "search_scripts", "continue_result",
            "run_script", "check_syntax", "list_executions", "get_execution", "wait_execution",
            "stop_script", "stop_all_scripts",
        ).forEach { put(it, "script") }
        listOf(
            "workspace_open", "workspace_list", "workspace_read", "workspace_write",
            "workspace_delete", "workspace_diff", "workspace_request_apply", "workspace_mkdir",
            "workspace_cancel", "workspace_cleanup",
        ).forEach { put(it, "workspace") }
        listOf(
            "list_engines", "list_engine_api", "probe_engine_api", "engine_api_diff", "call_engine_api",
        ).forEach { put(it, "engine") }
        listOf(
            "get_ui_layout", "capture_screen",
            "ui_find", "ui_click", "ui_long_click", "ui_node_action", "ui_input", "ui_swipe", "ui_global", "ui_wait", "ui_scroll",
            "app_list", "app_info", "app_launch", "app_open_settings", "app_open_url",
            "apk_install", "apk_uninstall",
        ).forEach { put(it, "device") }
        listOf(
            "terminal_exec", "terminal_read", "terminal_write", "terminal_stop", "terminal_list",
            "get_build_tools",
        ).forEach { put(it, "terminal") }
        listOf("list_overlays", "close_overlays").forEach { put(it, "overlay") }
        listOf("list_samples", "read_sample", "learn_autojs_skill", "search_api_docs", "read_api_doc").forEach { put(it, "asset") }
    }

    fun categoryFor(toolName: String): McpToolCategory =
        byId[toolToCategory[toolName]] ?: McpToolCategory("other", "其它", "未分类", 99)

    fun toolNamesIn(categoryId: String): List<String> =
        toolToCategory.entries.filter { it.value == categoryId }.map { it.key }

    fun counts(): Map<String, Int> = buildMap {
        all.forEach { category -> put(category.id, toolNamesIn(category.id).size) }
    }
}
