package com.stardust.autojs.engine.node24;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.LruCache;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.stardust.autojs.core.ui.inflater.DynamicLayoutInflater;
import com.stardust.autojs.core.ui.inflater.ProjectResourceBundle;
import com.stardust.autojs.core.ui.inflater.ResourceParser;
import com.stardust.autojs.core.ui.inflater.inflaters.BaseViewInflater;
import com.stardust.autojs.core.ui.inflater.util.Drawables;
import com.stardust.autojs.core.ui.inflater.util.Ids;
import com.stardust.autojs.overlay.OverlayRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Field;

/**
 * Node UI 支持（task-59 B）：Node24 进程里处理 {@code ui} 模块的宿主调用。
 *
 * <p>架构（与 Lead/A 冻结的契约，2026-10-18）：
 * <ul>
 *   <li>Node24 native 桥是单向 Node -&gt; Java，没有 Java-&gt;Node 回调；</li>
 *   <li>Node 侧 {@code require("ui")}（assets/node24/ui.js）所有调用都走
 *       {@code __autojsHost(JSON.stringify({kind:"ui", op:...}))}；A(task-58) 的 Host 实现里
 *       判断 {@code kind=="ui"} 后反射调用 {@link #handle(Context, String, JSONObject)}；</li>
 *   <li>工程资源统一走 C(task-60) 的 {@link ProjectResourceBundle}（resolve project.json /
 *       res/ 合成 id / build/project-res.apk 真 id / .codecache dex ClassLoader）；</li>
 *   <li>第一阶段界面显示在 <b>:node24 进程</b>：WindowManager 全屏 OVERLAY 承载
 *       DynamicLayoutInflater inflate 出来的原生布局（SYSTEM_ALERT_WINDOW）。没有真实 Activity
 *       生命周期/返回键（第二阶段再评估主进程 Activity 方案）。</li>
 *   <li>事件用队列反向传递：show 后入队 create/start/resume/content_view_set；关闭时入队 destroy；
 *       Node 侧 setInterval 调 {@code pollEvents} 拉取并派发到 JS handler。</li>
 * </ul>
 *
 * <p>OP 契约（JS -&gt; Java；request 均带 kind:"ui"、projectDir；返回 {ok:true,...} 或
 * {ok:false,error:"中文"}）：
 * <ul>
 *   <li>{@code loadAndroidResources} -&gt; bundle.describeJson() + {ok}（同时 logcat 打
 *       {@code C-RES: ...}，task-60 约定）；</li>
 *   <li>{@code layoutId} {name} -&gt; {ok,id}（ui.R.layout.xxx）；</li>
 *   <li>{@code resourceId} {type,name} -&gt; {ok,id}（ui.R.id/style/color...）；</li>
 *   <li>{@code show} {className,layoutId?,layout?} -&gt; {ok,layout,layoutId,className}；</li>
 *   <li>{@code pollEvents} -&gt; {ok,events:[{type,...}]}；</li>
 *   <li>{@code close} -&gt; {ok}。</li>
 * </ul>
 */
public final class NodeUiHost {

    private static final String TAG = "NodeUiHost";
    /** 等待主线程完成 inflate/加窗口的最长时间（A 的 Host 调用线程会被同步阻塞这么久）。 */
    private static final long MAIN_THREAD_TIMEOUT_MS = 10_000L;

