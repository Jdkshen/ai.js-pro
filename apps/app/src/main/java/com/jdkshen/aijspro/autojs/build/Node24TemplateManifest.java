package com.jdkshen.aijspro.autojs.build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 构建脚本侧的模板一致性核对（纯 JDK，Gradle/Groovy 里可直接编译调用）。
 *
 * <p>为什么与 {@link Node24TemplateConsistency} 分成两份：那一份跑在 App 运行时/单测里，
 * 用 {@code org.json} 与 {@code android.util.Log}；构建脚本只有 JDK，且
 * {@code GroovyClassLoader.parseClass} 是按 **Groovy** 语法解析的，Java 数组初始化式
 * （{@code String[] x = {...}}）会直接解析失败。所以这里刻意写成「Groovy 也能编译」的
 * 最小子集：只用简单语句、不用 Java 数组初始化式。
 *
 * <p>职责划分：本类负责「构建期把模板和清单对齐」（生成 + 过期检测 + 插件侧校验），
 * App 侧的 {@code Node24TemplateConsistency} 负责「打包时工作区里的运行资产与清单一致」。
 * 两边共用同一份 JSON 约定：{@code assets/source-engine-upgrade/node24/arm64-v8a/runtime.json}。
 */
public final class Node24TemplateManifest {

    /** 运行资产目录前缀（与 App 侧常量保持一致）。 */
    private static final String RUNTIME_PREFIX =
            "assets/source-engine-upgrade/node24/arm64-v8a/";

    /** Node 工程运行必需的资产（相对 APK 根）。 */
    public static List<String> requiredNodeFiles() {
        List<String> files = new ArrayList<String>();
        files.add("assets/node24/prelude.js");
        files.add("assets/node24/ui.js");
        files.add(RUNTIME_PREFIX + "runtime.json");
        files.add(RUNTIME_PREFIX + "libnode.so");
        files.add(RUNTIME_PREFIX + "libajsnode24bridge.so");
        return files;
    }

    private Node24TemplateManifest() {
    }

    // ------------------------------------------------------------------ 读取

