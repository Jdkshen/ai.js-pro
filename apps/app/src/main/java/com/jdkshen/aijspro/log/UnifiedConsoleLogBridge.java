package com.jdkshen.aijspro.log;
import com.stardust.autojs.core.console.ConsoleLogStyle;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 主源集访问「统一日志源」{@code HostConsoleSink} 的反射门面（编辑日志面板 / 原生日志页共用）。
 *
 * <p>为什么用反射：{@code HostConsoleSink} 在 flutterPoc 源集（只有 {@code -PflutterPoc=true}
 * 的 debug 构建参与编译），主源集直接 import 会让普通构建编译失败——与
 * {@code com.jdkshen.aijspro.external.HostScripts} 是同一套可选依赖写法。
 *
 * <p>统一数据源结构（HostConsoleSink）：
 * <ul>
 *   <li>主进程自身日志：GlobalConsole 的 log4j appender 旁路写入，token/name 固定 {@code main}；</li>
 *   <li>{@code :script} / {@code :native} 脚本日志：what=103 转发后由 PocScriptHost/PocNativeRunner
 *       写入，token 为 {@code script:*} / {@code native:*}，name 为脚本名；</li>
 *   <li>2000 行环形缓冲 + {@code Listener} 实时回调；本门面提供 snapshot 与订阅。</li>
 * </ul>
 *
 * <p>所有方法在类不可用（普通构建）时安全返回空；调用方自行回退旧实现。
 */
public final class UnifiedConsoleLogBridge {

    private static final String TAG = "UnifiedConsoleLogBridge";
    private static final String SINK_CLASS =
            "com.jdkshen.aijspro.flutterpoc.scriptproc.HostConsoleSink";

    /** 统一流的一行（主源集 POJO，字段与 HostConsoleSink.Entry 对应）。 */
    public static final class Line {
        public final String token;
        public final String name;
        public final String text;
        public final int level;
        public final long time;
        public final long seq;

        Line(String token, String name, String text, int level, long time, long seq) {
            this.token = token == null ? "" : token;
            this.name = name == null ? "" : name;
            this.text = text == null ? "" : text;
            this.level = level;
            this.time = time;
            this.seq = seq;
        }

        /** 统一格式：HH:mm:ss.SSS [I] name: line（与 Flutter 运行日志页一致）。 */
        public String formatted() {
            String displayName = name.isEmpty()
                    ? (token.isEmpty() ? "main" : token) : name;
            return ConsoleLogStyle.format(time, level, displayName, text);
        }
    }

    public interface Listener {
        void onLine(Line line);
        default void onClear(long generation) { }
    }

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    /** 本门面的订阅者（代理只向 HostConsoleSink 注册一次，便于 add/remove 且不泄漏 Activity）。 */
    private static final java.util.concurrent.CopyOnWriteArrayList<Listener> USER_LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile boolean sProxyRegistered;

    private static volatile boolean sResolved;
    private static Class<?> sSinkClass;
    private static Class<?> sListenerClass;
    private static Method sEnsureMethod;
    private static Method sIsMainProcessMethod;
    private static Method sSnapshotMethod;
    private static Method sClearMethod;
    private static Method sAddListenerMethod;
    private static Method sRemoveListenerMethod;
    private static Field sTokenField;
    private static Field sNameField;
    private static Field sLineField;
    private static Field sLevelField;
    private static Field sTimeField;
    private static Field sSeqField;

    private UnifiedConsoleLogBridge() {
    }

    /** 当前构建是否带统一日志源（flutterPoc debug 构建才有）。 */
    public static boolean isAvailable() {
        resolve();
        return sSinkClass != null && sSnapshotMethod != null;
    }

    /**
     * 当前是否主进程：:script/:native 进程里的统一环是空的（103 都转发到主进程），
     * 因此在脚本进程里 {@code console.show()} 应回退到旧日志页而不是显示空页面。
     * 统一源不可用/旧版本无此方法时按 true 处理。
     */
    public static boolean isMainProcess() {
        resolve();
        if (sIsMainProcessMethod != null) {
            try {
                Object value = sIsMainProcessMethod.invoke(null);
                return !(value instanceof Boolean) || (Boolean) value;
            } catch (Throwable error) {
                Log.w(TAG, "isMainProcess failed", error);
            }
        }
        return true;
    }

    /** 确保主进程日志已接入统一环（幂等；不可用时什么都不做）。 */
    public static void ensureMainTap() {
        if (!isMainProcess()) { ConsoleLogService.Remote.get().start(); return; }
        resolve();
        if (sEnsureMethod != null) {
            try {
                sEnsureMethod.invoke(null);
            } catch (Throwable error) {
                Log.w(TAG, "ensureMainTap failed", error);
            }
        }
    }

