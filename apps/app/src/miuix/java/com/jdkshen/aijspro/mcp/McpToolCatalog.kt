package com.jdkshen.aijspro.mcp

/** Ordered public contract used by the server, UI diagnostics and tests. */
internal object McpToolCatalog {
    val names = listOf(
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
        "workspace_cleanup"
    )
}
