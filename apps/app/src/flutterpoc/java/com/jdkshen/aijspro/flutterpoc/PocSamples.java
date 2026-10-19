package com.jdkshen.aijspro.flutterpoc;

import android.content.Context;

import org.json.JSONArray;

/**
 * 示例库桥（task-129 起彻底禁用下载）。
 *
 * <p>用户明确要求「不要下载」：所有示例都随 APK 内置（{@code assets/sample}），示例页只列
 * {@code files/sample} 里的内置示例；本类不再提供任何网络下载能力，也不再读取
 * {@code assets/sample-manifest.json}（该文件保留但已无调用方）。
 *
 * <p>{@link #library} 恒返回空数组：Flutter 示例页据此不显示任何「下载」行/按钮，
 * 示例在安装包里缺失时只会「不显示」，不存在发网络请求的路径。
 */
final class PocSamples {

    private PocSamples() {
    }

    /** 可下载示例清单：已彻底禁用下载，恒为空（示例全部来自 APK 内置 assets，缺失即不显示）。 */
    static JSONArray library(Context context) {
        return new JSONArray();
    }
}