    /** 最近 maxLines 行的统一快照（时间顺序；不可用返回空）。 */
    public static List<Line> snapshot(int maxLines) {
        if (!isMainProcess()) return ConsoleLogService.Remote.get().snapshot(maxLines);
        List<Line> result = new ArrayList<>();
        resolve();
        if (sSnapshotMethod == null) {
            return result;
        }
        try {
            Object raw = sSnapshotMethod.invoke(null, maxLines);
            if (!(raw instanceof List)) {
                return result;
            }
            for (Object item : (List<?>) raw) {
                Line line = toLine(item);
                if (line != null) {
                    result.add(line);
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "snapshot failed", error);
        }
        return result;
    }

    /** 订阅实时追加（主线程回调；同一 listener 重复添加只保留一份）。 */
    public static void addListener(Listener listener) {
        if (listener == null) {
            return;
        }
        USER_LISTENERS.addIfAbsent(listener);
        if (!isMainProcess()) { ConsoleLogService.Remote.get().start(); return; }
        ensureSinkProxy();
    }

    /** 取消订阅（Activity 销毁时要调用，避免静态列表持有已销毁界面）。 */
    public static void removeListener(Listener listener) {
        if (listener != null) {
            USER_LISTENERS.remove(listener);
            if (USER_LISTENERS.isEmpty() && !isMainProcess()) ConsoleLogService.Remote.get().stop();
        }
    }

    private static void ensureSinkProxy() {
        resolve();
        if (sAddListenerMethod == null || sListenerClass == null || sProxyRegistered) {
            return;
        }
        synchronized (UnifiedConsoleLogBridge.class) {
            if (sProxyRegistered) {
                return;
            }
            try {
                Object proxy = Proxy.newProxyInstance(sListenerClass.getClassLoader(),
                        new Class<?>[]{sListenerClass}, new InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, Method method, Object[] args) {
                                if (method.getDeclaringClass() == Object.class) {
                                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                                    if ("equals".equals(method.getName())) return args != null && args[0] == proxy;
                                    return "UnifiedConsoleLogBridge.Listener";
                                }
                                if ("onConsoleCleared".equals(method.getName())) {
                                    long generation = args == null ? 0 : ((Number) args[0]).longValue();
                                    for (Listener user : USER_LISTENERS) {
                                        try { user.onClear(generation); }
                                        catch (Throwable error) { Log.w(TAG, "clear listener failed", error); }
                                    }
                                    return null;
                                }
                                if ("onConsoleLine".equals(method.getName())
                                        && args != null && args.length == 1) {
                                    Line line = toLine(args[0]);
                                    if (line != null) {
                                        for (Listener user : USER_LISTENERS) {
                                            try {
                                                user.onLine(line);
                                            } catch (Throwable error) {
                                                Log.w(TAG, "listener failed", error);
                                            }
                                        }
                                    }
                                }
                                return null;
                            }
                        });
                sAddListenerMethod.invoke(null, proxy);
                sProxyRegistered = true;
            } catch (Throwable error) {
                Log.w(TAG, "register listener proxy failed", error);
            }
        }
    }

    /** 清空统一环（不影响 GlobalConsole 已显示内容）。 */
    public static void clear() {
        if (!isMainProcess()) { ConsoleLogService.Remote.get().clear(); return; }
        resolve();
        if (sClearMethod != null) {
            try {
                sClearMethod.invoke(null);
            } catch (Throwable error) {
                Log.w(TAG, "clear failed", error);
            }
        }
    }

    static void remoteLine(Line line) {
        for (Listener listener : USER_LISTENERS) {
            try { listener.onLine(line); }
            catch (Throwable error) { Log.w(TAG, "remote line listener failed", error); }
        }
    }

    static void remoteClear(long generation) {
        for (Listener listener : USER_LISTENERS) {
            try { listener.onClear(generation); }
            catch (Throwable error) { Log.w(TAG, "remote clear listener failed", error); }
        }
    }

    private static Line toLine(Object entry) {
        if (entry == null) {
            return null;
        }
        try {
            return new Line(
                    (String) sTokenField.get(entry),
                    (String) sNameField.get(entry),
                    (String) sLineField.get(entry),
                    sLevelField.getInt(entry),
                    sTimeField.getLong(entry),
                    sSeqField.getLong(entry));
        } catch (Throwable error) {
            return null;
        }
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

    private static void resolve() {
        if (sResolved) {
            return;
        }
        synchronized (UnifiedConsoleLogBridge.class) {
            if (sResolved) {
                return;
            }
            try {
                ClassLoader loader = UnifiedConsoleLogBridge.class.getClassLoader();
                Class<?> sink = Class.forName(SINK_CLASS, true, loader);
                Class<?> entryClass = null;
                for (Class<?> inner : sink.getDeclaredClasses()) {
                    if (inner.getSimpleName().equals("Entry")) {
                        entryClass = inner;
                    }
                }
                for (Method method : sink.getDeclaredMethods()) {
                    String name = method.getName();
                    if ("ensureMainConsoleTap".equals(name)) {
                        sEnsureMethod = method;
                    } else if ("isMainProcess".equals(name)
                            && method.getParameterTypes().length == 0) {
                        sIsMainProcessMethod = method;
                    } else if ("snapshot".equals(name) && method.getParameterTypes().length == 1
                            && method.getParameterTypes()[0] == int.class) {
                        sSnapshotMethod = method;
                    } else if ("clear".equals(name)) {
                        sClearMethod = method;
                    } else if ("addListener".equals(name) && method.getParameterTypes().length == 1) {
                        sListenerClass = method.getParameterTypes()[0];
                        sAddListenerMethod = method;
                    } else if ("removeListener".equals(name) && method.getParameterTypes().length == 1) {
                        sRemoveListenerMethod = method;
                    }
                }
                if (entryClass != null && sListenerClass == null) {
                    // 只有 Entry 没有 Listener 接口：仍可 snapshot，不能订阅。
                    Log.w(TAG, "HostConsoleSink without Listener interface");
                }
                if (entryClass != null) {
                    sTokenField = entryClass.getField("token");
                    sNameField = entryClass.getField("name");
                    sLineField = entryClass.getField("line");
                    sLevelField = entryClass.getField("level");
                    sTimeField = entryClass.getField("time");
                    sSeqField = entryClass.getField("seq");
                    sSinkClass = sink;
                }
            } catch (Throwable error) {
                Log.i(TAG, "统一日志源不可用（非 flutterPoc 构建？）：" + error);
            }
            sResolved = true;
        }
    }
}
