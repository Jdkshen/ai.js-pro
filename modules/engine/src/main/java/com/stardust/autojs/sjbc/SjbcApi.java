package com.stardust.autojs.sjbc;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * task-79 片3：脚本可见的**最小只读入口** `sjbc`。
 *
 * <p>用法（脚本侧）：
 * <pre>
 * var r = sjbc.run('assets/sjbc/hello.sjbc');   // 或 'sjbc/hello.sjbc'、工程内 'modules/x.sjbc'（相对工作目录）
 * r.prints   // ["fib(10)=55", …]
 * r.steps    // 指令派发次数（与 pc-tools/vm.js 同口径）
 * r.entries  // 每个函数的入口 PC
 * r.certShaUsed // 生效的签名证书 SHA-256（说明是哪把证书解的）
 * var r2 = sjbc.runEntry(path, 1);              // 指定 entryIndex（默认 0）
 * </pre>
 *
 * <p>**只读**：不改引擎既有行为、不写文件、不注册全局以外的任何东西；失败抛具名 {@link SjbcException}。
 * 主密钥来自**本包签名证书 SHA-256**（{@link SjbcKeySource}），与 manifest 冲突时以签名证书为准并打 warning。
 */
public final class SjbcApi {

    private static final String TAG = "SjbcApi";

    private final Context context;
    private final String workingDirectory;

    public SjbcApi(Context context, String workingDirectory) {
        this.context = context;
        this.workingDirectory = workingDirectory;
    }

    /** 脚本入口：跑 entryIndex=0；返回**脚本友好对象**（Rhino 里可直接 `r.steps`/`r.prints`）。 */
    public Object run(String path) {
        return runEntry(path, 0);
    }

    /** 脚本入口：指定 entryIndex（entries[entryIndex] 是起始 PC）；返回**脚本友好对象**。 */
    public Object runEntry(String path, int entryIndex) {
        return toScriptValue(runEntryMap(path, entryIndex));
    }

