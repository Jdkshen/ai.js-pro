package com.jdkshen.aijspro.flutterpoc.scriptproc;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.jdkshen.aijspro.autojs.AutoJs;
import com.stardust.autojs.core.console.GlobalConsole;
import com.stardust.autojs.core.console.ConsoleLogStyle;
import com.stardust.autojs.core.console.ConsoleImpl;
import com.jdkshen.aijspro.log.ConsoleLogBuffer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 主进程侧的 {@code :script} 实时 console 落地（Phase1 日志转发，what=103 的接收端）。
 *
 * <p>职责：{@link PocScriptHost} 收到 103 CONSOLE 后把每行交给本类，本类做三件事：
 * <ol>
 *   <li><b>写主进程 GlobalConsole</b>：原生日志页（LogActivity / ConsoleView）以及任何监听
 *       GlobalConsole 的地方都能实时看到脚本输出（与主进程脚本日志同一套 UI/着色）；</li>
 *   <li><b>进环形缓冲</b>：保留最近 {@value #MAX_BUFFER_LINES} 行（带 token/脚本名/等级/时间），
 *       后续 Flutter 实时日志页或诊断工具可以通过 {@link #snapshot(int)} / {@link Listener} 取用，
 *       不依赖运行日志文件；</li>
 *   <li><b>通知监听者</b>：主线程回调 {@link Listener#onConsoleLine(Entry)}，方便后续 UI 直接订阅。</li>
 * </ol>
 *
 * <p>线程：{@link #append} 可在任意线程调用（PocScriptHost 的回复 Handler 本来就在主线程）；
 * 内部统一切主线程再写 GlobalConsole/回调，避免 ConsoleView 之类的 UI 监听在 binder 线程被碰。
 * 任何异常都只在 logcat 记录，不影响脚本运行/日志转发。
 */
public final class HostConsoleSink {

    private static final String TAG = "HostConsoleSink";

    /** 环形缓冲最多保留的行数（超出丢最旧的）。 */
    public static final int MAX_BUFFER_LINES = 2000;
    /** 单次 append 的输入上限（防御异常大包，按行截断）。 */
    private static final int MAX_INPUT_CHARS = 64 * 1024;

    /** 主进程日志监听接口（主线程回调；Flutter 实时日志页/编辑器/原生日志页都可订阅）。 */
    public interface Listener {
        void onConsoleLine(Entry entry);
        default void onConsoleCleared(long generation) { }
    }

    /**
     * 追加监听接口（与 {@link Listener} 等价，语义更明确）：每个新行 append 后主线程回调。
     *
     * <p>统一日志源的 UI 都通过它订阅；先 {@link #snapshot(int)} 渲染历史，再靠回调增量追加。
     * 回调在主线程执行，可直接更新 View。
     */
    public interface AppendListener {
        void onAppended(Entry entry);
        default void onCleared(long generation) { }
    }

    /** 与 ScriptRunLog / Flutter parseLogItems 一致的统一行格式：HH:mm:ss.SSS [I] name: line。 */
    public static String formatLine(Entry entry) {
        if (entry == null) {
            return "";
        }
        String name = entry.name == null ? "" : entry.name;
        if (name.isEmpty()) {
            name = entry.token == null || entry.token.isEmpty() ? "main" : entry.token;
        }
        return ConsoleLogStyle.format(entry.time, entry.level, name, entry.line);
    }

    private static String levelChar(int level) {
        switch (level) {
            case Log.VERBOSE:
                return "V";
            case Log.DEBUG:
                return "D";
            case Log.WARN:
                return "W";
            case Log.ERROR:
                return "E";
            case Log.ASSERT:
                return "A";
            case Log.INFO:
            default:
                return "I";
        }
    }

    /** 一条可显示的脚本 console 行。 */
    public static final class Entry {
        public final String token;
        public final String name;
        public final String line;
        /** android.util.Log 等级（VERBOSE..ASSERT）。 */
        public final int level;
        public final long time;
        /** 全局自增序号（只增不减，供 Flutter 增量拉取；清空缓冲后仍继续递增）。 */
        public final long seq;

        Entry(String token, String name, String line, int level, long time, long seq) {
            this.token = token == null ? "" : token;
            this.name = name == null ? "" : name;
            this.line = line == null ? "" : line;
            this.level = level;
            this.time = time;
            this.seq = seq;
        }

        @Override
        public String toString() {
            return (name.isEmpty() ? "" : name + ": ") + line;
        }
    }

    private static final Object LOCK = new Object();
    /** 行序号发生器：append 时递增，Flutter 实时日志页用 since 增量拉取（只加只读 API，不改缓冲行为）。 */
    private static final ConsoleLogBuffer<Entry> BUFFER =
            new ConsoleLogBuffer<>(MAX_BUFFER_LINES, entry -> entry.seq);
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    /** 语义化的追加订阅（编辑器日志面板/原生统一日志页使用）。 */
    private static final CopyOnWriteArrayList<AppendListener> APPEND_LISTENERS =
            new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean sClearingGlobal;
    private static final ConsoleImpl.LogListener GLOBAL_CLEAR_LISTENER = new ConsoleImpl.LogListener() {
        @Override public void onNewLog(ConsoleImpl.LogEntry entry) { }
        @Override public void onLogClear() {
            if (!sClearingGlobal) clear();
        }
    };
    private static final java.text.SimpleDateFormat TIME_FORMAT =
            new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US);

    /** 主进程 GlobalConsole 的 log4j appender 是否已挂上（每进程只挂一次）。 */
    private static volatile boolean sMainTapInstalled;
    /**
     * 主进程 GlobalConsole 的行 -> HostConsoleSink 的回流标记，防止死循环：
     * 本类把 :script/:native 行写进 GlobalConsole 时置 true，appender 收到这些事件时直接丢弃
     * （它们本来就已经在环里了）。
     */
    private static final ThreadLocal<Boolean> IN_SINK_WRITE = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };
    /** GlobalConsole 日志前缀：HH:mm:ss.SSS/L: 正文（GlobalConsole.println 自己加的）。 */
    private static final java.util.regex.Pattern GLOBAL_CONSOLE_PREFIX =
            java.util.regex.Pattern.compile("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3}/([VDIWEAF]): (.*)$",
                    java.util.regex.Pattern.DOTALL);

    private HostConsoleSink() {
    }

    /**
     * 追加一批脚本 console 输出（可多行，{@code line} 内用 {@code \n} 分隔；与 103 消息的 line 一致）。
     *
     * @param token 协议 token（可为 null）
     * @param name  脚本名（可为 null/空）
     * @param level android.util.Log 等级；非法值回退 INFO
     * @param line  一行或多行文本（多行按 {@code \n} 拆开逐行处理）
     */
    public static void append(final String token, final String name, final int level, final String line) {
        if (line == null) {
            return;
        }
        ensureMainConsoleTap();
        final int safeLevel = normalizeLevel(level);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatch(token, name, safeLevel, line, true);
        } else {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    dispatch(token, name, safeLevel, line, true);
                }
            });
        }
    }

    /** 最近 n 行快照（按时间顺序；n&lt;=0 时返回空列表）。 */
    public static List<Entry> snapshot(int maxLines) {
        return snapshotSince(0L, maxLines, null);
    }

    /** 最近 n 行的纯文本（每行 {@code line}，多行脚本输出已拆行）。 */
    public static String snapshotText(int maxLines) {
        StringBuilder builder = new StringBuilder();
        for (Entry entry : snapshot(maxLines)) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(entry.line);
        }
        return builder.toString();
    }

    /**
     * 递增到当前最后一行时的版本号（=最后一条 Entry.seq）；配合
     * {@link #snapshotSince(long, int, String)} 做 Flutter 实时日志页的增量拉取。
     */
    public static long version() {
        ensureMainConsoleTap();
        return BUFFER.version();
    }

    public static long generation() { return BUFFER.generation(); }

    /**
     * token 过滤：兼容任务页展示形态（{@code script:3} / {@code native:3}）与上游写入的形态。
     *
     * <p>上游 append 的 token 有两种可能：旧版是协议原始 token（如 {@code "3"}，:script/:native
     * 各自计数所以可能撞号），新版由调用方加命名空间前缀（{@code "script:3"} / {@code "native:3"}）。
     * 规则：双方都带前缀 → 前缀 + 裸 token 都必须相等（可精确区分进程）；有一方没有前缀 →
     * 只比较裸 token（兼容旧数据；撞号时会把同号行一起返回，属已知限制）。
     */
    private static boolean tokenMatches(String entryToken, String filter) {
        if (filter.isEmpty()) {
            return true;
        }
        String entry = entryToken == null ? "" : entryToken;
        if (filter.equals(entry)) {
            return true;
        }
        String entryPrefix = tokenPrefixOf(entry);
        String filterPrefix = tokenPrefixOf(filter);
        String entryRaw = stripTokenPrefix(entry);
        String filterRaw = stripTokenPrefix(filter);
        if (entryPrefix != null && filterPrefix != null) {
            return entryPrefix.equals(filterPrefix) && entryRaw.equals(filterRaw);
        }
        return entryRaw.equals(filterRaw);
    }

    private static String tokenPrefixOf(String token) {
        if (token.startsWith("script:")) {
            return "script";
        }
        if (token.startsWith("native:")) {
            return "native";
        }
        if ("main".equals(token) || token.startsWith("main:")) {
            return "main";
        }
        return null;
    }

    private static String stripTokenPrefix(String token) {
        int colon = token.indexOf(':');
        if (colon > 0 && tokenPrefixOf(token) != null) {
            return token.substring(colon + 1);
        }
        return token;
    }

    /** 当前环形缓冲行数（未过滤）。 */
    public static int lineCount() {
        ensureMainConsoleTap();
        synchronized (LOCK) {
            return BUFFER.size();
        }
    }

    /** 最近 n 行快照，按 token 过滤（token 为 null/空 = 不过滤）。 */
    public static List<Entry> snapshot(int maxLines, String token) {
        return snapshotSince(0L, maxLines, token);
    }

    /**
     * 增量 + 按 token 过滤的快照：只返回 seq &gt; afterSeq 且 token 匹配的行（时间顺序）。
     *
     * @param afterSeq 上次拿到的 version（0 = 从现在缓冲里取最近 maxLines 行）
     * @param maxLines 最多返回行数（超出时保留最新的）
     * @param token    脚本 token 过滤（null/空 = 全部；比较的是 {@link Entry#token} 原始值，
     *                 前缀归一化在前缀侧桥里做）
     */
    public static List<Entry> snapshotSince(long afterSeq, int maxLines, String token) {
        return pageSince(afterSeq, maxLines, token, -1L).entries;
    }

    /** Rows and their delivery cursor must come from the same atomic snapshot. */
    public static ConsoleLogBuffer.Page<Entry> pageSince(long afterSeq, int maxLines,
                                                         String token, long expectedGeneration) {
        ensureMainConsoleTap();
        String filter = token == null ? "" : token;
        return BUFFER.page(afterSeq, maxLines, entry -> tokenMatches(entry.token, filter), expectedGeneration);
    }

    /** 清空环形缓冲（只清本类的缓存，不影响 GlobalConsole 已显示内容）。 */
    public static void clear() {
        if (Looper.myLooper() != Looper.getMainLooper()) { MAIN.post(HostConsoleSink::clear); return; }
        long generation = BUFFER.clear();
        sClearingGlobal = true;
        try {
            AutoJs autojs = AutoJs.getInstance();
            if (autojs != null && autojs.getGlobalConsole() != null) autojs.getGlobalConsole().clear();
        } finally { sClearingGlobal = false; }
        for (Listener listener : LISTENERS) {
            try { listener.onConsoleCleared(generation); }
            catch (Throwable error) { Log.w(TAG, "console clear listener failed", error); }
        }
        for (AppendListener listener : APPEND_LISTENERS) {
            try { listener.onCleared(generation); }
            catch (Throwable error) { Log.w(TAG, "append clear listener failed", error); }
        }
    }

    public static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    public static void removeListener(Listener listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    /** {@link AppendListener} 订阅（与 {@link #addListener} 等价；统一日志 UI 用这个语义）。 */
    public static void addAppendListener(AppendListener listener) {
        if (listener != null) {
            ensureMainConsoleTap();
            APPEND_LISTENERS.addIfAbsent(listener);
        }
    }

    public static void removeAppendListener(AppendListener listener) {
        if (listener != null) {
            APPEND_LISTENERS.remove(listener);
        }
    }

    // ------------------------------------------------------------------

    private static void dispatch(String token, String name, int level, String text,
                                 boolean writeGlobalConsole) {
        String payload = text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text;
        String[] lines = payload.split("\n", -1);
        for (String rawLine : lines) {
            // 空行也保留（脚本里 console.log("") 是有效输出，便于看分段），但行尾的 \r 去掉。
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            Entry entry = BUFFER.append(seq -> new Entry(token, name, line, level, System.currentTimeMillis(), seq));
            if (writeGlobalConsole) {
                writeToGlobalConsole(entry);
            }
            for (Listener listener : LISTENERS) {
                try {
                    listener.onConsoleLine(entry);
                } catch (Throwable error) {
                    Log.w(TAG, "console 监听回调失败", error);
                }
            }
            for (AppendListener listener : APPEND_LISTENERS) {
                try {
                    listener.onAppended(entry);
                } catch (Throwable error) {
                    Log.w(TAG, "append 监听回调失败", error);
                }
            }
        }
    }

    /** 写主进程 GlobalConsole：原生日志页实时可见（与主进程脚本日志同一套显示）。 */
    private static void writeToGlobalConsole(Entry entry) {
        int level = entry.level;
        String line = entry.line;
        try {
            AutoJs autojs = AutoJs.getInstance();
            if (autojs == null) {
                Log.i(TAG, line);
                return;
            }
            GlobalConsole console = autojs.getGlobalConsole();
            if (console == null) {
                Log.i(TAG, line);
                return;
            }
            // 防止「写 GlobalConsole 又被主进程 log4j appender 抓回来」造成回环/重复：
            // appender 看到这个标记会直接丢弃（这些行本来就来自脚本进程，已在环里）。
            IN_SINK_WRITE.set(Boolean.TRUE);
            try {
                String name = entry.name.isEmpty() ? entry.token : entry.name;
                console.printlnAt(level, line, entry.time, name);
            } finally {
                IN_SINK_WRITE.set(Boolean.FALSE);
            }
        } catch (Throwable error) {
            // GlobalConsole 不可用时至少留 logcat，不能反过来影响脚本/转发。
            IN_SINK_WRITE.set(Boolean.FALSE);
            Log.w(TAG, "写主进程 GlobalConsole 失败", error);
            try {
                Log.i(TAG, line);
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // 主进程日志接入统一流：GlobalConsole 的 log4j appender（不抢 ConsoleView 的 LogListener 槽位）
    // ------------------------------------------------------------------

    /**
     * 确保主进程 GlobalConsole 的每一行都进统一环（token=main）。
     *
     * <p>为什么用 log4j appender 而不是 {@code ConsoleImpl.setLogListener}：那个槽位是单个
     * WeakReference，ConsoleView/原生日志页/编辑器都在用；抢过来会把它们的实时刷新弄坏。
     * GlobalConsole.println 同时写 log4j（{@code LOGGER.log}）和 android Log，这里给 logger 加
     * appender 就能旁路拿到所有主进程 console 行（含主进程脚本、UI 控制台消息），互不影响。
     *
     * <p>首次安装时会把 GlobalConsole 已有历史（{@code getAllLogs()}）灌进环，保证打开日志 UI
     * 就能看到本进程启动以来的主进程日志；之后按新行追加。只在本进程是主进程时安装。
     * 多进程构建里 :script/:native 不安装（避免各自一份 main 流互相污染）。
     */
    public static void ensureMainConsoleTap() {
        if (sMainTapInstalled) {
            return;
        }
        if (!isMainProcess()) {
            return;
        }
        synchronized (LOCK) {
            if (sMainTapInstalled) {
                return;
            }
            sMainTapInstalled = true;
        }
        try {
            seedFromGlobalConsole();
            org.apache.log4j.Logger logger =
                    org.apache.log4j.Logger.getLogger(GlobalConsole.class);
            logger.addAppender(new GlobalConsoleAppender());
            AutoJs autojs = AutoJs.getInstance();
            if (autojs != null && autojs.getGlobalConsole() != null) {
                autojs.getGlobalConsole().addLogListener(GLOBAL_CLEAR_LISTENER);
            }
            Log.i(TAG, "main console tap installed (token=main)");
        } catch (Throwable error) {
            Log.w(TAG, "安装主进程 console 统一流失败", error);
        }
    }

    /** 当前进程是否主进程（:script/:native 等带冒号后缀的进程不装 main tap；UI 据此决定回退）。 */
    public static boolean isMainProcess() {
        try {
            String processName = null;
            if (Build.VERSION.SDK_INT >= 28) {
                processName = android.app.Application.getProcessName();
            }
            if (processName == null) {
                processName = new java.io.BufferedReader(new java.io.FileReader("/proc/self/cmdline"))
                        .readLine();
                if (processName != null) {
                    processName = processName.trim();
                }
            }
            if (processName == null || processName.isEmpty()) {
                return true;
            }
            return !processName.contains(":");
        } catch (Throwable error) {
            Log.w(TAG, "读取进程名失败，按主进程处理", error);
            return true;
        }
    }

    /** 把 GlobalConsole 已有的历史灌进统一环（只灌一次，安装 appender 之前调用）。 */
    private static void seedFromGlobalConsole() {
        try {
            AutoJs autojs = AutoJs.getInstance();
            GlobalConsole console = autojs == null ? null : autojs.getGlobalConsole();
            if (console == null) {
                return;
            }
            // 先收集统一环里已有的行（:script/:native 转发行在 tap 安装前就写过 GlobalConsole）：
            // 它们一会儿会以 main token 再进一次历史，这里按「正文 + 出现次数」抵扣，避免重复。
            java.util.Map<String, Integer> existing = new java.util.HashMap<>();
            synchronized (LOCK) {
                for (Entry entry : BUFFER.page(0, MAX_BUFFER_LINES, null, -1L).entries) {
                    Integer count = existing.get(entry.line);
                    existing.put(entry.line, count == null ? 1 : count + 1);
                }
            }
            java.util.List<com.stardust.autojs.core.console.ConsoleImpl.LogEntry> logs =
                    new java.util.ArrayList<>(console.getAllLogs());
            for (com.stardust.autojs.core.console.ConsoleImpl.LogEntry log : logs) {
                if (log == null || log.content == null) {
                    continue;
                }
                MainLine parsed = parseGlobalConsoleLine(log.content.toString());
                Integer count = existing.get(parsed.text);
                if (count != null && count > 0) {
                    existing.put(parsed.text, count - 1);
                    continue;
                }
                appendInternal("main", "main", log.level, parsed.text, false);
            }
        } catch (Throwable error) {
            Log.w(TAG, "灌入主进程 console 历史失败", error);
        }
    }

    /** 解析 GlobalConsole 的前缀（HH:mm:ss.SSS/L: 正文），恢复等级并去掉重复时间戳。 */
    private static MainLine parseGlobalConsoleLine(String raw) {
        if (raw == null) {
            return new MainLine(Log.INFO, "");
        }
        java.util.regex.Matcher matcher = GLOBAL_CONSOLE_PREFIX.matcher(raw);
        if (matcher.matches()) {
            return new MainLine(levelOfChar(matcher.group(1)), matcher.group(2));
        }
        return new MainLine(Log.INFO, raw);
    }

    private static int levelOfChar(String c) {
        if (c == null || c.isEmpty()) {
            return Log.INFO;
        }
        switch (c.charAt(0)) {
            case 'V':
                return Log.VERBOSE;
            case 'D':
                return Log.DEBUG;
            case 'W':
                return Log.WARN;
            case 'E':
                return Log.ERROR;
            case 'A':
                return Log.ASSERT;
            default:
                return Log.INFO;
        }
    }

    /** 主进程行：token/name 固定 "main"，不再写回 GlobalConsole（避免回环）。 */
    private static void appendInternal(String token, String name, int level, String line,
                                       boolean writeGlobalConsole) {
        if (line == null) {
            return;
        }
        int safeLevel = normalizeLevel(level);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatch(token, name, safeLevel, line, writeGlobalConsole);
        } else {
            MAIN.post(() -> dispatch(token, name, safeLevel, line, writeGlobalConsole));
        }
    }

    private static final class MainLine {
        final int level;
        final String text;

        MainLine(int level, String text) {
            this.level = level;
            this.text = text == null ? "" : text;
        }
    }

    /** GlobalConsole 的 log4j appender：把主进程 console 行旁路进统一环。 */
    private static final class GlobalConsoleAppender extends org.apache.log4j.AppenderSkeleton {
        @Override
        protected void append(org.apache.log4j.spi.LoggingEvent event) {
            if (event == null || Boolean.TRUE.equals(IN_SINK_WRITE.get())) {
                return;
            }
            try {
                String message = event.getRenderedMessage();
                if (message == null || message.isEmpty()) {
                    return;
                }
                MainLine parsed = parseGlobalConsoleLine(message);
                appendInternal("main", "main", parsed.level, parsed.text, false);
            } catch (Throwable error) {
                Log.w(TAG, "主进程 console appender 处理失败", error);
            }
        }

        @Override
        public void close() {
        }

        @Override
        public boolean requiresLayout() {
            return false;
        }
    }

    private static int normalizeLevel(int level) {
        if (level < Log.VERBOSE || level > Log.ASSERT) {
            return Log.INFO;
        }
        return level;
    }
}
