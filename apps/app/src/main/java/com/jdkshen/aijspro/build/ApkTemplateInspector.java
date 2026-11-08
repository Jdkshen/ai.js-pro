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
 * <p>只读**流式**读取模板 APK 的 {@code assets/init.js}，不解压整包；读取失败不影响模板本身可用性，
 * 但会把 engineRequireRootFix=false 且 inspectError 写进结果。
 *
 * <p>dev-1413：整包 SHA-256 与 {@code assets/init.js} 都改成流式读取。旧实现先用 {@code readAll()}
 * 把整包读进内存并设了 64MiB 上限，于是**插件兜底通道**（{@code ApkBuilderPluginHelper} 第 115-119 行
 * 落地的是 88,448,312 B 全量模板）与 **{@code /sdcard/template.apk} 手动覆盖**（历史就是 88MiB 级）
 * 上，再新的模板也会被判成 available=false / engineRequireRootFix=false / templateStale=true
 * （inspectError="IOException: 模板超过 64MiB"）；打包本身不失败，但日志与 get_status 字段全错。
 * 现在只读 zip 中央目录 + 单条 {@code assets/init.js}（上限 {@link #MAX_INIT_BYTES}），模板多大都不进内存。
 */
public final class ApkTemplateInspector {
    public static final String SOURCE_SDCARD = "sdcard_override";
    public static final String SOURCE_HOST_CACHE = "host_cache";
    public static final String SOURCE_BUNDLED = "bundled_assets";
    public static final String SOURCE_NONE = "none";
    /** 模板里唯一需要读出来的条目。 */
    private static final String INIT_ENTRY = "assets/init.js";
    /** 单条 init.js 的内存上限（真实约 12KB）；与整包大小无关。 */
    private static final int MAX_INIT_BYTES = 4 * 1024 * 1024;
    /** 整包 SHA-256 的流式缓冲区。 */
    private static final int HASH_BUFFER_BYTES = 64 * 1024;
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
        String path = file.getPath();
        long size = file.length();
        String sha;
        try {
            sha = sha256File(file);
        } catch (Throwable error) {
            return new Info(false, source, path, size, "", "", false,
                    error.getClass().getSimpleName() + ": " + error.getMessage(), "读取模板失败");
        }
        String initSha = "";
        boolean fixed = false;
        String inspectError = "";
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry(INIT_ENTRY);
            if (entry != null) {
                byte[] init = readBounded(zip.getInputStream(entry), MAX_INIT_BYTES, INIT_ENTRY);
                initSha = sha256(init).substring(0, 16);
                fixed = new String(init, StandardCharsets.UTF_8).contains(REQUIRE_ROOT_MARKER);
            }
        } catch (Throwable error) {
            inspectError = error.getClass().getSimpleName() + ": " + error.getMessage();
        }
        return new Info(true, source, path, size, sha, initSha, fixed, inspectError,
                detailOf(fixed, inspectError));
    }

    public static Info inspect(byte[] bytes, String source, String path) {
        if (bytes == null) return new Info(false, source, path, 0, "", "", false, "", "模板字节为空");
        String sha = sha256(bytes);
        String initSha = "";
        boolean fixed = false;
        String inspectError = "";
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!INIT_ENTRY.equals(entry.getName())) continue;
                byte[] init = readBounded(zip, MAX_INIT_BYTES, INIT_ENTRY);
                initSha = sha256(init).substring(0, 16);
                fixed = new String(init, StandardCharsets.UTF_8).contains(REQUIRE_ROOT_MARKER);
                break;
            }
        } catch (Throwable error) {
            inspectError = error.getClass().getSimpleName() + ": " + error.getMessage();
        }
        return new Info(true, source, path, bytes.length, sha, initSha, fixed, inspectError,
                detailOf(fixed, inspectError));
    }

    private static String detailOf(boolean fixed, String inspectError) {
        if (fixed) return "含 " + REQUIRE_ROOT_MARKER;
        return inspectError.isEmpty() ? "未找到 assets/init.js 或缺少修复标记" : "读取 assets/init.js 失败";
    }

    /** 流式计算整包 SHA-256：88MiB 级模板也只占一个 64KiB 缓冲区。 */
    private static String sha256File(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            return "";
        }
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[HASH_BUFFER_BYTES];
            int read;
            while ((read = input.read(buffer)) > 0) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    /** 读出单个条目，超过 maxBytes 直接失败（不整包读盘/读内存）。 */
    private static byte[] readBounded(InputStream input, int maxBytes, String label) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) > 0) {
            if (out.size() + read > maxBytes) throw new IOException(label + " 超过 " + maxBytes + " 字节");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(bytes));
        } catch (Exception error) {
            return "";
        }
    }

    private static String hex(byte[] hash) {
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte value : hash) builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return builder.toString();
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
