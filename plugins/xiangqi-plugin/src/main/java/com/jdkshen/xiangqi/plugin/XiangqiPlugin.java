package com.jdkshen.xiangqi.plugin;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.regex.Pattern;

/**
 * 插件 SDK 实例：宿主把它交给 {@code assets/scripts/index.js}，脚本侧通过
 * {@code plugins.load("com.jdkshen.xiangqi.plugin").unwrap()} 拿到本对象。
 *
 * <p>这里只做「插件必须有原生/Java 才能做」的三件事，其余全是 JS：
 * <ol>
 *     <li>解析（必要时解包）NNUE 权重路径；</li>
 *     <li>把 UCI 会话的启停转发给 {@link NativeEngine}；</li>
 *     <li>在进 native 之前把 FEN / position 命令校验掉
 *         （非法 FEN 会让原生引擎解析非法局面并把整个进程静默带走，宿主侧同样在门口拦）。</li>
 * </ol>
 *
 * <p>返回值一律是 JSON 字符串或原生字符串——Rhino 与 QuickJS 两边的 Java 互操作都能直接过，
 * 不需要任何引擎特有的对象桥。
 */
public final class XiangqiPlugin {

    /** 插件内脚本目录（宿主会整棵拷到 cache/plugin-scripts/&lt;包名&gt;）。 */
    static final String ASSETS_SCRIPT_DIR = "scripts";

    private static final String NNUE_NAME = "pikafish.nnue";
    /** 我们自己解包出来的副本名（与宿主那份区分，宿主存在时直接用宿主的，不再拷 50MB）。 */
    private static final String PLUGIN_NNUE_NAME = "xiangqi-plugin-pikafish.nnue";
    /** 权重至少 50MB；小于这个数的一律当成坏文件，避免让引擎加载到半个文件。 */
    private static final long NNUE_MIN_BYTES = 20_000_000L;

    /** `$pikafish.analyze` 的 FEN 校验：必须是完整六段（棋盘段 + 走棋方 + "- -" + 两个计数）。 */
    private static final Pattern FEN = Pattern.compile(
            "^(?:[rnbakcpRNBAKCP1-9]+/){9}[rnbakcpRNBAKCP1-9]+ [wb] - - [0-9]+ [1-9][0-9]*$");

    /** `analyzePosition` 的命令校验：棋盘段 + 走棋方 + 1..512 步 ICCS 着法。 */
    private static final Pattern POSITION = Pattern.compile(
            "^position fen (?:[rnbakcpRNBAKCP1-9]+/){9}[rnbakcpRNBAKCP1-9]+ [wb] - - [0-9]+ [1-9][0-9]*"
                    + "(?: moves(?: [a-i][0-9][a-i][0-9]){1,512})?$");

    private final Context mHostContext;
    private final Context mPluginContext;
    private final Object mRuntime;
    private final Object mScope;
    /** 最近一次权重解析失败的原因（成功时是空串）。 */
    private String mNnueError = "";

    XiangqiPlugin(Context hostContext, Context pluginContext, Object runtime, Object scope) {
        mHostContext = hostContext;
        mPluginContext = pluginContext;
        mRuntime = runtime;
        mScope = scope;
    }

    // ---------------------------------------------------------------- 插件对象成员

    /** 宿主侧 `plugin.getVersion()`（部分宿主只认插件实例上的版本号）。 */
    public String getVersion() {
        return versionName();
    }

    /** 宿主侧 `plugin.getAssetsScriptDir()`：脚本目录在 APK 里的相对路径。 */
    public String getAssetsScriptDir() {
        return ASSETS_SCRIPT_DIR;
    }

    /**
     * 插件与本机引擎的能力清单（脚本侧用来给用户报「为什么不能用」，不参与业务判断）。
     *
     * @return JSON：{versionName, versionCode, assetsScriptDir, scriptDir, filesDir,
     * engineAvailable, engineLoadError, nnue}
     */
    public String capabilities() throws JSONException {
        JSONObject result = new JSONObject();
        result.put("versionName", versionName());
        result.put("versionCode", versionCode());
        result.put("assetsScriptDir", ASSETS_SCRIPT_DIR);
        result.put("scriptDir", scriptDir());
        result.put("engineAvailable", NativeEngine.isAvailable());
        result.put("engineLoadError", NativeEngine.loadError());
        result.put("nnue", resolveNnue(null));
        result.put("nnueError", mNnueError);
        result.put("uci", new JSONObject(uciInfo()));
        // 宿主把 runtime/scope 传进来没有：官方 SDK 的插件靠它做更深桥接，这里只作为兼容性事实记录。
        result.put("hostRuntime", mRuntime == null ? "" : mRuntime.getClass().getName());
        result.put("hostScope", mScope == null ? "" : mScope.getClass().getName());
        return result.toString();
    }

