package com.jdkshen.aijspro.mcp

/** Ordered public contract used by the server, UI diagnostics and tests. */
internal object McpToolCatalog {
    val canonicalNames = listOf(
        "get_status",
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
        "check_syntax",
        "learn_autojs_skill",
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
        "read_api_doc"
    )

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
        "learnAutoJsSkill" to "learn_autojs_skill",
        "stopAllScripts" to "stop_all_scripts",
        "getMcpLog" to "get_mcp_log",
        "listOverlays" to "list_overlays",
        "closeOverlays" to "close_overlays"
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

    private val canonicalByAlias: Map<String, String> =
        dispatchAliases.entries.associate { entry -> entry.value to entry.key } + aliases

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
    }

    fun canonicalName(name: String): String = canonicalByAlias[name] ?: name
}
