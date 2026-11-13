package com.jdkshen.aijspro.mcp

/** Ordered public contract used by the server, UI diagnostics and tests. */
internal object McpToolCatalog {
    val canonicalNames = listOf(
        "get_status",
        // §6.4 基线自检：只读入口，数字与既有工具同源。
        "get_baseline",
        "list_scripts",
        "read_script",
        "search_scripts",
        "continue_result",
        "list_samples",
        "read_sample",
        "list_executions",
        "get_execution",
        "wait_execution",
        "read_apk_logs",
        "run_script",
        "list_engine_api",
        "probe_engine_api",
        "engine_api_diff",
        "stop_script",
        "workspace_open",
        "workspace_list",
        "workspace_read",
        "workspace_write",
        "workspace_delete",
        "workspace_diff",
        "workspace_request_apply",
        "workspace_mkdir",
        "workspace_cancel",
        "workspace_cleanup",
        "get_ui_layout",
        "capture_screen",
        // task-26：逐帧像素差异（与 capture_screen 同一条截图管线）。
        "dev_screen_diff",
        "dev_gesture_probe",
        // P1-6（.4 反馈「三套坐标系没有换算工具」）：截图/显示/浮窗三套空间的换算与标定
        "coordinate_convert",
        "check_syntax",
        "learn_aijspro_skill",
        "stop_all_scripts",
        "get_mcp_log",
        "list_engines",
        "call_engine_api",
        "list_overlays",
        "close_overlays",
        "terminal_exec",
        "terminal_read",
        "terminal_write",
        "terminal_stop",
        "terminal_list",
        "shell_rish",
        "get_build_tools",
        "app_list",
        "app_info",
        "app_launch",
        "app_open_settings",
        "app_open_url",
        "apk_install",
        "apk_uninstall",
        "ui_find",
        "ui_click",
        "ui_long_click",
        "ui_node_action",
        "ui_input",
        "ui_swipe",
        "ui_global",
        "ui_wait",
        "ui_scroll",
        "search_api_docs",
        "read_api_doc",
        // §9 应用启动/日志/退出原因关联采集
        "app_exit_info",
        "app_trace_start",
        "app_trace_read",
        "app_trace_stop",
        "app_trace_list",
        "app_launch_trace",
        // 应用自身悬浮球：状态/显示/关闭（用户反馈「悬浮窗关闭不了」时唯一的可靠入口）
        "floating_menu",
        // §2.4/§2.5/§2.6 与 §2.1 的用户侧辅助入口
        "shell_capabilities",
        "shell_classify_failure",
        "node_script_command",
        "crash_buffer_semantics"
    ) + McpToolkitCatalog.names +
        // `.4` P0-1：schema 自描述入口。**必须排在 McpToolkitCatalog.names 之后**，
        // 否则与 listTools() 的添加顺序不一致 → 启动自检 check() 失败 → MCP 停止监听（dev-1402 踩过）。
        listOf("tool_schema")

    /**
     * Pro compatibility names advertised by tools/list, alongside the engine tools.
     * [learnAutoJsSkill] keeps the original Pro spelling ("AutoJs"); its deterministic
     * camelCase dispatch form is "learnAutojsSkill". Both are accepted by tools/call.
     */
    val aliases = linkedMapOf(
        "getUiLayout" to "get_ui_layout",
        "captureScreen" to "capture_screen",
        "checkSyntax" to "check_syntax",
        "runScript" to "run_script",
        "learnAutoJsSkill" to "learn_aijspro_skill",
        // 2026-11-13：产品早已不是 AI.js Pro（现为 AI.js Pro）⇒ 工具名改成 learn_aijspro_skill。
        // 旧名保留为兼容别名，老客户端/老 prompt 照旧能调，不会断。
        "learn_autojs_skill" to "learn_aijspro_skill",
        "stopAllScripts" to "stop_all_scripts",
        "getMcpLog" to "get_mcp_log",
        "listOverlays" to "list_overlays",
        "closeOverlays" to "close_overlays",
    )

