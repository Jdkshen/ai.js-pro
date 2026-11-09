package com.stardust.autojs.core.image;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * task-71（A1-1 口径对齐）：**未归还图像资源**的具名异常。
 *
 * <p>与 Pro 的差异（写清楚，避免口径漂移）：
 * <ul>
 *   <li>Pro：{@code com.stardust.autojs.core.image.UnrecycledImageDetectedException}，由池的泄漏检测在
 *       未回收时**直接抛出**（脚本必须处理）。</li>
 *   <li>我们：**默认**是「警告 + 强制回收」（{@link ImagePools#drainAll(String)}，不改变任何现有脚本的行为）；
 *       只有在宿主/脚本显式打开严格模式（{@link ImagePools#setStrictMode(boolean)}）时，才在**强制回收之后**
 *       抛本异常 —— 资源一定已经释放，异常只用于把问题暴露给调用方。</li>
 * </ul>
 *
 * <p>也就是说：本类存在 ≠ 默认会抛；默认路径不抛。这是为了「不破坏现有脚本」而有意做的语义收窄。
 */
public final class UnrecycledImageDetectedException extends RuntimeException {

    private final String reason;
    private final List<String> sites;
    private final int forced;

    public UnrecycledImageDetectedException(@Nullable String reason, @Nullable List<String> sites, int forced) {
        super(buildMessage(reason, sites, forced));
        this.reason = reason == null ? "unknown" : reason;
        this.sites = sites == null ? Collections.<String>emptyList() : Collections.unmodifiableList(new ArrayList<>(sites));
        this.forced = forced;
    }

    /** 触发时机描述（例如 {@code "script-exit"}）。 */
    public String getReason() {
        return reason;
    }

    /** 未归还资源的分配点（可能为空：关掉 diagnostics 时不采集）。 */
    public List<String> getSites() {
        return sites;
    }

    /** 已经被强制 release 的资源数量（抛异常时**一定已经回收**）。 */
    public int getForced() {
        return forced;
    }

    private static String buildMessage(String reason, List<String> sites, int forced) {
        StringBuilder builder = new StringBuilder(160);
        builder.append("[ImagePool] 检测到未回收的图像资源（严格模式）：脚本结束(")
                .append(reason == null || reason.isEmpty() ? "unknown" : reason)
                .append(")时仍有 ").append(forced).append(" 个资源没有 recycle()；已强制 release。");
        if (sites != null && !sites.isEmpty()) {
            builder.append("分配点：").append(String.join(" | ", sites)).append('。');
        }
        builder.append("改法：把作用域写成 try (ImagePool pool = new ImagePool()) { … }，")
                .append("或对每个 make()/mat()/matOfPoint() 的返回值调用 pool.recycle(x)；")
                .append("不要这个异常就把严格模式关掉（ImagePools.setStrictMode(false)）。");
        return builder.toString();
    }
}
