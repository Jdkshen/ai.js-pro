package com.jdkshen.aijspro.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * task-177「去掉 Miuix，统一 Flutter 界面」：main 源集里还活着的原生入口
 * （原生 Ace 编辑器的「打包 / 项目设置」、外部 Intent 等）把请求交给 Flutter 玻璃界面。
 *
 * <p>两条通道：
 * <ul>
 *   <li>普通打开：直接拉起 {@code GlassFlutterActivity}（类名用字符串，main 不编译依赖
 *       flutterPoc 调试源集）；</li>
 *   <li>打包/项目配置：先写 {@code flutter-glass.native_packaging_request} 偏好
 *       （Flutter 侧 {@code consumeNativePackaging()} 会读走并打开玻璃 ProjectPage），
 *       再把 Flutter 界面带回前台。</li>
 * </ul>
 *
 * <p>没有 {@code -PflutterPoc=true} 的构建没有 Flutter 源集：只提示，不回退 Miuix。
 */
public final class FlutterGlassEntry {

    /** 与 PocPackaging.nativePackagingRequest()/Flutter main.dart 共用的偏好与键。 */
    private static final String PREFS = "flutter-glass";
    private static final String KEY_NATIVE_PACKAGING_REQUEST = "native_packaging_request";

    private static final String GLASS_ACTIVITY =
            "com.jdkshen.aijspro.flutterpoc.GlassFlutterActivity";

    private FlutterGlassEntry() {
    }

    /** 把 Flutter 玻璃界面带回前台；失败时给出明确提示（不再回退 Miuix）。 */
    public static boolean open(Context context) {
        Intent intent = new Intent().setClassName(context, GLASS_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(intent);
            return true;
        } catch (Throwable error) {
            Log.w("FlutterGlassEntry", "Flutter 界面不可用（构建需 -PflutterPoc=true）", error);
            Toast.makeText(context,
                    "当前构建未包含 Flutter 界面（需要 -PflutterPoc=true）",
                    Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /**
     * 请求 Flutter 玻璃打包页打开 {@code path}：项目目录（含 project.json）或单个脚本文件。
     * 原生编辑器/外部打包入口用这条通道，不再打开已退役的 Miuix 打包页。
     */
    public static boolean requestPackaging(Context context, String path) {
        if (path == null || path.trim().isEmpty()) {
            Toast.makeText(context, "打包入口没有拿到脚本/项目路径", Toast.LENGTH_SHORT).show();
            return false;
        }
        try {
            SharedPreferences prefs =
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString(KEY_NATIVE_PACKAGING_REQUEST,
                            new JSONObject().put("path", path).toString())
                    .apply();
        } catch (Throwable error) {
            Log.w("FlutterGlassEntry", "写入打包请求失败", error);
            Toast.makeText(context, "无法把打包请求交给 Flutter 界面", Toast.LENGTH_SHORT).show();
            return false;
        }
        return open(context);
    }
}