    /** 列 APK 里的条目名。 */
    public static Set<String> listEntries(File apk) throws IOException {
        Set<String> names = new LinkedHashSet<String>();
        ZipFile zip = new ZipFile(apk);
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                names.add(entries.nextElement().getName());
            }
        } finally {
            zip.close();
        }
        return names;
    }

    /** 读 APK 里 Node 运行时清单（{@code runtime.json}）中的「文件名 → SHA-256」。 */
    public static Map<String, String> readRuntimeHashes(File apk) throws IOException {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (apk == null || !apk.isFile()) {
            return result;
        }
        ZipFile zip = new ZipFile(apk);
        try {
            ZipEntry entry = zip.getEntry(RUNTIME_PREFIX + "runtime.json");
            if (entry == null || entry.isDirectory()) {
                return result;
            }
            InputStream input = zip.getInputStream(entry);
            String text;
            try {
                text = readText(input);
            } finally {
                input.close();
            }
            return Node24TemplateManifest.parseHashObject(text);
        } finally {
            zip.close();
        }
    }

    // ------------------------------------------------------------------ 校验

    /** 模板必须带 Node 运行时清单，且清单里的运行文件与运行必需文件都在模板里。 */
    public static void verifyTemplate(File templateApk) throws IOException {
        if (templateApk == null || !templateApk.isFile()) {
            throw new IOException("打包模板不存在：" + templateApk);
        }
        Map<String, String> declared = Node24TemplateManifest.readRuntimeHashes(templateApk);
        if (declared.isEmpty()) {
            throw new IOException("模板缺少 Node 运行时清单 " + RUNTIME_PREFIX + "runtime.json："
                    + templateApk + "（请重建 :inrt 模板后再打包插件）");
        }
        Set<String> entries = Node24TemplateManifest.listEntries(templateApk);
        List<String> missing = new ArrayList<String>();
        for (String name : declared.keySet()) {
            String path = RUNTIME_PREFIX + name;
            if (!entries.contains(path)) {
                missing.add(path);
            }
        }
        for (String required : Node24TemplateManifest.requiredNodeFiles()) {
            if (!entries.contains(required)) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            throw new IOException("模板缺少 Node 运行资产 " + missing + "：" + templateApk
                    + "（请重建 :inrt 模板后再打包插件）");
        }
    }

    /** 清单必须与模板本体的运行时哈希一致（重建模板却忘了刷新清单时报错）。 */
    public static void verifyManifest(File templateApk, File manifestFile) throws IOException {
        if (manifestFile == null || !manifestFile.isFile()) {
            throw new IOException("模板清单不存在：" + manifestFile
                    + "（:inrt 构建模板时应生成 template-manifest.json）");
        }
        Map<String, String> template = Node24TemplateManifest.readRuntimeHashes(templateApk);
        if (template.isEmpty()) {
            throw new IOException("模板缺少 " + RUNTIME_PREFIX + "runtime.json：" + templateApk);
        }
        Map<String, String> recorded = Node24TemplateManifest.parseHashObject(
                Node24TemplateManifest.innerObject(Node24TemplateManifest.readText(manifestFile),
                        "nodeRuntimeHashes"));
        if (recorded.isEmpty()) {
            throw new IOException("模板清单没有可解析的 nodeRuntimeHashes：" + manifestFile);
        }
        if (!Node24TemplateManifest.sameHashes(recorded, template)) {
            throw new IOException("模板清单已过期：清单=" + recorded + " 模板=" + template
                    + "。请重建 :inrt 模板并刷新 " + manifestFile);
        }
    }

    // ------------------------------------------------------------------ 生成

    /** 从模板生成 {@code template-manifest.json}（:inrt 的 buildApkPlugin 调用）。 */
    public static void writeManifest(File templateApk, String templateVersion, File outFile)
            throws IOException {
        Map<String, String> runtime = Node24TemplateManifest.readRuntimeHashes(templateApk);
        if (runtime.isEmpty()) {
            throw new IOException("模板缺少 " + RUNTIME_PREFIX + "runtime.json，无法生成清单："
                    + templateApk);
        }
        Set<String> entries = Node24TemplateManifest.listEntries(templateApk);
        List<String> missing = new ArrayList<String>();
        for (String required : Node24TemplateManifest.requiredNodeFiles()) {
            if (!entries.contains(required)) {
                missing.add(required);
            }
        }
        StringBuilder text = new StringBuilder();
        text.append("{\n");
        text.append("  \"manifestVersion\": 1,\n");
        text.append("  \"templateVersion\": \"")
                .append(Node24TemplateManifest.escape(templateVersion)).append("\",\n");
        text.append("  \"templateBytes\": ").append(templateApk.length()).append(",\n");
        text.append("  \"nodeRuntimeManifest\": \"").append(RUNTIME_PREFIX)
                .append("runtime.json\",\n");
        text.append("  \"nodeRuntimeHashes\": {\n");
        Map<String, String> sorted = new TreeMap<String, String>(runtime);
        int index = 0;
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            text.append("    \"").append(Node24TemplateManifest.escape(entry.getKey()))
                    .append("\": \"")
                    .append(Node24TemplateManifest.escape(entry.getValue())).append("\"");
            text.append(index < sorted.size() - 1 ? "," : "").append("\n");
            index++;
        }
        text.append("  },\n");
        text.append("  \"nodeRequiredFilesMissing\": [");
        for (int i = 0; i < missing.size(); i++) {
            text.append(i > 0 ? ", " : "").append("\"")
                    .append(Node24TemplateManifest.escape(missing.get(i))).append("\"");
        }
        text.append("]\n");
        text.append("}\n");

        File parent = outFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建模板清单目录：" + parent);
        }
        OutputStream output = new FileOutputStream(outFile);
        try {
            output.write(text.toString().getBytes(StandardCharsets.UTF_8));
        } finally {
            output.close();
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 「键 → 64 位十六进制」的扁平 JSON 对象；其它字段一律忽略。 */
    public static Map<String, String> parseHashObject(String text) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (text == null) {
            return result;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"([^\"]+)\"\\s*:\\s*\"([0-9a-fA-F]{64})\"")
                .matcher(text);
        while (matcher.find()) {
            result.put(matcher.group(1), matcher.group(2).toLowerCase(Locale.ROOT));
        }
        return result;
    }

    /** 取一个嵌套对象的文本片段（只按花括号配对找，够这份固定清单用）。 */
    public static String innerObject(String text, String key) {
        if (text == null) {
            return "";
        }
        int keyAt = text.indexOf("\"" + key + "\"");
        if (keyAt < 0) {
            return "";
        }
        int open = text.indexOf('{', keyAt);
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(open, i + 1);
                }
            }
        }
        return text.substring(open);
    }

    public static boolean sameHashes(Map<String, String> left, Map<String, String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return true;
        }
        if (!left.keySet().equals(right.keySet())) {
            return false;
        }
        for (Map.Entry<String, String> entry : left.entrySet()) {
            String other = right.get(entry.getKey());
            if (other == null || !other.equalsIgnoreCase(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 读文本文件（Groovy 侧按方法签名派发，所以 File 与 InputStream 各留一个重载）。 */
    public static String readText(File file) throws IOException {
        InputStream input = new FileInputStream(file);
        try {
            return Node24TemplateManifest.readText(input);
        } finally {
            input.close();
        }
    }

    public static String readText(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192]; // Groovy 可解析的显式构造（不要写成 {..} 初始化式）
        int read;
        while ((read = input.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    /** SHA-256（十六进制小写）；预留给需要逐文件校验的构建脚本。 */
    public static String sha256(File file) throws IOException {
        MessageDigest digest = Node24TemplateManifest.messageDigest();
        InputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        } finally {
            input.close();
        }
        return Node24TemplateManifest.toHex(digest.digest());
    }

    /**
     * 取 SHA-256 摘要器。
     *
     * <p>刻意不用 try-with-resources 之外的写法：Gradle 的 {@code GroovyClassLoader}
     * 是按 Groovy 语法解析 Java 源码的，Java 数组初始化式（{@code byte[] x = {...}}）
     * 会解析失败，所以这里也改成逐步赋值。
     */
    public static MessageDigest messageDigest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 不可用", error);
        }
    }

    public static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xf, 16));
            out.append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }
}
