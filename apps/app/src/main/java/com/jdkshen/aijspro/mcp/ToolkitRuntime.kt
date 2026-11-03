package com.jdkshen.aijspro.mcp

import com.google.gson.JsonObject
import java.io.File

/** Optional components are queried without execution; only explicit actions install or run them. */
internal interface ToolkitRuntime {
    fun capabilities(): JsonObject
    fun install(args: JsonObject): JsonObject
    fun remove(args: JsonObject): JsonObject
    fun compileResources(args: JsonObject): JsonObject
    fun decompile(input: File, locator: String, args: JsonObject): JsonObject
}
