package com.jdkshen.aijspro.mcp
class ToolkitException(val code: String, message: String, val argument: String? = null) : IllegalArgumentException(message)
