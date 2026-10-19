package com.jdkshen.aijspro.flutterpoc;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 「我的 / 导入记录」的本地持久化。
 *
 * <p>记录保存在应用私有目录 {@code files/flutter-glass/import-records.json}，与重建线
 * FlutterBridge 的 import.records 契约对齐：每条记录含 {@code kind/name/path/importedAt}
 * （毫秒时间戳），可选 {@code file/id/version}；{@code kind="plugin"} 的记录会被资源页过滤，
 * 只保留本地导入的资源。额外带 {@code size}（字节，未知为 0），详情弹窗会显示文件大小。
 *
 * <p>写入是「读-改-写」：同 path 复用原记录（刷新时间与字段）而不是追加；最多保留
 * {@link #MAX_RECORDS} 条，超出丢弃最旧的。所有 IO/JSON 异常都在内部吞掉并记日志，
 * 桥调用方永远拿到数组（最差是空数组），不会因此报错。
 */
final class PocImportRecords {

    private static final String TAG = "GlassFlutterPoc";
    private static final String DIR_NAME = "flutter-glass";
    private static final String FILE_NAME = "import-records.json";
    /** 与重建线一致：历史最多保留 200 条，超出丢弃最旧的。 */
    private static final int MAX_RECORDS = 200;

    /** 同一进程内读-改-写互斥；导入回调可能在后台线程，必须跨线程安全。 */
    private static final Object LOCK = new Object();

    private PocImportRecords() {
    }

    /** 读取全部导入记录（按导入时间从新到旧）；文件不存在或损坏时返回空数组。 */
    static JSONArray list(Context context) {
        synchronized (LOCK) {
            try {
                return load(context);
            } catch (Throwable error) {
                Log.w(TAG, "读取导入记录失败", error);
                return new JSONArray();
            }
        }
    }

    /**
     * 登记一条导入记录；同 path 更新已有记录而不是追加。
     *
     * @param file    原始来源（content uri / 下载地址 / 目录资源 file），可为空
     * @param id      目录资源 id，可为空
     * @param version 可选版本号，可为空
     */
    static void add(Context context, String kind, String name, String path, String file,
                    String id, String version) {
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            try {
                JSONArray old = load(context);
                JSONObject record = new JSONObject()
                        .put("kind", safe(kind))
                        .put("name", safe(name))
                        .put("path", path)
                        .put("importedAt", System.currentTimeMillis())
                        .put("size", sizeOf(path));
                putOptional(record, "file", file);
                putOptional(record, "id", id);
                putOptional(record, "version", version);
                JSONArray records = new JSONArray();
                records.put(record);
                for (int i = 0; i < old.length() && records.length() < MAX_RECORDS; i++) {
                    JSONObject item = old.optJSONObject(i);
                    if (item == null || path.equals(item.optString("path", ""))) {
                        continue;
                    }
                    records.put(item);
                }
                write(context, records);
            } catch (Throwable error) {
                Log.w(TAG, "登记导入记录失败：" + path, error);
            }
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static void putOptional(JSONObject record, String key, String value)
            throws JSONException {
        if (value != null && !value.trim().isEmpty()) {
            record.put(key, value);
        }
    }

    private static long sizeOf(String path) {
        try {
            File file = new File(path);
            return file.isFile() ? file.length() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static JSONArray load(Context context) {
        File source = file(context);
        if (source == null || !source.isFile() || source.length() <= 0) {
            return new JSONArray();
        }
        String raw;
        try {
            raw = read(source);
        } catch (Throwable error) {
            Log.w(TAG, "读取导入记录文件失败", error);
            return new JSONArray();
        }
        if (raw.trim().isEmpty()) {
            return new JSONArray();
        }
        JSONArray parsed;
        try {
            parsed = new JSONArray(raw);
        } catch (Throwable error) {
            Log.w(TAG, "导入记录格式异常，按空记录处理", error);
            return new JSONArray();
        }
        JSONArray result = new JSONArray();
        for (int i = 0; i < parsed.length(); i++) {
            JSONObject item = parsed.optJSONObject(i);
            if (item != null) {
                result.put(item);
            }
        }
        return result;
    }

    private static File file(Context context) {
        if (context == null) {
            return null;
        }
        return new File(new File(context.getFilesDir(), DIR_NAME), FILE_NAME);
    }

    private static String read(File source) throws IOException {
        try (InputStream in = new FileInputStream(source)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** 先写临时文件再 rename，避免进程被杀时留下半截 JSON。 */
    private static void write(Context context, JSONArray records) throws IOException {
        File target = file(context);
        if (target == null) {
            return;
        }
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("无法创建导入记录目录");
        }
        byte[] bytes = records.toString().getBytes(StandardCharsets.UTF_8);
        File temp = File.createTempFile("import-records-", ".tmp", dir);
        try {
            FileOutputStream out = new FileOutputStream(temp);
            try {
                out.write(bytes);
                out.flush();
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (!temp.renameTo(target)) {
                // 少数文件系统不允许覆盖式 rename：删掉旧文件后再试一次。
                if (target.exists() && !target.delete()) {
                    throw new IOException("无法替换导入记录文件");
                }
                if (!temp.renameTo(target)) {
                    throw new IOException("无法写入导入记录文件");
                }
            }
        } finally {
            if (temp.exists()) {
                temp.delete();
            }
        }
    }
}