    // ---------------------------------------------------------------- 原生引擎

    /** `$pikafish.start` 语义：启动（或复用）UCI 会话，必要时在两个搜索之间重设选项。 */
    public String engineStart(String evalFile, int threads, int hashMb, int contempt) throws JSONException {
        JSONObject result = new JSONObject();
        if (!NativeEngine.isAvailable()) {
            result.put("ok", false);
            result.put("error", "插件的原生引擎不可用（" + NativeEngine.loadError() + "）；目前只随包 arm64-v8a");
            return result.toString();
        }
        String nnue = resolveNnue(evalFile);
        if (nnue.isEmpty()) {
            result.put("ok", false);
            result.put("error", "找不到 " + NNUE_NAME + "（" + nnueError() +
                    "）；可以用 engineStart({evalFile:'/绝对路径'}) 指定");
            return result.toString();
        }
        int clamped = Math.max(-200, Math.min(200, contempt));
        boolean ok = NativeEngine.nativeStart(nnue, threads, hashMb, clamped);
        result.put("ok", ok);
        result.put("running", NativeEngine.nativeIsRunning());
        result.put("evalFile", nnue);
        result.put("threads", NativeEngine.nativeConfiguredThreads());
        result.put("hashMb", hashMb);
        result.put("contempt", clamped);
        result.put("options", options());
        if (!ok) {
            result.put("error", "原生引擎启动失败（uciok/readyok 阶段超时；权重可读吗：" + nnue + "）");
        }
        return result.toString();
    }

    /**
     * @param input      非 withHistory 时是 FEN，withHistory 时是 `position fen ... moves ...`
     * @param withHistory 走棋谱历史
     * @return 原生输出（"info ..." 行 + "bestmove ..." 行）；失败时 "error: ..."；解析在上层 JS
     */
    public String engineAnalyze(String input, int movetimeMs, int timeoutMs, int multiPv, int depth,
                                boolean withHistory) {
        if (!NativeEngine.isAvailable()) {
            return "error: 插件的原生引擎不可用（" + NativeEngine.loadError() + "）";
        }
        String value = input == null ? "" : input.trim();
        if (value.isEmpty() || value.length() > 4096) {
            return "error: 无效的引擎输入";
        }
        if (withHistory ? !isValidPositionCommand(value) : !isValidFen(value)) {
            return "error: " + (withHistory
                    ? "无效的 position 命令（应为 position fen <FEN> moves <着法...>）"
                    : "无效 FEN：缺 走棋方/回合 字段（应带「 w - - 0 1」）");
        }
        int pv = Math.max(1, Math.min(8, multiPv));
        int searchDepth = Math.max(0, Math.min(40, depth));
        String raw = withHistory
                ? NativeEngine.nativeAnalyzePosition(value, movetimeMs, timeoutMs, pv, searchDepth)
                : NativeEngine.nativeAnalyze(value, movetimeMs, timeoutMs, pv, searchDepth);
        return raw == null ? "" : raw;
    }

    /** 启动时抓到的 UCI 选项行（自检用）。 */
    public String engineOptions() throws JSONException {
        JSONArray array = options();
        return array.toString();
    }

    public int engineConfiguredThreads() {
        return NativeEngine.isAvailable() ? NativeEngine.nativeConfiguredThreads() : 0;
    }

    public boolean engineRunning() {
        return NativeEngine.isAvailable() && NativeEngine.nativeIsRunning();
    }

    /** 打断正在跑的搜索（analyze 带着 bestmove 正常收尾）。 */
    public void engineStop() {
        if (NativeEngine.isAvailable()) {
            NativeEngine.nativeStop();
        }
    }

    /** 退出引擎线程（幂等；下次 start 会重新拉起）。 */
    public void engineQuit() {
        if (NativeEngine.isAvailable()) {
            NativeEngine.nativeQuit();
        }
    }

