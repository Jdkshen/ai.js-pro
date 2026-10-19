package com.stardust.autojs.runtime.api.remote;

import android.util.Log;

import com.stardust.autojs.runtime.exception.ScriptException;

/**
 * Phase1 P1：{@code :script} 进程 -> 主进程自动化能力（Accessibility / Automator /
 * ScreenCapturer）的桥。
 *
 * <p>背景：{@code AccessibilityService} 只跑在主进程；{@code :script} 进程里的脚本调
 * auto/click/selector/screenshot 时必须把请求转给主进程的
 * {@code com.jdkshen.aijspro.automation.AutomationHostService}（实现
 * {@code com.stardust.autojs.host.IAutomationHost}）。engine 模块不能依赖 app 模块的 AIDL
 * 生成类（模块方向相反），所以这里定义纯 Java 的 {@link Backend} 接口；app 侧的
 * {@code AutomationHostClient} 实现它，并由 {@code ScriptHostService} 在每个 RUN 前注入。
 *
 * <p>启用条件：{@link #isActive()} = 本进程是隔离脚本进程（{@link #setIsolationProcess}，
 * 由 {@code ScriptHostService} 在 onCreate 里设置）+ 已安装 Backend。{@code :native} 进程不算
 * 隔离脚本进程（自动化能力仍不可用）。
 *
 * <p>失败策略：所有调用都抛 {@link ScriptException}（中文消息），与主进程本地执行时的
 * 无障碍异常语义一致；未连接时抛「隔离进程尚未连接主进程自动化服务…」。
 */
public final class RemoteAutomationBridge {

    private static final String TAG = "RemoteAutomationBridge";

    /** 未连接主进程自动化服务时的统一提示。 */
    private static final String NOT_CONNECTED_MESSAGE =
            "隔离进程尚未连接主进程自动化服务，请稍后重试";
    /** 无障碍服务未开启（与主进程版同义的中文提示）。 */
    private static final String SERVICE_NOT_ENABLED_MESSAGE =
            "无障碍服务未开启，请在系统设置中开启 AI.js Pro 的无障碍服务";
    /** 无障碍服务已开启但没连上（主进程服务未运行/刚被杀）。 */
    private static final String SERVICE_NOT_RUNNING_MESSAGE =
            "无障碍服务已开启但未运行，请回到 AI.js Pro 重新打开后再试";

    /**
     * 主进程 {@code AutomationHostService} 的能力接口（与
     * {@code com.stardust.autojs.host.IAutomationHost} 一一对应，参数/返回值保持纯 Java 类型）。
     *
     * <p>所有方法同步执行、允许脚本线程调用；实现方（app 侧）负责切到主进程无障碍主线程并等待。
     */
    public interface Backend {

        /** AccessibilityService 是否已连接（=主进程自动化服务可用）。 */
        boolean isReady();

        /** 无障碍服务是否已在系统设置里开启（只查设置状态，不拉起设置页）。 */
        boolean isAccessibilityServiceEnabled();

        /** 当前窗口的完整节点 dump（JSON 数组，格式见 IAutomationHost 契约文档）。 */
        String getLatestNodeDump();

        /** 按 selectorJson 匹配，返回节点 JSON 数组。 */
        String findNodes(String selectorJson);

        /** 按 selectorJson 匹配，返回第 index 个节点 JSON；找不到返回空串。 */
        String getNodeInfo(String selectorJson, int index);

        boolean clickNode(String selectorJson, int index);

        boolean longClickNode(String selectorJson, int index);

        boolean setTextNode(String selectorJson, int index, String text);

        boolean click(int x, int y);

        boolean longClick(int x, int y);

        boolean swipe(int x1, int y1, int x2, int y2, long duration);

        /** gestures 描述见 IAutomationHost 契约文档（[[{x,y,t}...],...]）。 */
        boolean gesture(String strokesJson);

        boolean back();

        boolean home();

        boolean recents();

        boolean notifications();

        /** 保存 PNG 到 targetPath；成功返回实际路径，失败返回空串。 */
        String screenshot(String targetPath);

        // ---- task-163：主进程无障碍事件反向通道（仅 :script 隔离进程用） ----

        /** 订阅主进程无障碍事件；返回 false 表示主进程未连接/不支持。 */
        boolean subscribeAccessibilityEvents(AccessibilityEventListener listener);

        /** 取消订阅。 */
        void unsubscribeAccessibilityEvents(AccessibilityEventListener listener);
    }

    /** 无障碍事件回调：事件 JSON 格式与 Node24 通路一致（kind/packageName/className/text/...）。 */
    public interface AccessibilityEventListener {
        void onAccessibilityEvent(String eventJson);

        void onServiceStateChanged(boolean ready);
    }

    private static volatile Backend sBackend;
    private static volatile boolean sIsolationProcess;

    private RemoteAutomationBridge() {
    }

    /**
     * 标记「当前进程是 :script 隔离脚本进程」（由 ScriptHostService 在 onCreate 里调用一次）。
     *
     * <p>只有隔离脚本进程才会把自动化 API 代理到主进程；主进程/其它进程保持原有本地实现。
     */
    public static void setIsolationProcess(boolean value) {
        sIsolationProcess = value;
        Log.i(TAG, "isolation process = " + value);
    }

    public static boolean isIsolationProcess() {
        return sIsolationProcess;
    }

