package com.stardust.autojs.core.content;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 脚本侧广播接收的过滤条件（task-54，自研）。
 *
 * 只做归一化与校验，不碰 Android：
 *  - action/category/scheme 去首尾空白、丢空串、按原顺序去重；
 *  - 至少要有一个 action（Pro/老版 Auto.js 的 registerBroadcastReceiver 也是按 action 收）。
 *  - Android 13（API 33）起 Context.registerReceiver 必须显式给 RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED，
 *    否则 SecurityException；这里把「按 API 级别算 flags」做成纯函数，便于单测与复用。
 */
public final class BroadcastFilter {
    /** android.content.Context.RECEIVER_EXPORTED（常量值写死，避免纯层依赖 android.jar）。 */
    public static final int RECEIVER_EXPORTED = 0x2;
    /** android.content.Context.RECEIVER_NOT_EXPORTED。 */
    public static final int RECEIVER_NOT_EXPORTED = 0x4;
    /** 必须显式导出标志的最低 API 级别（Android 13 / T）。 */
    public static final int FLAGS_REQUIRED_API = 33;

    public final List<String> actions;
    public final List<String> categories;
    public final List<String> schemes;
    public final String permission;
    public final boolean exported;

    private BroadcastFilter(List<String> actions, List<String> categories, List<String> schemes,
                            String permission, boolean exported) {
        this.actions = actions;
        this.categories = categories;
        this.schemes = schemes;
        this.permission = permission;
        this.exported = exported;
    }

    /** 归一化 + 校验；不合法抛 IllegalArgumentException（消息可直接给脚本看）。 */
    public static BroadcastFilter of(String[] actions, String[] categories, String[] schemes,
                                     String permission, boolean exported) {
        List<String> normalizedActions = normalize(actions);
        if (normalizedActions.isEmpty()) {
            throw new IllegalArgumentException("registerBroadcastReceiver: actionOrFilter 至少要有一个非空 action");
        }
        return new BroadcastFilter(normalizedActions, normalize(categories), normalize(schemes),
                permission == null || permission.trim().isEmpty() ? null : permission.trim(), exported);
    }

    /** 去空白、丢空串、去重（保持顺序）。 */
    public static List<String> normalize(String[] values) {
        LinkedHashSet<String> unique = new LinkedHashSet<String>();
        if (values != null) {
            for (String value : values) {
                if (value == null) continue;
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) unique.add(trimmed);
            }
        }
        return new ArrayList<String>(unique);
    }

    /**
     * API 33+ 必须显式导出标志；更早的版本传 flags 会被忽略（部分 ROM 还会抛），所以小于 33 一律 0。
     * 默认取更安全的 RECEIVER_NOT_EXPORTED：其它应用用显式组件或同进程广播才送达，
     * 普通 am broadcast（隐式广播）不会命中它。
     */
    public int receiverFlags(int apiLevel) {
        if (apiLevel < FLAGS_REQUIRED_API) return 0;
        return exported ? RECEIVER_EXPORTED : RECEIVER_NOT_EXPORTED;
    }

    public String describe() {
        return "actions=" + actions + " categories=" + categories + " schemes=" + schemes
                + " permission=" + permission + " exported=" + exported;
    }
}