    /** 原生库是否加载成功（与宿主 `$pikafish.available()` 同义）。 */
    public boolean engineAvailable() {
        return NativeEngine.isAvailable();
    }

    /** 加载失败原因（成功时是空串）。 */
    public String engineLoadError() {
        return NativeEngine.loadError();
    }

    /** 解析（必要时解包）后的 NNUE 权重绝对路径；空串 = 找不到。 */
    public String nnuePath() {
        return resolveNnue(null);
    }

    /** Node may spawn this installed PIE without a dependency on host JNI APIs. */
    public String uciInfo() throws JSONException {
        JSONObject result = new JSONObject();
        String directory = mPluginContext.getApplicationInfo().nativeLibraryDir;
        File compatible = directory == null ? null : new File(directory, "libxiangqi_uci.so");
        File optimized = directory == null ? null : new File(directory, "libxiangqi_uci_dotprod.so");
        boolean dotprod = false;
        try { dotprod = NativeEngine.isAvailable() && NativeEngine.nativeSupportsDotprod(); }
        catch (Throwable ignored) { /* An older loaded JNI library safely uses baseline. */ }
        boolean useOptimized = dotprod && optimized != null && optimized.isFile() && optimized.canExecute();
        File binary = useOptimized ? optimized : compatible;
        String nnue = resolveNnue(null);
        boolean executable = binary != null && binary.isFile() && binary.canExecute();
        result.put("ok", executable && !nnue.isEmpty());
        result.put("apiVersion", 1);
        result.put("protocol", "uci");
        result.put("ponder", true);
        result.put("variant", useOptimized ? "armv8-dotprod" : "armv8-neon");
        result.put("dotprodSupported", dotprod);
        result.put("compatibleBinary", compatible == null ? "" : compatible.getAbsolutePath());
        result.put("binary", binary == null ? "" : binary.getAbsolutePath());
        result.put("evalFile", nnue);
        if (!executable) {
            result.put("error", "插件预算引擎没有安装为可执行文件（需 arm64-v8a）：" + binary);
        } else if (nnue.isEmpty()) {
            result.put("error", "找不到预算引擎权重：" + nnueError());
        }
        return result.toString();
    }

    /**
     * 权重路径：显式路径优先，其次宿主已解出的同一份 / 我们解包出来的副本 / 常见的手工放置位置，
     * 最后从 APK 资产解包。
     *
     * @return 绝对路径；找不到返回空串（原因见 {@link #nnueError()}）
     */
    public String resolveNnue(String evalFile) {
        mNnueError = "";
        if (evalFile != null && !evalFile.trim().isEmpty()) {
            File file = new File(evalFile.trim());
            if (isUsableNnue(file)) {
                return file.getAbsolutePath();
            }
            mNnueError = "指定的 evalFile 不是可用的权重文件：" + evalFile;
            return "";
        }
        for (File candidate : defaultNnueCandidates()) {
            if (isUsableNnue(candidate)) {
                return candidate.getAbsolutePath();
            }
        }
        return extractBundledNnue();
    }

    /** 最近一次权重解析失败的原因（成功时是空串）；面板/自检用。 */
    public String nnueError() {
        return mNnueError;
    }

    // ---------------------------------------------------------------- 校验（纯函数，单测直接调）

    /** FEN 是否可进原生引擎（缺 走棋方/回合 字段会让 native 静默带走进程）。 */
    static boolean isValidFen(String fen) {
        return fen != null && FEN.matcher(fen).matches();
    }

    /** `position fen ... moves ...` 命令是否可进原生引擎。 */
    static boolean isValidPositionCommand(String command) {
        return command != null && POSITION.matcher(command).matches();
    }

    // ---------------------------------------------------------------- 内部

