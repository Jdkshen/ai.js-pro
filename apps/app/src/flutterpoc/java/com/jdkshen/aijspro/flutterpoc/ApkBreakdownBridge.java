package com.jdkshen.aijspro.flutterpoc;

import android.app.Activity;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * 只读桥方法 {@code apkBreakdown}（task-96）：把 {@link ApkSizeBreakdown} 的统计转成 JSON 给打包页。
 *
 * <p>参数：{@code {path}}（产物 APK 的绝对路径）。返回 {@code {ok:true,data:{...}}}；
 * 文件不存在/不是文件/读不了 → {@code {ok:false,error}}（**页面据此不显示该行**，不猜、不写死）。
 * 本方法**只读**：不写任何文件、不改打包行为、不碰 project.json。</p>
 */
final class ApkBreakdownBridge {

    private ApkBreakdownBridge() {
    }

    private static final String TAG = "GlassFlutterPoc";

    static JSONObject execute(Activity activity, JSONObject args) throws Exception {
        try {
            return breakdown(args);
        } catch (Exception error) {
            // task-100：不再静默 —— 失败至少留一条 logcat，便于真机定位（页面另有一行「暂不可用」）
            Log.w(TAG, "apkBreakdown 失败: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            throw error;
        }
    }

    private static JSONObject breakdown(JSONObject args) throws Exception {
        String path = args == null ? null : args.optString("path", null);
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少输出参数 path");
        }
        File apk = new File(path);
        if (!apk.isFile()) {
            throw new IllegalArgumentException("找不到 APK 文件：" + path);
        }
        ApkSizeBreakdown.Summary summary = ApkSizeBreakdown.summarize(ApkSizeBreakdown.read(apk));
        JSONObject data = new JSONObject();
        data.put("path", apk.getAbsolutePath());
        data.put("apkBytes", apk.length());
        data.put("entries", summary.entries);
        data.put("uncompressedBytes", summary.uncompressedBytes);
        data.put("compressedBytes", summary.compressedBytes);
        data.put("unknownSizeEntries", summary.unknownSizes);
        JSONArray buckets = new JSONArray();
        for (String key : ApkSizeBreakdown.BUCKET_ORDER) {
            ApkSizeBreakdown.Bucket bucket = summary.buckets.get(key);
            JSONObject item = new JSONObject();
            item.put("key", key);
            item.put("uncompressedBytes", bucket.uncompressedBytes);
            item.put("uncompressedMiB", ApkSizeBreakdown.toMiB(bucket.uncompressedBytes));
            item.put("compressedBytes", bucket.compressedBytes);
            item.put("entries", bucket.entries);
            buckets.put(item);
        }
        data.put("buckets", buckets);
        data.put("note", "未压缩大小（unzip -l 口径）；APK 实际大小是压缩后的，" + "各桶相加不等于文件大小");
        return data;
    }
}
