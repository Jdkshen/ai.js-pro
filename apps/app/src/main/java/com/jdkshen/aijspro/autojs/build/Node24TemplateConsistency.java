package com.jdkshen.aijspro.autojs.build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Node 运行时与打包模板的一致性核对（模板版本漂移检测）。
 *
 * <h2>要解决的问题</h2>
 *
 * <p>打包模板 {@code template.apk} 有多条来路，用户/自动化选到哪条就会用到哪条：
 * <ol>
 *   <li>{@code /sdcard/template.apk}（手动覆盖，优先级最高）；</li>
 *   <li>宿主私有缓存里从打包插件落地的副本；</li>
 *   <li>主程序自带的 {@code assets/template.apk}。</li>
 * </ol>
 * 这些副本不保证同一版：仓库里 {@code apps/app/src/main/assets/template.apk}（2026-10-30）
 * 与 {@code plugins/apkbuilder-plugin/src/main/assets/template.apk}（2026-10-29）自带的
 * {@code assets/source-engine-upgrade/node24/arm64-v8a/runtime.json} 哈希就完全不同
 * （{@code libnode.so} 一个是 {@code d2165091…}、一个是 {@code 50cfa7b0…}），
 * 自带 Node 模块集合也不一样。选到旧模板的用户会拿到「运行库与脚本互不匹配」的产物。
 *
 * <p>打包时 {@link Node24PackagingAssets#copyFromHost} 会用宿主 APK 里的 Node 运行资产
 * 覆盖模板里的同名文件，所以**内容**最终是对的；但如果拷贝失败、被裁剪，或模板里的
 * {@code runtime.json} 是旧版（记录旧哈希），产物自检就会与运行库对不上。
 *
 * <h2>判定规则</h2>
 *
 * <ul>
 *   <li><b>硬失败</b>：工作区缺运行资产、或运行资产实际 SHA-256 与工作区
 *       {@code runtime.json} 声明不一致。此时产物必然对不上，宁可打包失败。</li>
 *   <li><b>告警</b>：模板自带的 Node 运行哈希与宿主不是同一版（版本漂移）。这次产物仍以
 *       宿主运行资产为准，所以只记日志，供排查「换模板」。</li>
 * </ul>
 *
 * <p>本类只依赖 JVM 与 {@code org.json}，可被 :app 的 JVM 单测直接覆盖。
 */
final class Node24TemplateConsistency {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Node 运行资产在 APK 里的前缀（与 {@link Node24PackagingAssets} 保持一致）。 */
    static final String RUNTIME_PREFIX = "assets/source-engine-upgrade/node24/arm64-v8a/";

    /** 运行时自检清单：记录每个 ABI 运行库的 SHA-256。 */
    static final String RUNTIME_MANIFEST = RUNTIME_PREFIX + "runtime.json";

    /**
     * Node 工程必需的运行资产。
     *
     * <p>{@link Node24PackagingAssets} 的 {@code REQUIRED} 还要求 {@code libc++_shared.so}：
     * 那是按「宿主 APK 是自洽的完整包」写的断言，但仓库里
     * {@code apps/app/src/main/assets/template.apk} 这份瘦身模板的 ABI 目录下只有 bridge，
     * 并没有 libc++。这里用不带 libc++ 的最小集合做**运行必需**判定，避免把「模板本来就
     * 瘦身」误报成缺件。
     */
    static final String[] REQUIRED_NODE_FILES = {
            "assets/node24/prelude.js",
            "assets/node24/ui.js",
            RUNTIME_PREFIX + "runtime.json",
            RUNTIME_PREFIX + "libnode.so",
            RUNTIME_PREFIX + "libajsnode24bridge.so"
    };

    private Node24TemplateConsistency() {
    }

    // ------------------------------------------------------------------ 打包时核对

    /**
     * 严格核对打包工作区里的 Node 运行资产：缺件或与清单不符直接失败。
     *
     * @param hostApk          宿主 APK（本次产物的运行资产来源）
     * @param workspace        已经解包并补齐过 Node 资产的工作区
     * @param templateDeclared 补齐之前从模板读到的运行时哈希（可为空 Map）
     */
    static void verifyWorkspace(File hostApk, File workspace, Map<String, String> templateDeclared)
            throws IOException {
        verifyWorkspace(hostApk, workspace, templateDeclared, true);
    }

    /**
     * 核对工作区里的 Node 运行资产。
     *
     * @param strict {@code true}：缺件/内容不符是打包错误（入口确实是 Node 脚本，产物一定
     *               跑不起来）；{@code false}：只记日志（工程只是开了 nodejs 特性位，
     *               入口还是 Rhino/QuickJS，按旧行为不阻断打包）
     */
    static void verifyWorkspace(File hostApk, File workspace, Map<String, String> templateDeclared,
            boolean strict) throws IOException {
        if (workspace == null || !workspace.isDirectory()) {
            if (strict) {
                throw new IOException("Node 运行资产核对失败：工作区不存在 " + workspace);
            }
            return;
        }
        failOnProblems(runtimeProblems(workspace), strict);
        Map<String, String> hostDeclared = readRuntimeHashes(hostApk);
        if (templateDeclared != null && !templateDeclared.isEmpty() && !hostDeclared.isEmpty()
                && !sameHashes(templateDeclared, hostDeclared)) {
            // 只记日志：这次产物用的是宿主运行资产，仍然自洽；提示换模板是为了避免在没有
            // 宿主可补的路径上直接用这份模板。
            android.util.Log.w("C-PKG", "C-PKG: node24 template runtime drift detected."
                    + " template=" + shortJson(templateDeclared)
                    + " host=" + shortJson(hostDeclared)
                    + " (本次产物已用宿主运行资产覆盖；建议更新打包插件模板)");
        }
    }

    /**
     * 按 strict 处理问题清单：{@code true} 抛打包错误；{@code false} 只写日志。
     *
     * <p>独立成一个方法，一方面让「什么算打包失败」收在一处，另一方面让 {@code strict=false}
     * 的判定逻辑可被单测覆盖（告警本身走 {@code android.util.Log}，JVM 单测里无法断言）。
     */
    static void failOnProblems(List<String> problems, boolean strict) throws IOException {
        if (problems == null || problems.isEmpty()) {
            return;
        }
        if (strict) {
            throw new IOException("Node 运行资产与 " + RUNTIME_MANIFEST + " 不一致："
                    + problems + "。请更新打包插件/模板后重试；宿主 APK 被裁剪过也会出现这一条。");
        }
        android.util.Log.w("C-PKG", "C-PKG: node24 runtime assets incomplete but entry is not"
                + " a Node script; packaging continues. problems=" + problems);
    }

    /** 工作区里 Node 运行资产的问题清单（空 = 一致）；判定见 {@link #failOnProblems}。 */
    static List<String> runtimeProblems(File workspace) throws IOException {
        List<String> problems = new ArrayList<>();
        for (String relative : REQUIRED_NODE_FILES) {
            File file = new File(workspace, relative);
            if (!file.isFile() || file.length() == 0) {
                problems.add(relative + "（缺失）");
            }
        }
        Map<String, String> declared = readRuntimeHashes(new File(workspace, RUNTIME_MANIFEST));
        if (declared.isEmpty()) {
            problems.add(RUNTIME_MANIFEST + "（清单为空或无法解析）");
        }
        for (Map.Entry<String, String> entry : declared.entrySet()) {
            File file = new File(workspace, RUNTIME_PREFIX + entry.getKey());
            if (!file.isFile()) {
                // 清单声明了但文件不在 —— 例外是 libc++_shared.so：仓库里的瘦身模板本来
                // 就不带它（缺失由上面的 REQUIRED_NODE_FILES 单独判定），旧模板也常见。
                if (!"libc++_shared.so".equals(entry.getKey())) {
                    problems.add(entry.getKey() + "（清单声明了但文件缺失）");
                }
                continue;
            }
            String actual = sha256(file);
            if (!actual.equalsIgnoreCase(entry.getValue())) {
                problems.add(entry.getKey() + "（清单=" + shorten(entry.getValue())
                        + " 实际=" + shorten(actual) + "）");
            }
        }
        return problems;
    }

    /** 补齐之前先记下模板里的运行时哈希，供 {@link #verifyWorkspace} 判断漂移。 */
    static Map<String, String> readWorkspaceRuntimeHashes(File workspace) throws IOException {
        if (workspace == null || !workspace.isDirectory()) {
            return Collections.emptyMap();
        }
        return readRuntimeHashes(new File(workspace, RUNTIME_MANIFEST));
    }

    /** 两组运行时哈希是否是同一版（键集合相同且逐项相等）；任一为空视为无法判定，返回 true。 */
    static boolean sameHashes(Map<String, String> left, Map<String, String> right) {
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

    /**
     * 读 APK 里的运行时清单；传 {@code .json} 文件时按纯 JSON 读。
     *
     * <p>返回空 Map 表示没有可用的清单（旧模板/非 Node 模板），调用方按「不判定」处理。
     */
    static Map<String, String> readRuntimeHashes(File apkOrManifest) throws IOException {
        if (apkOrManifest == null || !apkOrManifest.isFile()) {
            return Collections.emptyMap();
        }
        if (apkOrManifest.getName().toLowerCase(Locale.ROOT).endsWith(".json")) {
            return parseHashes(readText(apkOrManifest));
        }
        try (ZipFile zip = new ZipFile(apkOrManifest)) {
            ZipEntry entry = zip.getEntry(RUNTIME_MANIFEST);
            if (entry == null || entry.isDirectory()) {
                return Collections.emptyMap();
            }
            try (InputStream input = zip.getInputStream(entry)) {
                return parseHashes(readText(input));
            }
        }
    }

    /** 校验模板：必须带 Node 运行时清单，且清单里的每个运行文件与运行必需文件都在模板里。 */
    static void verifyTemplate(File templateApk) throws IOException {
        if (templateApk == null || !templateApk.isFile()) {
            throw new IOException("打包模板不存在：" + templateApk);
        }
        Map<String, String> declared = readRuntimeHashes(templateApk);
        if (declared.isEmpty()) {
            throw new IOException("模板缺少 Node 运行时清单 " + RUNTIME_MANIFEST + "："
                    + templateApk + "（请重建 :inrt 模板后再打包插件）");
        }
        Set<String> entries = listEntries(templateApk);
        List<String> missing = new ArrayList<>();
        for (String name : declared.keySet()) {
            String path = RUNTIME_PREFIX + name;
            if (!entries.contains(path)) {
                missing.add(path);
            }
        }
        for (String required : REQUIRED_NODE_FILES) {
            if (!entries.contains(required)) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            throw new IOException("模板缺少 Node 运行资产 " + missing + "：" + templateApk
                    + "（请重建 :inrt 模板后再打包插件）");
        }
    }

    /**
     * 核对已生成/已提交的模板清单与模板本体是否一致。
     *
     * <p>与 {@link #verifyTemplate} 的区别：这里校验的是**清单这份文件**有没有过期
     * （例如重建了模板却忘了刷新清单），是「漂移检测」里对清单自身的那一半。
     */
    static void verifyTemplateManifest(File templateApk, File manifestFile) throws IOException {
        if (manifestFile == null || !manifestFile.isFile()) {
            throw new IOException("模板清单不存在：" + manifestFile
                    + "（:inrt 构建模板时应生成 template-manifest.json）");
        }
        Map<String, String> template = readRuntimeHashes(templateApk);
        if (template.isEmpty()) {
            throw new IOException("模板缺少 " + RUNTIME_MANIFEST + "：" + templateApk);
        }
        Map<String, String> recorded;
        try {
            JSONObject json = new JSONObject(readText(manifestFile));
            recorded = parseHashes(json.optJSONObject("nodeRuntimeHashes") == null
                    ? "{}" : json.getJSONObject("nodeRuntimeHashes").toString());
        } catch (JSONException error) {
            throw new IOException("模板清单无法解析：" + manifestFile, error);
        }
        if (recorded.isEmpty()) {
            throw new IOException("模板清单没有 nodeRuntimeHashes：" + manifestFile);
        }
        if (!sameHashes(recorded, template)) {
            throw new IOException("模板清单已过期：清单=" + shortJson(recorded)
                    + " 模板=" + shortJson(template) + "。请重建 :inrt 模板并刷新 " + manifestFile);
        }
    }

    // ------------------------------------------------------------------ 构建期生成清单

    /**
     * 从模板 APK 生成 {@code template-manifest.json}（{@code :inrt:buildApkPlugin} 调用）。
     *
     * <p>生成而不是手写：手写的哈希一定会漂移，而这份清单正是用来发现漂移的。
     */
    static void writeManifest(File templateApk, String templateVersion, File outFile)
            throws IOException {
        Map<String, String> runtime = readRuntimeHashes(templateApk);
        if (runtime.isEmpty()) {
            throw new IOException("模板缺少 " + RUNTIME_MANIFEST + "，无法生成清单：" + templateApk);
        }
        Set<String> entries = listEntries(templateApk);
        JSONObject json = new JSONObject();
        try {
            json.put("manifestVersion", 1);
            json.put("templateVersion", templateVersion == null ? "" : templateVersion);
            json.put("templateBytes", templateApk.length());
            json.put("nodeRuntimeManifest", RUNTIME_MANIFEST);
            JSONObject hashes = new JSONObject();
            for (Map.Entry<String, String> entry : new TreeMap<>(runtime).entrySet()) {
                hashes.put(entry.getKey(), entry.getValue());
            }
            json.put("nodeRuntimeHashes", hashes);
            List<String> missing = new ArrayList<>();
            for (String required : REQUIRED_NODE_FILES) {
                if (!entries.contains(required)) {
                    missing.add(required);
                }
            }
            json.put("nodeRequiredFilesMissing", new org.json.JSONArray(missing));
        } catch (JSONException error) {
            throw new IOException("生成模板清单失败", error);
        }
        File parent = outFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建模板清单目录：" + parent);
        }
        String text;
        try {
            text = json.toString(2);
        } catch (JSONException error) {
            throw new IOException("模板清单序列化失败", error);
        }
        try (OutputStream output = new FileOutputStream(outFile)) {
            output.write(text.getBytes(UTF8));
        }
    }

    // ------------------------------------------------------------------ 内部

    private static Set<String> listEntries(File apk) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                names.add(entries.nextElement().getName());
            }
        }
        return names;
    }

    private static Map<String, String> parseHashes(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        if (text == null || text.trim().isEmpty()) {
            return result;
        }
        try {
            JSONObject json = new JSONObject(text);
            for (java.util.Iterator<String> it = json.keys(); it.hasNext(); ) {
                String key = it.next();
                Object value = json.opt(key);
                if (value instanceof String && isSha256((String) value)) {
                    result.put(key, ((String) value).toLowerCase(Locale.ROOT));
                }
            }
        } catch (JSONException ignored) {
            return result;
        }
        return result;
    }

    private static boolean isSha256(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 不可用", error);
        }
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xf, 16));
            out.append(Character.forDigit(b & 0xf, 16));
        }
        return out.toString();
    }

    private static String shorten(String hash) {
        return hash == null || hash.length() <= 12
                ? String.valueOf(hash) : hash.substring(0, 12) + "…";
    }

    private static String shortJson(Map<String, String> hashes) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : new TreeMap<>(hashes).entrySet()) {
            if (!first) {
                out.append(", ");
            }
            first = false;
            out.append(entry.getKey()).append('=').append(shorten(entry.getValue()));
        }
        return out.append('}').toString();
    }

    private static String readText(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            return readText(input);
        }
    }

    private static String readText(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = input.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), UTF8);
    }
}
