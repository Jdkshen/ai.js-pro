package com.jdkshen.aijspro.mcp

/**
 * P3-1：**工具层业务参数错**（不是协议层形状错，也不是内部不变量失败）。
 *
 * 为什么单独立一个类型（Lead 要求先核实再决定，实测依据）：
 *  · mcp 目录下 require( 有 **720 处**（跨 68 文件）、IllegalArgumentException **476 处**（跨 44 文件）⇒
 *    来源**不单一**（既含业务参数校验，也含 require(mkdirs()) / IO 结果 / 内部不变量等）✗
 *  ⇒ 若用宽 catch 把 IllegalArgumentException 一律判成 invalidArg，会把**内部错误**误标成用户参数错 ✗
 *  ⇒ 所以只用这个**自定义类型**做精确 catch（零误判）。
 *
 * 采用方式（分批）：样板工具先改；其余工具在各自批次里把「业务参数校验」与「内部不变量」分开后再改。
 */
internal class McpToolArgException(message: String) : IllegalArgumentException(message)
