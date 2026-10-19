// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * task-120：Node 24 运行时按需下载（主 APK 不再打包 ~30MB/94MB 的 libnode 等）。
 *
 * <p>更新源 http://192.168.10.7:8080/node24-runtime.zip；下载后解压到 files/node24-runtime/current，
 * 用 zip 内 runtime.json 的 SHA-256 pin 校验三个库；已缓存则直接复用。失败给中文提示并支持重试。
 */
public final class Node24RuntimeDownloader {

    private static final String TAG = "C-NODE24";
    private static final String RUNTIME_URL = "http://192.168.10.7:8080/node24-runtime.zip";
    private static final String[] LIBS = {"libnode.so", "libc++_shared.so", "libajsnode24bridge.so"};
    private static final String[] HOST_PATHS = {
            "node24/arm64-v8a/", "source-engine-upgrade/node24/arm64-v8a/", ""
    };
    private static final String READY_MARKER = ".ready";

    private Node24RuntimeDownloader() {
    }

    /** 已下载目录是否可用（快速检查：marker + 三库存在）。 */
    public static boolean isReady(Context context) {
        File target = targetDir(context);
        if (!new File(target, READY_MARKER).isFile()) return false;
        for (String name : LIBS) {
            if (!new File(target, name).isFile()) return false;
        }
        return true;
    }

    public static File targetDir(Context context) {
        return new File(context.getDir("node24-runtime", Context.MODE_PRIVATE), "current");
    }

    /** 阻塞式 ensure：已缓存返回目录；否则下载+校验+解压；失败抛中文异常。 */
    public static File ensure(Context context) throws Exception {
        sContext = context.getApplicationContext();
        File target = targetDir(context);
        if (isReady(context)) {
            Log.i(TAG, "C-NODE24: runtime cached dir=" + target);
            return target;
        }
        return download(context, target);
    }

    private static File download(final Context context, File target) throws Exception {
        File cache = context.getCacheDir();
        File zip = new File(cache, "node24-runtime.zip.part");
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Log.i(TAG, "C-NODE24: download attempt=" + attempt + " url=" + RUNTIME_URL);
                downloadToFile(RUNTIME_URL, zip, context);
                last = null;
                break;
            } catch (IOException error) {
                last = error;
                Log.w(TAG, "C-NODE24: download failed attempt=" + attempt, error);
                toast("Node24 运行时下载失败（第 " + attempt + "/3 次）：" + error.getMessage());
            }
        }
        if (last != null) {
            throw new IOException("Node24 运行时未下载且无法联网：" + last.getMessage()
                    + "；请连接网络后重试（更新源 " + RUNTIME_URL + "）", last);
        }
        File temporary = new File(target.getPath() + ".tmp");
        deleteTree(temporary);
        if (!temporary.mkdirs()) throw new IOException("无法创建 Node24 运行时目录：" + temporary);
        JSONObject pins = new JSONObject();
        try (ZipInputStream input = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = baseName(entry.getName());
                if ("runtime.json".equals(name)) {
                    pins = new JSONObject(new String(readAll(input), StandardCharsets.UTF_8));
                } else if (isLib(name)) {
                    File out = new File(temporary, name);
                    if (!out.getParentFile().isDirectory() && !out.getParentFile().mkdirs()) {
                        throw new IOException("无法创建 Node24 运行时子目录");
                    }
                    try (FileOutputStream output = new FileOutputStream(out)) {
                        byte[] buffer = new byte[65536];
                        int size;
                        while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
                        output.getFD().sync();
                    }
                }
            }
        }
        for (String name : LIBS) {
            File file = new File(temporary, name);
            if (!file.isFile()) throw new IOException("下载包缺少 " + name + "（node24-runtime.zip 不完整）");
            String expected = pins.optString(name, "");
            if (!expected.matches("[0-9a-f]{64}")) throw new IOException("下载包 runtime.json 缺少 " + name + " 的 SHA-256");
            String actual = sha256(file);
            if (!actual.equals(expected)) throw new IOException("Node24 运行时校验失败：" + name + " (sha256=" + actual + ")");
        }
        try (FileOutputStream output = new FileOutputStream(new File(temporary, READY_MARKER))) {
            output.write(pins.toString().getBytes(StandardCharsets.UTF_8));
        }
        deleteTree(target);
        if (!temporary.renameTo(target)) throw new IOException("无法提交 Node24 运行时目录");
        long downloadedBytes = zip.length();
        if (!zip.delete()) Log.w(TAG, "C-NODE24: cannot delete downloaded zip " + zip);
        Log.i(TAG, "C-NODE24: runtime ready dir=" + target + " bytes=" + downloadedBytes);
        toast("Node24 运行时下载完成");
        return target;
    }

    private static void downloadToFile(String url, File target, Context context) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            long total = connection.getContentLengthLong();
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[65536];
                long done = 0;
                int lastPercent = -1;
                int size;
                while ((size = input.read(buffer)) != -1) {
                    output.write(buffer, 0, size);
                    done += size;
                    if (total > 0) {
                        int percent = (int) (done * 100 / total);
                        if (percent / 10 != lastPercent / 10) {
                            lastPercent = percent;
                            Log.i(TAG, "C-NODE24: download progress=" + percent + "%");
                            toast("Node24 运行时下载中 " + percent + "%");
                        }
                    }
                }
                output.getFD().sync();
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean isLib(String name) {
        for (String lib : LIBS) if (lib.equals(name)) return true;
        return false;
    }

    private static String baseName(String path) {
        // task-120-fix：PowerShell Compress-Archive 在 Windows 上会用反斜杠做 zip entry 名
        // （如 node24\arm64-v8a\libnode.so），只按 '/' 截断会匹配不到 lib 名，导致
        // 「下载包缺少 libnode.so」。两种分隔符都要兼容。
        int slash = path.lastIndexOf('/');
        int backslash = path.lastIndexOf('\\');
        int index = Math.max(slash, backslash);
        return index < 0 ? path : path.substring(index + 1);
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int size;
        while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
        return output.toByteArray();
    }

    private static String sha256(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int size;
            while ((size = input.read(buffer)) != -1) hash.update(buffer, 0, size);
        }
        StringBuilder text = new StringBuilder();
        for (byte value : hash.digest()) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!file.delete()) Log.w(TAG, "C-NODE24: cannot delete " + file);
    }

    private static volatile Context sContext;

    private static void toast(final String message) {
        final Context context = sContext;
        if (context == null) return;
        try {
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
        } catch (Throwable ignored) {
        }
    }
}
