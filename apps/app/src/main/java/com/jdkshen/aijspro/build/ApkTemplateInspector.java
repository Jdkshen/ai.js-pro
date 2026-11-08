package com.jdkshen.aijspro.build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * W-H：打包模板指纹与 dev-1401 engine-require-root 修复标记检测。
 *
 * 只读流式读取模板 APK 的 assets/init.js，不解压整包；读取失败不影响模板本身可用性，
 * 但会把 engineRequireRootFix=false 且 inspectError 写进结果。
 */
public final class ApkTemplateInspector {
    public static final String SOURCE_SDCARD = "sdcard_override";
    public static final String SOURCE_HOST_CACHE = "host_cache";
    public static final String SOURCE_BUNDLED = "bundled_assets";
    public static final String SOURCE_NONE = "none";
    private static final int MAX_INIT_BYTES = 4 * 1024 * 1024;
    private static final String REQUIRE_ROOT_MARKER = "__ajsSetRequireRoot";

    public static final class Info {
        public final boolean available;
        public final String source;
        public final String path;
        public final long sizeBytes;
        public final String sha256;
        public final String engineInitSha256;
        public final boolean engineRequireRootFix;
        public final boolean templateStale;
        public final String inspectError;
        public final String detail;
        public final JSONArray warnings = new JSONArray();
        public final JSONArray nextActions = new JSONArray();

        Info(boolean available, String source, String path, long sizeBytes, String sha256,
             String engineInitSha256, boolean engineRequireRootFix, String inspectError, String detail) {
            this.available = available;
            this.source = source == null ? SOURCE_NONE : source;
            this.path = path == null ? "" : path;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256 == null ? "" : sha256;
            this.engineInitSha256 = engineInitSha256 == null ? "" : engineInitSha256;
            this.engineRequireRootFix = engineRequireRootFix;
            this.templateStale = !engineRequireRootFix;
            this.inspectError = inspectError == null ? "" : inspectError;
            this.detail = detail == null ? "" : detail;
            if (!available) {
                append(warnings, "打包模板不可用：" + this.detail);
            } else if (!engineRequireRootFix) {
                append(warnings, "打包模板缺少 dev-1401 的 engine require-root 修复标记 " + REQUIRE_ROOT_MARKER
                        + "（搜索到的 init.js 指纹=" + (this.engineInitSha256.isEmpty() ? "none" : this.engineInitSha256)
                        + "）生成的 APK 仍可能报 Can't resolve relative module ID \"./脚本.js\" when require() is used outside of a module。");
                append(nextActions, "删除 " + "/sdcard/template.apk" + "（手动覆盖优先级最高）后重新打包");
                append(nextActions, "删除宿主缓存 files/apk-builder/<versionCode>/template.apk 后重新打包");
                append(nextActions, "升级宿主 app 到 dev-1401 或更新版本（内置模板应含 " + REQUIRE_ROOT_MARKER + "）");
                append(nextActions, "或在打包调用中传 forceBundledTemplate=true，跳过前两级直接用 app 内置模板");
            }
        }

        public JSONObject toJson() {
            JSONObject json = new JSONObject();
            put(json, "available", available);
            put(json, "source", source);
            put(json, "path", path);
            put(json, "sizeBytes", sizeBytes);
            put(json, "sha256", sha256);
            put(json, "engineInitSha256", engineInitSha256);
            put(json, "engineRequireRootFix", engineRequireRootFix);
            put(json, "templateStale", templateStale);
            put(json, "inspectError", inspectError);
            put(json, "detail", detail);
            put(json, "warnings", warnings);
            put(json, "nextActions", nextActions);
            return json;
        }
    }

    public static Info inspect(File file, String source) {
        if (file == null || !file.isFile()) {
            return new Info(false, source, file == null ? "" : file.getPath(), 0, "", "", false,
                    "", "模板文件不存在");
        }
        try (InputStream input = new FileInputStream(file)) {
            return inspect(input, source, file.getPath(), file.length());
        } catch (Throwable error) {
            return new Info(false, source, file.getPath(), file.length(), "", "", false,
                    error.getClass().getSimpleName() + ": " + error.getMessage(), "读取模板失败");
        }
    }

    public static Info inspect(byte[] bytes, String source, String path) {
        if (bytes == null) return new Info(false, source, path, 0, "", "", false, "", "模板字节为空");
        try {
            return inspect(new ByteArrayInputStream(bytes), source, path, bytes.length);
        } catch (Throwable error) {
            return new Info(false, source, path, bytes.length, "", "", false,
                    error.getClass().getSimpleName() + ": " + error.getMessage(), "读取模板失败");
        }
    }

    private static Info inspect(InputStream input, String source, String path, long size) throws IOException {
        byte[] all = readAll(input);
        String sha = sha256(all);
        String initSha = "";
        boolean fixed = false;
        String inspectError = "";
        try (ZipFile zip = new ZipFile(path)) {
            // ZipFile path overload needs a real file; this branch cannot be used for raw asset bytes.
            inspectError = "internal";
        } catch (Throwable ignored) {
        }
        return inspectBytes(all, source, path, size, sha);
    }

    private static Info inspectBytes(byte[] all, String source, String path, long size, String sha) {
        String initSha = "";
        boolean fixed = false;
        String inspectError = "";
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(all))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (!"assets/init.js".equals(entry.getName())) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) > 0) {
                    if (out.size() + read > MAX_INIT_BYTES) throw new IOException("assets/init.js 超过 " + MAX_INIT_BYTES + " 字节");
                    out.write(buffer, 0, read);
                }
                byte[] init = out.toByteArray();
                initSha = sha256(init).substring(0, 16);
                fixed = new String(init, StandardCharsets.UTF_8).contains(REQUIRE_ROOT_MARKER);
                break;
            }
        } catch (Throwable error) {
            inspectError = error.getClass().getSimpleName() + ": " + error.getMessage();
        }
        String detail = fixed ? "含 " + REQUIRE_ROOT_MARKER : (inspectError.isEmpty() ? "未找到 assets/init.js 或缺少修复标记" : "读取 assets/init.js 失败");
        return new Info(true, source, path, size, sha, initSha, fixed, inspectError, detail);
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return builder.toString();
        } catch (Exception error) {
            return "";
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) > 0) {
            if (out.size() + read > 64 * 1024 * 1024) throw new IOException("模板超过 64MiB");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** org.json 的 put 抛受检 JSONException；这里包一层，避免整条调用链传播受检异常。 */
    private static JSONObject put(JSONObject target, String key, Object value) {
        try { return target.put(key, value); } catch (Exception ignored) { return target; }
    }

    private static JSONArray append(JSONArray target, Object value) {
        try { target.put(value); } catch (Exception ignored) { }
        return target;
    }

}
