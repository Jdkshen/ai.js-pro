package com.stardust.autojs.core.ui.inflater.util;

import android.content.Context;
import android.content.res.Resources;

import com.stardust.autojs.core.ui.inflater.CompiledProjectResources;

/**
 * 按名字查资源 id（inflater 里所有 {@code @color/xxx}、{@code ?attr/xxx} 之类都走这里）。
 *
 * <p>要试**两个**包名：
 *
 * <ol>
 *     <li>宿主 app 的包名（app 自带 + AAR 带进来的资源）；</li>
 *     <li>工程资源包的包名（PC 端 aapt2 编出来的 {@code build/project-res.apk}）。</li>
 * </ol>
 *
 * <p>为什么不能只写一个：工程资源包用独立包 id（{@code 0x81}）时才不会和 app 的 {@code 0x7f} 撞 id，
 * 而 aapt2 在这种情况会把包名改成 {@code <app 包名>.feature}，所以两个包名都得试。
 * 也不能把包名写成空串「跨包搜索」—— 实测 {@code getIdentifier(name, type, "")} 恒为 0。
 */
public class ResIds {

    private ResIds() {
    }

    /** 找不到返回 0。 */
    public static int of(Context context, String name, String type) {
        if (context == null || name == null || name.isEmpty()) {
            return 0;
        }
        Resources resources = context.getResources();
        String appPackage = context.getPackageName();
        int id = resources.getIdentifier(name, type, appPackage);
        if (id != 0) {
            return id;
        }
        String projectPackage = CompiledProjectResources.resourcePackageName();
        if (projectPackage != null && !projectPackage.isEmpty() && !projectPackage.equals(appPackage)) {
            id = resources.getIdentifier(name, type, projectPackage);
        }
        return id;
    }
}