    private JSONArray options() {
        JSONArray array = new JSONArray();
        try {
            String raw = NativeEngine.isAvailable() ? NativeEngine.nativeOptions() : null;
            if (raw != null) {
                for (String line : raw.split("\n")) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        array.put(trimmed);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 会话未启动 / 旧 .so 没有这个导出：给空清单即可，不能打断 start 的结果
        }
        return array;
    }

    private boolean isUsableNnue(File file) {
        return file != null && file.isFile() && file.length() > NNUE_MIN_BYTES;
    }

    /**
     * 权重候选（都是宿主进程能读的路径）：
     * 插件代码跑在宿主进程里、用的是宿主的 uid，**写不了插件包自己的私有目录**，
     * 所以副本只能落在宿主的 files/external 目录，或者用户手工放的公共目录。
     */
    private File[] defaultNnueCandidates() {
        return new File[]{
                // 宿主自己那份（AI.js Pro 随包的 $pikafish 会解包到这里）：同进程、同一份权重
                fileIn(mHostContext.getFilesDir(), NNUE_NAME),
                fileIn(mHostContext.getFilesDir(), PLUGIN_NNUE_NAME),
                fileIn(mHostContext.getExternalFilesDir(null), PLUGIN_NNUE_NAME),
                new File("/sdcard/xiangqi-p4/" + NNUE_NAME)
        };
    }

    private File fileIn(File directory, String name) {
        return directory == null ? null : new File(directory, name);
    }

    /**
     * 把 APK 内置权重解包到宿主进程能写的目录（只做一次）。
     *
     * <p>⚠️ 不能写插件包自己的私有目录：插件脚本跑在宿主进程里、用的是宿主的 uid，
     * 写别的应用私有目录会被拒。这里逐个候选目录试，失败原因记进 {@link #nnueError()}，
     * 而不是静默返回空串（真机上曾因此只看到一句「找不到权重」）。
     */
    private String extractBundledNnue() {
        File[] targets = {
                fileIn(mHostContext.getFilesDir(), PLUGIN_NNUE_NAME),
                fileIn(mHostContext.getExternalFilesDir(null), PLUGIN_NNUE_NAME)
        };
        StringBuilder failures = new StringBuilder();
        synchronized (XiangqiPlugin.class) {
            for (File target : targets) {
                if (target == null) {
                    continue;
                }
                if (isUsableNnue(target)) {
                    return target.getAbsolutePath();
                }
                try {
                    if (copyAssetTo(target)) {
                        return target.getAbsolutePath();
                    }
                    failures.append(target).append(" 解包后长度不对；");
                } catch (Throwable failure) {
                    failures.append(target).append(" -> ").append(failure).append("；");
                }
            }
        }
        mNnueError = failures.length() == 0
                ? ("没有可写的目录来放 " + NNUE_NAME)
                : ("解包 " + NNUE_NAME + " 失败：" + failures);
        return "";
    }

    /** 逐块拷贝资产到文件（先写 .tmp 再改名，避免半个文件被当成可用权重）。 */
    private boolean copyAssetTo(File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("建不了目录：" + parent);
        }
        File temporary = new File(target.getPath() + ".tmp");
        long copied = 0;
        try (InputStream input = mPluginContext.getAssets().open(NNUE_NAME);
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                copied += count;
            }
        }
        if (copied <= NNUE_MIN_BYTES) {
            temporary.delete();
            return false;
        }
        if (target.isFile() || temporary.renameTo(target)) {
            return true;
        }
        temporary.delete();
        throw new IllegalStateException("改名失败：" + target);
    }

    /**
     * 插件脚本目录：宿主固定把 assets/scripts 拷到「宿主 cache/plugin-scripts/&lt;包名&gt;」。
     * 公开是有意的——宿主有的入口脚本加载方式不给入口脚本路径（例如本仓库 Rhino 侧把
     * 插件 SDK 实例直接传进来），插件入口这时就靠它定位自己的脚本目录。
     */
    public String scriptDir() {
        try {
            File dir = new File(new File(mHostContext.getCacheDir(), "plugin-scripts"),
                    mPluginContext.getPackageName());
            return dir.getAbsolutePath();
        } catch (Throwable failure) {
            return "";
        }
    }

    private String versionName() {
        loadVersion();
        return mVersionName;
    }

    private int versionCode() {
        loadVersion();
        return mVersionCode;
    }

    private String mVersionName = "";
    private int mVersionCode = 0;
    private boolean mVersionLoaded = false;

    private void loadVersion() {
        if (mVersionLoaded) {
            return;
        }
        mVersionLoaded = true;
        try {
            PackageManager manager = mPluginContext.getPackageManager();
            PackageInfo info = manager.getPackageInfo(mPluginContext.getPackageName(), 0);
            mVersionName = info.versionName == null ? "" : info.versionName;
            mVersionCode = info.versionCode;
        } catch (PackageManager.NameNotFoundException ignored) {
            // 取不到就留空：版本号只用于展示/自检，不影响能力
        }
    }
}
