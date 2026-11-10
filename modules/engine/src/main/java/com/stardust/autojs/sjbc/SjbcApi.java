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

    /** 脚本入口：跑 entryIndex=0。 */
    public Map<String, Object> run(String path) {
        return runEntry(path, 0);
    }

    /** 脚本入口：指定 entryIndex（entries[entryIndex] 是起始 PC）。 */
    public Map<String, Object> runEntry(String path, int entryIndex) {
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