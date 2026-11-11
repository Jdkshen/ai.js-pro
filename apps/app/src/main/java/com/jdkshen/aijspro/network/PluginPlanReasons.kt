package com.jdkshen.aijspro.network

// task-131/132：结构化原因码（UI 直接按码出文案，不解析字符串）。
//
// 下载/安装链路的失败码（Lead 指定）：network / checksum / package / permission / missing，
// 外加 task-131 补的两类（VS Code 式区分）：source（自建源不可达/404）/ manifest（清单格式或 schema 不支持）。
// 「为什么现在不该下」另有计划码（不属于失败）：pending / abi / minAppVersion / upToDate。
enum class PluginFailureReason(val code: String) {
    NETWORK("network"),
    CHECKSUM("checksum"),
    PACKAGE("package"),
    PERMISSION("permission"),
    MISSING("missing"),
    /** 自建源不可达 / 404 / 返回错误状态码 —— 是「源」的问题，不是清单的问题。 */
    SOURCE("source"),
    /** 清单取到了但格式/schema 不受支持（含空正文）—— 是「清单」的问题。 */
    MANIFEST("manifest");
}

enum class PluginPlanReason(val code: String) {
    INSTALL("install"),
    UPDATE("update"),
    UP_TO_DATE("upToDate"),
    PENDING_ARTIFACT("pending"),
    ABI_UNSUPPORTED("abi"),
    APP_TOO_OLD("minAppVersion"),
    NOT_INSTALLED("missing");
}