    /** 安装远程后端（ScriptHostService 在每个 RUN 前调用；必须是隔离进程里才有意义）。 */
    public static void install(Backend backend) {
        sBackend = backend;
        Log.i(TAG, "backend installed: " + (backend == null ? "null" : backend.getClass().getName()));
    }

    /** 卸载远程后端（最后一个脚本结束时调用；只清掉同一个实例，避免误删后装的）。 */
    public static void uninstall(Backend backend) {
        if (backend == null || sBackend == backend) {
            sBackend = null;
            Log.i(TAG, "backend uninstalled");
        }
    }

    /** 远程自动化是否生效：隔离脚本进程 + 已注入后端。 */
    public static boolean isActive() {
        return sIsolationProcess && sBackend != null;
    }

    /** task-163：订阅主进程无障碍事件（隔离进程 + 已注入后端才生效）。 */
    public static boolean subscribeAccessibilityEvents(AccessibilityEventListener listener) {
        if (listener == null) {
            return false;
        }
        Backend backend = backendOrNull();
        if (backend == null) {
            return false;
        }
        try {
            return backend.subscribeAccessibilityEvents(listener);
        } catch (Throwable error) {
            Log.w(TAG, "subscribeAccessibilityEvents failed", error);
            return false;
        }
    }

    /** task-163：取消订阅主进程无障碍事件。 */
    public static void unsubscribeAccessibilityEvents(AccessibilityEventListener listener) {
        if (listener == null) {
            return;
        }
        Backend backend = backendOrNull();
        if (backend == null) {
            return;
        }
        try {
            backend.unsubscribeAccessibilityEvents(listener);
        } catch (Throwable error) {
            Log.w(TAG, "unsubscribeAccessibilityEvents failed", error);
        }
    }

    /** 当前后端；不在隔离模式/未注入时返回 null（调用方自行降级或报错）。 */
    public static Backend backendOrNull() {
        return isActive() ? sBackend : null;
    }

    /** 取后端；未连接抛中文 ScriptException。 */
    public static Backend requireBackend() throws ScriptException {
        Backend backend = backendOrNull();
        if (backend == null) {
            throw new ScriptException(NOT_CONNECTED_MESSAGE);
        }
        return backend;
    }

    /**
     * 检查无障碍可用性，等价主进程版 {@code auto()} 的语义：未开启/未连接时抛中文异常
     * （供 {@code auto()/auto.waitFor()} 与所有动作入口复用）。
     */
    public static void ensureAccessibility() throws ScriptException {
        Backend backend = requireBackend();
        try {
            if (backend.isReady()) {
                return;
            }
            if (!backend.isAccessibilityServiceEnabled()) {
                throw new ScriptException(SERVICE_NOT_ENABLED_MESSAGE);
            }
            throw new ScriptException(SERVICE_NOT_RUNNING_MESSAGE);
        } catch (ScriptException error) {
            throw error;
        } catch (Throwable error) {
            throw wrapFailure("无障碍可用性检查", error);
        }
    }

    /** 无障碍是否可用（不抛异常版本，供 UI/脚本判断）。 */
    public static boolean isAccessibilityReady() {
        Backend backend = backendOrNull();
        if (backend == null) {
            return false;
        }
        try {
            return backend.isReady();
        } catch (Throwable error) {
            Log.w(TAG, "isReady failed", error);
            return false;
        }
    }

    // ---- 动作快捷封装（SimpleActionAutomator/其它引擎侧代码直接调用） ------------

    public static boolean click(int x, int y) {
        return action("click", backend -> backend.click(x, y), false);
    }

    public static boolean longClick(int x, int y) {
        return action("longClick", backend -> backend.longClick(x, y), false);
    }

    public static boolean swipe(int x1, int y1, int x2, int y2, long duration) {
        return action("swipe", backend -> backend.swipe(x1, y1, x2, y2, duration), false);
    }

    public static boolean gesture(String strokesJson) {
        return action("gesture", backend -> backend.gesture(strokesJson), false);
    }

    public static boolean back() {
        return action("back", Backend::back, false);
    }

    public static boolean home() {
        return action("home", Backend::home, false);
    }

    public static boolean recents() {
        return action("recents", Backend::recents, false);
    }

    public static boolean notifications() {
        return action("notifications", Backend::notifications, false);
    }

    /** 截图到文件；返回实际路径，失败抛中文异常。 */
    public static String screenshot(String targetPath) {
        return action("screenshot", backend -> backend.screenshot(targetPath), "");
    }

    /**
     * 统一的调用包装：未连接抛 NOT_CONNECTED，实现方异常统一包成 ScriptException。
     */
    private interface Action<T> {
        T run(Backend backend) throws Throwable;
    }

    private static <T> T action(String api, Action<T> call, T fallback) {
        Backend backend = backendOrNull();
        if (backend == null) {
            throw new ScriptException(NOT_CONNECTED_MESSAGE);
        }
        try {
            return call.run(backend);
        } catch (ScriptException error) {
            throw error;
        } catch (Throwable error) {
            if (fallback != null) {
                Log.w(TAG, "remote action failed: " + api, error);
                return fallback;
            }
            throw wrapFailure(api, error);
        }
    }

    private static ScriptException wrapFailure(String api, Throwable error) {
        String message = error == null ? "未知错误" : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error == null ? "未知错误" : error.getClass().getSimpleName();
        }
        return new ScriptException("主进程自动化服务调用失败（" + api + "）：" + message, error);
    }
}