    /**
     * Dispatch-only aliases: every canonical tool gets its deterministic camelCase spelling.
     * They are accepted by tools/call but deliberately are not added to [names]/tools/list.
     */
    val dispatchAliases: Map<String, String> = canonicalNames.associateWith { canonical ->
        canonical.split('_').mapIndexed { index, part ->
            if (index == 0) part else part.replaceFirstChar { it.uppercaseChar() }
        }.joinToString("")
    }

    /**
     * 族前缀别名（W4 task-33）：`dex_*` → `apk_dex_*`、`so_*` → `apk_native_*`。
     *
     * **只派发、不广播**：它们进 [canonicalByAlias] 让 `tools/call` 能用，但**不进** [names]/tools/list ——
     * 否则 advertised 会从 224 涨到 240，白占客户端上下文。旧名（canonical）长期保留，两边等价。
     * 前缀策略见范围提案 §5：只做别名 + 长期共存，不做破坏性改名。
     */
    val familyAliases: Map<String, String> = linkedMapOf(
        "dex_read_class_outline" to "apk_dex_read_class_outline",
        "dex_xref" to "apk_dex_xref",
        "dex_search_members" to "apk_dex_search_members",
        "dex_call_graph" to "apk_dex_call_graph",
        "dex_hierarchy" to "apk_dex_hierarchy",
        "dex_interface_table" to "apk_dex_interface_table",
        "dex_list_classes" to "apk_dex_list_classes",
        "dex_list_methods" to "apk_dex_list_methods",
        "so_inspect" to "apk_native_inspect",
        "so_list_items" to "apk_native_list_items",
        "so_map_address" to "apk_native_map_address",
        "so_xref" to "apk_native_xref",
        "so_disassemble" to "apk_native_disassemble",
        "so_analyze_control_flow" to "apk_native_analyze_control_flow",
        "so_patch_instructions" to "apk_native_patch_instructions",
        "so_patch_string" to "apk_native_patch_string",
    )

    private val canonicalByAlias: Map<String, String> =
        dispatchAliases.entries.associate { entry -> entry.value to entry.key } + familyAliases + aliases + McpToolkitCatalog.aliases

    val names = canonicalNames + aliases.keys

    init {
        check(dispatchAliases.size == canonicalNames.size)
        check(dispatchAliases.values.toSet().size == canonicalNames.size)
        // Pro aliases may keep their historical spelling (learnAutoJsSkill); every
        // accepted alias must dispatch to a canonical tool, but it does not have to
        // equal the automatically generated camelCase spelling.
        check(aliases.all { (_, canonical) -> canonical in canonicalNames })
        check(canonicalNames.all { canonical ->
            canonicalByAlias[dispatchAliases.getValue(canonical)] == canonical
        })
        check(aliases.all { (alias, canonical) -> canonicalByAlias[alias] == canonical })
        check(names.toSet().size == names.size)
        // W4 task-33：族前缀别名只派发、不广播。
        check(familyAliases.all { (_, canonical) -> canonical in canonicalNames }) {
            "族前缀别名的目标必须是 canonical：" + familyAliases.filterValues { it !in canonicalNames }.keys
        }
        check(familyAliases.keys.none { it in canonicalNames }) {
            "族前缀别名不能与 canonical 名冲突：" + familyAliases.keys.filter { it in canonicalNames }
        }
        check(familyAliases.keys.none { it in names }) {
            "族前缀别名不能进 names/tools/list（只派发）：" + familyAliases.keys.filter { it in names }
        }
        check(familyAliases.keys.none { it in dispatchAliases.values || it in aliases.keys || it in McpToolkitCatalog.aliases.keys }) {
            "族前缀别名不能覆盖既有别名：" + familyAliases.keys.filter { it in dispatchAliases.values || it in aliases.keys || it in McpToolkitCatalog.aliases.keys }
        }
        check(familyAliases.all { (alias, canonical) -> canonicalByAlias[alias] == canonical })
        check(familyAliases.size == familyAliases.keys.toSet().size)
    }

    fun canonicalName(name: String): String = canonicalByAlias[name] ?: name

    /** 某 canonical 的族前缀别名（给 tool_schema 回显用；不含 Pro 别名）。 */
    fun familyAliasesOf(canonical: String): List<String> = familyAliases.filterValues { it == canonical }.keys.toList()
}
