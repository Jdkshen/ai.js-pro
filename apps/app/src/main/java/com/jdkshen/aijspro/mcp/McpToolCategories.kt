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
        McpToolCategory("toolkit", "文件与APK工具", "本地文件、ZIP、APK会话、DEX、资源与ELF", 9),
        McpToolCategory("reverse", "逆向分析", "工具指南、完整解包回编译、SO、YARA、Unicorn与Frida", 10),
    )

    private val byId = all.associateBy { it.id }

    private val toolToCategory: Map<String, String> = buildMap {
        McpToolkitCatalog.names.forEach { put(it, "toolkit") }
        ToolkitReverseTools.names.forEach { put(it,"reverse") }
        listOf(
            "get_status", "get_baseline", "read_apk_logs", "get_mcp_log", "tool_schema",
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
            "get_ui_layout", "capture_screen", "dev_screen_diff", "coordinate_convert",
            "ui_find", "ui_click", "ui_long_click", "ui_node_action", "ui_input", "ui_swipe", "ui_global", "ui_wait", "ui_scroll",
            "app_list", "app_info", "app_launch", "app_open_settings", "app_open_url",
            "apk_install", "apk_uninstall",
            "app_exit_info", "app_trace_start", "app_trace_read", "app_trace_stop", "app_trace_list", "app_launch_trace",
            "floating_menu",
        ).forEach { put(it, "device") }
        listOf(
            "terminal_exec", "terminal_read", "terminal_write", "terminal_stop", "terminal_list",
            "shell_rish", "get_build_tools",
            "shell_capabilities", "shell_classify_failure", "node_script_command", "crash_buffer_semantics",
        ).forEach { put(it, "terminal") }
        listOf("list_overlays", "close_overlays").forEach { put(it, "overlay") }
        listOf("list_samples", "read_sample", "learn_autojs_skill", "search_api_docs", "read_api_doc").forEach { put(it, "asset") }
    }

    /**
     * `tier=core` 的精简工具集：覆盖"起手就要用"的高频工具（读状态/跑脚本/读写文件/终端/APK 会话与作业/截图/项目目录）。
     * 这是**主观白名单**（不是自动推导），目的是让客户端在 token 紧张时只拉一小撮工具；完整集仍可 `groups` 或不过滤。
     */
    val coreToolNames: Set<String> = setOf(
        "get_status", "learn_autojs_skill", "reverse_guide",
        "list_scripts", "read_script", "search_scripts", "run_script", "check_syntax", "stop_script",
        "file_list", "file_read_text", "file_write_bytes", "file_edit_text", "file_move", "file_stat",
        "project_folder", "capture_screen",
        "terminal_exec", "terminal_read", "get_build_tools",
        "apk_workspace_open", "apk_session_create", "apk_search",
        "apk_decompile_class", "apk_decompile_method",
        "apk_job_status", "apk_job_result", "apk_job_list", "apk_job_export",
    )

    /** 组 id 列表（按 order 排序），供客户端挑选过滤。 */
    fun groupIds(): List<String> = all.sortedBy { it.order }.map { it.id }

    /** 名称 → 组 id 的只读视图（过滤用）。 */
    fun groupsOf(toolNames: Collection<String>): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        toolNames.forEach { name -> result.getOrPut(categoryFor(name).id) { ArrayList() }.add(name) }
        return result
    }

    fun categoryFor(toolName: String): McpToolCategory =
        byId[toolToCategory[toolName]] ?: McpToolCategory("other", "其它", "未分类", 99)

    fun toolNamesIn(categoryId: String): List<String> =
        toolToCategory.entries.filter { it.value == categoryId }.map { it.key }

    fun counts(): Map<String, Int> = buildMap {
        all.forEach { category -> put(category.id, toolNamesIn(category.id).size) }
    }
}