    /**
     * task-83：把结果 Map 变成**脚本能按属性访问**的对象。
     *
     * <p>真机教训（Lead 实测）：直接返回 Java `Map` 时脚本里 `r.steps` 是 `undefined`（Rhino 会把 Map 当
     * Java 对象，属性访问落到 `getSteps()` 上）⇒ 这里在**Rhino 上下文存在**时转成 {@code NativeObject}，
     * 且列表字段（`prints`/`entries`）转成 {@code NativeArray}——**真 JS 数组**，可以 `forEach`。
     *
     * <p>没有 Rhino 上下文（QuickJS/Node24 或纯 JVM）时**原样返回 Map**，由各自宿主自己转换（不写第二份实现）。
     */
    public static Object toScriptValue(Map<String, Object> map) {
        try {
            org.mozilla.javascript.Context context = org.mozilla.javascript.Context.getCurrentContext();
            if (context == null) {
                return map;
            }
            // 必须带**标准原型**创建：无参 new NativeObject() 的原型是 null，Rhino 在字符串转换/数组方法上会报
            // 「Cannot find default value for object」（task-52 同一类坑），所以这里用 newObject/newArray + 一个标准 scope。
            org.mozilla.javascript.Scriptable scope = context.initStandardObjects();
            org.mozilla.javascript.Scriptable object = context.newObject(scope);
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof List) {
                    object.put(entry.getKey(), object,
                            context.newArray(scope, ((List<?>) value).toArray()));
                } else {
                    object.put(entry.getKey(), object, value);
                }
            }
            return object;
        } catch (Throwable error) {
            // Rhino 不在类路径（纯 QuickJS/Node24 进程）或转换失败 ⇒ 退回 Map，绝不因此让入口不可用。
            Log.w(TAG, "结果转脚本对象失败，退回 Map：" + error);
            return map;
        }
    }

    /**
     * QuickJS/Node24 等**没有 Rhino 对象模型**的宿主用：把结果转成 JSON 字符串（宿主侧 `JSON.parse` 即成真 JS 对象/数组）。
     */
    public static String toJson(Map<String, Object> map) {
        return new org.json.JSONObject(map).toString();
    }

    /** 脚本入口（JSON 形态）：QuickJS 侧模块用 `JSON.parse(hostCall(...))` 得到 `r.steps`/`r.prints` 可直读的对象。 */
    public String runJson(String path) {
        return toJson(runEntryMap(path, 0));
    }

    /** 脚本入口（JSON 形态，指定 entryIndex）。 */
    public String runEntryJson(String path, int entryIndex) {
        return toJson(runEntryMap(path, entryIndex));
    }

    /** 内部/单测用：返回 Map（脚本请用 {@link #run}）。 */
    public Map<String, Object> runEntryMap(String path, int entryIndex) {
        if (context == null) {
            throw new SjbcException("sjbc.run 需要 Context（宿主未注入）");
        }
        String certSha = SjbcKeySource.resolveCertSha256Hex(context);
        byte[] master = SjbcV5Container.masterFromCertSha256Hex(certSha);
        byte[] blob = readBlob(path);
        Map<String, Object> result = runBlob(blob, master, entryIndex);
        result.put("path", path);
        result.put("certShaUsed", certSha);
        result.put("certSource", "package-signing-certificate");
        Log.i(TAG, "sjbc.run ok path=" + path + " steps=" + result.get("steps")
                + " prints=" + ((List<?>) result.get("prints")).size() + " cert=" + certSha.substring(0, 16) + "…");
        return result;
    }

    /** 直接跑一段容器字节（JVM 可测；不含 Android 依赖）。 */
    public static Map<String, Object> runBlob(byte[] blob, byte[] master, int entryIndex) {
        SjbcV5Container.Opened opened = SjbcV5Container.open(blob, master);
        List<Object> prints = new ArrayList<>();
        SjbcVm.Result result = SjbcVm.run(opened.code, opened.pool, SjbcVm.defaultHost(prints),
                opened.entries, entryIndex);
        Map<String, Object> map = new LinkedHashMap<>();
        List<String> display = new ArrayList<>();
        for (Object value : prints) {
            display.add(SjbcValues.toDisplayString(value));
        }
        List<Object> entries = new ArrayList<>();
        for (int entry : opened.entries) {
            entries.add(Double.valueOf(entry));
        }
        map.put("ok", result.uncaught == null);
        map.put("prints", display);
        map.put("steps", Double.valueOf(result.steps));
        map.put("entries", entries);
        map.put("codeBytes", Double.valueOf(opened.code.length));
        map.put("poolSize", Double.valueOf(opened.pool.size()));
        map.put("entryIndex", Double.valueOf(entryIndex));
        if (result.uncaught != null) {
            map.put("uncaught", SjbcValues.toDisplayString(result.uncaught));
        }
        return map;
    }

    /** 读容器：`assets/...`、`sjbc/...`（先 assets 后文件）、绝对/相对（相对工作目录）路径。 */
    private byte[] readBlob(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new SjbcException("sjbc.run 需要非空路径");
        }
        String normalized = path.trim().replace('\\', '/');
        if (normalized.startsWith("assets/")) {
            return readAsset(normalized.substring("assets/".length()));
        }
        File file = new File(normalized);
        if (!file.isAbsolute() && workingDirectory != null && !workingDirectory.isEmpty()) {
            file = new File(workingDirectory, normalized);
        }
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                return readAll(in);
            } catch (Exception e) {
                throw new SjbcException("读 SJBC 文件失败：" + file, e);
            }
        }
        // 回退：当成 assets 里的相对路径（工程内常见写法 sjbc/xxx.sjbc）
        return readAsset(normalized);
    }

    private byte[] readAsset(String assetPath) {
        if (context == null) {
            throw new SjbcException("读 assets/" + assetPath + " 需要 Context");
        }
        try (InputStream in = context.getAssets().open(assetPath)) {
            return readAll(in);
        } catch (Exception e) {
            throw new SjbcException("读 assets/" + assetPath + " 失败（路径不对或未打进包）", e);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}