    private static final Object LOCK = new Object();
    private static final Map<String, ProjectResourceBundle> BUNDLE_CACHE = new LinkedHashMap<String, ProjectResourceBundle>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, ProjectResourceBundle> entry) { return size() > 8; }
    };
    private static final ArrayDeque<JSONObject> EVENTS = new ArrayDeque<>();
    /**
     * 主线程 Handler 延迟创建：类加载不触碰 Android Looper，JVM 单测可以加载本类做纯逻辑回归。
     * 同时保证 2.2 的宿主侧 postDelayed 一定落在真实主 Looper 上。
     */
    private static volatile Handler sMainHandler;
    private static Handler main() {
        Handler handler = sMainHandler;
        if (handler == null) {
            synchronized (LOCK) {
                handler = sMainHandler;
                if (handler == null) {
                    handler = new Handler(Looper.getMainLooper());
                    sMainHandler = handler;
                }
            }
        }
        return handler;
    }

    private static volatile WindowManager sWindowManager;
    private static volatile View sOverlayView;
    // task-218：overlay 的 owner/touchable（Node24HostBridge 注入 request）。
    private static volatile int sOverlayOwnerEngineId = -1;
    private static volatile String sOverlayOwnerTag = "";
    private static volatile boolean sOverlayTouchable = true;
    private static volatile String sShownProjectDir;
    private static volatile String sShownLayout;
    /** task-63：overlay 被右上角关闭/停止关闭时的通知（A 的 keep-alive 用它提前结束等待）。 */
    private static volatile OverlayClosedListener sOverlayClosedListener;
    /** task-63：轮询友好的「关闭请求」标记（close 后置 true，consumeCloseRequested 读后清除）。 */
    private static final AtomicBoolean CLOSE_REQUESTED = new AtomicBoolean(false);
    /** MaterialComponents 主题资源 id（反射 com.google.android.material.R$style；-1=未解析）。 */
    private static int sMaterialThemeId = -1;
    /** 最近一次 overlay 用的主题诊断文案（写进 C-THEME 日志）。 */
    private static String sThemeDiagnostics = "";
    /** 主题基底名称（m3: / bridge: / android:，task-70）。 */
    private static String sThemeBaseLabel = "unresolved";
    /** 当前 inflate 出来的内容视图/主题上下文/资源包/views 映射（task-69 setAdapter 用）。 */
    private static volatile View sContentView;
    private static volatile Context sThemedContext;
    private static volatile ProjectResourceBundle sShownBundle;
    private static volatile JSONObject sShownViews;
    /** task-71：ViewPager 里动态 inflate 的页面视图（setAdapter 找 grid 用）。 */
    private static volatile List<View> sPageViews = new ArrayList<>();
    /** task-71：setAdapter 早于 setPager 时的缓存请求，页面生成后补挂。 */
    private static volatile JSONObject sLastAdapterRequest;
    /** task-76：当前是否主进程 NodeUiActivity 模式（无 overlay）。 */
    private static volatile boolean sActivityMode;
    private static volatile String sActivitySession;
    private static volatile boolean sActivityListenerInstalled;
    /** task-95：单通道三态（activity/overlay/none 互斥），show 成功 Activity 后绝不建 overlay。 */
    private static final int UI_MODE_NONE = 0;
    private static final int UI_MODE_ACTIVITY = 1;
    private static final int UI_MODE_OVERLAY = 2;
    private static volatile int sUiMode = UI_MODE_NONE;
    private static volatile String sUiModeReason = "";
    /** 最近一次 Activity 启动失败原因（overlay 降级 reason 用）。 */
    private static volatile String sActivityError = "";
    /**
     * Activity.layoutXml 的合成布局名：NodeUiActivity 收到它时先放一个占位 content，
     * 再由 setContentViewXml 更新真正 inflate 字符串布局。不会与正常 res 布局重名。
     */
    public static final String INLINE_LAYOUT_TOKEN = "__node24_inline_layout_xml__";
    /**
     * 真实 View 句柄表（findViewById/viewCommand/bindClick/uiRequestView）。
     *
     * <p>用同一张 IdentityHashMap 反查句柄，保证 {@code parent.addView(child)} 之后
     * {@code child.getParent()} 返回的是同一个句柄（JS 侧 facade 身份一致，2.5）。
     * 只在主线程访问；{@link ViewHandleRegistry} 是纯 Java，可在 JVM 单测里回归。
     */
    private static final ViewHandleRegistry VIEW_HANDLES = new ViewHandleRegistry();
    /** 2.2：uiRequestView postDelayed 的待执行定时任务（key -> Runnable），主线程 Handler 执行。 */
    private static final Map<String, Runnable> TIMER_CALLBACKS = new LinkedHashMap<>();
    /** Activity 模式 click 事件拉取节流：pollEvents 每 120ms 一次，Binder 不必这么频繁。 */
    private static long sLastActivityViewEventPollAt;
    private static final long ACTIVITY_VIEW_EVENT_POLL_MS = 250L;
    /** 字符串布局里的 android:id / @+id。 */
    private static final Pattern XML_ID_PATTERN = Pattern.compile(
            "(?:\\s|^)(?:android:)?id\\s*=\\s*[\"']@\\+?id/([A-Za-z_][A-Za-z0-9_]*)[\"']");

    private NodeUiHost() {
    }

    /**
     * A 的 Host（:node24 进程）在 {@code kind=="ui"} 时调用。
     *
     * @param context     :node24 进程 Context（Node24Service/applicationContext 均可）
     * @param projectDir  工程根目录（prelude 注入的 __ajsProjectDir；含 project.json 与 res/）
     * @param request     JS 传来的请求（含 op）
     * @return JSONObject（A 侧 toString() 后回给 __autojsHost）
     */
    public static Object handle(Context context, String projectDir, JSONObject request) {
        try {
            return dispatch(context, projectDir, request);
        } catch (Throwable error) {
            Log.w(TAG, "Node UI 宿主调用失败: " + request, error);
            try {
                return new JSONObject().put("ok", false)
                        .put("error", "Node UI 宿主调用失败：" + describe(error));
            } catch (Throwable ignored) {
                return "{\"ok\":false,\"error\":\"Node UI 宿主内部错误\"}";
            }
        }
    }

    /** 便利方法：返回 JSON 字符串（A 的 Host.invoke 要求 String）。 */
    public static String handleJson(Context context, String projectDir, JSONObject request) {
        Object value = handle(context, projectDir, request);
        return value instanceof JSONObject ? value.toString() : String.valueOf(value);
    }

    // ------------------------------------------------------------------
    // task-63：keep-alive 配合 API（A 的 Node24Service/Engine 使用）
    // ------------------------------------------------------------------

    /** overlay 被用户关闭（右上角关闭按钮）或引擎停止关闭时的回调；在任意线程回调（实际在主线程）。 */
    public interface OverlayClosedListener {
        void onOverlayClosed();
    }

    /** task-95：单通道状态切换 + C-UI 日志（activity/overlay/none 互斥）。 */
    private static void setUiMode(int mode, String reason) {
        int old = sUiMode;
        sUiMode = mode;
        sUiModeReason = reason == null ? "" : reason;
        Log.i(TAG, "C-UI: mode=" + modeName(mode) + " (was=" + modeName(old) + ")"
                + (sUiModeReason.isEmpty() ? "" : " reason=" + sUiModeReason));
    }

    private static String modeName(int mode) {
        if (mode == UI_MODE_ACTIVITY) {
            return "activity";
        }
        if (mode == UI_MODE_OVERLAY) {
            return "overlay";
        }
        return "none";
    }

    /** 当前是否有 Node UI overlay 显示（A 的 keep-alive 判断用）。 */
    public static boolean hasOverlay() {
        return sOverlayView != null && sUiMode == UI_MODE_OVERLAY;
    }

    /** hasOverlay 的别名（A 的反射容错兼容名）。 */
    public static boolean isOverlayShowing() {
        return hasOverlay();
    }

    /**
     * A 的 keep-alive 轮询接口：overlay 已被关闭/请求关闭时返回 true，并清掉标记（只消费一次）。
     * 由 ui.close、右上角「关闭」、{@link #closeOverlayFromEngine()} 置位。
     */
    public static boolean consumeCloseRequested() {
        return CLOSE_REQUESTED.getAndSet(false);
    }

    /** 注册/清除 overlay 关闭通知（传 null 清除）；A 在每次 execute 前注册、结束后清除即可。 */
    public static void setOverlayClosedListener(OverlayClosedListener listener) {
        sOverlayClosedListener = listener;
    }

    /**
     * 引擎停止/销毁时先关闭 overlay（A 停止脚本路径调用）：移除视图、入队 destroy，并触发关闭通知。
     * 没有 overlay 时安全 no-op。
     */
    public static void closeOverlayFromEngine() {
        try {
            if (NodeUiLocalSession.isShowing()) {
                NodeUiLocalSession.close();
                return;
            }
            if (sActivityMode || sUiMode == UI_MODE_ACTIVITY) {
                closeCurrent();
                return;
            }
            closeOverlay();
        } catch (Throwable error) {
            Log.w(TAG, "停止时关闭 Node UI 界面失败", error);
        }
    }

    private static Object dispatch(Context context, String projectDir, JSONObject request) throws Exception {
        Context app = context;
        String op = request == null ? "" : request.optString("op", "");
        if (app == null) {
            return new JSONObject().put("ok", false).put("error", "Node UI 缺少 Context");
        }
        // Activity 之外调用 ui.inflate 时的最后兜底 Context；每次 ui 宿主调用都刷新一次。
        NodeUiLocalSession.setApplicationContext(app);
        String dir = resolveProjectDir(projectDir, request);
        switch (op) {
            case "ping":
                return new JSONObject().put("ok", true).put("version", 2);
            case "resourceState": {
                com.stardust.autojs.core.ui.inflater.ScriptResourceContext resources =
                        app instanceof com.stardust.autojs.core.ui.inflater.ScriptResourceContext
                        ? (com.stardust.autojs.core.ui.inflater.ScriptResourceContext) app : null;
                return new JSONObject().put("ok", true).put("compiled", resources != null && resources.getCompiledResources() != null);
            }
            case "loadAndroidResources":
                return loadAndroidResources(app, dir);
            case "layoutId":
                return layoutId(app, dir, request);
            case "resourceId":
                return resourceId(app, dir, request);
            case "show":
                return show(app, dir, request);
            case "setAdapter":
                return setAdapter(app, dir, request);
            case "setPager":
                return setPager(app, dir, request);
            case "pollEvents":
                pollActivityViewEvents();
                JSONArray events = drainEvents();
                JSONArray local = NodeUiLocalSession.drainEvents();
                for (int i = 0; i < local.length(); i++) events.put(local.get(i));
                return new JSONObject().put("ok", true).put("events", events);
            case "timedTask": {
                try {
                    return NodeUiActivityClient.get().work(context, request);
                } catch (Throwable error) {
                    return new org.json.JSONObject().put("ok", false)
                            .put("error", "work_manager 失败：" + error.getMessage());
                }
            }
            case "settings": {
                request.put("domain", "settings");
                return NodeUiActivityClient.get().work(context, request);
            }
            case "floatingWindow": {
                try {
                    return NodeUiActivityClient.get().overlay(context, request);
                } catch (Throwable error) {
                    return new org.json.JSONObject().put("ok", false)
                            .put("error", "floating_window 失败：" + error.getMessage());
                }
            }
            case "dialog": {
                // task-141：主进程对话框（NodeUiDialogActivity），同步等待用户操作结果。
                try {
                    return NodeUiActivityClient.get().dialog(context, request);
                } catch (Throwable error) {
                    return new org.json.JSONObject().put("ok", false)
                            .put("error", "对话框失败：" + error.getMessage());
                }
            }
            case "layoutName":
                return layoutName(app, dir, request);
            case "inflate": {
                // task-254（2.5）：本地会话/overlay 有真实内容视图时，inflate 必须就地 inflate
                // 并返回真实 View 句柄；主进程 Activity 模式才转发。
                if (!sActivityMode && sessionContentView() != null) {
                    try {
                        return inflateForSession(app, dir, request);
                    } catch (Throwable error) {
                        return new org.json.JSONObject().put("ok", false)
                                .put("error", "inflate 失败：" + describe(error));
                    }
                }
                try {
                    return NodeUiActivityClient.get().view(request);
                } catch (Throwable error) {
                    return new org.json.JSONObject().put("ok", false)
                            .put("error", "View 命令失败：" + error.getMessage());
                }
            }
            case "setContentView":
            case "layout": {
                // task-135 Phase2：转发给 :node24 侧 NodeUiActivityClient（主进程 Activity 执行）。
                try {
                    return NodeUiActivityClient.get().view(request);
                } catch (Throwable error) {
                    return new org.json.JSONObject().put("ok", false)
                            .put("error", "View 命令失败：" + error.getMessage());
                }
            }
            case "uiRequestView":
                return uiRequestView(app, dir, request);
            case "findViewById":
            case "viewCommand":
            case "bindClick": {
                // Activity 模式走真实主进程 View 句柄；本地会话/overlay 在本进程维护真实 View 句柄。
                if (sActivityMode && !NodeUiLocalSession.isShowing()) {
                    try {
                        return NodeUiActivityClient.get().view(request);
                    } catch (Throwable error) {
                        return new org.json.JSONObject().put("ok", false)
                                .put("error", "View 命令失败：" + error.getMessage());
                    }
                }
                try {
                    return overlayViewCommand(app, dir, request);
                } catch (Throwable error) {
                    return new org.json.JSONObject().put("ok", false)
                            .put("error", "View 命令失败：" + describe(error));
                }
            }
            // task-254：uiRequestView(action, params) 也允许直接以 action 作为 op（旧/新客户端都接受）。
            case "addView":
            case "removeView":
            case "removeAllViews":
            case "getParent":
            case "getChildCount":
            case "getChildAt":
            case "setOnClickListener":
            case "setOnCheckedChangeListener":
            case "setOnTextChangedListener":
            case "setOnItemSelectedListener":
            case "getSelectedItemId":
            case "setSelectedItemId":
            case "getMenuSize":
            case "getMenuItemId":
            case "menuItems":
            case "postDelayed":
            case "removeCallbacks":
                return uiRequestView(app, dir, request);
            case "close":
                return closeCurrent();
            default:
                return new JSONObject().put("ok", false)
                        .put("error", "未知的 Node UI op：" + op);
        }
    }

    // ------------------------------------------------------------------
    // task-76：主进程 NodeUiActivity 模式
    // ------------------------------------------------------------------

    /** 当前是否有主进程 NodeUiActivity 显示（A 的 keep-alive 可反射使用）。 */
    public static boolean hasNodeUiActivity() {
        return NodeUiLocalSession.isShowing() || (sActivityMode && sUiMode == UI_MODE_ACTIVITY);
    }

    private static void ensureActivityListener() {
        if (sActivityListenerInstalled) {
            return;
        }
        sActivityListenerInstalled = true;
        try {
            NodeUiActivityClient.get().setListener(new NodeUiActivityClient.Listener() {
                @Override
                public void onEvent(String type, JSONObject payload) {
                    JSONObject views = payload == null ? null : payload.optJSONObject("views");
                    Log.i(TAG, "C-ACTIVITY event=" + type);
                    if (NodeUiProtocol.EVENT_CONTENT_VIEW_SET.equals(type)) {
                        enqueueEvent(type, views);
                        return;
                    }
                    enqueueEvent(type, payload);
                }

                @Override
                public void onClosed() {
                    boolean wasActivity = sActivityMode;
                    sActivityMode = false;
                    CLOSE_REQUESTED.set(true);
                    enqueueEvent("destroy");
                    setUiMode(UI_MODE_NONE, "activity_closed");
                    if (wasActivity) {
                        Log.i(TAG, "C-ACTIVITY closed，置 closeRequested（keep-alive 退出）");
                    }
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "C-ACTIVITY 安装 Listener 失败", error);
        }
    }

    /** 尝试主进程真 Activity；成功返回 true（不建 overlay），失败仅日志并返回 false（走 overlay）。 */
    private static boolean tryActivityMode(Context context, String projectDir, String layoutName,
                                          int layoutId, String className, String xml) {
        try {
            String title = findXmlAttribute(xml, "app:title");
            if (title == null || title.isEmpty()) {
                title = findXmlAttribute(xml, "android:title");
            }
            JSONObject result = NodeUiActivityClient.get().show(
                    context, projectDir, layoutName, layoutId, className, title);
            if (result == null) {
                sActivityError = "activity_client_returned_null";
                Log.i(TAG, "C-ACTIVITY show 返回 null，回退 overlay");
                return false;
            }
            sActivityMode = true;
            sActivityError = "";
            sActivitySession = result.optString(NodeUiProtocol.K_SESSION, "");
            setUiMode(UI_MODE_ACTIVITY, "show_ok");
            Log.i(TAG, "C-ACTIVITY mode=activity session=" + sActivitySession
                    + " layout=" + layoutName + " project=" + projectDir);
            enqueueEvent("create");
            enqueueEvent("start");
            enqueueEvent("resume");
            // content_view_set 由 show() 在拿到 Activity 的真实 views 后入队（task-24：
            // Activity 模式的 binding 之前一直是空的，onContentViewSet 拿不到任何 id）。
            clearCloseRequested();
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "C-ACTIVITY show 失败，回退 overlay：" + describe(error), error);
            sActivityMode = false;
            sActivityError = describe(error);
            setUiMode(UI_MODE_NONE, "activity_show_failed: " + describe(error));
            return false;
        }
    }

    /**
     * Activity 模式下的 setAdapter/setPager 更新：走 A 的 NodeUiActivityClient.update(JSONObject)。
     *
     * <p>主进程 startActivity 返回后 Activity 的 onCreate/inflate 可能还没完成，B 的 JS 会紧接着
     * setPager/setAdapter；这里对「尚未完成 inflate/没有打开」这类可恢复错误做短重试（≤2s）。
     */
    private static JSONObject forwardToActivity(String op, JSONObject request) throws Exception {
        JSONObject last = null;
        for (int attempt = 1; attempt <= 12; attempt++) {
            last = invokeActivityUpdate(op, request);
            if (last.optBoolean("ok", false) || !isActivityUpdateRetryable(last.optString("error", ""))) {
                return last;
            }
            Log.i(TAG, "C-ACTIVITY update 等待 Activity 就绪 attempt=" + attempt
                    + " op=" + op + " error=" + last.optString("error", ""));
            try {
                Thread.sleep(150L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return last == null
                ? new JSONObject().put("ok", false).put("error", "Activity 模式更新失败（" + op + "）")
                : last;
    }

    private static JSONObject invokeActivityUpdate(String op, JSONObject request) throws Exception {
        try {
            request.put("op", op);
            java.lang.reflect.Method update = NodeUiActivityClient.class.getMethod("update", JSONObject.class);
            Object raw = update.invoke(NodeUiActivityClient.get(), request);
            if (raw == null) {
                return new JSONObject().put("ok", false)
                        .put("error", "Activity 模式更新通道未返回（" + op + "）");
            }
            if (raw instanceof JSONObject) {
                return (JSONObject) raw;
            }
            return new JSONObject(String.valueOf(raw));
        } catch (Throwable error) {
            Log.w(TAG, "C-ACTIVITY update 失败（" + op + "）：" + describe(error));
            return new JSONObject().put("ok", false)
                    .put("error", "Activity 模式更新失败（" + op + "）：" + describe(error));
        }
    }

    private static boolean isActivityUpdateRetryable(String error) {
        if (error == null) {
            return false;
        }
        return error.contains("尚未完成") || error.contains("没有打开") || error.contains("inflate")
                || error.contains("超时") || error.contains("尚未打开");
    }

    private static Object closeCurrent() throws Exception {
        if (NodeUiLocalSession.isShowing()) {
            NodeUiLocalSession.close();
            return new JSONObject().put("ok", true);
        }
        if (sActivityMode) {
            Log.i(TAG, "C-ACTIVITY close: " + sActivitySession);
            try {
                NodeUiActivityClient.get().close();
            } catch (Throwable error) {
                Log.w(TAG, "C-ACTIVITY close 失败", error);
            }
            sActivityMode = false;
            CLOSE_REQUESTED.set(true);
            enqueueEvent("destroy");
            setUiMode(UI_MODE_NONE, "activity_close");
            return new JSONObject().put("ok", true).put("mode", "activity");
        }
        return closeOverlay();
    }

    // ---- task-76：主进程 NodeUiActivity 的 setPager/setAdapter 更新 ----

    /**
     * 主进程 NodeUiActivity.handleUpdate 调用：在 Activity 自己的 content 上执行 setPager/setAdapter。
     * 与 overlay 路径共用同一套 inflater/adapter，但显式传 content/themed/bundle（无 :node24 静态状态）。
     */
    public static JSONObject applyUpdateForActivity(View content, Context themed,
                                                    ProjectResourceBundle bundle, JSONObject request) {
        try {
            if (content == null || bundle == null) {
                return activityError("NodeUiActivity 内容视图为空");
            }
            String op = request == null ? "" : request.optString("op", "");
            if ("setPager".equals(op)) {
                return applyPagerForActivity(content, themed, bundle, request);
            }
            if ("setAdapter".equals(op)) {
                return applyAdapterForActivity(content, themed, bundle, request);
            }
            return activityError("未知的 Activity 更新 op：" + op);
        } catch (Throwable error) {
            Log.w(TAG, "C-ACTIVITY update 失败: " + request, error);
            return activityError("Activity 更新失败：" + describe(error));
        }
    }

    private static JSONObject applyPagerForActivity(View content, Context themed, ProjectResourceBundle bundle,
                                                    JSONObject request) throws Exception {
        String viewName = request.optString("viewName", "viewPager");
        int viewId = request.optInt("viewId", 0);
        if (viewId == 0) {
            viewId = bundle.idOf("id", viewName);
        }
        View pagerView = viewId == 0 ? null : findInlineViewById(content, viewId);
        if (!(pagerView instanceof ViewPager)) {
            return activityError("找不到 ViewPager（view=" + viewName + "）");
        }
        ViewPager pager = (ViewPager) pagerView;
        List<String> pageNames = resolvePageNames(bundle, request);
        List<View> pages = new ArrayList<>();
        DynamicLayoutInflater inflater = bundle.newLayoutInflater(themed);
        for (String name : pageNames) {
            String path = bundle.layoutFile(name);
            if (path == null) {
                continue;
            }
            String xml = readTextFile(new File(path));
            if (xml == null || xml.isEmpty()) {
                continue;
            }
            try {
                pages.add(inflater.inflate(xml));
            } catch (Throwable pageError) {
                Log.w(TAG, "C-ACTIVITY setPager page 失败，跳过: " + name, pageError);
            }
        }
        if (pages.isEmpty()) {
            return activityError("没有可 inflate 的页面布局（page_*）");
        }
        pager.setAdapter(new NodeUiPagerAdapter(pages, resolveWindowBackground(themed)));
        try {
            pager.setOffscreenPageLimit(pages.size());
        } catch (Throwable ignored) {
            // offscreen 限制失败不影响显示
        }
        Log.i(TAG, "C-ACTIVITY setPager view=" + viewName + " pages=" + pages.size()
                + " names=" + pageNames);
        return new JSONObject().put("ok", true).put("view", viewName).put("pageCount", pages.size());
    }

    private static JSONObject applyAdapterForActivity(View content, Context themed, ProjectResourceBundle bundle,
                                                      JSONObject request) throws Exception {
        String viewName = request.optString("viewName", "grid");
        int viewId = request.optInt("viewId", 0);
        if (viewId == 0) {
            viewId = bundle.idOf("id", viewName);
            if (viewId == 0 && "grid".equals(viewName)) {
                viewId = bundle.idOf("id", "grid");
            }
        }
        View target = viewId == 0 ? null : findInlineViewById(content, viewId);
        if (!(target instanceof RecyclerView)) {
            return activityError("找不到 RecyclerView（view=" + viewName + "）");
        }
        int requestedLayoutId = request.optInt("itemLayoutId", 0);
        JSONArray items = request.optJSONArray("items");
        int spanCount = request.optInt("spanCount", 2);
        String layoutName = null;
        String itemXml = null;
        if (requestedLayoutId != 0) {
            layoutName = bundle.layoutNameById(requestedLayoutId);
            String path = bundle.layoutFileById(requestedLayoutId);
            if (path != null) {
                itemXml = readTextFile(new File(path));
            }
        }
        if (itemXml == null) {
            if (layoutName == null || layoutName.isEmpty()) {
                layoutName = "grid_item";
            }
            String path = bundle.layoutFile(layoutName);
            if (path != null) {
                itemXml = readTextFile(new File(path));
            }
        }
        boolean fallbackCard = itemXml == null || itemXml.isEmpty();
        if (!fallbackCard) {
            try {
                View probe = bundle.newLayoutInflater(themed).inflate(itemXml);
                if (probe == null) {
                    fallbackCard = true;
                }
            } catch (Throwable error) {
                Log.w(TAG, "C-ACTIVITY grid_item inflate 失败，使用内置占位卡片", error);
                fallbackCard = true;
            }
        }
        int titleId = bundle.idOf("id", "title");
        int imageId = bundle.idOf("id", "image");
        RecyclerView recyclerView = (RecyclerView) target;
        JSONArray safeItems = items == null ? new JSONArray() : items;
        recyclerView.setLayoutManager(new GridLayoutManager(themed, spanCount > 0 ? spanCount : 2));
        recyclerView.setAdapter(new NodeUiAdapter(themed, bundle,
                fallbackCard ? null : itemXml, safeItems, titleId, imageId));
        recyclerView.setClipToPadding(false);
        recyclerView.requestLayout();
        Log.i(TAG, "C-ACTIVITY setAdapter view=" + viewName + " items=" + safeItems.length()
                + " itemLayout=" + (fallbackCard ? "fallback-card" : layoutName) + " viewId=" + viewId);
        return new JSONObject().put("ok", true)
                .put("view", viewName)
                .put("items", safeItems.length())
                .put("itemLayout", fallbackCard ? "fallback-card" : layoutName);
    }

    private static JSONObject activityError(String message) {
        try {
            return new JSONObject().put("ok", false).put("error", message);
        } catch (Throwable error) {
            return new JSONObject();
        }
    }

    // ---- 供主进程 NodeUiActivity 复用的公开 helper ----

    /** 给主进程 Activity 的 M3/项目主题上下文。 */
    public static Context wrapThemedContext(Context context, ProjectResourceBundle bundle) {
        return themedContextFor(context, bundle);
    }

    public static JSONObject buildViewsForScale(View content, ProjectResourceBundle bundle) {
        return buildViews(content, bundle);
    }

    public static void wireNavigationFor(View content, ProjectResourceBundle bundle, JSONObject views) {
        wireNavigation(content, bundle, views);
    }

    public static void applyToolbarTitleFor(View content, String xml, JSONObject views) {
        applyToolbarTitle(content, xml, views);
    }

    // ------------------------------------------------------------------
    // 资源（统一走 C 的 ProjectResourceBundle）
    // ------------------------------------------------------------------

    private static Object loadAndroidResources(Context context, String projectDir) throws Exception {
        if (context instanceof com.stardust.autojs.core.ui.inflater.ScriptResourceContext) {
            com.stardust.autojs.core.ui.inflater.ScriptResourceContext script =
                    (com.stardust.autojs.core.ui.inflater.ScriptResourceContext) context;
            File compiled = new File(projectDir, "build/project-res.apk");
            if (script.getCompiledResources() == null && compiled.isFile()) script.loadResources(compiled.getPath());
        }
        // Explicit loads start a fresh resource snapshot. A project can be rebuilt/pushed while
        // the pooled Node process stays alive; retaining its old synthetic R table makes the
        // Activity and the script disagree even after a valid compiled APK has arrived.
        synchronized (LOCK) {
            BUNDLE_CACHE.clear();
        }
        ProjectResourceBundle bundle = bundleOf(context, projectDir);
        if (bundle == null) {
            return new JSONObject().put("ok", false).put("error", "工程目录为空：" + projectDir);
        }
        // task-60 约定：load 成功后打一行 C-RES: describeJson()，dev-986 用这行定位资源链路。
        String description = bundle.describeJson();
        if (bundle.getError() != null) {
            Log.w(TAG, "C-RES: " + description);
            return new JSONObject().put("ok", false).put("error", bundle.getError());
        }
        Log.i(TAG, "C-RES: " + description);
        JSONObject result = new JSONObject(description);
        if (!result.optBoolean("ok", false)) {
            return new JSONObject().put("ok", false)
                    .put("error", result.optString("error", "工程 Android 资源加载失败"));
        }
        return result;
    }

    private static Object layoutId(Context context, String projectDir, JSONObject request) throws Exception {
        ProjectResourceBundle bundle = bundleOf(context, projectDir);
        if (bundle == null || bundle.getError() != null) {
            return new JSONObject().put("ok", false)
                    .put("error", bundle == null ? "工程目录为空" : bundle.getError());
        }
        String name = request.optString("name", "");
        return new JSONObject().put("ok", true).put("id", bundle.layoutId(name));
    }

    private static Object resourceId(Context context, String projectDir, JSONObject request) throws Exception {
        String type = request.optString("type", "");
        String name = request.optString("name", "");
        ProjectResourceBundle bundle = bundleOf(context, projectDir);
        if (bundle == null || bundle.getError() != null) {
            // Inline UI can use the host's bundled icons/styles without a project resource table.
            int resolved = context.getResources().getIdentifier(name, type, context.getPackageName());
            return new JSONObject().put("ok", true).put("id", inlineResourceIdFallback(resolved, type, name));
        }
        return new JSONObject().put("ok", true).put("id", bundle.idOf(type, name));
    }

    /**
     * 2.7：非编译（inline）工程的 {@code ui.R.id.*} 必须与布局/菜单项同一张名字表。
     *
     * <p>布局 {@code @+id/xxx} 与 BottomNavigationView 的 {@code app:menu} 项 id 都走
     * {@link Ids#parse(String)}；而 {@code getIdentifier(name, "id", hostPackage)} 只查宿主 APK 的资源表，
     * 工程自建 id（如 navigation_home）在宿主里是 0，于是脚本里
     * {@code ui.R.id.navigation_home} 与 {@code item.getItemId()} 对不上。这里对 id 类型补上
     * {@link Ids#parse(String)} 兜底；其它资源类型仍按宿主资源表（找不到就是 0）。
     */
    static int inlineResourceIdFallback(int resolved, String type, String name) {
        if (resolved != 0 || name == null || name.isEmpty()) {
            return resolved;
        }
        if (!"id".equals(type)) {
            return resolved;
        }
        return Ids.parse(name);
    }

    /** 2.4：数字布局 id 反查布局名（宿主不支持时返回空名，JS 回退到只按 id 请求）。 */
    private static Object layoutName(Context context, String projectDir, JSONObject request) throws Exception {
        int id = request.optInt("id", 0);
        if (id == 0) {
            return new JSONObject().put("ok", true).put("name", "");
        }
        ProjectResourceBundle bundle = bundleOf(context, projectDir);
        String name = bundle == null || bundle.getError() != null ? "" : bundle.layoutNameById(id);
        return new JSONObject().put("ok", true).put("name", name == null ? "" : name);
    }

    /** 取（并缓存）工程资源包；加载失败也缓存错误，避免每次属性读取都重扫 res/。 */
    private static ProjectResourceBundle bundleOf(Context context, String projectDir) {
        if (projectDir == null || projectDir.isEmpty()) {
            return null;
        }
        String key = projectDir;
        if (context instanceof com.stardust.autojs.core.ui.inflater.ScriptResourceContext) {
            key += "@" + ((com.stardust.autojs.core.ui.inflater.ScriptResourceContext) context).getResourceOwner() + ":" +
                    ((com.stardust.autojs.core.ui.inflater.ScriptResourceContext) context).getResourceRevision();
        }
        synchronized (LOCK) {
            ProjectResourceBundle cached = BUNDLE_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        ProjectResourceBundle bundle = ProjectResourceBundle.load(context, new File(projectDir));
        synchronized (LOCK) {
            BUNDLE_CACHE.put(key, bundle);
        }
        return bundle;
    }

    private static String resolveProjectDir(String projectDir, JSONObject request) {
        if (projectDir != null && !projectDir.trim().isEmpty()) {
            return projectDir.trim();
        }
        return request == null ? "" : request.optString("projectDir", "");
    }

    // ------------------------------------------------------------------
    // 显示 overlay
    // ------------------------------------------------------------------

    private static Object show(final Context context, final String projectDir, JSONObject request) throws Exception {
        // task-218：owner 由 Node24HostBridge 注入；touchable 默认 true，绘制型入口显式传 false。
        sOverlayOwnerEngineId = request.optInt("engineId", request.optInt("ownerEngineId", -1));
        sOverlayOwnerTag = request.optString("owner", "");
        sOverlayTouchable = request.optBoolean("touchable", true);
        if (request.optBoolean("realJava")) {
            synchronized (LOCK) { EVENTS.clear(); }
            clearCloseRequested();
            return NodeUiLocalSession.show(context, projectDir, request);
        }
        final ProjectResourceBundle bundle = bundleOf(context, projectDir);
        final String inlineXml = request.optString("layoutXml", "").trim();
        final boolean inline = !inlineXml.isEmpty();
        final String javaClassOwner = request.optString("javaClassOwner", "");
        final ClassLoader runtimeClasses = org.autojs.source.engine.android.Node24GeneratedClasses.loader(
                javaClassOwner, context.getClassLoader());
        if (!inline) {
            if (bundle == null) {
                return new JSONObject().put("ok", false).put("error", "工程目录为空：" + projectDir);
            }
            if (bundle.getError() != null) {
                return new JSONObject().put("ok", false).put("error", bundle.getError());
            }
        }
        final int requestedId = request.optInt("layoutId", 0);
        JSONObject layout = request.optJSONObject("layout");
        final String requestedName = layout == null ? "" : layout.optString("name", "");
        final String className = request.optString("className", "MainActivity");

        final String xml;
        final String shownName;
        final int shownId;
        if (inline) {
            // Activity.layoutXml：字符串布局不依赖 project.json/res/build。Activity 侧通过
            // setContentViewXml 更新真正 inflate，overlay 回退就地 inflate。
            xml = inlineXml;
            shownName = INLINE_LAYOUT_TOKEN;
            shownId = 0;
        } else {
            int layoutId = requestedId;
            String layoutName = layoutId != 0 ? bundle.layoutNameById(layoutId) : null;
            String layoutPath = layoutId != 0 ? bundle.layoutFileById(layoutId) : null;
            if ((layoutPath == null || layoutPath.isEmpty()) && !requestedName.isEmpty()) {
                layoutName = requestedName;
                layoutPath = bundle.layoutFile(requestedName);
                if (layoutId == 0) {
                    layoutId = bundle.layoutId(requestedName);
                }
            }
            if (layoutPath == null || layoutPath.isEmpty()) {
                return new JSONObject().put("ok", false)
                        .put("error", "找不到布局（layoutId=" + requestedId + ", name=" + requestedName
                                + "）；请检查 res/layout 与 project.json androidResources");
            }
            String fileText = readTextFile(new File(layoutPath));
            if (fileText == null || fileText.isEmpty()) {
                return new JSONObject().put("ok", false).put("error", "读取布局文件失败：" + layoutPath);
            }
            xml = fileText;
            shownName = layoutName == null || layoutName.isEmpty() ? requestedName : layoutName;
            shownId = layoutId;
        }
        sShownProjectDir = projectDir;
        sShownLayout = shownName;
        final String displayLayout = inline ? "inline" : "layout/" + shownName;
        // task-95：Activity 已显示 → 复用，绝不再创建 overlay（单通道互斥）。
        if (javaClassOwner.isEmpty() && sActivityMode && sUiMode == UI_MODE_ACTIVITY) {
            Log.i(TAG, "C-UI: mode=activity reuse session=" + sActivitySession);
            return new JSONObject().put("ok", true)
                    .put("mode", "activity")
                    .put("reused", true)
                    .put("layout", displayLayout)
                    .put("layoutId", shownId)
                    .put("className", className)
                    .put("activityToken", sActivitySession == null ? "" : sActivitySession)
                    .put("views", sShownViews == null ? new JSONObject() : sShownViews);
        }
        // 新一轮 UI：复用分支已返回，这里丢弃上一轮运行残留的静态事件。旧 destroy 若被新一轮
        // ui.js 的 destroy 清理消费，会立即关掉刚打开的界面；必须在任何新生命周期事件入队前清空。
        synchronized (LOCK) {
            EVENTS.clear();
        }
        // 同时清掉旧 closeRequested，避免 A 的 Java lifetime watcher 在新 Activity 尚未
        // hasNodeUiActivity() 时读到 stale 标记而误判关闭（tryActivityMode 成功后会再次清）。
        clearCloseRequested();
        // task-95：切换 Activity 前先清掉旧 overlay（不置 closeRequested，避免误杀 keep-alive）。
        if (sOverlayView != null) {
            try {
                runOnMain(new Runnable() {
                    @Override
                    public void run() {
                        removeOverlayLocked();
                    }
                });
            } catch (Throwable error) {
                Log.w(TAG, "切换 Activity 前移除旧 overlay 失败", error);
            }
            setUiMode(UI_MODE_NONE, "switch_to_activity");
        }
        // task-76：默认尝试主进程真 Activity；失败（A 服务未就绪/绑超时）回退 overlay。
        ensureActivityListener();
        if (javaClassOwner.isEmpty() && tryActivityMode(context, projectDir, shownName, shownId, className, xml)) {
            JSONObject views = new JSONObject();
            if (inline) {
                JSONObject applied = forwardToActivity("setContentViewXml",
                        new JSONObject().put("xml", inlineXml));
                if (!applied.optBoolean("ok", false)) {
                    String error = applied.optString("error", "未知错误");
                    Log.w(TAG, "C-INLINE: NodeUiActivity 无法显示 layoutXml：" + error);
                    try {
                        NodeUiActivityClient.get().close();
                    } catch (Throwable closeError) {
                        Log.w(TAG, "C-INLINE: 关闭失败的 Activity 也失败", closeError);
                    }
                    sActivityMode = false;
                    sActivitySession = "";
                    sShownViews = null;
                    synchronized (LOCK) {
                        EVENTS.clear();
                    }
                    setUiMode(UI_MODE_NONE, "inline_activity_failed: " + error);
                    sActivityError = "inline_activity_failed: " + error;
                    return new JSONObject().put("ok", false)
                            .put("error", "Activity 无法显示 layoutXml：" + error);
                }
                JSONObject appliedViews = applied.optJSONObject("views");
                if (appliedViews != null) {
                    views = appliedViews;
                }
            } else {
                // 等 Activity onCreate/inflate 完成，同时拿到真实 id 名→viewId（binding）。
                // 失败时退化为从 XML 里提取 id（旧行为是返回空 views，不能更差）。
                JSONObject ready = forwardToActivity("getViews", new JSONObject());
                JSONObject readyViews = ready.optJSONObject("views");
                if (ready.optBoolean("ok", false) && readyViews != null) {
                    views = readyViews;
                } else {
                    views = collectViewsFromXml(xml, bundle);
                    Log.w(TAG, "C-ACTIVITY getViews 未就绪，binding 使用 XML id 兜底: "
                            + ready.optString("error", ""));
                }
            }
            sShownViews = views;
            enqueueEvent(NodeUiProtocol.EVENT_CONTENT_VIEW_SET, views);
            return new JSONObject().put("ok", true)
                    .put("mode", "activity")
                    .put("layout", displayLayout)
                    .put("layoutId", shownId)
                    .put("className", className)
                    .put("activityToken", sActivitySession == null ? "" : sActivitySession)
                    .put("views", views);
        }
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMain(new Runnable() {
            @Override
            public void run() {
                try {
                    removeOverlayLocked();
                    // task-61：MaterialComponents 控件（AppBarLayout/CoordinatorLayout…）需要 Material 主题，
                    // 普通 application context 会 InflateException（dev-987：layout/js_appbar AppBarLayout）。
                    // 1) 优先项目 compiled MainTheme（project-res.apk 真 id）叠加；
                    // 2) 基底用反射解析到的 MaterialComponents 主题，回退 android.R.style.Theme_Material_Light_NoActionBar；
                    // 3) 仍失败时用 App 主题 inflate 一次兜底，错误保留中文返回。
                    Context themed = themedContextFor(context, bundle);
                    NodeWebView.prepareProcess(context.getApplicationContext());
                    View content;
                    try {
                        content = inline
                                ? inflateInlineLayout(themed, xml, runtimeClasses)
                                : bundle.newLayoutInflater(themed).inflate(xml);
                    } catch (Throwable first) {
                        Log.w(TAG, "C-THEME: Material 主题 inflate 失败，回退 App 主题重试: " + describe(first));
                        Context fallback = fallbackContextFor(context);
                        content = inline
                                ? inflateInlineLayout(fallback, xml, runtimeClasses)
                                : bundle.newLayoutInflater(fallback).inflate(xml);
                    }
                    content = NodeWebView.replaceJsWebViews(content);
                    // task-69：保存内容视图/主题/资源包，并采集 id 名 → viewId 映射给 JS binding。
                    sThemedContext = themed;
                    sShownBundle = bundle;
                    sContentView = content;
                    sShownViews = inline ? buildInlineViews(content, xml) : buildViews(content, bundle);
                    // task-73：AppBar 标题（MaterialToolbar app:title 可能没被 inflater 设置，宿主兜底）。
                    applyToolbarTitle(content, xml, sShownViews);
                    // task-70：底部导航 ↔ ViewPager、Toolbar → Drawer 的基础联动（宿主侧，不依赖 JS 回调）。
                    wireNavigation(content, bundle, sShownViews);
                    sOverlayView = buildOverlay(themed, content);
                    WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                    sWindowManager = manager;
                    if (!sOverlayTouchable) {
                        // 绘制型 UI：不拦截触摸（规格 3.2）。
                        sOverlayView.setClickable(false);
                        sOverlayView.setFocusable(false);
                        sOverlayView.setFocusableInTouchMode(false);
                    }
                    manager.addView(sOverlayView, overlayParams(sOverlayTouchable));
                    // task-218：统一登记，引擎结束/owner 清理时可 closeByOwner。
                    OverlayRegistry.register(sOverlayOwnerEngineId, sOverlayOwnerTag,
                            "nodejs", OverlayRegistry.Kind.NODE_UI,
                            sOverlayView, manager, sOverlayTouchable);
                    final View overlayForFocus = sOverlayView;
                    overlayForFocus.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            checkOverlayFocus(overlayForFocus);
                        }
                    }, 1200L);
                    overlayForFocus.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            // 二次检查（有些 ROM 窗口焦点来得晚）。已装 ✕ 时 install 会幂等跳过。
                            checkOverlayFocus(overlayForFocus);
                        }
                    }, 4000L);
                    Log.i(TAG, "C-CLOSE: modal=back（overlay 默认无按钮，返回键关闭；焦点检查 1.2s/4s）");
                    sShownProjectDir = projectDir;
                    sShownLayout = shownName;
                    enqueueEvent("create");
                    enqueueEvent("start");
                    enqueueEvent("resume");
                    enqueueEvent(NodeUiProtocol.EVENT_CONTENT_VIEW_SET, sShownViews);
                    clearCloseRequested();
                    String fallbackReason = sActivityError == null || sActivityError.isEmpty()
                            ? "activity_unavailable" : "activity_unavailable: " + sActivityError;
                    setUiMode(UI_MODE_OVERLAY, fallbackReason);
                    Log.i(TAG, "Node UI overlay 已显示: layout=" + displayLayout + " id=" + shownId
                            + " class=" + className + " project=" + projectDir
                            + " theme=" + sThemeDiagnostics);
                } catch (Throwable error) {
                    // addView/inflate 失败时不能留下「看似有 overlay」的 stale 引用（task-63 hasOverlay）。
                    removeOverlayLocked();
                    failure.set(error);
                }
            }
        });
        if (failure.get() != null) {
            setUiMode(UI_MODE_NONE, "overlay_show_failed: " + describe(failure.get()));
            return new JSONObject().put("ok", false)
                    .put("error", "inflate 布局失败（" + displayLayout + "，主题=" + sThemeDiagnostics + "）："
                            + describe(failure.get()));
        }
        return new JSONObject().put("ok", true)
                .put("mode", "overlay")
                .put("reason", sUiModeReason)
                .put("layout", displayLayout)
                .put("layoutId", shownId)
                .put("className", className)
                .put("views", sShownViews == null ? new JSONObject() : sShownViews);
    }

    // ------------------------------------------------------------------
    // Activity.layoutXml：不依赖工程 res/build 的字符串布局
    // ------------------------------------------------------------------

    /**
     * inflate Auto.js 字符串布局（{@code Activity.layoutXml}）。
     *
     * <p>入口不加载 project.json/res/project-res.apk，因此工程资源引用（@drawable/@color…）
     * 不可用；基础框架控件、material 控件与 Auto.js 简称（column/row/text/button/input…）可用。
     * grid/list/canvas 使用不依赖 ScriptRuntime 的 NodeUi* 真实控件（RecyclerView/View），
     * item 数据由 Node 侧 setItemTemplate/setDataSource 填充。
     */
    /** task-230：批量 inflate 的 Node 侧统一入口（Delegate 到持有 Activity 的 NodeUiLocalSession）。 */
    public static View[] inflateLayoutBatch(int layoutId, int count, ViewGroup parent) {
        return NodeUiLocalSession.inflateLayoutBatch(layoutId, count, parent);
    }

    /** task-230：批量 XML inflate 的 Node 侧统一入口（Delegate 到 NodeUiLocalSession）。 */
    public static View[] inflateXmlBatch(String xml, int count, ViewGroup parent) {
        return NodeUiLocalSession.inflateXmlBatch(xml, count, parent);
    }

    public static View inflateInlineLayout(Context themed, String xml) {
        return inflateInlineLayout(themed, xml, themed == null ? null : themed.getClassLoader());
    }

    public static View inflateInlineLayout(Context themed, String xml, ClassLoader loader) {
        return inflateInlineLayout(themed, xml, loader, null, false);
    }

    /**
     * ui.inflate(xml, parent, attachToParent) 的底层实现。
     *
     * <p>parent 非空时 DynamicLayoutInflater 会按 parent 的真实类型生成 LayoutParams
     * （LinearLayout/FrameLayout/DrawerLayout…），attachToParent=true 时再 addView；
     * 这样 layout_margin/layout_gravity/weight 等属性不会在挂载时丢失。
     */
    public static View inflateInlineLayout(Context themed, String xml, ClassLoader loader,
                                           ViewGroup parent, boolean attachToParent) {
        if (themed == null) {
            throw new IllegalArgumentException("layoutXml 缺少主题 Context");
        }
        if (xml == null || xml.trim().isEmpty()) {
            throw new IllegalArgumentException("layoutXml 为空");
        }
        if (attachToParent && parent == null) {
            throw new IllegalArgumentException("attachToParent=true 需要非空 parent");
        }
        NodeWebView.prepareProcess(themed.getApplicationContext());
        DynamicLayoutInflater inflater = newInlineLayoutInflater(themed);
        String normalized = inflater instanceof NodeUiWidgetInflater ? xml : normalizeInlineLayoutXml(xml);
        if (loader != null) inflater.setClassLoader(loader);
        // 没有 parent 时 root 的 layout_width/layout_height/layout_margin 也需要一个 LP 宿主；
        // 用一个不挂窗口的临时 FrameLayout 生成 LP（attachToParent 必须为 false），
        // 否则这些属性会被 BaseViewInflater 在 layoutParams==null 时跳过。
        ViewGroup inflationParent = parent;
        if (inflationParent == null) {
            inflationParent = new FrameLayout(themed);
            if (inflater instanceof NodeUiWidgetInflater) {
                ((NodeUiWidgetInflater) inflater).setSyntheticParent(inflationParent);
            }
        }
        View root = inflater.inflate(normalized, inflationParent, attachToParent);
        if (root == null) {
            return null;
        }
        // 兜底：若工程自定义 ClassLoader/旧路径仍产出 JsWebView，也换成带 Node 桥的真实 WebView。
        View replaced = NodeWebView.replaceJsWebViews(root);
        if (replaced != root && attachToParent && parent != null && root.getParent() == parent) {
            // 根节点本身是 JsWebView 时 replace 会新建实例；已经挂载的旧根必须原位换掉。
            int index = parent.indexOfChild(root);
            ViewGroup.LayoutParams params = root.getLayoutParams();
            parent.removeView(root);
            if (params != null) parent.addView(replaced, index, params);
            else parent.addView(replaced, index);
        }
        return replaced;
    }

    private static DynamicLayoutInflater newInlineLayoutInflater(Context context) {
        ResourceParser parser = new ResourceParser(new Drawables());
        NodeUiWidgetInflater.Registration widgets = NodeUiLocalSession.widgetRegistration(context);
        DynamicLayoutInflater inflater = widgets == null ? new DynamicLayoutInflater(parser)
                : new NodeUiWidgetInflater(parser, widgets);
        inflater.setContext(context);
        // Node 原生 grid/list/canvas：不依赖 ScriptRuntime 的真实控件。
        inflater.registerViewAttrSetter(NodeUiGridView.class.getName(),
                new NodeUiRecyclerViewInflater(parser, true));
        inflater.registerViewAttrSetter(NodeUiListView.class.getName(),
                new NodeUiRecyclerViewInflater(parser, false));
        inflater.registerViewAttrSetter(NodeUiCanvasView.class.getName(),
                new BaseViewInflater<NodeUiCanvasView>(parser));
        try {
            if (context.getClassLoader() != null) {
                inflater.setClassLoader(context.getClassLoader());
            }
        } catch (Throwable error) {
            Log.w(TAG, "C-INLINE: 设置 ClassLoader 失败（继续）", error);
        }
        return inflater;
    }

    /**
     * Auto.js 简称 → XmlConverter 认识的标签。grid/list/canvas 换成 Node 的轻量真实控件
     * （NodeUiGridView/NodeUiListView/NodeUiCanvasView），不再用 Text 占位。
     */
    static String normalizeInlineLayoutXml(String xml) {
        String normalized = xml;
        for (String tag : new String[]{"column", "row", "webview", "switch", "grid", "list", "canvas"})
            normalized = renameInlineTag(normalized, tag, normalizeInlineTagName(tag));
        return normalized;
    }

    static String normalizeInlineTagName(String tag) {
        switch (tag.toLowerCase(java.util.Locale.ROOT)) {
            case "column": return "vertical";
            case "row": return "horizontal";
            case "webview": return NodeWebView.class.getName();
            case "switch": return "androidx.appcompat.widget.SwitchCompat";
            case "grid": return NodeUiGridView.class.getName();
            case "list": return NodeUiListView.class.getName();
            case "canvas": return NodeUiCanvasView.class.getName();
            case "materialcard": return "com.google.android.material.card.MaterialCardView";
            default: return tag;
        }
    }

    /**
     * item 模板的 Node 化：column/row/materialcard/switch 换成可直接 inflate 的控件。
     * 保留 {@code {{this.xxx}}}，由 NodeUiRecyclerView 在 bind 时用当前 item 求值。
     */
    public static String normalizeItemTemplateXml(String xml) {
        if (xml == null) return "";
        String normalized = renameInlineTag(xml, "column", "vertical");
        normalized = renameInlineTag(normalized, "row", "horizontal");
        normalized = renameInlineTag(normalized, "materialcard",
                "com.google.android.material.card.MaterialCardView");
        normalized = renameInlineTag(normalized, "switch", "androidx.appcompat.widget.SwitchCompat");
        return normalized;
    }

    /** item 模板 inflate（主线程，由 NodeUiRecyclerView.onBindViewHolder 调用）。 */
    public static View inflateItemTemplate(Context context, String xml, ViewGroup parent) {
        DynamicLayoutInflater inflater = newInlineLayoutInflater(context);
        if (context.getClassLoader() != null) {
            inflater.setClassLoader(context.getClassLoader());
        }
        return inflater.inflate(inflater instanceof NodeUiWidgetInflater ? xml : normalizeItemTemplateXml(xml), parent, false);
    }

    private static String renameInlineTag(String xml, String from, String to) {
        String open = "(?i)<\\s*" + Pattern.quote(from) + "(?=\\s|/|>)";
        String close = "(?i)<\\s*/\\s*" + Pattern.quote(from) + "\\s*>";
        return xml.replaceAll(open, "<" + to).replaceAll(close, "</" + to + ">");
    }

    /**
     * task-190：按 id 查找 XML 控件时跳过 ConsoleView/globalconsole 的内部控件。
     * 示例 <globalconsole> 内部自带 @+id/input，与脚本 XML 的 <input id="input"> 同名；
     * 普通 findViewById 会先命中 console 内部（GONE 后也不应作为 binding 目标）。
     */
    public static View findInlineViewById(View root, int id) {
        if (root == null || id == 0) {
            return null;
        }
        if (root.getId() == id) {
            return root;
        }
        if (!(root instanceof android.view.ViewGroup)) {
            return null;
        }
        // 不进入 ConsoleView/globalconsole 的内部视图树，避免同名 id 抢到控制台 input/submit。
        if (root instanceof com.stardust.autojs.core.console.ConsoleView) {
            return null;
        }
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findInlineViewById(group.getChildAt(i), id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** 字符串布局 inflate 后按 XML 里的 @+id 建 name→viewId；供 binding 真实查找。 */
    public static JSONObject buildInlineViews(View content, String xml) {
        JSONObject views = new JSONObject();
        if (content == null || xml == null) {
            return views;
        }
        try {
            for (String name : NodeInlineLayoutIds.names(xml)) {
                if (name == null || name.isEmpty() || views.has(name)) {
                    continue;
                }
                int id = Ids.parse(name);
                if (id != 0 && findInlineViewById(content, id) != null) {
                    views.put(name, id);
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "C-INLINE: 采集 binding views 失败", error);
        }
        Log.i(TAG, "C-VIEWS: inline " + views);
        return views;
    }

    /** res 布局在 Activity 模式下的备用 views（无法查 Activity 内容时从 XML + 资源表推）。 */
    private static JSONObject collectViewsFromXml(String xml, ProjectResourceBundle bundle) {
        JSONObject views = new JSONObject();
        if (bundle == null || bundle.getError() != null || xml == null) {
            return views;
        }
        try {
            Matcher matcher = XML_ID_PATTERN.matcher(xml);
            while (matcher.find()) {
                String name = matcher.group(1);
                if (name == null || name.isEmpty() || views.has(name)) {
                    continue;
                }
                int id = bundle.idOf("id", name);
                if (id != 0) {
                    views.put(name, id);
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "C-VIEWS: 从 XML 兜底采集失败", error);
        }
        return views;
    }

    /**
     * task-61：overlay inflate 的主题上下文。
     *
     * <p>基底用 MaterialComponents 主题（dev-987 的 AppBarLayout/CoordinatorLayout 必须有这个主题才能 inflate），
     * 有项目 compiled MainTheme（project-res.apk 真 id）时再 applyStyle 叠加项目样式；
     * 没有 Material 库/主题时回退系统 {@code android.R.style.Theme_Material_Light_NoActionBar}。
     */
    private static Context themedContextFor(Context context, ProjectResourceBundle bundle) {
        int themeId = resolveMaterialThemeId();
        ContextThemeWrapper wrapper = new ContextThemeWrapper(context, themeId);
        StringBuilder diagnostics = new StringBuilder("base=").append(sThemeBaseLabel)
                .append(", material=").append(themeId);
        if (bundle != null && bundle.isCompiled()) {
            int projectTheme = bundle.idOf("style", "MainTheme");
            if (projectTheme != 0) {
                try {
                    wrapper.getTheme().applyStyle(projectTheme, true);
                    diagnostics.append(", projectMainTheme=").append(projectTheme);
                    Log.i(TAG, "C-THEME: 已叠加项目 compiled MainTheme id=" + projectTheme);
                } catch (Throwable error) {
                    diagnostics.append(", projectMainTheme=failed");
                    Log.w(TAG, "C-THEME: 项目 MainTheme 不能用于 overlay，继续用 Material 基底", error);
                }
            } else {
                diagnostics.append(", projectMainTheme=missing");
            }
        } else {
            diagnostics.append(", compiled=false");
        }
        sThemeDiagnostics = diagnostics.toString();
        Log.i(TAG, "C-THEME: " + sThemeDiagnostics);
        return wrapper;
    }

    /** inflate 失败兜底：App 自己的主题（ApplicationInfo.theme）；没有就用系统 Material 主题。 */
    private static Context fallbackContextFor(Context context) {
        int theme = context.getApplicationInfo() == null ? 0 : context.getApplicationInfo().theme;
        if (theme == 0) {
            theme = android.R.style.Theme_Material_Light_NoActionBar;
        }
        return new ContextThemeWrapper(context, theme);
    }

    /**
     * task-70：反射 com.google.android.material.R$style 选主题，**优先 Material3**（M3 控件：
     * MaterialSwitch/ShapeableImageView/MaterialButton Widget.Material3.* 需要 M3 主题），
     * 取不到再回退 MaterialComponents，最后回退系统 Material 主题。
     */
    private static synchronized int resolveMaterialThemeId() {
        if (sMaterialThemeId >= 0) {
            return sMaterialThemeId;
        }
        String[][] candidates = {
                {"m3", "Theme_Material3_DayNight_NoActionBar"},
                {"m3", "Theme_Material3_Light_NoActionBar"},
                {"m3", "Theme_Material3_Dark_NoActionBar"},
                {"m3", "Theme_Material3_Light"},
                {"m3", "Theme_Material3_DayNight"},
                {"m3", "Theme_Material3_Dark"},
                {"m3", "Theme_Material3"},
                {"bridge", "Theme_MaterialComponents_Light_NoActionBar_Bridge"},
                {"bridge", "Theme_MaterialComponents_DayNight_NoActionBar"},
                {"bridge", "Theme_MaterialComponents_Light_NoActionBar"},
                {"bridge", "Theme_MaterialComponents_NoActionBar"},
                {"bridge", "Theme_MaterialComponents_Light"},
                {"bridge", "Theme_MaterialComponents_DayNight"},
                {"bridge", "Theme_MaterialComponents"}
        };
        try {
            Class<?> style = Class.forName("com.google.android.material.R$style");
            for (String[] candidate : candidates) {
                try {
                    Field field = style.getField(candidate[1]);
                    int id = field.getInt(null);
                    if (id != 0) {
                        sMaterialThemeId = id;
                        sThemeBaseLabel = candidate[0] + ":" + candidate[1];
                        Log.i(TAG, "C-THEME: 使用 " + sThemeBaseLabel + " id=" + id);
                        return id;
                    }
                } catch (Throwable ignored) {
                    // 候选主题在这个 material 版本里不存在：试下一个。
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "C-THEME: 反射 Material 主题失败，回退系统 Material 主题", error);
        }
        sMaterialThemeId = android.R.style.Theme_Material_Light_NoActionBar;
        sThemeBaseLabel = "android:Theme_Material_Light_NoActionBar";
        Log.i(TAG, "C-THEME: 使用系统回退主题 " + sThemeBaseLabel);
        return sMaterialThemeId;
    }

    /** task-69：把 inflate 后的 View id 名字映射交给 JS（view.binding.<name>）。 */
    private static JSONObject buildViews(View content, ProjectResourceBundle bundle) {
        JSONObject views = new JSONObject();
        if (content == null || bundle == null) {
            return views;
        }
        try {
            JSONObject description = new JSONObject(bundle.describeJson());
            JSONArray ids = description.optJSONArray("ids");
            if (ids != null) {
                for (int i = 0; i < ids.length(); i++) {
                    String name = ids.optString(i, "");
                    if (name == null || name.isEmpty() || views.has(name)) {
                        continue;
                    }
                    int id = bundle.idOf("id", name);
                    if (id == 0) {
                        continue;
                    }
                    if (content.findViewById(id) != null) {
                        views.put(name, id);
                    }
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "采集 views 映射失败", error);
        }
        // describeJson().ids 是全量 id；这里再显式补示例用到的名字，防止 include 页 id 没进 ids 列表。
        try {
            String[] known = {"drawer", "viewPager", "toolbar", "fab", "navigation", "drawerNavigation",
                    "grid", "console", "toggleButton", "title", "image"};
            for (String name : known) {
                if (views.has(name)) {
                    continue;
                }
                int id = bundle.idOf("id", name);
                if (id != 0 && content.findViewById(id) != null) {
                    views.put(name, id);
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "补充已知 views id 失败", error);
        }
        Log.i(TAG, "C-VIEWS: " + views);
        return views;
    }

    /**
     * task-69：JS marker.setAdapter(adapter) 的宿主实现。
     *
     * <p>task-71：页面由 ViewPager 动态填充，grid 不在 sContentView 树上；这里先在
     * sContentView + sPageViews 找 RecyclerView，找不到就缓存请求（setPager 生成页面后补挂）。
     */
    private static Object setAdapter(final Context context, final String projectDir, final JSONObject request) throws Exception {
        if (sActivityMode) {
            return forwardToActivity("setAdapter", request);
        }
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final int[] applied = new int[1];
        Log.i(TAG, "setAdapter request view=" + request.optString("viewName", "grid")
                + " items=" + (request.optJSONArray("items") == null ? 0 : request.optJSONArray("items").length())
                + " viewId=" + request.optInt("viewId", 0)
                + " itemLayoutId=" + request.optInt("itemLayoutId", 0)
                + " spanCount=" + request.optInt("spanCount", 2));
        runOnMain(new Runnable() {
            @Override
            public void run() {
                try {
                    applied[0] = applyAdapterRequest(context, request);
                } catch (Throwable error) {
                    failure.set(error);
                }
            }
        });
        if (failure.get() != null) {
            Log.w(TAG, "setAdapter failed: view=" + request.optString("viewName", "grid"), failure.get());
            return new JSONObject().put("ok", false)
                    .put("error", "setAdapter 失败：" + describe(failure.get()));
        }
        boolean deferred = applied[0] <= 0;
        if (deferred) {
            Log.i(TAG, "setAdapter deferred (no RecyclerView yet): view="
                    + request.optString("viewName", "grid"));
            // task-72：页面可能还没生成/measure，稍后重试；sPageViews 就绪后即可挂上。
            scheduleAdapterRetry(context, request, 1);
        }
        return new JSONObject().put("ok", true)
                .put("view", request.optString("viewName", "grid"))
                .put("items", request.optJSONArray("items") == null ? 0 : request.optJSONArray("items").length())
                .put("itemLayout", request.optString("itemLayoutName", ""))
                .put("applied", applied[0])
                .put("deferred", deferred);
    }

    /** 在 sContentView + sPageViews 里找 RecyclerView 并挂 adapter；返回挂上的数量（0=还没页面节点）。 */
    private static int applyAdapterRequest(Context fallbackContext, JSONObject request) throws Exception {
        View content = sContentView;
        ProjectResourceBundle bundle = sShownBundle;
        Context themed = sThemedContext != null ? sThemedContext : fallbackContext;
        if (content == null || bundle == null) {
            throw new IllegalStateException("Node UI overlay 尚未显示（没有内容视图）");
        }
        String viewName = request.optString("viewName", "grid");
        int viewId = request.optInt("viewId", 0);
        if (viewId == 0 && sShownViews != null) {
            viewId = sShownViews.optInt(viewName, 0);
        }
        if (viewId == 0) {
            // 兜底：viewName 就是资源 id 名（示例 grid）时直接从资源表拿 id。
            viewId = bundle.idOf("id", viewName);
            if (viewId == 0 && "grid".equals(viewName)) {
                viewId = bundle.idOf("id", "grid");
            }
            Log.i(TAG, "setAdapter viewId fallback: view=" + viewName + " viewId=" + viewId);
        }
        int requestedLayoutId = request.optInt("itemLayoutId", 0);
        JSONArray items = request.optJSONArray("items");
        int spanCount = request.optInt("spanCount", 2);
        String layoutName = null;
        String itemXml = null;
        if (requestedLayoutId != 0) {
            layoutName = bundle.layoutNameById(requestedLayoutId);
            String path = bundle.layoutFileById(requestedLayoutId);
            if (path != null) {
                itemXml = readTextFile(new File(path));
            }
        }
        if (itemXml == null) {
            if (layoutName == null || layoutName.isEmpty()) {
                layoutName = "grid_item";
            }
            String path = bundle.layoutFile(layoutName);
            if (path != null) {
                itemXml = readTextFile(new File(path));
            }
        }
        int titleId = bundle.idOf("id", "title");
        int imageId = bundle.idOf("id", "image");
        boolean fallbackCard = itemXml == null || itemXml.isEmpty();
        if (fallbackCard) {
            Log.w(TAG, "找不到 item 布局（layoutId=" + requestedLayoutId + ", name=" + layoutName
                    + "），使用内置占位卡片");
        } else {
            // 预检一次 item inflate：grid_item 的 M3 控件/项目 drawable 有问题时降级为内置卡片，
            // 不能因为模板失败让整个卡片区空白。
            try {
                View probe = bundle.newLayoutInflater(themed).inflate(itemXml);
                if (probe == null) {
                    Log.w(TAG, "item 布局 inflate 结果为空，使用内置占位卡片：" + layoutName);
                    fallbackCard = true;
                }
            } catch (Throwable error) {
                Log.w(TAG, "grid_item inflate 失败，使用内置占位卡片："
                        + describe(error), error);
                fallbackCard = true;
            }
        }
        request.put("itemLayoutName", fallbackCard ? "fallback-card" : layoutName);
        List<RecyclerView> targets = findRecyclerViews(viewId);
        if (targets.isEmpty()) {
            sLastAdapterRequest = request;
            return 0;
        }
        JSONArray safeItems = items == null ? new JSONArray() : items;
        for (RecyclerView recyclerView : targets) {
            recyclerView.setLayoutManager(new GridLayoutManager(themed, spanCount > 0 ? spanCount : 2));
            recyclerView.setAdapter(new NodeUiAdapter(themed, bundle,
                    fallbackCard ? null : itemXml, safeItems, titleId, imageId));
            recyclerView.setClipToPadding(false);
            recyclerView.requestLayout();
            final RecyclerView targetView = recyclerView;
            targetView.post(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "setAdapter target=" + targetView.getClass().getSimpleName()
                            + " " + targetView.getWidth() + "x" + targetView.getHeight()
                            + " parent=" + targetView.getParent()
                            + " childCount=" + targetView.getChildCount()
                            + " adapter=" + targetView.getAdapter());
                }
            });
        }
        sLastAdapterRequest = request;
        Log.i(TAG, "setAdapter view=" + viewName + " items=" + safeItems.length()
                + " itemLayout=" + (fallbackCard ? "fallback-card" : layoutName)
                + " count=" + targets.size() + " viewId=" + viewId);
        return targets.size();
    }

    /** 按 id 在内容视图 + ViewPager 页面里找 RecyclerView（页面视图不在内容树里）。 */
    private static List<RecyclerView> findRecyclerViews(int viewId) {
        List<RecyclerView> result = new ArrayList<>();
        if (viewId == 0) {
            return result;
        }
        List<View> roots = new ArrayList<>();
        View content = sContentView;
        if (content != null) {
            roots.add(content);
        }
        List<View> pages = sPageViews;
        if (pages != null && !pages.isEmpty()) {
            roots.addAll(pages);
        }
        for (View root : roots) {
            View view = root.findViewById(viewId);
            if (view instanceof RecyclerView && !result.contains(view)) {
                result.add((RecyclerView) view);
            }
        }
        return result;
    }

    /**
     * task-71：JS marker.initAdapterFromChildren()/setAdapter() on ViewPager 的宿主实现。
     *
     * <p>示例 activity_main 的 ViewPager 内容页是 page_main/page_console/page_settings；
     * 这里用 show 时捕获的 themed context/bundle inflate 成 3 页，装 PagerAdapter 后再补挂
     * 之前缓存的 grid adapter（顺序不敏感）。
     */
    private static Object setPager(final Context context, final String projectDir, final JSONObject request) throws Exception {
        if (sActivityMode) {
            return forwardToActivity("setPager", request);
        }
        if (INLINE_LAYOUT_TOKEN.equals(sShownLayout)) {
            return viewError("layoutXml 模式暂不支持 setPager：viewpager 页面未真实创建");
        }
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final String[] summary = new String[2];
        runOnMain(new Runnable() {
            @Override
            public void run() {
                try {
                    View content = sContentView;
                    ProjectResourceBundle bundle = sShownBundle;
                    Context themed = sThemedContext != null ? sThemedContext : context;
                    if (content == null || bundle == null) {
                        throw new IllegalStateException("Node UI overlay 尚未显示（没有内容视图）");
                    }
                    String viewName = request.optString("viewName", "viewPager");
                    int viewId = request.optInt("viewId", 0);
                    if (viewId == 0 && sShownViews != null) {
                        viewId = sShownViews.optInt(viewName, 0);
                    }
                    View pagerView = viewId == 0 ? null : findInlineViewById(content, viewId);
                    if (!(pagerView instanceof ViewPager)) {
                        throw new IllegalStateException("找不到 ViewPager（view=" + viewName + ", viewId=" + viewId + "）");
                    }
                    ViewPager pager = (ViewPager) pagerView;
                    List<String> pageNames = resolvePageNames(bundle, request);
                    List<View> pages = new ArrayList<>();
                    DynamicLayoutInflater inflater = bundle.newLayoutInflater(themed);
                    for (String name : pageNames) {
                        String path = bundle.layoutFile(name);
                        if (path == null) {
                            continue;
                        }
                        String xml = readTextFile(new File(path));
                        if (xml == null || xml.isEmpty()) {
                            continue;
                        }
                        try {
                            pages.add(inflater.inflate(xml));
                            Log.i(TAG, "setPager page ok: " + name);
                        } catch (Throwable pageError) {
                            // 单个页面（如控制台自定义 View）inflate 失败不影响其它页：记日志继续。
                            Log.w(TAG, "setPager page 失败，跳过: " + name, pageError);
                        }
                    }
                    if (pages.isEmpty()) {
                        throw new IllegalStateException("没有可 inflate 的页面布局（page_*）");
                    }
                    pager.setAdapter(new NodeUiPagerAdapter(pages, resolveWindowBackground(themed)));
                    try {
                        pager.setOffscreenPageLimit(pages.size());
                    } catch (Throwable ignored) {
                        // ViewPager offscreen 限制失败不影响显示
                    }
                    sPageViews = pages;
                    summary[0] = String.valueOf(pages.size());
                    summary[1] = pageNames.toString();
                    Log.i(TAG, "setPager view=" + viewName + " pages=" + pages.size()
                            + " names=" + pageNames + " viewId=" + viewId);
                    // task-72 诊断：page_main 内 grid 是否找得到 + View 树 + 页面尺寸（measured 为 0 时说明还没布局）
                    int gridId = bundle.idOf("id", "grid");
                    List<RecyclerView> pageGrids = findRecyclerViews(gridId);
                    Log.i(TAG, "setPager gridId=" + Integer.toHexString(gridId)
                            + " pageGrids=" + pageGrids.size() + " pageViews=" + pages.size());
                    dumpViewTree("C-PAGETREE", pages.get(0), 0, 6);
                    for (final View page : pages) {
                        page.post(new Runnable() {
                            @Override
                            public void run() {
                                Log.i(TAG, "setPager measure page=" + page.getClass().getSimpleName()
                                        + " " + page.getWidth() + "x" + page.getHeight());
                            }
                        });
                    }
                    for (RecyclerView grid : pageGrids) {
                        Log.i(TAG, "setPager grid found: " + grid + " " + grid.getWidth() + "x" + grid.getHeight()
                                + " parent=" + grid.getParent());
                    }
                    JSONObject pending = sLastAdapterRequest;
                    if (pending != null) {
                        int applied = applyAdapterRequest(context, pending);
                        Log.i(TAG, "setPager applied pending adapter=" + applied);
                    }
                } catch (Throwable error) {
                    failure.set(error);
                }
            }
        });
        if (failure.get() != null) {
            return new JSONObject().put("ok", false)
                    .put("error", "setPager 失败：" + describe(failure.get()));
        }
        return new JSONObject().put("ok", true)
                .put("view", request.optString("viewName", "viewPager"))
                .put("pageCount", summary[0] == null ? "0" : summary[0])
                .put("names", summary[1] == null ? "" : summary[1]);
    }

    /** page_* 布局名：请求里 pages 优先；否则从 bundle.describeJson().layouts 里枚举，main/console/settings 优先。 */
    private static List<String> resolvePageNames(ProjectResourceBundle bundle, JSONObject request) {
        List<String> result = new ArrayList<>();
        JSONArray requested = request.optJSONArray("pages");
        if (requested != null) {
            for (int i = 0; i < requested.length(); i++) {
                String name = requested.optString(i, "");
                if (name != null && !name.isEmpty()) {
                    result.add(name);
                }
            }
            return result;
        }
        try {
            JSONObject description = new JSONObject(bundle.describeJson());
            JSONObject layouts = description.optJSONObject("layouts");
            if (layouts != null) {
                String[] preferred = {"page_main", "page_console", "page_settings"};
                for (String name : preferred) {
                    if (layouts.has(name)) {
                        result.add(name);
                    }
                }
                java.util.Iterator<String> keys = layouts.keys();
                while (keys.hasNext()) {
                    String name = keys.next();
                    if (name.startsWith("page_") && !result.contains(name)) {
                        result.add(name);
                    }
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "枚举 page_* 布局失败", error);
        }
        if (result.isEmpty()) {
            result.add("page_main");
            result.add("page_console");
            result.add("page_settings");
        }
        return result;
    }

    /** task-71：ViewPager 页面适配器（页面视图已 inflate，instantiateItem 时挂到 container）。 */
    private static final class NodeUiPagerAdapter extends PagerAdapter {
        private final List<View> pages;
        private final int backgroundColor;

        NodeUiPagerAdapter(List<View> pages, int backgroundColor) {
            this.pages = pages;
            this.backgroundColor = backgroundColor;
        }

        @Override
        public int getCount() {
            return pages.size();
        }

        @Override
        public boolean isViewFromObject(View view, Object object) {
            return view == object;
        }

        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            View page = pages.get(position);
            if (page.getParent() instanceof ViewGroup) {
                ((ViewGroup) page.getParent()).removeView(page);
            }
            // task-72：页面根背景用主题 window background，避免内容区全透明（overlay 没有 Activity 背景）。
            if (backgroundColor != 0) {
                page.setBackgroundColor(backgroundColor);
            }
            ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            container.addView(page, params);
            return page;
        }

        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            container.removeView((View) object);
        }
    }

    /** task-72：页面/measure 延迟时重试挂 adapter（最多 3 次，overlay 关闭后停止）。 */
    private static void scheduleAdapterRetry(final Context context, final JSONObject request, final int attempt) {
        if (attempt > 3) {
            return;
        }
        // 2.2：宿主侧 postDelayed 必须落在真实主 Looper 上；本地 NodeUI Activity 会话同样要有内容视图，
        // 不能只看 overlay 静态字段（否则本地会话的延迟重试永远不执行）。
        main().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (sessionContentView() == null) {
                    return;
                }
                try {
                    Log.i(TAG, "setAdapter retry attempt=" + attempt + " layoutId="
                            + request.optInt("itemLayoutId", 0));
                    int applied = applyAdapterRequest(context, request);
                    Log.i(TAG, "setAdapter retry applied=" + applied);
                } catch (Throwable error) {
                    Log.w(TAG, "setAdapter retry 失败 attempt=" + attempt, error);
                }
            }
        }, 350L * attempt);
    }

    /** task-72：打印 View 树（类名/id/尺寸），用于确认 page_main 内的 grid RecyclerView 是否存在。 */
    private static void dumpViewTree(String tag, View view, int depth, int maxDepth) {
        if (view == null || depth > maxDepth) {
            return;
        }
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            indent.append("  ");
        }
        Log.i(TAG, tag + " " + indent + view.getClass().getSimpleName()
                + " id=" + Integer.toHexString(view.getId())
                + " " + view.getWidth() + "x" + view.getHeight());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && i < 40; i++) {
                dumpViewTree(tag, group.getChildAt(i), depth + 1, maxDepth);
            }
        }
    }

    // ------------------------------------------------------------------
    // task-83：卡片网络图片（HttpURLConnection + BitmapFactory + LruCache）
    // ------------------------------------------------------------------

    /** ImageView 上记录当前 url 的 tag int key（不占用 Object tag，避免和 fallback 卡片 tag="image" 冲突）。 */
    private static final int IMAGE_URL_TAG_KEY = 0x4E554901;
    private static final Object IMAGE_CACHE_LOCK = new Object();
    private static LruCache<String, Bitmap> sImageCache;
    private static ExecutorService sImageExecutor;

    private static LruCache<String, Bitmap> imageCache() {
        synchronized (IMAGE_CACHE_LOCK) {
            if (sImageCache == null) {
                long maxBytes = Math.min(20L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8);
                int cacheSize = (int) Math.max(4L * 1024 * 1024, maxBytes);
                sImageCache = new LruCache<String, Bitmap>(cacheSize) {
                    @Override
                    protected int sizeOf(String key, Bitmap value) {
                        return value == null ? 0 : value.getByteCount();
                    }
                };
            }
            return sImageCache;
        }
    }

    private static ExecutorService imageExecutor() {
        synchronized (IMAGE_CACHE_LOCK) {
            if (sImageExecutor == null) {
                sImageExecutor = Executors.newFixedThreadPool(3);
            }
            return sImageExecutor;
        }
    }

    /** 绑定卡片图片：先灰占位，缓存命中同步显示，否则后台下载；回调按 tag 比对防错位。 */
    private static void bindCardImage(final ImageView imageView, final String url) {
        imageView.setTag(IMAGE_URL_TAG_KEY, url == null ? "" : url);
        imageView.setImageBitmap(null);
        imageView.setBackgroundColor(0xFFB0BEC5);
        imageView.setContentDescription(url == null ? "" : url);
        if (url == null || url.isEmpty()) {
            return;
        }
        Bitmap cached = imageCache().get(url);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            imageView.setBackgroundColor(0xFFEEEEEE);
            return;
        }
        try {
            imageExecutor().execute(new Runnable() {
                @Override
                public void run() {
                    Bitmap bitmap = null;
                    try {
                        bitmap = downloadBitmap(url);
                    } catch (Throwable error) {
                        Log.w(TAG, "C-IMG: 下载失败 url=" + url, error);
                    }
                    if (bitmap == null) {
                        return;
                    }
                    imageCache().put(url, bitmap);
                    final Bitmap loaded = bitmap;
                    main().post(new Runnable() {
                        @Override
                        public void run() {
                            // item 已回收/复用给别的 url 时不能覆盖（防错位）。
                            Object tag = imageView.getTag(IMAGE_URL_TAG_KEY);
                            if (url.equals(tag)) {
                                imageView.setImageBitmap(loaded);
                                imageView.setBackgroundColor(0xFFEEEEEE);
                                Log.i(TAG, "C-IMG: loaded url=" + url
                                        + " " + loaded.getWidth() + "x" + loaded.getHeight());
                            }
                        }
                    });
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "C-IMG: 提交下载任务失败 url=" + url, error);
        }
    }

    private static Bitmap downloadBitmap(String url) throws Exception {
        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(10_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "AutoJsPro-NodeUI/1.0");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "C-IMG: HTTP " + code + " url=" + url);
                return null;
            }
            input = connection.getInputStream();
            return BitmapFactory.decodeStream(input);
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Throwable ignored) {
                    // 关闭失败不影响结果
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** task-69：卡片网格适配器（grid_item.xml inflate + title/image 绑定）。 */
    private static final class NodeUiAdapter extends RecyclerView.Adapter<NodeUiAdapter.Holder> {

        private final Context context;
        private final ProjectResourceBundle bundle;
        private final String itemXml;
        private final JSONArray items;
        private final int titleId;
        private final int imageId;

        NodeUiAdapter(Context context, ProjectResourceBundle bundle, String itemXml,
                      JSONArray items, int titleId, int imageId) {
            this.context = context;
            this.bundle = bundle;
            this.itemXml = itemXml;
            this.items = items;
            this.titleId = titleId;
            this.imageId = imageId;
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (itemXml != null) {
                try {
                    DynamicLayoutInflater inflater = bundle.newLayoutInflater(context);
                    View view = inflater.inflate(itemXml);
                    // 模板 root 没有 parent 时 layout_* 会被丢弃：显式给 RecyclerView 的 LayoutParams，
                    // 否则 GridLayoutManager 量出来可能是 0 宽/0 高，卡片看不见。
                    // dev-1029：item 高度必须 WRAP_CONTENT（不能固定，否则底部图标行被裁）；顺带补回
                    // grid_item.xml 根节点 android:layout_margin="8dp"（root inflate 时丢了 parent 拿不到）。
                    RecyclerView.LayoutParams itemParams = new RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    int itemMargin = dp(context, 8);
                    itemParams.setMargins(itemMargin, itemMargin, itemMargin, itemMargin);
                    view.setLayoutParams(itemParams);
                    logItemLayout(view);
                    return new Holder(view);
                } catch (Throwable error) {
                    Log.w(TAG, "card item inflate 失败，使用内置占位卡片", error);
                }
            }
            return new Holder(buildFallbackCard());
        }

        /** task-73：不依赖 grid_item.xml 的内置卡片（ImageView 灰占位 + TextView 标题）。 */
        private View buildFallbackCard() {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(0xFFFFFFFF);
            int padding = dp(context, 12);
            card.setPadding(padding, padding, padding, padding);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int margin = dp(context, 4);
            params.setMargins(margin, margin, margin, margin);
            card.setLayoutParams(params);

            ImageView image = new ImageView(context);
            image.setTag("image");
            image.setBackgroundColor(0xFFB0BEC5);
            card.addView(image, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 120)));

            TextView title = new TextView(context);
            title.setTag("title");
            title.setTextSize(18);
            title.setTextColor(0xFF212121);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            titleParams.topMargin = dp(context, 12);
            card.addView(title, titleParams);
            return card;
        }

        private static final java.util.concurrent.atomic.AtomicBoolean ITEM_LOGGED =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        /**
         * dev-1029：卡片 item 布局诊断（root/按钮行/按钮的 measured 与 parent），只打第一张避免刷屏。
         * 用于确认「按钮 measured 正常但图标不可见」到底是 clip、LP 还是 draw 问题。
         */
        private static void logItemLayout(final View root) {
            if (!ITEM_LOGGED.compareAndSet(false, true)) {
                return;
            }
            root.post(new Runnable() {
                @Override
                public void run() {
                    ViewGroup.LayoutParams params = root.getLayoutParams();
                    Log.i(TAG, "C-ITEM: root=" + root.getClass().getSimpleName()
                            + " measured=" + root.getMeasuredWidth() + "x" + root.getMeasuredHeight()
                            + " lp=" + (params == null ? "null" : params.getClass().getSimpleName()
                            + "(" + params.width + "," + params.height + ")"));
                    java.util.List<View> buttons = new java.util.ArrayList<>();
                    collectButtons(root, buttons);
                    for (View button : buttons) {
                        View parent = (View) button.getParent();
                        Log.i(TAG, "C-ITEM: btn=" + button.getClass().getSimpleName()
                                + " measured=" + button.getMeasuredWidth() + "x" + button.getMeasuredHeight()
                                + " visible=" + button.getVisibility()
                                + " parent=" + (parent == null ? "null" : parent.getClass().getSimpleName())
                                + " parentMeasured=" + (parent == null ? "-"
                                : parent.getMeasuredWidth() + "x" + parent.getMeasuredHeight()));
                    }
                }
            });
        }

        private static void collectButtons(View view, java.util.List<View> out) {
            if (view instanceof android.widget.Button) {
                out.add(view);
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    collectButtons(group.getChildAt(i), out);
                }
            }
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            JSONObject item = items.optJSONObject(position);
            if (item == null) {
                return;
            }
            View title = titleId != 0 ? holder.itemView.findViewById(titleId) : null;
            if (!(title instanceof TextView)) {
                title = holder.itemView.findViewWithTag("title");
            }
            if (title instanceof TextView) {
                ((TextView) title).setText(item.optString("title", ""));
            }
            View image = imageId != 0 ? holder.itemView.findViewById(imageId) : null;
            if (!(image instanceof ImageView)) {
                image = holder.itemView.findViewWithTag("image");
            }
            if (image instanceof ImageView) {
                // task-83：异步下载真实网络图；加载中/失败保持灰色占位，tag 比对防滑动作错位。
                bindCardImage((ImageView) image, item.optString("url", ""));
            }
        }

        @Override
        public int getItemCount() {
            return items.length();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            Holder(View itemView) {
                super(itemView);
            }
        }
    }

    /** task-73：MaterialToolbar 的 app:title 兜底（inflater 若没应用 title 属性，从 XML 文本取值设置）。 */
    private static void applyToolbarTitle(View content, String xml, JSONObject views) {
        try {
            MaterialToolbar toolbar = null;
            int toolbarId = views == null ? 0 : views.optInt("toolbar", 0);
            if (toolbarId != 0) {
                View view = content.findViewById(toolbarId);
                if (view instanceof MaterialToolbar) {
                    toolbar = (MaterialToolbar) view;
                }
            }
            if (toolbar == null) {
                toolbar = findMaterialToolbar(content);
            }
            if (toolbar == null) {
                return;
            }
            CharSequence current = toolbar.getTitle();
            if (current != null && current.length() > 0) {
                Log.i(TAG, "C-TITLE: toolbar 已有标题=" + current);
                return;
            }
            String title = findXmlAttribute(xml, "app:title");
            if (title == null || title.isEmpty()) {
                title = findXmlAttribute(xml, "android:title");
            }
            if (title != null && !title.isEmpty()) {
                toolbar.setTitle(title);
                Log.i(TAG, "C-TITLE: set toolbar title=" + title);
            } else {
                Log.i(TAG, "C-TITLE: 未找到 toolbar 标题属性");
            }
        } catch (Throwable error) {
            Log.w(TAG, "设置 AppBar 标题失败", error);
        }
    }

    private static MaterialToolbar findMaterialToolbar(View view) {
        if (view == null) {
            return null;
        }
        if (view instanceof MaterialToolbar) {
            return (MaterialToolbar) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                MaterialToolbar toolbar = findMaterialToolbar(group.getChildAt(i));
                if (toolbar != null) {
                    return toolbar;
                }
            }
        }
        return null;
    }

    private static String findXmlAttribute(String xml, String attribute) {
        if (xml == null || attribute == null) {
            return null;
        }
        try {
            Pattern pattern = Pattern.compile(Pattern.quote(attribute) + "\\s*=\\s*\"([^\"]*)\"");
            Matcher matcher = pattern.matcher(xml);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Throwable error) {
            Log.w(TAG, "解析 XML 属性失败: " + attribute, error);
        }
        return null;
    }

    /**
     * task-70：宿主侧基础联动（不依赖 Java→JS 回调）。
     *
     * <p>BottomNavigationView 点击 → ViewPager 切页；MaterialToolbar 导航图标点击 → DrawerLayout 打开。
     */
    private static void wireNavigation(View content, ProjectResourceBundle bundle, JSONObject views) {
        if (content == null || views == null) {
            return;
        }
        try {
            int navigationId = views.optInt("navigation", 0);
            int pagerId = views.optInt("viewPager", 0);
            if (navigationId != 0 && pagerId != 0) {
                View navigationView = content.findViewById(navigationId);
                View pagerView = content.findViewById(pagerId);
                if (navigationView instanceof BottomNavigationView && pagerView instanceof ViewPager) {
                    final BottomNavigationView navigation = (BottomNavigationView) navigationView;
                    final ViewPager pager = (ViewPager) pagerView;
                    navigation.setOnItemSelectedListener(new BottomNavigationView.OnItemSelectedListener() {
                        @Override
                        public boolean onNavigationItemSelected(android.view.MenuItem item) {
                            try {
                                int index = menuIndex(navigation, item.getItemId());
                                if (index < 0) {
                                    // 2.7：itemId 不在真实菜单里时不猜第 0 页，也不消费选中事件。
                                    return false;
                                }
                                if (pager.getAdapter() != null && pager.getAdapter().getCount() > 0) {
                                    index = Math.min(index, pager.getAdapter().getCount() - 1);
                                }
                                pager.setCurrentItem(index, true);
                                // 返回 false 让 BottomNavigationView 完成真实选中态更新，
                                // 否则 getSelectedItemId() 与真实菜单会不一致（2.7）。
                                return false;
                            } catch (Throwable error) {
                                Log.w(TAG, "底部导航切换页失败", error);
                                return false;
                            }
                        }
                    });
                    pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
                        @Override
                        public void onPageSelected(int position) {
                            try {
                                android.view.Menu menu = navigation.getMenu();
                                if (position < 0 || position >= menu.size()) {
                                    return;
                                }
                                navigation.setSelectedItemId(menu.getItem(position).getItemId());
                            } catch (Throwable ignored) {
                                // 同步底部选中态失败不影响翻页
                            }
                        }
                    });
                    Log.i(TAG, "nav wired: bottomNavigation ↔ viewPager");
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "底部导航联动初始化失败", error);
        }
        try {
            int drawerId = views.optInt("drawer", 0);
            int toolbarId = views.optInt("toolbar", 0);
            if (drawerId != 0 && toolbarId != 0) {
                View drawerView = content.findViewById(drawerId);
                View toolbarView = content.findViewById(toolbarId);
                if (drawerView instanceof DrawerLayout && toolbarView instanceof MaterialToolbar) {
                    final DrawerLayout drawer = (DrawerLayout) drawerView;
                    MaterialToolbar toolbar = (MaterialToolbar) toolbarView;
                    toolbar.setNavigationIcon(android.R.drawable.ic_menu_sort_by_size);
                    toolbar.setNavigationOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            try {
                                drawer.openDrawer(android.view.Gravity.START);
                            } catch (Throwable error) {
                                Log.w(TAG, "打开抽屉失败", error);
                            }
                        }
                    });
                    Log.i(TAG, "nav wired: toolbar → drawer");
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "抽屉联动初始化失败", error);
        }
    }

    private static int menuIndex(BottomNavigationView navigation, int itemId) {
        android.view.Menu menu = navigation.getMenu();
        for (int i = 0; i < menu.size(); i++) {
            if (menu.getItem(i).getItemId() == itemId) {
                return i;
            }
        }
        return -1;
    }

    /**
     * task-74：overlay 根布局。默认**不再注入右上角关闭按钮**，改为拦截系统返回键关闭
     * （关闭时置 closeRequested，A 的 keep-alive 会退出）；只有 ROM 限制 overlay 焦点、
     * 返回键收不到时才启用半透明小 ✕ 兜底。
     */
    private static final class OverlayRoot extends FrameLayout {

        OverlayRoot(Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled())
                    com.stardust.autojs.ScriptEngineService.stopAllByVolumeUp();
                return true;
            }
            if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    Log.i(TAG, "C-CLOSE: back pressed, closing overlay");
                    closeOverlayFromEngine();
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        @Override
        public boolean onKeyPreIme(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event != null && event.getAction() == KeyEvent.ACTION_UP) {
                    Log.i(TAG, "C-CLOSE: back preIme, closing overlay");
                    closeOverlayFromEngine();
                }
                return true;
            }
            return super.onKeyPreIme(keyCode, event);
        }
    }

    private static View buildOverlay(Context context, View content) {
        OverlayRoot root = new OverlayRoot(context);
        // task-72：overlay 没有 Activity window，默认透明；铺主题 colorBackground 避免内容区看起来空白。
        root.setBackgroundColor(resolveWindowBackground(context));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // task-74：默认无可见按钮；先请求焦点（系统返回键），焦点检查放在 show 后 1.2s。
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        return root;
    }

    /** task-74：overlay 获焦检查；获焦 → modal=back（无按钮），未获焦 → 半透明小 ✕ 兜底。 */
    private static void checkOverlayFocus(final View overlay) {
        if (overlay == null || sOverlayView != overlay) {
            return;
        }
        boolean focused = overlay.hasWindowFocus() || overlay.isFocused();
        if (focused) {
            Log.i(TAG, "C-CLOSE: modal=back focused=true（返回键关闭已启用，无关闭按钮）");
            return;
        }
        Log.i(TAG, "C-CLOSE: fallback=✕ focused=false（ROM 限制 overlay 焦点，启用半透明小 ✕ 兜底）");
        installFallbackCloseButton(overlay);
    }

    /** task-74：焦点拿不到时的半透明小 ✕（很小、不显眼，仅兜底）。 */
    private static void installFallbackCloseButton(final View overlay) {
        if (!(overlay instanceof ViewGroup)) {
            return;
        }
        final ViewGroup root = (ViewGroup) overlay;
        if (root.findViewWithTag("node-ui-fallback-close") != null) {
            return;
        }
        Context context = overlay.getContext();
        TextView close = new TextView(context);
        close.setTag("node-ui-fallback-close");
        close.setText("✕");
        close.setTextSize(14);
        close.setTextColor(0x88FFFFFF);
        close.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(0x33000000);
        close.setBackground(background);
        close.setAlpha(0.6f);
        int size = dp(context, 26);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
        params.setMargins(0, dp(context, 32), dp(context, 8), 0);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.i(TAG, "C-CLOSE: fallback ✕ clicked");
                closeOverlayFromEngine();
            }
        });
        root.addView(close, params);
        Log.i(TAG, "C-CLOSE: fallback=✕ installed");
    }

    private static WindowManager.LayoutParams overlayParams(boolean touchable) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                // task-74：去掉 FLAG_NOT_FOCUSABLE，overlay 才能接收系统返回键。
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        if (!touchable) {
            // 绘制型不交互：与 LocalOverlay 一致带齐 NOT_FOCUSABLE/NOT_TOUCHABLE；
            // 交互型保持原行为（可接收返回键/焦点）。
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        return params;
    }

    // ------------------------------------------------------------------
    // task-254：本地会话/overlay 的真实 View 句柄、视图树与通用事件
    //
    // 与 assets/node24/ui.js 的 uiRequestView(action, params) 对应：
    //   findViewById/inflate 返回真实 View 的 handleId；addView/removeView/getParent/
    //   getChildCount/getChildAt/removeAllViews 在真实 View 树上执行；
    //   setOnClickListener/setOnCheckedChangeListener/setOnTextChangedListener/
    //   setOnItemSelectedListener 安装真实监听器，事件写入现有 pollEvents 队列。
    // ------------------------------------------------------------------

    /** 当前会话（本地 NodeUiLocalActivity 或 overlay）的真实内容视图；两者都可能为空。 */
    private static View sessionContentView() {
        View overlay = sContentView;
        if (overlay != null) {
            return overlay;
        }
        try {
            return NodeUiLocalSession.isShowing() ? NodeUiLocalSession.getContentView() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object overlayViewCommand(final Context context, final String projectDir,
                                             final JSONObject request) throws Exception {
        final String op = request.optString("op", "");
        final AtomicReference<JSONObject> result = new AtomicReference<>();
        runOnMain(new Runnable() {
            @Override
            public void run() {
                try {
                    if ("findViewById".equals(op)) {
                        result.set(overlayFindViewById(request));
                    } else if ("bindClick".equals(op)) {
                        result.set(overlayBindClick(request));
                    } else {
                        result.set(executeViewAction(context, projectDir,
                                resolveViewAction(request), viewActionParams(request)));
                    }
                } catch (Throwable error) {
                    Log.w(TAG, "View 命令失败: " + request, error);
                    result.set(viewError("View 命令失败：" + describe(error)));
                }
            }
        });
        return result.get() == null ? viewError("View 命令没有返回结果") : result.get();
    }

    /** op:"uiRequestView" 或 action 作为 op 时的统一入口（在主线程执行）。 */
    private static Object uiRequestView(final Context context, final String projectDir,
                                        final JSONObject request) throws Exception {
        final String action = resolveViewAction(request);
        final JSONObject params = viewActionParams(request);
        final AtomicReference<JSONObject> result = new AtomicReference<>();
        runOnMain(new Runnable() {
            @Override
            public void run() {
                try {
                    result.set(executeViewAction(context, projectDir, action, params));
                } catch (Throwable error) {
                    Log.w(TAG, "uiRequestView 失败: action=" + action + " request=" + request, error);
                    result.set(viewError("uiRequestView " + action + " 失败：" + describe(error)));
                }
            }
        });
        return result.get() == null ? viewError("uiRequestView 没有返回结果") : result.get();
    }

    // ---- 协议参数解析（纯逻辑，JVM 单测覆盖） ----

    /** uiRequestView(action, params) 的 action：优先 action 字段，其次 params.action，最后 op。 */
    static String resolveViewAction(JSONObject request) {
        if (request == null) {
            return "";
        }
        String action = request.optString(NodeUiProtocol.K_ACTION, "");
        if (!action.isEmpty()) {
            return action;
        }
        JSONObject params = request.optJSONObject(NodeUiProtocol.K_PARAMS);
        if (params != null) {
            action = params.optString(NodeUiProtocol.K_ACTION, "");
            if (!action.isEmpty()) {
                return action;
            }
        }
        String op = request.optString("op", "");
        return NodeUiProtocol.OP_UI_REQUEST_VIEW.equals(op) ? "" : op;
    }

    /** 把 request 顶层字段与 params 合并成一层（params 优先）。 */
    static JSONObject viewActionParams(JSONObject request) {
        JSONObject merged = new JSONObject();
        if (request == null) {
            return merged;
        }
        Iterator<String> keys = request.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (NodeUiProtocol.K_PARAMS.equals(key)) {
                continue;
            }
            try {
                merged.put(key, request.get(key));
            } catch (Throwable ignored) {
                // 非法字段不参与合并
            }
        }
        JSONObject params = request.optJSONObject(NodeUiProtocol.K_PARAMS);
        if (params != null) {
            keys = params.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    merged.put(key, params.get(key));
                } catch (Throwable ignored) {
                    // 非法字段不参与合并
                }
            }
        }
        return merged;
    }

    static boolean isViewAction(String action) {
        if (action == null) {
            return false;
        }
        switch (action) {
            case NodeUiProtocol.ACTION_ADD_VIEW:
            case NodeUiProtocol.ACTION_REMOVE_VIEW:
            case NodeUiProtocol.ACTION_REMOVE_ALL_VIEWS:
            case NodeUiProtocol.ACTION_GET_PARENT:
            case NodeUiProtocol.ACTION_GET_CHILD_COUNT:
            case NodeUiProtocol.ACTION_GET_CHILD_AT:
            case NodeUiProtocol.ACTION_SET_ON_CLICK_LISTENER:
            case NodeUiProtocol.ACTION_SET_ON_CHECKED_CHANGE_LISTENER:
            case NodeUiProtocol.ACTION_SET_ON_TEXT_CHANGED_LISTENER:
            case NodeUiProtocol.ACTION_SET_ON_ITEM_SELECTED_LISTENER:
            case NodeUiProtocol.ACTION_GET_SELECTED_ITEM_ID:
            case NodeUiProtocol.ACTION_SET_SELECTED_ITEM_ID:
            case NodeUiProtocol.ACTION_GET_MENU_SIZE:
            case NodeUiProtocol.ACTION_GET_MENU_ITEM_ID:
            case NodeUiProtocol.ACTION_MENU_ITEMS:
            case NodeUiProtocol.ACTION_POST_DELAYED:
            case NodeUiProtocol.ACTION_REMOVE_CALLBACKS:
                return true;
            default:
                return false;
        }
    }

    private static JSONObject overlayFindViewById(JSONObject request) {
        View content = sessionContentView();
        if (content == null) {
            return viewError("Node UI 尚未显示（没有内容视图）");
        }
        String name = request.optString("name", "");
        int id = resolveSessionViewId(request);
        View view = id == 0 ? null : findInlineViewById(content, id);
        if (view == null && id != 0) {
            view = content.findViewById(id);
        }
        if (view == null) {
            return viewError("findViewById 未命中：id=" + id + " name=" + name);
        }
        return viewHandleResult(view, view.getId() == 0 ? id : view.getId(), name);
    }

    private static JSONObject overlayBindClick(JSONObject request) throws Exception {
        View content = sessionContentView();
        if (content == null) {
            return viewError("Node UI 尚未显示（没有内容视图）");
        }
        int id = resolveSessionViewId(request);
        View view = id == 0 ? null : findInlineViewById(content, id);
        if (view == null && id != 0) {
            view = content.findViewById(id);
        }
        if (view == null) {
            return viewError("bindClick 未命中：id=" + id + " name=" + request.optString("name", ""));
        }
        installClickListener(view);
        view.setClickable(true);
        return new JSONObject().put("ok", true).put("viewId", eventViewId(view));
    }

    private static JSONObject viewHandleResult(View view, int viewId, String viewName) {
        try {
            return new JSONObject()
                    .put("ok", true)
                    .put("handleId", VIEW_HANDLES.handleFor(view))
                    .put("viewId", viewId)
                    .put("viewName", viewName == null ? "" : viewName)
                    .put("className", view.getClass().getName());
        } catch (Throwable error) {
            return viewError("生成 View 句柄失败：" + describe(error));
        }
    }

    private static int resolveSessionViewId(JSONObject request) {
        int id = request.optInt(NodeUiProtocol.K_VIEW_ID, request.optInt("id", 0));
        String name = request.optString("name", "");
        if (id == 0 && !name.isEmpty()) {
            if (sShownViews != null) {
                id = sShownViews.optInt(name, 0);
            }
            if (id == 0) {
                try {
                    id = NodeUiLocalSession.viewId(name);
                } catch (Throwable ignored) {
                    // 本地会话不在时继续走 bundle / Ids
                }
            }
            if (id == 0 && sShownBundle != null) {
                id = sShownBundle.idOf("id", name);
            }
            if (id == 0) {
                // inline 布局与菜单项同源：@+id/name 走 Ids.parse 名字表（2.7）。
                id = Ids.parse(name);
            }
        }
        return id;
    }

    /** uiRequestView / viewCommand / 旧 View 命令的统一执行体（调用方保证在主线程）。 */
    private static JSONObject executeViewAction(Context context, String projectDir,
                                                String action, JSONObject request) {
        if (action == null || action.isEmpty()) {
            return viewError("uiRequestView 缺少 action");
        }
        try {
            if (NodeUiProtocol.ACTION_POST_DELAYED.equals(action)) {
                return scheduleTimer(request);
            }
            if (NodeUiProtocol.ACTION_REMOVE_CALLBACKS.equals(action)) {
                return cancelTimer(request);
            }
            View view = (View) VIEW_HANDLES.get(handleIdOf(request));
            if (view == null) {
                return viewError("无效的 View handle：" + handleIdOf(request));
            }
            Object value = request.opt(NodeUiProtocol.K_VALUE);
            switch (action) {
                case "setText":
                    if (!(view instanceof TextView)) {
                        return viewError("setText 只支持 TextView，当前=" + view.getClass().getName());
                    }
                    ((TextView) view).setText(String.valueOf(value));
                    return new JSONObject().put("ok", true);
                case "getText":
                    if (!(view instanceof TextView)) {
                        return viewError("getText 只支持 TextView，当前=" + view.getClass().getName());
                    }
                    return new JSONObject().put("ok", true)
                            .put("value", ((TextView) view).getText().toString());
                case "setVisibility":
                    view.setVisibility(value instanceof Number
                            ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value)));
                    return new JSONObject().put("ok", true);
                case "getVisibility":
                    return new JSONObject().put("ok", true).put("value", view.getVisibility());
                case "setEnabled":
                    view.setEnabled(Boolean.parseBoolean(String.valueOf(value)));
                    return new JSONObject().put("ok", true);
                case "setChecked":
                    if (!(view instanceof CompoundButton)) {
                        return viewError("setChecked 只支持 CompoundButton，当前=" + view.getClass().getName());
                    }
                    ((CompoundButton) view).setChecked(Boolean.parseBoolean(String.valueOf(value)));
                    return new JSONObject().put("ok", true);
                case "isChecked":
                    return new JSONObject().put("ok", true).put("value", isChecked(view));
                case "toggle":
                    if (!(view instanceof CompoundButton)) {
                        return viewError("toggle 只支持 CompoundButton，当前=" + view.getClass().getName());
                    }
                    ((CompoundButton) view).toggle();
                    return new JSONObject().put("ok", true);
                case "click":
                    return new JSONObject().put("ok", true).put("value", view.performClick());
                case NodeUiProtocol.ACTION_ADD_VIEW:
                    return addView((ViewGroup) view, request);
                case NodeUiProtocol.ACTION_REMOVE_VIEW:
                    return removeView(view, request);
                case NodeUiProtocol.ACTION_REMOVE_ALL_VIEWS:
                    if (!(view instanceof ViewGroup)) {
                        return viewError("removeAllViews 需要 ViewGroup，当前=" + view.getClass().getName());
                    }
                    ((ViewGroup) view).removeAllViews();
                    return new JSONObject().put("ok", true);
                case NodeUiProtocol.ACTION_GET_CHILD_COUNT:
                    if (!(view instanceof ViewGroup)) {
                        return viewError("getChildCount 需要 ViewGroup，当前=" + view.getClass().getName());
                    }
                    return new JSONObject().put("ok", true).put("value", ((ViewGroup) view).getChildCount());
                case NodeUiProtocol.ACTION_GET_CHILD_AT:
                    return getChildAt(view, request);
                case NodeUiProtocol.ACTION_GET_PARENT:
                    return getParent(view);
                case NodeUiProtocol.ACTION_SET_ON_CLICK_LISTENER:
                    installClickListener(view);
                    view.setClickable(true);
                    return new JSONObject().put("ok", true).put("viewId", eventViewId(view));
                case NodeUiProtocol.ACTION_SET_ON_CHECKED_CHANGE_LISTENER:
                    return installCheckedChangeListener(view);
                case NodeUiProtocol.ACTION_SET_ON_TEXT_CHANGED_LISTENER:
                    return installTextChangedListener(view);
                case NodeUiProtocol.ACTION_SET_ON_ITEM_SELECTED_LISTENER:
                    return installItemSelectedListener(view);
                case NodeUiProtocol.ACTION_GET_SELECTED_ITEM_ID:
                    return new JSONObject().put("ok", true).put("value", selectedItemId(view));
                case NodeUiProtocol.ACTION_SET_SELECTED_ITEM_ID:
                    return setSelectedItemId(view, request);
                case NodeUiProtocol.ACTION_GET_MENU_SIZE:
                    return new JSONObject().put("ok", true).put("value", menuSize(view));
                case NodeUiProtocol.ACTION_GET_MENU_ITEM_ID:
                    return new JSONObject().put("ok", true).put("value", menuItemId(view, request));
                case NodeUiProtocol.ACTION_MENU_ITEMS:
                    return menuItems(view);
                default:
                    return viewError("viewCommand 不支持：" + action);
            }
        } catch (Throwable error) {
            return viewError("viewCommand 失败（" + action + "）：" + describe(error));
        }
    }

    private static String handleIdOf(JSONObject request) {
        String handle = request.optString(NodeUiProtocol.K_HANDLE, "");
        if (handle.isEmpty()) {
            handle = request.optString("handle", "");
        }
        return handle;
    }

    private static JSONObject addView(ViewGroup group, JSONObject request) throws Exception {
        String childHandle = request.optString(NodeUiProtocol.K_CHILD_HANDLE, "");
        if (childHandle.isEmpty()) {
            childHandle = request.optString("childHandleId", request.optString("child", ""));
        }
        View child = (View) VIEW_HANDLES.get(childHandle);
        if (child == null) {
            return viewError("addView 缺少有效 childHandle：" + childHandle);
        }
        if (child == group) {
            return viewError("addView 不能把 View 加到自身");
        }
        ViewParent previous = child.getParent();
        if (previous instanceof ViewGroup) {
            ((ViewGroup) previous).removeView(child);
        }
        int index = request.has(NodeUiProtocol.K_INDEX)
                ? request.optInt(NodeUiProtocol.K_INDEX, -1)
                : request.optInt(NodeUiProtocol.K_VALUE, -1);
        if (index < 0 || index > group.getChildCount()) {
            index = -1;
        }
        try {
            if (index >= 0) {
                ViewGroup.LayoutParams params = child.getLayoutParams();
                if (params != null) {
                    group.addView(child, index, params);
                } else {
                    group.addView(child, index);
                }
            } else {
                ViewGroup.LayoutParams params = child.getLayoutParams();
                if (params != null) {
                    group.addView(child, params);
                } else {
                    group.addView(child);
                }
            }
        } catch (RuntimeException mismatch) {
            // 旧父容器的 LayoutParams 类型不匹配时，退回目标容器的默认 LP。
            if (index >= 0) {
                group.addView(child, index);
            } else {
                group.addView(child);
            }
        }
        return new JSONObject().put("ok", true)
                .put("viewId", eventViewId(child))
                .put("handleId", VIEW_HANDLES.handleFor(child));
    }

    private static JSONObject removeView(View view, JSONObject request) throws Exception {
        if (!(view instanceof ViewGroup)) {
            return viewError("removeView 需要 ViewGroup，当前=" + view.getClass().getName());
        }
        ViewGroup group = (ViewGroup) view;
        String childHandle = request.optString(NodeUiProtocol.K_CHILD_HANDLE, "");
        if (childHandle.isEmpty()) {
            childHandle = request.optString("childHandleId", request.optString("child", ""));
        }
        View child = (View) VIEW_HANDLES.get(childHandle);
        boolean removed;
        if (child != null) {
            removed = child.getParent() == group;
            group.removeView(child);
        } else if (request.has(NodeUiProtocol.K_VALUE) || request.has(NodeUiProtocol.K_INDEX)) {
            int index = request.optInt(NodeUiProtocol.K_VALUE, request.optInt(NodeUiProtocol.K_INDEX, -1));
            if (index < 0 || index >= group.getChildCount()) {
                return viewError("removeView 索引越界：" + index);
            }
            child = group.getChildAt(index);
            removed = child != null;
            group.removeViewAt(index);
        } else {
            return viewError("removeView 缺少有效 childHandle 或 index");
        }
        return new JSONObject().put("ok", true).put("value", removed);
    }

    private static JSONObject getChildAt(View view, JSONObject request) throws Exception {
        if (!(view instanceof ViewGroup)) {
            return viewError("getChildAt 需要 ViewGroup，当前=" + view.getClass().getName());
        }
        int index = request.optInt(NodeUiProtocol.K_VALUE, request.optInt(NodeUiProtocol.K_INDEX, 0));
        ViewGroup group = (ViewGroup) view;
        if (index < 0 || index >= group.getChildCount()) {
            return new JSONObject().put("ok", true).put(NodeUiProtocol.K_HANDLE, JSONObject.NULL);
        }
        View child = group.getChildAt(index);
        return new JSONObject().put("ok", true)
                .put(NodeUiProtocol.K_HANDLE, VIEW_HANDLES.handleFor(child))
                .put(NodeUiProtocol.K_VIEW_ID, eventViewId(child))
                .put(NodeUiProtocol.K_VIEW_NAME, "");
    }

    private static JSONObject getParent(View view) throws Exception {
        ViewParent parent = view.getParent();
        if (!(parent instanceof View)) {
            return new JSONObject().put("ok", true).put(NodeUiProtocol.K_HANDLE, JSONObject.NULL);
        }
        View parentView = (View) parent;
        return new JSONObject().put("ok", true)
                .put(NodeUiProtocol.K_HANDLE, VIEW_HANDLES.handleFor(parentView))
                .put(NodeUiProtocol.K_VIEW_ID, eventViewId(parentView));
    }

    private static void installClickListener(final View view) {
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enqueueViewEvent(NodeUiProtocol.EVENT_CLICK, eventViewId(view), null);
            }
        });
    }

    /** MaterialSwitch/SwitchCompat/CheckBox 等 CompoundButton，以及 MaterialButton。 */
    private static JSONObject installCheckedChangeListener(final View view) throws Exception {
        final int viewId = eventViewId(view);
        if (view instanceof CompoundButton) {
            ((CompoundButton) view).setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton button, boolean checked) {
                    enqueueViewEvent(NodeUiProtocol.EVENT_CHECK, viewId, payload("checked", checked));
                }
            });
            return new JSONObject().put("ok", true).put("viewId", viewId);
        }
        if (installMaterialButtonCheckedListener(view, viewId)) {
            return new JSONObject().put("ok", true).put("viewId", viewId);
        }
        return viewError("setOnCheckedChangeListener 只支持 CompoundButton/MaterialButton，当前="
                + view.getClass().getName());
    }

    /**
     * MaterialButton 的 checkable 变化走 addOnCheckedChangeListener(MaterialButton.OnCheckedChangeListener)，
     * 与 CompoundButton 接口不同；用反射挂真实监听器（材质版本差异时安全失败）。
     */
    private static boolean installMaterialButtonCheckedListener(final View view, final int viewId) {
        try {
            for (final java.lang.reflect.Method method : view.getClass().getMethods()) {
                if (!"addOnCheckedChangeListener".equals(method.getName())
                        || method.getParameterTypes().length != 1
                        || !method.getParameterTypes()[0].isInterface()) {
                    continue;
                }
                Class<?> listenerType = method.getParameterTypes()[0];
                Object listener = java.lang.reflect.Proxy.newProxyInstance(
                        listenerType.getClassLoader(), new Class<?>[]{listenerType},
                        (proxy, called, args) -> {
                            if ("onCheckedChanged".equals(called.getName()) && args != null && args.length >= 1) {
                                Object checked = args[args.length - 1];
                                enqueueViewEvent(NodeUiProtocol.EVENT_CHECK, viewId,
                                        payload("checked", checked instanceof Boolean
                                                ? checked : Boolean.parseBoolean(String.valueOf(checked))));
                            }
                            return null;
                        });
                method.invoke(view, listener);
                return true;
            }
        } catch (Throwable error) {
            Log.w(TAG, "安装 MaterialButton OnCheckedChangeListener 失败", error);
        }
        return false;
    }

    private static JSONObject installTextChangedListener(final View view) throws Exception {
        if (!(view instanceof TextView)) {
            return viewError("setOnTextChangedListener 只支持 TextView，当前=" + view.getClass().getName());
        }
        final TextView textView = (TextView) view;
        final int viewId = eventViewId(view);
        textView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String text = s == null ? textView.getText().toString() : s.toString();
                enqueueViewEvent(NodeUiProtocol.EVENT_TEXT_CHANGE, viewId, payload("text", text));
            }
        });
        return new JSONObject().put("ok", true).put("viewId", viewId);
    }

    private static JSONObject installItemSelectedListener(final View view) throws Exception {
        final int viewId = eventViewId(view);
        if (view instanceof BottomNavigationView) {
            ((BottomNavigationView) view).setOnItemSelectedListener(
                    new BottomNavigationView.OnItemSelectedListener() {
                        @Override
                        public boolean onNavigationItemSelected(android.view.MenuItem item) {
                            // 不消费事件：让 BottomNavigationView 完成真实选中态更新（2.7 getSelectedItemId 一致）。
                            enqueueViewEvent(NodeUiProtocol.EVENT_ITEM_SELECTED, viewId, itemPayload(item));
                            return false;
                        }
                    });
            return new JSONObject().put("ok", true).put("viewId", viewId);
        }
        if (view instanceof AdapterView) {
            ((AdapterView<?>) view).setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View selected, int position, long id) {
                    JSONObject payload = payload("itemId", id);
                    try {
                        payload.put("position", position);
                    } catch (Throwable ignored) {
                        // position 一定可序列化
                    }
                    enqueueViewEvent(NodeUiProtocol.EVENT_ITEM_SELECTED, viewId, payload);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
            return new JSONObject().put("ok", true).put("viewId", viewId);
        }
        if (installReflectiveItemSelectedListener(view, viewId)) {
            return new JSONObject().put("ok", true).put("viewId", viewId);
        }
        return viewError("setOnItemSelectedListener 只支持 BottomNavigationView/AdapterView，当前="
                + view.getClass().getName());
    }

    /** Material NavigationBarView（material>=1.4）的 setOnItemSelectedListener(OnItemSelectedListener)。 */
    private static boolean installReflectiveItemSelectedListener(final View view, final int viewId) {
        try {
            for (final java.lang.reflect.Method method : view.getClass().getMethods()) {
                if (!"setOnItemSelectedListener".equals(method.getName())
                        || method.getParameterTypes().length != 1
                        || !method.getParameterTypes()[0].isInterface()) {
                    continue;
                }
                Class<?> listenerType = method.getParameterTypes()[0];
                Object listener = java.lang.reflect.Proxy.newProxyInstance(
                        listenerType.getClassLoader(), new Class<?>[]{listenerType},
                        (proxy, called, args) -> {
                            if ("onItemSelected".equals(called.getName()) && args != null && args.length >= 1) {
                                enqueueViewEvent(NodeUiProtocol.EVENT_ITEM_SELECTED, viewId,
                                        itemPayload(args[0]));
                            }
                            return null;
                        });
                method.invoke(view, listener);
                return true;
            }
        } catch (Throwable error) {
            Log.w(TAG, "安装 setOnItemSelectedListener 失败", error);
        }
        return false;
    }

    private static int selectedItemId(View view) {
        if (view instanceof BottomNavigationView) {
            return ((BottomNavigationView) view).getSelectedItemId();
        }
        if (view instanceof AdapterView) {
            return (int) ((AdapterView<?>) view).getSelectedItemId();
        }
        try {
            java.lang.reflect.Method method = view.getClass().getMethod("getSelectedItemId");
            Object value = method.invoke(view);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        } catch (Throwable ignored) {
            // 非菜单型控件没有选中项
        }
        return 0;
    }

    private static JSONObject setSelectedItemId(View view, JSONObject request) throws Exception {
        int id = intValue(request.opt(NodeUiProtocol.K_VALUE));
        if (view instanceof BottomNavigationView) {
            ((BottomNavigationView) view).setSelectedItemId(id);
            return new JSONObject().put("ok", true).put("value", ((BottomNavigationView) view).getSelectedItemId());
        }
        try {
            java.lang.reflect.Method method = view.getClass().getMethod("setSelectedItemId", int.class);
            method.invoke(view, id);
            return new JSONObject().put("ok", true).put("value", selectedItemId(view));
        } catch (Throwable error) {
            return viewError("setSelectedItemId 只支持 BottomNavigationView，当前=" + view.getClass().getName());
        }
    }

    private static int menuSize(View view) {
        if (view instanceof BottomNavigationView) {
            return ((BottomNavigationView) view).getMenu().size();
        }
        return -1;
    }

    private static int menuItemId(View view, JSONObject request) {
        if (!(view instanceof BottomNavigationView)) {
            return -1;
        }
        android.view.Menu menu = ((BottomNavigationView) view).getMenu();
        int index = intValue(request.opt(NodeUiProtocol.K_VALUE));
        if (index < 0 || index >= menu.size()) {
            return -1;
        }
        return menu.getItem(index).getItemId();
    }

    private static JSONObject menuItems(View view) throws Exception {
        android.view.Menu menu = view instanceof BottomNavigationView
                ? ((BottomNavigationView) view).getMenu() : null;
        JSONArray ids = new JSONArray();
        if (menu != null) {
            for (int i = 0; i < menu.size(); i++) {
                ids.put(menu.getItem(i).getItemId());
            }
        }
        JSONObject value = new JSONObject();
        try {
            value.put("size", menu == null ? 0 : menu.size());
            value.put("selectedItemId", selectedItemId(view));
            value.put("itemIds", ids);
        } catch (Throwable ignored) {
            // 上面的 put 都是合法 key；失败也不影响 ok
        }
        return new JSONObject().put("ok", true).put("value", value);
    }

    private static boolean isChecked(View view) {
        if (view instanceof CompoundButton) {
            return ((CompoundButton) view).isChecked();
        }
        try {
            java.lang.reflect.Method method = view.getClass().getMethod("isChecked");
            Object value = method.invoke(view);
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 2.2：把 JS 定时回调排到宿主主线程 Looper 上，到期后用 pollEvents 的 timer 事件回调。 */
    private static JSONObject scheduleTimer(JSONObject request) throws Exception {
        final String key = timerKey(request);
        long delay = request.optLong("delay", request.optLong(NodeUiProtocol.K_VALUE, 0L));
        if (delay < 0) {
            delay = 0;
        }
        final int viewId = request.optInt(NodeUiProtocol.K_VIEW_ID, 0);
        final JSONObject payload = payload("channel", key);
        Runnable previous;
        synchronized (LOCK) {
            previous = TIMER_CALLBACKS.remove(key);
        }
        if (previous != null) {
            main().removeCallbacks(previous);
        }
        final Runnable task = new Runnable() {
            @Override
            public void run() {
                synchronized (LOCK) {
                    TIMER_CALLBACKS.remove(key);
                }
                enqueueViewEvent(NodeUiProtocol.EVENT_TIMER, viewId, payload);
            }
        };
        synchronized (LOCK) {
            TIMER_CALLBACKS.put(key, task);
        }
        main().postDelayed(task, delay);
        return new JSONObject().put("ok", true).put("value", key);
    }

    private static JSONObject cancelTimer(JSONObject request) throws Exception {
        String key = timerKey(request);
        Runnable task;
        synchronized (LOCK) {
            task = TIMER_CALLBACKS.remove(key);
        }
        if (task != null) {
            main().removeCallbacks(task);
        }
        return new JSONObject().put("ok", true).put("value", task != null);
    }

    private static String timerKey(JSONObject request) {
        String key = request.optString(NodeUiProtocol.K_CHANNEL, "");
        if (key.isEmpty()) {
            key = request.optString("key", "");
        }
        if (key.isEmpty()) {
            key = "timer-" + SystemClock.elapsedRealtimeNanos();
        }
        return key;
    }

    private static void enqueueViewEvent(String type, int viewId, JSONObject extra) {
        try {
            JSONObject event = new JSONObject().put("type", type).put("viewId", viewId);
            if (extra != null) {
                Iterator<String> keys = extra.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    event.put(key, extra.get(key));
                }
            }
            enqueueEventObject(event);
        } catch (Throwable error) {
            Log.w(TAG, "入队 View 事件失败: " + type, error);
        }
    }

    private static int eventViewId(View view) {
        return view == null ? 0 : view.getId();
    }

    private static JSONObject itemPayload(Object item) {
        JSONObject payload = new JSONObject();
        int itemId = 0;
        if (item instanceof android.view.MenuItem) {
            itemId = ((android.view.MenuItem) item).getItemId();
        }
        try {
            payload.put("itemId", itemId);
        } catch (Throwable ignored) {
            // itemId 一定合法
        }
        return payload;
    }

    private static JSONObject payload(String key, Object value) {
        JSONObject object = new JSONObject();
        try {
            object.put(key, value);
        } catch (Throwable ignored) {
            // key 为字面量，不会失败
        }
        return object;
    }

    private static int intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 2.5：本地会话/overlay 的 ui.inflate。支持 XML 字符串与工程布局名/数字 id，
     * parentHandle 用于生成 LayoutParams/可选 attach；返回真实 View 的 handle。
     */
    private static Object inflateForSession(final Context context, final String projectDir,
                                            final JSONObject request) throws Exception {
        final AtomicReference<JSONObject> result = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMain(new Runnable() {
            @Override
            public void run() {
                try {
                    ViewGroup parent = null;
                    String parentHandle = request.optString(NodeUiProtocol.K_PARENT_HANDLE,
                            request.optString("parentHandleId", ""));
                    if (!parentHandle.isEmpty()) {
                        View parentView = (View) VIEW_HANDLES.get(parentHandle);
                        if (parentView instanceof ViewGroup) {
                            parent = (ViewGroup) parentView;
                        }
                    }
                    boolean attach = request.optBoolean(NodeUiProtocol.K_ATTACH_TO_PARENT, parent != null);
                    String xml = request.optString("layoutXml", "");
                    String viewName = request.optString("name", "");
                    if (xml == null || xml.trim().isEmpty()) {
                        ProjectResourceBundle bundle = bundleOf(context, projectDir);
                        int layoutId = request.optInt("layoutId", 0);
                        String layoutName = null;
                        String path = null;
                        if (bundle != null && bundle.getError() == null) {
                            if (layoutId != 0) {
                                layoutName = bundle.layoutNameById(layoutId);
                                path = bundle.layoutFileById(layoutId);
                            }
                            if ((path == null || path.isEmpty()) && !viewName.isEmpty()) {
                                layoutName = viewName;
                                path = bundle.layoutFile(viewName);
                            }
                        }
                        if (path == null || path.isEmpty()) {
                            throw new IllegalStateException("找不到布局：" + (viewName.isEmpty()
                                    ? String.valueOf(layoutId) : viewName));
                        }
                        xml = readTextFile(new File(path));
                        if (layoutName != null && !layoutName.isEmpty()) {
                            viewName = layoutName;
                        }
                    }
                    if (xml == null || xml.trim().isEmpty()) {
                        throw new IllegalStateException("布局内容为空");
                    }
                    Context themed = NodeUiWidgetInflater.currentContext();
                    if (themed == null) {
                        themed = NodeUiLocalSession.getActivity();
                    }
                    if (themed == null && parent != null) {
                        themed = parent.getContext();
                    }
                    if (themed == null) {
                        themed = context;
                    }
                    View view = inflateInlineLayout(themed, xml, themed.getClassLoader(), parent, attach);
                    if (view == null) {
                        throw new IllegalStateException("inflate 结果为空");
                    }
                    result.set(viewHandleResult(view, view.getId(), viewName));
                } catch (Throwable error) {
                    failure.set(error);
                }
            }
        });
        if (failure.get() != null) {
            throw new IllegalStateException("inflate 失败：" + describe(failure.get()), failure.get());
        }
        return result.get() == null ? viewError("inflate 没有返回结果") : result.get();
    }

    private static JSONObject viewError(String message) {
        try {
            return new JSONObject().put("ok", false).put("error", message);
        } catch (Throwable ignored) {
            return new JSONObject();
        }
    }

    /** Activity 模式：click 事件在主进程队列里，随 pollEvents 拉回 :node24（250ms 节流）。 */
    private static void pollActivityViewEvents() {
        if (!sActivityMode) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (now - sLastActivityViewEventPollAt < ACTIVITY_VIEW_EVENT_POLL_MS) {
                return;
            }
            sLastActivityViewEventPollAt = now;
        }
        try {
            JSONObject result = invokeActivityUpdate("drainViewEvents", new JSONObject());
            JSONArray events = result.optJSONArray("events");
            if (events == null) {
                return;
            }
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event != null) {
                    enqueueEventObject(event);
                }
            }
        } catch (Throwable error) {
            String message = describe(error);
            if (!isActivityUpdateRetryable(message)) {
                Log.w(TAG, "C-ACTIVITY 拉取 View 事件失败（继续轮询）：" + message);
            }
        }
    }

    private static Object closeOverlay() throws Exception {
        final Runnable remove = new Runnable() {
            @Override
            public void run() {
                boolean hadOverlay = sOverlayView != null;
                removeOverlayLocked();
                enqueueEvent("destroy");
                // task-63：显式 close 一律置「关闭请求」标记，A 的 keep-alive 轮询 consumeCloseRequested()。
                CLOSE_REQUESTED.set(true);
                setUiMode(UI_MODE_NONE, "overlay_close");
                if (hadOverlay) {
                    notifyOverlayClosed();
                }
            }
        };
        // task-166：停止路径不能阻塞/在主线程等主线程。主线程直接执行；其它线程只 post 不等待，
        // 返回 ok 让 Node24Service 的取消/清理继续（floating overlay 由主进程 closeAll 同步关闭）。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            remove.run();
        } else {
            boolean posted = main().post(remove);
            if (!posted) {
                remove.run();
            }
        }
        return new JSONObject().put("ok", true);
    }

    /** task-63：overlay 显示成功后清掉可能残留的关闭标记（新 overlay = 重新开始）。 */
    private static void clearCloseRequested() {
        CLOSE_REQUESTED.set(false);
    }

    /** 关闭通知（吞异常，不能影响关闭流程/引擎收尾）。 */
    private static void notifyOverlayClosed() {
        OverlayClosedListener listener = sOverlayClosedListener;
        if (listener == null) {
            return;
        }
        try {
            listener.onOverlayClosed();
        } catch (Throwable error) {
            Log.w(TAG, "Node UI overlay 关闭通知失败", error);
        }
    }

    private static void removeOverlayLocked() {
        if (sOverlayView != null) {
            try { OverlayRegistry.unregister(sOverlayView); } catch (Throwable ignored) { }
        }
        if (sContentView != null) {
            // overlay 里的 NodeWebView 不归 NodeUiLocalSession 管；移除窗口前显式释放，
            // 否则关闭脚本后页面/JS 回调会继续存活。
            NodeWebView.destroyInTree(sContentView);
        }
        if (sOverlayView != null && sWindowManager != null) {
            try {
                sWindowManager.removeView(sOverlayView);
            } catch (Throwable error) {
                Log.w(TAG, "移除 Node UI overlay 失败", error);
            }
        }
        sOverlayView = null;
        sWindowManager = null;
        sShownProjectDir = null;
        sShownLayout = null;
        sContentView = null;
        sThemedContext = null;
        sShownBundle = null;
        sShownViews = null;
        VIEW_HANDLES.clear();
        synchronized (LOCK) {
            for (Runnable timer : TIMER_CALLBACKS.values()) {
                main().removeCallbacks(timer);
            }
            TIMER_CALLBACKS.clear();
        }
    }

    // ------------------------------------------------------------------
    // 事件队列 / 工具
    // ------------------------------------------------------------------

    private static void enqueueEvent(String type) {
        enqueueEvent(type, null);
    }

    /** payload 目前用于 content_view_set 的 views 映射（task-69）。 */
    private static void enqueueEvent(String type, JSONObject views) {
        try {
            JSONObject event = new JSONObject().put("type", type);
            if (type.equals("create") || type.equals("resume")) {
                event.put("layout", sShownLayout == null ? "" : sShownLayout);
            }
            if (views != null) {
                event.put("views", views);
            }
            enqueueEventObject(event);
        } catch (Throwable error) {
            Log.w(TAG, "入队 Node UI 事件失败: " + type, error);
        }
    }

    /** 原样入队事件对象（click 等带 viewId 的事件不能塞进 views 字段）。 */
    private static void enqueueEventObject(JSONObject event) {
        if (event == null) {
            return;
        }
        try {
            if (!event.has("type")) {
                event.put("type", "event");
            }
            synchronized (LOCK) {
                EVENTS.addLast(event);
                while (EVENTS.size() > 64) {
                    EVENTS.removeFirst();
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "入队 Node UI 事件对象失败", error);
        }
    }

    private static JSONArray drainEvents() {
        JSONArray result = new JSONArray();
        synchronized (LOCK) {
            while (!EVENTS.isEmpty()) {
                result.put(EVENTS.removeFirst());
            }
        }
        return result;
    }

    private static void runOnMain(final Runnable task) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        main().post(new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    latch.countDown();
                }
            }
        });
        if (!latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("等待主线程超时（Node UI 显示/关闭）");
        }
        if (failure.get() != null) {
            throw new IllegalStateException(describe(failure.get()));
        }
    }

    private static String readTextFile(File file) {
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable error) {
            Log.w(TAG, "读取文件失败: " + file, error);
            return null;
        }
    }

    /** task-72：主题的 window background 颜色（android.R.attr.colorBackground）。 */
    private static int resolveWindowBackground(Context context) {
        try {
            android.util.TypedValue value = new android.util.TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.colorBackground, value, true)) {
                return value.data;
            }
        } catch (Throwable error) {
            Log.w(TAG, "读取主题 colorBackground 失败", error);
        }
        return 0xFFFFFFFF;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 真实 View 的 handle 注册表（task-254 / 2.5）。
     *
     * <p>同一 View 永远返回同一 handleId，并用 IdentityHashMap 反查；这样
     * {@code root.addView(child)} 之后 {@code child.getParent()} 拿回的父句柄就是 root 的句柄，
     * JS 侧 facade 缓存命中，身份一致。纯 Java（只依赖 Object），JVM 单测可直接回归。
     */
    static final class ViewHandleRegistry {
        private static final int MAX_HANDLES = 4096;
        private final Map<String, Object> byId = new LinkedHashMap<>();
        private final java.util.IdentityHashMap<Object, String> byView = new java.util.IdentityHashMap<>();
        private int sequence = 1;

        synchronized String handleFor(Object view) {
            if (view == null) {
                throw new IllegalArgumentException("View 不能为空");
            }
            String existing = byView.get(view);
            if (existing != null) {
                return existing;
            }
            while (byId.size() >= MAX_HANDLES) {
                Iterator<Map.Entry<String, Object>> iterator = byId.entrySet().iterator();
                if (!iterator.hasNext()) {
                    break;
                }
                Map.Entry<String, Object> eldest = iterator.next();
                iterator.remove();
                byView.remove(eldest.getValue());
            }
            String handle = "view-" + (sequence++);
            byId.put(handle, view);
            byView.put(view, handle);
            return handle;
        }

        synchronized Object get(String handle) {
            return handle == null || handle.isEmpty() ? null : byId.get(handle);
        }

        synchronized String handleOf(Object view) {
            return view == null ? null : byView.get(view);
        }

        synchronized int size() {
            return byId.size();
        }

        synchronized void clear() {
            byId.clear();
            byView.clear();
            sequence = 1;
        }
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : "：" + message);
    }
}
