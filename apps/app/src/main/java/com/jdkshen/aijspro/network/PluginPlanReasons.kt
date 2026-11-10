package com.jdkshen.aijspro.network

// task-131/132：结构化原因码（UI 直接按码出文案，不解析字符串）。
//
// 下载/安装链路的五种失败（Lead 指定）：network / checksum / package / permission / missing。
// 「为什么现在不该下」另有计划码（不属于失败）：pending / abi / minAppVersion / upToDate。
enum class PluginFailureReason(val code: String) {
    NETWORK("network"),
    CHECKSUM("checksum"),
    PACKAGE("package"),
    PERMISSION("permission"),
    MISSING("missing");
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
