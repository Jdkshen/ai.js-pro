package com.jdkshen.aijspro.mcp

/**
 * P3-1 1b-分页层（裁决 (a)：「统一但不改语义」）。
 *
 * 背景：既有 5 处用 coerceIn(lo,hi) **静默夹紧**超限参数（timeoutSeconds 600 / limit 500 /
 * count 10000 / intervalMs 2000 …）—— 契约的本意是「不要让调用方以为拿到的是全部 / 参数被悄悄改而不告知」，
 * 所以这里**不改行为**（仍然夹紧），但把「发生过夹紧」**显式带进返回信封**（clamped/requested/effective）。
 *
 * 规则（写进契约文档）：
 *  · 既有读取点：走本 helper（行为逐字不变）；默认值与上下限**逐个照抄原处**，不许统一成一个数；
 *  · **将来新增参数：一律严格模式**（超限 ⇒ invalidArg，不夹紧）；
 *  · clamped 信息必须进**信封顶层/structuredContent**（不能只写日志 —— 消费方看不到日志）。
 */
internal object McpArgClamp {

    /** 一次夹紧的结果。requested = 调用方原值（缺省时为 null）；effective = 实际使用值。 */
    internal data class ClampResult(
        val value: Int,
        val clamped: Boolean,
        val requested: Int?,
        val effective: Int,
    )

    /**
     * 缺省 ⇒ default（不算 clamped）；越界 ⇒ 夹到 lo/hi 并标 clamped=true；界内 ⇒ 原值。
     * lo 必须 <= hi（由调用点各自保证，值取自各工具**既有**默认/上限）。
     */
    fun clampInt(requested: Int?, default: Int, lo: Int, hi: Int): ClampResult {
        val base = requested ?: default
        val value = if (base < lo) lo else if (base > hi) hi else base
        val clamped = requested != null && value != requested
        return ClampResult(value = value, clamped = clamped, requested = requested, effective = value)
    }
}
