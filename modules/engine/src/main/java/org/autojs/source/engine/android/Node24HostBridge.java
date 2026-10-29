// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import com.stardust.autojs.core.image.ImageWrapper;
import com.stardust.autojs.engine.node24.NodeEngineBridgeClient;
import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.runtime.api.Ocr;
import com.stardust.autojs.runtime.api.OcrSession;
import com.stardust.autojs.runtime.api.OCRResult;
import com.stardust.autojs.runtime.api.OpenCvYoloDetector;
import com.stardust.autojs.runtime.api.Yolo;
import com.stardust.autojs.runtime.api.PPOcr;
import com.stardust.autojs.overlay.OverlayRegistry;
import com.stardust.util.IntentUtil;

import org.autojs.source.engine.Node24Engine;
import org.json.JSONArray;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Node24 的 Java 宿主桥（任务 task-58）：把 Node 侧 `__autojsHost(JSON)` 的同步请求翻译成 Java 反射调用。
 *
 * <p>协议（JSON，请求 → 响应）：
 * <pre>
 * 请求 {"op":"new","cls":"java.lang.StringBuilder","args":[wire...]}
 *      {"op":"static","cls":"android.os.Build$VERSION","method":"...","args":[...]}
 *      {"op":"call","obj":objId,"method":"append","args":[...]}
 *      {"op":"member","cls|obj":...,"name":"foo"}   // 判断字段/方法（JS 属性访问用）
 *      {"op":"field"/"setField","cls|obj":...,"name":...}
 *      {"op":"class","name":"com.xxx.Y"}            // 只解析/校验类
 *      {"op":"app","action":"openUrl/startActivity/getPackageName/toast/viewFile","args":[...]}
 *      {"op":"context"}                             // 返回 application Context
 *      {"op":"ping"}
 * 响应 {"ok":true,"value":wire} 或 {"ok":false,"error":"中文说明"}
 * wire: null | boolean | number | string | {"$obj":id,"$class":"..."} | {"$arr":[...]}
 *       | {"$jsInterface":{"$callback":id,"session":s}}   // 多方法 JS 对象实现接口
 * </pre>
 *
 * <p>生成的 Java 子类通过原生回调队列返回 Node；所有 V8 执行保持在 Node 线程。
 * 每个 Node 执行任务创建一个实例；对象句柄只在本次任务内有效，任务结束随实例回收。
 */
public final class Node24HostBridge implements Node24Engine.Host {

    private static final String TAG = "Node24Host";
    /** 跨进程传感器事件类型（prelude sensorsModule 按 token 路由到具体 Sensor 对象）。 */
    private static final String SENSOR_EVENT_CHANGE = "sensor_change";
    private static final String SENSOR_EVENT_ACCURACY = "sensor_accuracy_change";

    /**
     * 常见传感器名 → Android type。键是去掉下划线/空格并转大写后的名字，
     * 因此 accelerometer / TYPE_ACCELEROMETER / magnetic-field 都能命中。
     * 未收录的名字再回退到 Sensor.TYPE_* 反射，避免硬编码限制未来新增类型。
     */
    private static final Map<String, Integer> SENSOR_TYPES = new HashMap<>();
    static {
        SENSOR_TYPES.put("ACCELEROMETER", 1);
        SENSOR_TYPES.put("MAGNETICFIELD", 2);
        SENSOR_TYPES.put("ORIENTATION", 3);
        SENSOR_TYPES.put("GYROSCOPE", 4);
        SENSOR_TYPES.put("LIGHT", 5);
        SENSOR_TYPES.put("PRESSURE", 6);
        SENSOR_TYPES.put("TEMPERATURE", 7);
        SENSOR_TYPES.put("PROXIMITY", 8);
        SENSOR_TYPES.put("GRAVITY", 9);
        SENSOR_TYPES.put("LINEARACCELERATION", 10);
        SENSOR_TYPES.put("ROTATIONVECTOR", 11);
        SENSOR_TYPES.put("RELATIVEHUMIDITY", 12);
        SENSOR_TYPES.put("AMBIENTTEMPERATURE", 13);
        SENSOR_TYPES.put("MAGNETICFIELDUNCALIBRATED", 14);
        SENSOR_TYPES.put("GAMEROTATIONVECTOR", 15);
        SENSOR_TYPES.put("SIGNIFICANTMOTION", 16);
        SENSOR_TYPES.put("STEPDETECTOR", 18);
        SENSOR_TYPES.put("STEPCOUNTER", 19);
        SENSOR_TYPES.put("GEOMAGNETICROTATIONVECTOR", 20);
        SENSOR_TYPES.put("HEARTRATE", 21);
        SENSOR_TYPES.put("POSE6DOF", 28);
        SENSOR_TYPES.put("STATIONARYDETECT", 29);
        SENSOR_TYPES.put("MOTIONDETECT", 30);
        SENSOR_TYPES.put("HEARTBEAT", 31);
        SENSOR_TYPES.put("LOWLATENCYOFFBODYDETECT", 34);
        SENSOR_TYPES.put("ACCELEROMETERUNCALIBRATED", 35);
    }

    private final Context mContext;
    private final com.stardust.autojs.core.ui.inflater.ScriptResourceContext mAndroidContext;
    /** task-183：每个 Node 执行宿主一个 owner；overlay 关闭/清理只作用于本 owner，避免并行 job 互相关闭浮窗。 */
    private final String mOverlayOwner = "node24-" + java.util.UUID.randomUUID().toString();
    /** task-218：主进程引擎 id（脚本任务 owner），用于 OverlayRegistry 精确清理。 */
    private final int mOwnerEngineId;
    /** 最近一次 closeUiOverlay 的 direct View 清理数量（Node24Service 日志用）。 */
    private volatile int mLastDirectOverlayCount;
    /** 当前脚本项目目录（prelude 注入给 __ajsProjectDir，B 的 ui 模块用）。 */
    private final String mProjectDir;
    /** 对象句柄：id -> Java 对象（强引用；实例随一次 Node 执行回收）。 */
    private final Map<Integer, Object> mObjects = new ConcurrentHashMap<>();
    private final com.stardust.autojs.engine.ScriptJavaResources mOwnedJavaResources =
            new com.stardust.autojs.engine.ScriptJavaResources();
    private final Map<String, Object> mOwnedNotificationKeys = new ConcurrentHashMap<>();
    private final Map<Object, Integer> mObjectIds = new IdentityHashMap<>();
    private int mNextObjectId = 1;
    /** task-58 轮询式反向事件通道：Java（B 的 UI/生命周期）投递、Node 定时 poll 取走同步派发。 */
    private static final int MAX_EVENTS = 256;
    private final ConcurrentLinkedQueue<JSONObject> mEvents = new ConcurrentLinkedQueue<>();
    // Promise completions cannot share the lossy sensor/event buffer: dropping
    // one leaves its JS Promise pending forever under a large Promise.all().
    private final ConcurrentLinkedQueue<JSONObject> mJavaResults = new ConcurrentLinkedQueue<>();
    /** 当前任务注册的 Android 传感器监听器：token -> registration（close 时统一注销）。 */
    private final Map<Integer, SensorRegistration> mSensorRegistrations = new HashMap<>();
    private final Map<Integer, android.os.FileObserver> mFileObservers = new HashMap<>();
    private int mNextFileObserver = 1;
    private int mNextSensorToken = 1;
    /** task-198：host.runtime.loadDex 加载的动态 dex classloader（插件类优先从这里解析）。 */
    private volatile ClassLoader mRuntimeClassLoader;
    private final Object mJavaClassLock = new Object();
    private final Map<String, Class<?>> mGeneratedClasses = new ConcurrentHashMap<>();
    private final Map<Integer, NodeJsCallback> mJavaCallbacks = new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> mConstructingFromJs = new ThreadLocal<>();
    private final java.util.concurrent.ExecutorService mJavaExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread thread = new Thread(r, "node24-java-io"); thread.setDaemon(true); return thread;
            });
    /** task-195：prelude 记录 process.exit 请求（exit(0) 强制成功，非 0 保留 code）。 */
    private volatile boolean mExitRequested;
    private volatile int mExitRequestedCode = -1;

    /** task-63：prelude 的 $autojs.keepRunning()/stopRunning() 通过 host op kind=keepalive 置位。 */
    private volatile boolean mKeepRunning;
    /** task-64 兜底：最近一次 kind=ui / kind=keepalive 请求时间（elapsedRealtime），用于时序兜底。 */
    private volatile long mLastUiRequestAt;
    private volatile long mLastKeepAliveAt;
    /** task-65：记录 NodeUiHost 缺失/反射失败的 helper 名，最多记一次，便于 logcat 定位。 */
    private final java.util.Set<String> mMissingUiHelpers =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    /** 冻结协议的能力域；每个任务独立持有，close 时统一释放。 */
    private final Node24Images mImages;
    private final Node24AutomationBridge mAutomation;
    private final Node24CaptureConsentActivity.Session mProjection;
    /** 跨引擎能力：:node24 → 主进程真实 ScriptEngineService（懒绑定，close 时解绑）。 */
    private final NodeEngineBridgeClient mEngineBridge;
    private final Node24PluginLoader mPluginLoader;
    private final java.util.Set<Integer> mShellGroups = ConcurrentHashMap.newKeySet();
    /** Node24 require('ocr') 的真实内置 OCR 会话（PP-OCR / ML Kit 同形，close 统一释放）。 */
    private final Map<Integer, OcrSession> mOcrSessions = new ConcurrentHashMap<>();
    private final AtomicInteger mNextOcrHandle = new AtomicInteger(1);
    /** task-26：YOLO 模型会话（kind:"yolo"）；与 OCR 一样借 Node24Images 的 Bitmap。 */
    private final Map<Integer, YoloSession> mYoloSessions = new ConcurrentHashMap<>();
    private final AtomicInteger mNextYoloModel = new AtomicInteger(1);
    private Yolo mYolo;

    // --- task-156：通知 action 点击 + 无障碍事件订阅（同 UID 跨进程广播） ---
    private static final String ACTION_NODE_ACCESSIBILITY_SUBSCRIBE =
            "com.jdkshen.aijspro.NODE_ACCESSIBILITY_SUBSCRIBE";
    private static final String ACTION_NODE_ACCESSIBILITY_UNSUBSCRIBE =
            "com.jdkshen.aijspro.NODE_ACCESSIBILITY_UNSUBSCRIBE";
    private static final String ACTION_NODE_ACCESSIBILITY_EVENT =
            "com.jdkshen.aijspro.NODE_ACCESSIBILITY_EVENT";
    // task-171：低版本补全 API
    private static final String ACTION_NODE_ACCESSIBILITY_KEY_EVENTS =
            "com.jdkshen.aijspro.NODE_ACCESSIBILITY_KEY_EVENTS";
    private static final String ACTION_NODE_ACCESSIBILITY_LOCK_SCREEN =
            "com.jdkshen.aijspro.NODE_ACCESSIBILITY_LOCK_SCREEN";
    private static final String ACTION_NODE_NOTIFICATION_LISTENER_EVENT =
            "com.jdkshen.aijspro.NODE_NOTIFICATION_LISTENER_EVENT";
    private static final String ACTION_NODE_NOTIFICATION_LISTENER_SUBSCRIBE =
            "com.jdkshen.aijspro.NODE_NOTIFICATION_LISTENER_SUBSCRIBE";
    private static final String ACTION_NODE_NOTIFICATION_LISTENER_UNSUBSCRIBE =
            "com.jdkshen.aijspro.NODE_NOTIFICATION_LISTENER_UNSUBSCRIBE";
    private static final String EVENT_NODE_NOTIFICATION_LISTENER = "__ajs_notification_listener";
    private static final String ACTION_NODE_NOTIFICATION_EVENT =
            "com.jdkshen.aijspro.NODE_NOTIFICATION_EVENT";
    private static final String EVENT_NODE_ACCESSIBILITY = "__ajs_accessibility_event";
    private static final String EVENT_NODE_NOTIFICATION = "__ajs_notification_event";
    private static final String NODE_NOTIFICATION_CHANNEL = "node24";
    private android.content.BroadcastReceiver mAccessibilityEventReceiver;
    private android.content.BroadcastReceiver mNotificationEventReceiver;
    private android.content.BroadcastReceiver mNotificationListenerReceiver;
    private int mNotificationPendingSeq = 1000;

    // --- task-158：media 播放/音量/扫描（本进程 MediaPlayer，audio focus 由系统处理） ---
    private android.media.MediaPlayer mMusicPlayer;
    private String mMusicPath;
    private static final String[] KNOWN_PLUGIN_PACKAGES = {
            "com.jdkshen.apkbuilder.plugin",
            "com.jdkshen.xiangqi.plugin",
            "com.jdkshen.xiangqi.quickjs"
    };
    /** MediaPlayer 必须在带 Looper 的线程上创建/使用，否则 release 时会触发 native 崩溃。 */
    private android.os.HandlerThread mMediaThread;
    private android.os.Handler mMediaHandler;
    /** Node24Images.requireBitmap(int) 的惰性反射句柄；失败时回退到临时 PNG。 */
    private static volatile Method sNodeBitmapAccessor;
    private static volatile boolean sNodeBitmapAccessorResolved;
    private static final int OCR_IMAGE_MAX_PIXELS = 16_000_000;
    private final java.util.concurrent.atomic.AtomicBoolean mClosed =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile boolean mCallbacksClosing;
    private com.stardust.autojs.engine.node24.NodeUiWidgetInflater.Registration mWidgetRegistration;
    private volatile boolean mCleanupIncomplete;
    private volatile String mScriptError = "";
    /**
     * 本次 Node job 独占的反射元数据缓存（容量 256 类 + 256 方法候选，close 清空）。
     * 不能做成 static：否则会跨 job 强引用插件 Class/ClassLoader。
     */
    private final Node24ReflectionCache mReflectionCache = new Node24ReflectionCache();
    private final Node24NoArgCallCache mNoArgCalls = new Node24NoArgCallCache();
    private final Node24JavaMemberTable mJavaMembers = new Node24JavaMemberTable();
    private final Node24UiThreadScope mUiThreadScope = new Node24UiThreadScope();
    // Synchronous handlers can wait behind ViewRoot traversal barriers on every
    // individual bridge operation. Async messages still execute on main, without
    // turning a large programmatic layout into hundreds of frame-sized waits.
    private final android.os.Handler mUiHandler = android.os.Build.VERSION.SDK_INT >= 28
            ? android.os.Handler.createAsync(Looper.getMainLooper())
            : new android.os.Handler(Looper.getMainLooper());
    private static final Object[] NO_ARGUMENTS = new Object[0];

    public Node24HostBridge(Context context) {
        this(context, null);
    }

    public Node24HostBridge(Context context, String projectDir) {
        this(context, projectDir, -1);
    }

    public Node24HostBridge(Context context, String projectDir, int ownerEngineId) {
        mContext = context.getApplicationContext();
        mProjectDir = projectDir;
        mAndroidContext = new com.stardust.autojs.core.ui.inflater.ScriptResourceContext(mContext, projectDir, mOverlayOwner);
        mOwnerEngineId = ownerEngineId;
        mImages = new Node24Images();
        mAutomation = new Node24AutomationBridge(mContext, mImages);
        mProjection = new Node24CaptureConsentActivity.Session();
        mEngineBridge = new NodeEngineBridgeClient(mContext, ownerEngineId);
        mPluginLoader = new Node24PluginLoader(mContext);
    }

    @Override
    public String invoke(String requestJson) {
        if (mClosed.get()) {
            return error("本次 Node 任务的宿主桥已结束").toString();
        }
        try {
            // The prelude emits this canonical suffix for parameterless calls.
            // Other requests keep their normal path without hashing/warming a binding.
            boolean noArgRequest = requestJson != null && requestJson.endsWith(",\"args\":[]}");
            Node24NoArgCallCache.Call bound = noArgRequest ? mNoArgCalls.get(requestJson) : null;
            if (bound != null) {
                return ok(toWire(invokeMethod(bound.method, bound.target, NO_ARGUMENTS))).toString();
            }
            JSONObject request = new JSONObject(requestJson);
            String kind = request.optString("kind", "");
            if ("ui".equals(kind)) {
                mLastUiRequestAt = android.os.SystemClock.elapsedRealtime();
            } else if ("keepalive".equals(kind)) {
                mLastKeepAliveAt = android.os.SystemClock.elapsedRealtime();
            }
            JSONObject response = handle(request);
            if (noArgRequest && response.optBoolean("ok", false)) {
                rememberNoArgCall(requestJson, request);
            }
            return response.toString();
        } catch (Throwable failure) {
            Log.w(TAG, "host request failed: " + requestJson, failure);
            String message = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            // 能力域自己抛的 IllegalArgumentException/IllegalStateException/UnsupportedOperationException
            // 是给脚本看的中文提示，不再套一层「宿主桥内部错误」（否则 plugins/ocr 等模块提示变毛）。
            if (failure instanceof IllegalArgumentException
                    || failure instanceof IllegalStateException
                    || failure instanceof UnsupportedOperationException) {
                return error(message).toString();
            }
            return error("宿主桥内部错误：" + message).toString();
        }
    }

    /**
     * 冻结协议入口（task-58/59 对齐）：请求顶层用 kind 区分能力域。
     *   {"kind":"java"|"rhino","op":"class|new|static|call|member|field|setField|classOf|context",...}
     *   {"kind":"app","action":"openUrl|startActivity|getPackageName|toast",...}
     *   {"kind":"poll"}                                  取走 Java 侧投递的事件
     *   {"kind":"sensors","op":"get|register|unregister|unregisterAll",...} 真实 SensorEventListener
     *   {"kind":"ui","action":"...","payload":{...}}     反射委托 B 的 NodeUiHost.handle(context, projectDir, request)
     *   {"kind":"automation"|"image"|"projection"|"ocr",...}   协议见 docs/guides/Node24基础自动化API.md
     *   {"kind":"host"}                                  桥/项目目录信息
     * 兼容：没有 kind 时按旧的 {"op":...} 处理（内部测试用）。
     */
    private JSONObject handle(JSONObject request) throws Exception {
        String kind = request.optString("kind", "");
        if (!kind.isEmpty()) {
            switch (kind) {
                case "rhino":
                case "java":
                    return handleJava(request);
                case "app":
                    return ok(toWire(appAction(request)));
                case "notification":
                    return ok(handleNodeNotification(request));
                case "accessibility":
                    return ok(handleAccessibility(request));
                case "media":
                    return ok(handleMedia(request));
                case "plugins":
                    return ok(handlePlugins(request));
                case "shell": {
                    int pid = request.getInt("pid");
                    if ("register".equals(request.getString("action"))) {
                        try {
                            String stat = new String(java.nio.file.Files.readAllBytes(
                                    new File("/proc/" + pid + "/stat").toPath()), java.nio.charset.StandardCharsets.UTF_8);
                            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
                            if (pid <= 1 || Integer.parseInt(fields[1]) != android.os.Process.myPid()
                                    || Integer.parseInt(fields[2]) != pid) throw new IllegalArgumentException("不是当前任务的 Shell 进程组");
                            mShellGroups.add(pid);
                        } catch (java.nio.file.NoSuchFileException alreadyExited) { }
                    } else if ("release".equals(request.getString("action"))) {
                        if (mShellGroups.remove(pid)) killShellGroup(pid);
                    } else throw new IllegalArgumentException("Unknown shell action");
                    return ok(true);
                }
                case "engines":
                    // 真实跨进程引擎注册表在主进程：不能在这里伪造 Node 侧引擎对象。
                    return handleEngineRequest(request);
                case "device":
                case "files":
                case "storages":
                case "events":
                case "threads":
                    // task-133-fix：JSONObject/JSONArray 直接作为 value（之前 toWire 会把 device info 标成 $obj）。
                    return ok(domainAction(kind, request));
                case "automation":
                    return mAutomation.handle(request);
                case "image":
                    return mImages.handle(request);
                case "projection":
                    return Node24CaptureConsentActivity.handle(mContext, mProjection, mImages, request);
                case "ocr":
                    return handleOcr(request);
                case "yolo":
                    return ok(handleYolo(request));
                case "poll":
                    return ok(pollEvents());
                case "sensors":
                    return handleSensors(request);
                case "keepalive":
                    mKeepRunning = request.optBoolean("running", true);
                    return ok(mKeepRunning);
                case "lifecycle": {
                    // task-195：prelude 的 process.exit 记录正常退出请求。
                    String lifecycleAction = request.optString("action", "");
                    if ("scriptError".equals(lifecycleAction)) {
                        JSONObject lifecycleArgs = request.optJSONObject("args");
                        String detail = lifecycleArgs == null ? "" : lifecycleArgs.optString("detail", "");
                        mScriptError = detail.length() > 16384 ? detail.substring(0, 16384) : detail;
                        return ok(true);
                    }
                    if ("exitRequested".equals(lifecycleAction)) {
                        JSONObject lifecycleArgs = request.optJSONObject("args");
                        mExitRequestedCode = lifecycleArgs == null ? 0 : lifecycleArgs.optInt("code", 0);
                        mExitRequested = true;
                        return ok(mExitRequestedCode);
                    }
                    if ("prepareExit".equals(lifecycleAction)) {
                        mOwnedJavaResources.detachViews();
                        com.stardust.autojs.engine.node24.NodeUiLocalSession.closeBeforeCallbackShutdown(mOverlayOwner);
                        return ok(true);
                    }
                    return error("lifecycle 不支持：" + lifecycleAction);
                }
                case "runtime": {
                    // task-198：第三方 Node 工程的 host.runtime.getAndroidClassLoader/loadDex 兼容。
                    String runtimeAction = request.optString("action", "");
                    JSONObject runtimeArgs = request.optJSONObject("args");
                    if ("classLoader".equals(runtimeAction)) {
                        return ok(mContext.getClassLoader());
                    }
                    if ("loadDex".equals(runtimeAction)) {
                        String dexPath = runtimeArgs == null ? "" : runtimeArgs.optString("path", "");
                        return ok(loadRuntimeDex(dexPath));
                    }
                    if ("methods".equals(runtimeAction) || "fields".equals(runtimeAction)) {
                        String className = runtimeArgs == null ? "" : runtimeArgs.optString("name", "");
                        return ok(reflectMembers(className, "methods".equals(runtimeAction)));
                    }
                    return error("runtime 不支持：" + runtimeAction);
                }
                case "ui": {
                    // B 的 ui.js 约定：kind:"ui" 的返回必须是 NodeUiHost 原始 JSON（内含 {ok:true,...}），
                    // 不能再包一层 {ok,value}。
                    String raw = handleUiJson(request);
                    try {
                        return new JSONObject(raw);
                    } catch (JSONException parseError) {
                        return error("Node UI 宿主返回非法 JSON：" + raw);
                    }
                }
                case "host":
                    return ok(new JSONObject().put("bridge", "node24-host").put("version", 2)
                            .put("projectDir", mProjectDir == null ? JSONObject.NULL : mProjectDir));
                default:
                    return error("Node24 host 不支持的 kind：" + kind);
            }
        }
        return handleJava(request);
    }

    private JSONObject handleJava(JSONObject request) throws Exception {
        String op = request.optString("op", "");
        if ("beginUiScope".equals(op)) {
            mUiThreadScope.enter();
            return ok(null);
        }
        if ("endUiScope".equals(op)) {
            mUiThreadScope.exit();
            return ok(null);
        }
        // An explicit async call retains its own executor/mode; a UI scope
        // must not turn background Java work into a main-thread operation.
        if (mUiThreadScope.isActive() && !"asyncCall".equals(op)
                && Looper.myLooper() != Looper.getMainLooper()) {
            return onUi(() -> handleJava(request));
        }
        switch (op) {
            case "widgetFactory": {
                org.json.JSONArray names = request.getJSONArray("names");
                String[] tags = new String[names.length()];
                for (int i = 0; i < tags.length; i++) tags[i] = names.getString(i);
                if (mWidgetRegistration == null) {
                    Object callback = fromWire(request.get("callback"));
                    com.stardust.autojs.engine.node24.NodeUiWidgetInflater.Callbacks factory =
                            (com.stardust.autojs.engine.node24.NodeUiWidgetInflater.Callbacks)
                            Node24JavaArguments.coerce(com.stardust.autojs.engine.node24.NodeUiWidgetInflater.Callbacks.class, callback);
                    mWidgetRegistration = com.stardust.autojs.engine.node24.NodeUiLocalSession.registerWidgetFactory(
                            mOverlayOwner, tags, factory);
                } else mWidgetRegistration.updateTags(tags);
                return ok(true);
            }
            case "isInterface":
                return ok(findClass(request.getString("name")).isInterface());
            case "proxyInterface": {
                Class<?> iface = findClass(request.getString("cls"));
                Object callback = fromWire(request.get("callback"));
                Object proxy = Node24JavaArguments.coerce(iface, callback);
                if (!iface.isInstance(proxy)) {
                    throw new IllegalArgumentException("Java 回调需要接口实现：" + iface.getName());
                }
                return ok(toWire(proxy));
            }
            case "defineClass":
                return ok(defineJavaClass(request));
            case "asyncCall": {
                final JSONObject call = request.getJSONObject("call");
                final int id = request.getInt("id");
                Runnable work = () -> {
                    JSONObject result = new JSONObject();
                    try {
                        result.put("id", id);
                        if (mClosed.get()) throw new IllegalStateException("脚本已结束");
                        result.put("value", "defineClass".equals(call.optString("op"))
                                ? defineJavaClass(call)
                                : toWire(call.has("cls") ? staticCall(call) : instanceCall(call)));
                    } catch (Throwable failure) {
                        try { result.put("error", describeCause(failure)); } catch (JSONException ignored) {}
                    }
                    if (!mClosed.get()) enqueueEvent("__ajs_java_result", result);
                };
                if (!"defineClass".equals(call.optString("op")) && "ui".equals(request.optString("mode")))
                    new android.os.Handler(Looper.getMainLooper()).post(work);
                else mJavaExecutor.execute(work);
                return ok(null);
            }
            case "uiCall": {
                return ok(toWire(onUi(() -> {
                    if (mClosed.get()) throw new IllegalStateException("脚本已结束");
                    return request.has("cls") ? staticCall(request) : instanceCall(request);
                })));
            }
            case "ping":
                return ok(new JSONObject().put("bridge", "node24-host").put("version", 1));
            case "class":
                return ok(new JSONObject().put("$class", findClass(request.optString("name", "")).getName()));
            case "classObject":
                return ok(new JSONObject().put("$obj", objectId(findClass(request.optString("name", ""))))
                        .put("$class", "java.lang.Class"));
            case "arrayGet":
                return ok(toWire(Array.get(objectOf(request), request.getInt("index"))));
            case "arraySet": {
                Object array = objectOf(request);
                Array.set(array, request.getInt("index"), coerce(array.getClass().getComponentType(),
                        fromWire(request.opt("value"))));
                return ok(null);
            }
            case "new":
                return ok(toWire(ownNewInstance(request)));
            case "newRef": {
                // task-136-fix：始终返回对象句柄（避免 String 等被 toWire 成原始值后无法调方法）。
                Object created = ownNewInstance(request);
                if (created == null) return ok(null);
                if (created.getClass().isArray()) return ok(toWire(created));
                return ok(new JSONObject().put("$obj", objectId(created))
                        .put("$class", created.getClass().getName())
                        .put("$type", mJavaMembers.typeId(created.getClass()))
                        .put("$view", created instanceof android.view.View));
            }
            case "static":
                return ok(toWire(staticCall(request)));
            case "call":
                return ok(toWire(instanceCall(request)));
            case "member":
                return member(request);
            case "field":
                return ok(toWire(readField(request)));
            case "setField":
                writeField(request);
                return ok(null);
            case "classOf": {
                Object object = objectOf(request);
                return ok(object == null ? null : object.getClass().getName());
            }
            case "context":
                return ok(toWire(mAndroidContext));
            case "app":
                return ok(toWire(appAction(request)));
            case "poll":
                return ok(pollEvents());
            case "post": {
                // 预留给测试/宿主自测：{"op":"post","type":"ui:click","payload":{...}}
                enqueueEventValue(request.optString("type", ""), request.opt("payload"));
                return ok(true);
            }
            default:
                return error("Node24 host 不支持的操作：" + op);
        }
    }

    // ------------------------------------------------------------------
    // OCR（kind:"ocr"）：复用 Java 侧真实的 Ocr / PPOcr 会话
    // ------------------------------------------------------------------

    /**
     * 协议：
     * <pre>
     *   {"kind":"ocr","action":"create","backend":"ppocr|mlkit", ...PP-OCR 参数}
     *   {"kind":"ocr","action":"detect","handle":1,"imageId":2|"imagePath":"/x.png",
     *    "max":1000,"detectRotation":false,"region":[x,y,w,h]}
     *   {"kind":"ocr","action":"release","handle":1}
     *   {"kind":"ocr","action":"liveCount"}
     * </pre>
     */
    private JSONObject handleOcr(JSONObject request) {
        String action = request.optString("action", "");
        try {
            switch (action) {
                case "create":
                    return ok(createOcr(request));
                case "detect":
                    return ok(detectOcr(request));
                case "release":
                    return ok(releaseOcr(request.optInt("handle", -1)));
                case "liveCount":
                    return ok(mOcrSessions.size());
                default:
                    return error("ocr 不支持的操作：" + action
                            + "（支持 create/detect/release/liveCount）");
            }
        } catch (Throwable failure) {
            Log.w(TAG, "ocr " + action + " failed: " + request, failure);
            return error("OCR " + (action.isEmpty() ? "操作" : action) + " 失败："
                    + ocrFailureMessage(failure));
        }
    }

    private JSONObject createOcr(JSONObject request) throws Exception {
        String backend = request.optString("backend", "ppocr").trim().toLowerCase(Locale.US);
        OcrSession session;
        if ("ppocr".equals(backend) || "paddle".equals(backend) || "paddleocr".equals(backend)) {
            backend = "ppocr";
            if (!PPOcr.isAvailable()) {
                throw new IllegalStateException("默认 PP-OCR 后端在当前 ABI 不可用（只随包 arm64-v8a）；"
                        + "请使用 createOCR({backend:'mlkit'}) 走内置 ML Kit");
            }
            // PPOcr 默认模型目录走 ScriptRuntime.getApplicationContext()；
            // :node24 进程没有 ScriptEngineService 去设置它，这里补一次（只保存弱引用）。
            try {
                ScriptRuntime.setApplicationContext(mContext);
            } catch (Throwable error) {
                throw new IllegalStateException("无法准备 PP-OCR 的 Application Context："
                        + ocrFailureMessage(error), error);
            }
            session = new PPOcr(null, null,
                    request.optInt("cpuPower", 0),
                    request.optInt("threads", 0),
                    (float) request.optDouble("minScore", 0),
                    request.optInt("targetSize", 0),
                    request.optBoolean("useFp16", false),
                    request.optInt("recMaxWidth", 0));
        } else if ("mlkit".equals(backend)) {
            // The ML Kit InitProvider runs in the main process. Node's isolated
            // :node24 process must initialize its own ML Kit component registry.
            synchronized (Node24HostBridge.class) {
                try {
                    com.google.mlkit.common.sdkinternal.MlKitContext.getInstance();
                } catch (IllegalStateException notInitialized) {
                    com.google.mlkit.common.MlKit.initialize(mContext);
                }
            }
            session = new Ocr(null);
        } else {
            throw new IllegalArgumentException("不支持的 OCR backend：" + backend
                    + "（支持 ppocr/mlkit）");
        }
        int handle = mNextOcrHandle.getAndIncrement();
        mOcrSessions.put(handle, session);
        return new JSONObject()
                .put("handle", handle)
                .put("backend", backend)
                .put("liveCount", mOcrSessions.size());
    }

    private JSONArray detectOcr(JSONObject request) throws Exception {
        int handle = request.optInt("handle", -1);
        OcrSession session = mOcrSessions.get(handle);
        if (session == null || session.isReleased()) {
            throw new IllegalStateException("OCR 会话不存在或已释放（handle=" + handle + "）");
        }
        int imageId = request.optInt("imageId", -1);
        String imagePath = request.optString("imagePath", "");
        OcrBitmap frame;
        if (imageId > 0) {
            frame = acquireNodeBitmap(imageId);
        } else if (!imagePath.isEmpty()) {
            frame = decodeImageFile(imagePath);
        } else {
            throw new IllegalArgumentException("OCR detect 需要 imageId（Node 图片句柄）或 imagePath");
        }
        try {
            int max = request.optInt("max", 1000);
            boolean detectRotation = request.optBoolean("detectRotation", false);
            int[] region = parseOcrRegion(request.optJSONArray("region"));
            OCRResult[] results = session.detect(ImageWrapper.ofBitmap(frame.bitmap),
                    max, detectRotation, region);
            return ocrResults(results);
        } finally {
            if (frame.owned && frame.bitmap != null && !frame.bitmap.isRecycled()) {
                try {
                    frame.bitmap.recycle();
                } catch (Throwable error) {
                    Log.w(TAG, "recycle OCR bitmap failed", error);
                }
            }
        }
    }

    /** 取 Node24Images 里的 Bitmap（借用，不回收）；反射不可用时回退到临时 PNG。 */
    private OcrBitmap acquireNodeBitmap(int id) throws Exception {
        Method accessor = nodeBitmapAccessor();
        if (accessor != null) {
            try {
                Object value = accessor.invoke(mImages, id);
                if (!(value instanceof Bitmap)) {
                    throw new IllegalStateException("Node24Images 返回了非 Bitmap 对象："
                            + (value == null ? "null" : value.getClass().getName()));
                }
                Bitmap bitmap = (Bitmap) value;
                if (bitmap.isRecycled()) {
                    throw new IllegalArgumentException("图片句柄已回收：" + id);
                }
                return new OcrBitmap(bitmap, false);
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                throw new IllegalArgumentException(ocrFailureMessage(cause), cause);
            } catch (IllegalAccessException | SecurityException fallback) {
                Log.w(TAG, "direct OCR bitmap access failed; falling back to temp PNG", fallback);
            }
        }
        return decodeNodeBitmapFallback(id);
    }

    private static Method nodeBitmapAccessor() {
        if (!sNodeBitmapAccessorResolved) {
            synchronized (Node24HostBridge.class) {
                if (!sNodeBitmapAccessorResolved) {
                    try {
                        Method method = Node24Images.class.getDeclaredMethod("requireBitmap", int.class);
                        method.setAccessible(true);
                        sNodeBitmapAccessor = method;
                    } catch (Throwable error) {
                        // Node24Images 的句柄 map 是私有的；在不能改该文件的约束下，
                        // 反射失败就用「写临时 PNG -> 解码」，功能仍然真实，只是多一次编解码。
                        Log.w(TAG, "Node24Images.requireBitmap unavailable; "
                                + "OCR image handles will use the temp-PNG fallback", error);
                        sNodeBitmapAccessor = null;
                    } finally {
                        sNodeBitmapAccessorResolved = true;
                    }
                }
            }
        }
        return sNodeBitmapAccessor;
    }

    private OcrBitmap decodeNodeBitmapFallback(int id) throws Exception {
        File temporary = File.createTempFile("ajs-ocr-", ".png", mContext.getCacheDir());
        try {
            JSONObject response = mImages.handle(new JSONObject()
                    .put("kind", "image")
                    .put("action", "write")
                    .put("id", id)
                    .put("path", temporary.getAbsolutePath())
                    .put("format", "png")
                    .put("quality", 100));
            if (!response.optBoolean("ok", false)) {
                throw new IllegalArgumentException(response.optString("error",
                        "图片句柄不存在或已回收：" + id));
            }
            return decodeImageFile(temporary.getAbsolutePath());
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                Log.w(TAG, "delete OCR temporary image failed: " + temporary);
            }
        }
    }

    private OcrBitmap decodeImageFile(String path) throws Exception {
        if (path == null || path.isEmpty() || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("OCR 图片路径非法");
        }
        File file = new File(path);
        if (!file.isAbsolute()) {
            throw new IllegalArgumentException("OCR 图片路径必须是绝对路径：" + path);
        }
        if (!file.isFile()) {
            throw new IllegalArgumentException("OCR 图片不存在：" + path);
        }
        if (file.length() <= 0L) {
            throw new IllegalArgumentException("OCR 图片为空：" + path);
        }
        if (file.length() > 256L * 1024L * 1024L) {
            throw new IllegalArgumentException("OCR 图片过大（上限 256MB）：" + path);
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IllegalArgumentException("无法解码 OCR 图片（格式不支持或文件损坏）：" + path);
        }
        long pixels = (long) bounds.outWidth * (long) bounds.outHeight;
        if (pixels > OCR_IMAGE_MAX_PIXELS) {
            throw new IllegalArgumentException("OCR 图片尺寸过大：" + bounds.outWidth + "x"
                    + bounds.outHeight + "（" + pixels + " 像素，上限 " + OCR_IMAGE_MAX_PIXELS + "）");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inScaled = false;
        Bitmap bitmap;
        try {
            bitmap = BitmapFactory.decodeFile(path, options);
        } catch (OutOfMemoryError memory) {
            throw new IllegalStateException("OCR 图片解码内存不足：" + path, memory);
        }
        if (bitmap == null) {
            throw new IllegalArgumentException("无法解码 OCR 图片（格式不支持或文件损坏）：" + path);
        }
        return new OcrBitmap(bitmap, true);
    }

    private static int[] parseOcrRegion(JSONArray array) {
        if (array == null) {
            return null;
        }
        int length = array.length();
        if (length != 2 && length != 4) {
            throw new IllegalArgumentException("OCR region 数组必须是 [x,y] 或 [x,y,width,height]");
        }
        int[] region = new int[length];
        for (int i = 0; i < length; i++) {
            Object raw = array.opt(i);
            if (!(raw instanceof Number)) {
                throw new IllegalArgumentException("OCR region[" + i + "] 必须是数字");
            }
            int value = ((Number) raw).intValue();
            if (value < 0) {
                throw new IllegalArgumentException("OCR region[" + i + "] 不能为负数");
            }
            region[i] = value;
        }
        if (length == 4 && (region[2] <= 0 || region[3] <= 0)) {
            throw new IllegalArgumentException("OCR region 的 width/height 必须大于 0");
        }
        return region;
    }

    private static JSONArray ocrResults(OCRResult[] results) throws JSONException {
        JSONArray array = new JSONArray();
        if (results == null) {
            return array;
        }
        for (OCRResult result : results) {
            if (result == null) {
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("text", result.getText() == null ? "" : result.getText());
            item.put("confidence", result.getConfidence());
            item.put("rotation", result.getRotation());
            item.put("rotationConfidence", result.getRotationConfidence());
            Rect bounds = result.getBounds();
            if (bounds == null) {
                item.put("bounds", JSONObject.NULL);
            } else {
                item.put("bounds", new JSONObject()
                        .put("left", bounds.left)
                        .put("top", bounds.top)
                        .put("right", bounds.right)
                        .put("bottom", bounds.bottom)
                        .put("width", bounds.width())
                        .put("height", bounds.height())
                        .put("centerX", bounds.centerX())
                        .put("centerY", bounds.centerY()));
            }
            array.put(item);
        }
        return array;
    }

    private boolean releaseOcr(int handle) {
        OcrSession session = mOcrSessions.remove(handle);
        if (session == null) {
            return false;
        }
        try {
            session.release();
        } catch (RuntimeException | Error error) {
            Log.w(TAG, "release OCR session failed: " + handle, error);
            throw error;
        }
        return true;
    }

    private static String ocrFailureMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause == null ? null : cause.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return cause == null ? "未知错误" : cause.getClass().getSimpleName();
        }
        return message;
    }

    private static final class OcrBitmap {
        final Bitmap bitmap;
        final boolean owned;

        OcrBitmap(Bitmap bitmap, boolean owned) {
            this.bitmap = bitmap;
            this.owned = owned;
        }
    }

    // ------------------------------------------------------------------
    // task-26：YOLO（kind:"yolo"）
    //
    // 复用 com.stardust.autojs.runtime.api.Yolo + OpenCvYoloDetector（与 QuickJS 同一条
    // OpenCV DNN 推理链）。detect 从 Node24Images 借 Bitmap → copyPixelsToBuffer 得到
    // RGBA DirectByteBuffer → OpenCvYoloDetector.detectRgba（原生支持 region 并回移坐标）。
    // 返回 packed v2 float32（小端，base64），与 QuickJS yolo 完全同构：
    //   [preprocessMs, inferenceMs, cols, (x1,y1,x2,y2,score,classId[,kx,ky,kc...])...]
    // ------------------------------------------------------------------

    private static final int YOLO_IMAGE_MAX_PIXELS = 16_000_000;

    private Yolo yolo() {
        if (mYolo == null) {
            mYolo = new Yolo(mContext);
        }
        return mYolo;
    }

    private Object handleYolo(JSONObject request) throws Exception {
        String action = request.optString("action", "");
        switch (action) {
            case "available":
                return availableYolo(request);
            case "create":
                return createYolo(request);
            case "detect":
                return detectYolo(request);
            case "release":
                return releaseYolo(request);
            case "liveCount":
                return mYoloSessions.size();
            default:
                throw new IllegalArgumentException("yolo 不支持的操作：" + action
                        + "（支持 available/create/detect/release/liveCount）");
        }
    }

    private static String normalizeYoloBackend(String backend) {
        String normalized = backend == null ? "" : backend.trim().toLowerCase(Locale.US);
        if (normalized.isEmpty() || "cpu".equals(normalized) || "dnn".equals(normalized)
                || "opencv".equals(normalized) || "opencv-dnn".equals(normalized)
                || "opencv5".equals(normalized)) {
            return "opencv";
        }
        return normalized;
    }

    private JSONObject availableYolo(JSONObject request) throws JSONException {
        String backend = normalizeYoloBackend(request.optString("backend", ""));
        Yolo yolo = yolo();
        String openCvError = "";
        try {
            Node24Images.ensureOpenCvReady();
        } catch (Throwable error) {
            openCvError = describeCause(error);
        }
        boolean available = openCvError.isEmpty() && yolo.isAvailable(backend);
        String reason = available ? ""
                : (openCvError.isEmpty() ? yolo.getUnavailableReason(backend)
                        : "OpenCV 初始化失败：" + openCvError);
        return new JSONObject()
                .put("backend", backend)
                .put("available", available)
                .put("reason", reason)
                .put("version", yolo.getVersion(backend))
                .put("liveCount", mYoloSessions.size());
    }

    private JSONObject createYolo(JSONObject request) throws Exception {
        String backend = normalizeYoloBackend(request.optString("backend", ""));
        if (!"opencv".equals(backend)) {
            throw new IllegalArgumentException("不支持的 YOLO 后端："
                    + request.optString("backend", "") + "（Node24 目前仅支持 opencv/dnn）");
        }
        // OpenCvYoloDetector 构造前必须先初始化 OpenCV（它会立即创建 Mat 并读 ONNX）。
        Node24Images.ensureOpenCvReady();
        Yolo yolo = yolo();
        if (!yolo.isAvailable(backend)) {
            throw new IllegalStateException("YOLO " + backend + " 后端不可用："
                    + yolo.getUnavailableReason(backend));
        }
        int inputWidth;
        int inputHeight;
        if (request.has("inputWidth") || request.has("inputHeight")) {
            inputWidth = request.optInt("inputWidth", 0);
            inputHeight = request.optInt("inputHeight", 0);
        } else {
            int inputSize = request.optInt("inputSize", 640);
            inputWidth = inputSize;
            inputHeight = inputSize;
        }
        if (inputWidth < 16 || inputHeight < 16 || inputWidth > 4096 || inputHeight > 4096) {
            throw new IllegalArgumentException("YOLO inputWidth/inputHeight 必须在 16..4096 之间："
                    + inputWidth + "x" + inputHeight);
        }
        int defaultThreads = Math.min(4, Runtime.getRuntime().availableProcessors());
        int threads = request.has("threads") ? request.optInt("threads", defaultThreads) : defaultThreads;
        threads = Math.max(1, Math.min(8, threads));
        String model = request.optString("model", request.optString("modelPath",
                request.optString("onnx", ""))).trim();
        String modelAsset = request.optString("modelAsset", "").trim();
        if (model.startsWith("asset://")) {
            modelAsset = model.substring("asset://".length());
            model = "";
        }
        if (model.isEmpty() && modelAsset.isEmpty()) {
            throw new IllegalArgumentException(
                    "yolo.create 需要 model/modelPath（ONNX 模型路径）或 modelAsset");
        }
        OpenCvYoloDetector detector;
        try {
            detector = modelAsset.isEmpty()
                    ? yolo.createOpenCv(model, inputWidth, inputHeight, threads)
                    : yolo.createOpenCvFromAssets(modelAsset, inputWidth, inputHeight, threads);
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Throwable error) {
            throw new IllegalStateException("YOLO 模型加载失败：" + describeCause(error), error);
        }
        int modelId = mNextYoloModel.getAndIncrement();
        String modelRef = modelAsset.isEmpty() ? model : "asset://" + modelAsset;
        mYoloSessions.put(modelId, new YoloSession(detector, backend, inputWidth, inputHeight,
                threads, modelRef));
        return new JSONObject()
                .put("modelId", modelId)
                .put("backend", backend)
                .put("inputWidth", inputWidth)
                .put("inputHeight", inputHeight)
                .put("threads", threads)
                .put("model", modelRef)
                .put("version", yolo.getVersion(backend));
    }

    private JSONObject detectYolo(JSONObject request) throws Exception {
        int modelId = request.optInt("modelId", -1);
        YoloSession session = mYoloSessions.get(modelId);
        if (session == null) {
            throw new IllegalStateException("YOLO detector 不存在或已关闭（modelId=" + modelId + "）");
        }
        Bitmap bitmap = mImages.borrowBitmap(request.optInt("imageId", -1));
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if ((long) width * height > YOLO_IMAGE_MAX_PIXELS) {
            throw new IllegalArgumentException("YOLO detect 图片过大：" + width + "x" + height);
        }
        int regionX = 0;
        int regionY = 0;
        int regionWidth = width;
        int regionHeight = height;
        JSONArray region = request.optJSONArray("region");
        if (region != null) {
            if (region.length() != 4) {
                throw new IllegalArgumentException("YOLO region 必须是 [x,y,width,height]");
            }
            regionX = region.optInt(0, 0);
            regionY = region.optInt(1, 0);
            regionWidth = region.optInt(2, 0);
            regionHeight = region.optInt(3, 0);
            if (regionX < 0 || regionY < 0 || regionWidth <= 0 || regionHeight <= 0
                    || regionX + regionWidth > width || regionY + regionHeight > height) {
                throw new IllegalArgumentException("region [x,y,w,h] 超出画面范围："
                        + regionX + "," + regionY + "," + regionWidth + "," + regionHeight
                        + "，图片 " + width + "x" + height);
            }
        }
        float confidence = (float) parseYoloThreshold(request, "confidence", 0.25);
        float nms = (float) parseYoloThreshold(request, "nms", 0.45);
        if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            throw new IllegalArgumentException("YOLO detect 需要 ARGB_8888 图片（当前 "
                    + bitmap.getConfig() + "）");
        }
        int rowStride = width * 4;
        // 复用每个模型会话的 DirectByteBuffer：1080×2400 每帧约 10MB，逐帧 new 会造成
        // native 内存与 GC 压力（detectRgba 要求 DirectByteBuffer）。
        ByteBuffer rgba;
        synchronized (session) {
            int required = rowStride * height;
            if (session.rgbaBuffer == null || session.rgbaBuffer.capacity() < required) {
                session.rgbaBuffer = ByteBuffer.allocateDirect(required);
            }
            rgba = session.rgbaBuffer;
            rgba.clear();
        }
        bitmap.copyPixelsToBuffer(rgba);
        rgba.rewind();
        float[] packed;
        try {
            packed = session.detector.detectRgba(rgba, width, height, rowStride,
                    regionX, regionY, regionWidth, regionHeight, confidence, nms);
        } catch (Throwable error) {
            throw new IllegalStateException("YOLO 推理失败：" + describeCause(error), error);
        }
        byte[] raw = new byte[packed.length * 4];
        ByteBuffer out = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : packed) {
            out.putFloat(value);
        }
        return new JSONObject()
                .put("modelId", modelId)
                .put("length", packed.length)
                .put("data", Base64.encodeToString(raw, Base64.NO_WRAP));
    }

    private boolean releaseYolo(JSONObject request) {
        int modelId = request.optInt("modelId", -1);
        YoloSession session = mYoloSessions.remove(modelId);
        if (session == null) {
            return false;
        }
        try {
            session.detector.close();
        } catch (Throwable error) {
            Log.w(TAG, "close YOLO detector failed", error);
        }
        return true;
    }

    private static double parseYoloThreshold(JSONObject request, String name, double fallback) {
        if (!request.has(name) || request.isNull(name)) {
            return fallback;
        }
        Object raw = request.opt(name);
        if (!(raw instanceof Number)) {
            throw new IllegalArgumentException(name + " 必须是 0..1 的数字");
        }
        double value = ((Number) raw).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " 必须在 0..1 之间，收到：" + value);
        }
        return value;
    }

    /** 一个 YOLO 模型会话（OpenCvYoloDetector；close 时逐个关闭）。 */
    private static final class YoloSession {
        final OpenCvYoloDetector detector;
        final String backend;
        final int inputWidth;
        final int inputHeight;
        final int threads;
        final String model;
        /** detect 复用缓冲区（按图片尺寸增长，不逐帧分配）。 */
        ByteBuffer rgbaBuffer;

        YoloSession(OpenCvYoloDetector detector, String backend, int inputWidth, int inputHeight,
                int threads, String model) {
            this.detector = detector;
            this.backend = backend;
            this.inputWidth = inputWidth;
            this.inputHeight = inputHeight;
            this.threads = threads;
            this.model = model;
        }
    }

    /**
     * kind="ui"：反射委托给 B 任务的 `com.stardust.autojs.engine.node24.NodeUiHost`。
     * 约定：static Object handle(android.content.Context, String projectDir, org.json.JSONObject request)
     * 返回 JSONObject（内含 ok/error）；也兼容 handleJson(...) 返回 String 或 handle(JSONObject) 实例方法。
     * 返回给 Node 的一律是 NodeUiHost 的原始 JSON，不再包 {ok,value}。
     */
    private String handleUiJson(JSONObject request) {
        try {
            request.put("owner", mOverlayOwner);
            request.put("engineId", mOwnerEngineId);
            if (!mGeneratedClasses.isEmpty() || request.optBoolean("realJava")) request.put("javaClassOwner", mOverlayOwner);
            // binding.raw()：把本地 overlay 的真实 View 注册成 Java 对象句柄返回给 JS（$obj wire）。
            if (request != null && "floatingWindow".equals(request.optString("op", ""))
                    && "rawView".equals(request.optString("action", ""))) {
                View rawView = Node24LocalOverlay.findRawView(
                        request.optString("overlayId", ""), request.optInt("viewId", 0));
                if (rawView == null) {
                    return new JSONObject().put("ok", false).put("error", "找不到浮窗 View").toString();
                }
                return new JSONObject().put("ok", true).put("value", toWire(rawView)).toString();
            }
            // task-201：:node24 进程内 overlay；大 XML 不再跨进程 show，失败自动回退主进程。
            JSONObject localOverlay = Node24LocalOverlay.handle(mAndroidContext, mProjectDir, request);
            if (localOverlay != null) {
                return localOverlay.toString();
            }
            // task-183：floatingWindow 请求带上 owner，主进程 NodeUiBridgeService 按 owner 关闭/清理。
            if (request != null && "floatingWindow".equals(request.optString("op", ""))) {
                request.put("owner", mOverlayOwner);
            }
            Class<?> host = Class.forName("com.stardust.autojs.engine.node24.NodeUiHost",
                    false, mContext.getClassLoader());
            Object result;
            try {
                Method handleJson = host.getMethod("handleJson", Context.class, String.class, JSONObject.class);
                Object target = Modifier.isStatic(handleJson.getModifiers()) ? null : host.getConstructor().newInstance();
                result = handleJson.invoke(target, mAndroidContext, mProjectDir, request);
            } catch (NoSuchMethodException noJson) {
                Method handle;
                Object[] args;
                try {
                    handle = host.getMethod("handle", Context.class, String.class, JSONObject.class);
                    args = new Object[]{mAndroidContext, mProjectDir, request};
                } catch (NoSuchMethodException noContext) {
                    handle = host.getMethod("handle", JSONObject.class);
                    args = new Object[]{request};
                }
                Object target = Modifier.isStatic(handle.getModifiers()) ? null : host.getConstructor().newInstance();
                result = handle.invoke(target, args);
            }
            if (result == null) {
                return new JSONObject().put("ok", true).toString();
            }
            if (result instanceof JSONObject) {
                return result.toString();
            }
            String text = String.valueOf(result);
            new JSONObject(text); // 校验是合法 JSON（非法则走 catch 错误路径）
            return text;
        } catch (ClassNotFoundException notReady) {
            return error("Node UI 宿主尚未接入（com.stardust.autojs.engine.node24.NodeUiHost 未编译进来）："
                    + "这是 B(task-59) 的实现点；当前 Node 侧只能使用 java/app 互操作与轮询事件。").toString();
        } catch (Throwable error) {
            Log.w(TAG, "NodeUiHost.handle failed", error);
            return error("Node UI 宿主调用失败：" + describeCause(error)).toString();
        }
    }

    // ------------------------------------------------------------------
    // Java 反射
    // ------------------------------------------------------------------

    private void rememberNoArgCall(String json, JSONObject request) {
        if (json.length() > Node24NoArgCallCache.MAX_REQUEST_CHARS) return;
        String kind = request.optString("kind", "");
        if (!kind.isEmpty() && !"java".equals(kind) && !"rhino".equals(kind)) return;
        String op = request.optString("op", "");
        boolean isStatic = "static".equals(op);
        if (!isStatic && !"call".equals(op)) return;
        JSONArray args = request.optJSONArray("args");
        if (args != null && args.length() != 0) return;
        try {
            Object target = isStatic ? null : objectOf(request);
            if (!isStatic && target == null) return;
            Class<?> type = isStatic ? findClass(request.optString("cls", "")) : target.getClass();
            Method method = findMethod(type, request.optString("method", ""), NO_ARGUMENTS, isStatic);
            if (method != null) mNoArgCalls.put(json, method, target);
        } catch (Exception ignored) {
            // Optional metadata warmup must never change an already successful response.
        }
    }

    private JSONObject defineJavaClass(JSONObject request) throws Exception {
        // Each generated loader extends the previous one. Serialize this chain with
        // loadDex and close, including when Promise.all defines several classes.
        synchronized (mJavaClassLock) {
            if (mClosed.get()) throw new IllegalStateException("脚本已结束");
            return defineJavaClassLocked(request);
        }
    }

    private JSONObject defineJavaClassLocked(JSONObject request) throws Exception {
        final String name = request.getString("name");
        if (!name.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*")) {
            throw new IllegalArgumentException("无效的 Java 类名：" + name);
        }
        if (mGeneratedClasses.containsKey(name)) throw new IllegalArgumentException("Java 类已经定义：" + name);
        final int classId = request.getInt("classId");
        final long session = request.getLong("session");
        Class<?> parent = findClass(request.getString("superclass"));
        JSONArray interfaceNames = request.optJSONArray("interfaces");
        Class<?>[] interfaces = new Class<?>[interfaceNames == null ? 0 : interfaceNames.length()];
        for (int i = 0; i < interfaces.length; i++) interfaces[i] = findClass(interfaceNames.getString(i));
        Set<String> methods = new HashSet<>();
        JSONArray names = request.getJSONArray("methods");
        for (int i = 0; i < names.length(); i++) methods.add(names.getString(i));
        ClassLoader previous = mRuntimeClassLoader == null ? mContext.getClassLoader() : mRuntimeClassLoader;
        File cache = new File(mContext.getCodeCacheDir(), mOverlayOwner + "/class-" + classId);
        com.stardust.autojs.rhino.AndroidClassLoader loader =
                new com.stardust.autojs.rhino.AndroidClassLoader(previous, cache);
        Class<?> generated = loader.defineDex(name,
                Node24DirectDexGenerator.generateDex(name, parent, interfaces, methods));
        Node24DexLifetime.track(generated.getClassLoader());
        if (mClosed.get()) throw new IllegalStateException("脚本已结束");
        generated.getField("__dispatcher").set(null, new Node24ClassGenerator.Dispatcher() {
            @Override public Object invoke(Object receiver, String method, Object[] args) {
                return callback(receiver, method, args);
            }
            @Override public void construct(Object receiver, Object[] args) {
                if (!Boolean.TRUE.equals(mConstructingFromJs.get())) callback(receiver, "<init>", args);
            }
            private Object callback(Object receiver, String method, Object[] args) {
                if (mClosed.get() || mCallbacksClosing) return closedJavaCallback(receiver, method, args);
                try {
                    JSONArray values = new JSONArray();
                    for (Object value : args) values.put(toWire(value));
                    JSONObject call = new JSONObject().put("classId", classId).put("className", name)
                            .put("method", method).put("receiver", toWire(receiver)).put("args", values);
                    JSONObject result = new JSONObject(Node24Engine.invokeCallback(session, call.toString()));
                    if (!result.optBoolean("ok")) throw new IllegalStateException(result.optString("error"));
                    return fromWire(result.opt("value"));
                } catch (Node24Engine.CallbackClosedException closed) {
                    return closedJavaCallback(receiver, method, args);
                } catch (RuntimeException failure) {
                    if (shouldContainCallbackError(mClosed.get() || mCallbacksClosing, failure)) {
                        logContainedCallbackError("generated." + method, failure);
                        return closedJavaCallback(receiver, method, args);
                    }
                    throw failure;
                }
                catch (Exception failure) { throw new IllegalStateException("Java→Node 回调失败：" + method, failure); }
            }
        });
        mGeneratedClasses.put(name, generated);
        mRuntimeClassLoader = loader;
        Node24GeneratedClasses.register(mOverlayOwner, loader);
        mNoArgCalls.clear();
        Set<String> superNames = new HashSet<>();
        for (Method method : generated.getDeclaredMethods()) {
            if (method.getName().startsWith("super$")) superNames.add(method.getName().substring(6));
        }
        return new JSONObject().put("name", name).put("superMethods", new JSONArray(superNames));
    }

    private Object closedJavaCallback(Object receiver, String name, Object[] args) {
        if ("<init>".equals(name)) return null;
        try {
            Method parent = findMethod(receiver.getClass(), "super$" + name, args, false);
            if (parent != null) return invokeMethod(parent, receiver, args);
            Method method = findMethod(receiver.getClass(), name, args, false);
            if (method != null && method.getReturnType().isPrimitive()) {
                Class<?> type = method.getReturnType();
                if (type == boolean.class) return false;
                if (type == char.class) return (char) 0;
                if (type != void.class) return coerce(type, 0);
            }
        } catch (Exception failure) { Log.w(TAG, "closed callback super failed: " + name, failure); }
        return null;
    }

    private final class NodeJsCallback implements Node24JavaArguments.Callback {
        private final int id;
        private final long session;
        private final Map<Class<?>, Object> proxies = new ConcurrentHashMap<>();
        NodeJsCallback(int id, long session) { this.id = id; this.session = session; }
        @Override public Object asInterface(Class<?> type) {
            if (!type.isInterface()) throw new IllegalArgumentException("Java 回调需要接口：" + type.getName());
            return proxies.computeIfAbsent(type, iface -> java.lang.reflect.Proxy.newProxyInstance(
                    iface.getClassLoader(), new Class<?>[]{iface}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return proxy == args[0];
                            if ("toString".equals(method.getName())) return "[NodeJavaCallback " + iface.getName() + "#" + id + "]";
                        }
                        if (mClosed.get() || mCallbacksClosing) {
                            if (iface == com.stardust.autojs.engine.node24.NodeUiWidgetInflater.Callbacks.class)
                                throw new IllegalStateException("Widget script callback is closed");
                            return defaultJavaValue(method.getReturnType());
                        }
                        try {
                            JSONArray values = new JSONArray();
                            if (args != null) for (Object value : args) values.put(toWire(value));
                            JSONObject request = new JSONObject().put("callbackId", id)
                                    .put("method", method.getName()).put("args", values);
                            JSONObject result = new JSONObject(Node24Engine.invokeCallback(session, request.toString()));
                            if (!result.optBoolean("ok")) throw new IllegalStateException(result.optString("error"));
                            return coerce(method.getReturnType(), fromWire(result.opt("value")));
                        } catch (Node24Engine.CallbackClosedException closed) {
                            if (iface == com.stardust.autojs.engine.node24.NodeUiWidgetInflater.Callbacks.class) throw closed;
                            return defaultJavaValue(method.getReturnType());
                        } catch (RuntimeException failure) {
                            if (iface != com.stardust.autojs.engine.node24.NodeUiWidgetInflater.Callbacks.class
                                    && shouldContainCallbackError(mClosed.get() || mCallbacksClosing, failure)) {
                                logContainedCallbackError("NodeJsCallback." + method.getName(), failure);
                                return defaultJavaValue(method.getReturnType());
                            }
                            throw failure;
                        }
                    }));
        }
    }

    /**
     * A JS error in a callback invoked from the Android main thread must not escape
     * into Looper.loop(): keep the app alive and leave the stack in logcat. Callback
     * cancellation is also contained; synchronous callbacks from Node threads still
     * propagate real errors so the script can catch them.
     */
    private static boolean shouldContainCallbackError(boolean closing, Throwable failure) {
        if (closing || Looper.myLooper() == Looper.getMainLooper()) return true;
        String message = failure == null ? "" : String.valueOf(failure.getMessage());
        return message.contains("cancelled") || message.contains("canceled");
    }

    private static void logContainedCallbackError(String context, Throwable failure) {
        Log.e(TAG, "Node JS callback failed; contained in " + context + ": "
                + (failure == null ? "unknown" : failure.getMessage()), failure);
    }

    private static Object defaultJavaValue(Class<?> type) {
        return Node24JavaArguments.defaultValue(type);
    }

    private Object newInstance(JSONObject request) throws Exception {
        Class<?> type = findClass(request.optString("cls", ""));
        // View construction must happen on the UI thread even when no Node UI
        // session is showing (offscreen views, dialogs, later attach).
        if (Looper.myLooper() != Looper.getMainLooper() && isUiType(type)) {
            return onUi(() -> newInstance(request));
        }
        Object[] args = arguments(request.optJSONArray("args"));
        // task-197：Java 数组（如 int[] -> "[I"）没有构造器，按长度分配。
        if (type.isArray()) {
            if (args == null || args.length != 1 || !(args[0] instanceof Number)) {
                throw new IllegalArgumentException("Java 数组构造需要且只需要一个长度参数：" + type.getName());
            }
            int length = ((Number) args[0]).intValue();
            if (length < 0) {
                throw new IllegalArgumentException("Java 数组长度不能为负数：" + length);
            }
            return java.lang.reflect.Array.newInstance(type.getComponentType(), length);
        }
        Constructor<?> constructor = findConstructor(type, args);
        if (constructor == null) {
            throw new NoSuchMethodException("找不到构造器 " + type.getName() + "(" + describe(args)
                    + ")；支持的类型/数量不匹配时，请在 Node 侧显式转换参数（如 java.lang.Integer.valueOf）");
        }
        try {
            constructor.setAccessible(true);
            Boolean previous = mConstructingFromJs.get();
            if (request.optBoolean("fromJs")) mConstructingFromJs.set(true);
            try { return constructor.newInstance(coerce(constructor.getParameterTypes(), args)); }
            finally {
                if (previous == null) mConstructingFromJs.remove(); else mConstructingFromJs.set(previous);
            }
        } catch (InvocationTargetException error) {
            throw new RuntimeException("Java 构造器抛出异常：" + describeCause(error), error.getCause());
        }
    }

    private Object staticCall(JSONObject request) throws Exception {
        Class<?> type = findClass(request.optString("cls", ""));
        String name = request.optString("method", "");
        Object[] args = arguments(request.optJSONArray("args"));
        // P2-2: Class.forName(String) resolves with the caller frame's loader and
        // misses Material/plugin classes. Mirror $java.findClass's host loaders.
        if (type == Class.class && "forName".equals(name) && args != null
                && args.length == 1 && args[0] instanceof String) {
            return Node24JavaArguments.initializeClass(findClass((String) args[0]));
        }
        Method method = findMethod(type, name, args, true);
        if (method == null) {
            throw new NoSuchMethodException("找不到静态方法 " + type.getName() + "." + name
                    + "(" + describe(args) + ")；可用 java.lang.Integer/Long/Double 做显式数值转换");
        }
        return invokeMethod(method, null, args);
    }

    private Object instanceCall(JSONObject request) throws Exception {
        Object target = objectOf(request);
        if (target == null) {
            throw new NullPointerException("对 null 调用方法 " + request.optString("method", ""));
        }
        String name = request.optString("method", "");
        Object[] args = arguments(request.optJSONArray("args"));
        Method method = findMethod(target.getClass(), name, args, false);
        if (method == null) {
            throw new NoSuchMethodException("找不到方法 " + target.getClass().getName() + "." + name
                    + "(" + describe(args) + ")");
        }
        return invokeMethod(method, target, args);
    }

    /** Typed JNI boundary for primitive/reference arguments. No JSON is built or parsed.
     * Member IDs identify an overload set; choose again for every call's actual values.
     * Native callback dispatch still services this call on the original Java thread.
     */
    @androidx.annotation.Keep
    public Object[] invokeJava(int memberId, int receiverId, Object[] args, int[] references) {
        try {
            if (mClosed.get()) throw new IllegalStateException("本次 Node 任务的宿主桥已结束");
            if (mUiThreadScope.isActive() && Looper.myLooper() != Looper.getMainLooper()) {
                return onUi(() -> invokeJava(memberId, receiverId, args, references));
            }
            Node24JavaMemberTable.Member member = mJavaMembers.get(memberId);
            if (member == null) throw new IllegalArgumentException("无效的 Java 方法 ID：" + memberId);
            if (args == null || references == null || args.length != references.length)
                throw new IllegalArgumentException("Java 参数与引用数量不一致");
            for (int i = 0; i < references.length; i++) {
                if (references[i] != 0) {
                    args[i] = mObjects.get(references[i]);
                    if (args[i] == null) throw new IllegalArgumentException("Java 对象引用已失效：" + references[i]);
                }
            }
            Object target = member.isStatic ? null : mObjects.get(receiverId);
            if (!member.isStatic && (target == null || !member.type.isInstance(target)))
                throw new IllegalArgumentException("Java 方法接收对象不匹配");
            Object value;
            if (member.type == Class.class && member.isStatic && "forName".equals(member.name)
                    && args.length == 1 && args[0] instanceof String) {
                value = Node24JavaArguments.initializeClass(findClass((String) args[0]));
            } else {
                Method method = args.length == 0 ? member.zeroArgumentMethod : null;
                if (method == null) method = findMethod(member.type, member.name, args, member.isStatic);
                if (method == null) throw new NoSuchMethodException("找不到方法 " + member.type.getName()
                        + "." + member.name + "(" + describe(args) + ")");
                if (args.length == 0) member.zeroArgumentMethod = method;
                value = invokeMethod(method, target, args);
            }
            if (value == null) return new Object[] {0, null};
            if (value instanceof Boolean) return new Object[] {1, value};
            if (value instanceof Number) {
                if (!Double.isFinite(((Number) value).doubleValue()))
                    throw new IllegalArgumentException("Java 返回了非有限数值");
                return new Object[] {2, value};
            }
            if (value instanceof String || value instanceof Character) return new Object[] {3, value.toString()};
            if (value instanceof Class) return new Object[] {5, ((Class<?>) value).getName()};
            int handle = objectId(value);
            if (value.getClass().isArray()) return new Object[] {6, handle, value.getClass().getName(), Array.getLength(value), mJavaMembers.typeId(value.getClass())};
            return new Object[] {value instanceof View ? 7 : 4, handle, value.getClass().getName(), 0, mJavaMembers.typeId(value.getClass())};
        } catch (Throwable failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return new Object[] {-1, message};
        }
    }

    private static boolean isUiType(Class<?> type) {
        return android.view.View.class.isAssignableFrom(type)
                || android.app.Activity.class.isAssignableFrom(type)
                || android.app.Dialog.class.isAssignableFrom(type)
                || android.view.LayoutInflater.class.isAssignableFrom(type)
                || android.view.Window.class.isAssignableFrom(type)
                || android.view.Menu.class.isAssignableFrom(type)
                || android.view.MenuItem.class.isAssignableFrom(type)
                || android.view.MenuInflater.class.isAssignableFrom(type)
                || type.getName().startsWith("androidx.appcompat.app.ActionBar")
                || type.getName().startsWith("androidx.core.view.WindowInsetsController")
                || type.getName().startsWith("com.google.android.material.dialog.")
                || androidx.recyclerview.widget.RecyclerView.Adapter.class.isAssignableFrom(type)
                || androidx.recyclerview.widget.RecyclerView.LayoutManager.class.isAssignableFrom(type);
    }

    private static boolean needsUiThread(Object target, Method method, Object[] args) {
        if (isUiType(target == null ? method.getDeclaringClass() : target.getClass())) return true;
        // Static helpers such as WindowCompat and Snackbar operate on their View/Window arguments.
        if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
            for (Object arg : args) if (arg != null && isUiType(arg.getClass())) return true;
        }
        return false;
    }

    private <T> T onUi(java.util.concurrent.Callable<T> work) throws Exception {
        // Native Node24Callbacks.call() pumps nested callbacks while waiting. Keep
        // listener return values synchronous; do not defer them or substitute defaults.
        if (Looper.myLooper() == Looper.getMainLooper()) return work.call();
        java.util.concurrent.FutureTask<T> task = new java.util.concurrent.FutureTask<>(() -> {
            if (mClosed.get()) throw new IllegalStateException("脚本已结束");
            return work.call();
        });
        if (!mUiHandler.post(task)) throw new IllegalStateException("Android UI 队列已关闭");
        try { return task.get(10, java.util.concurrent.TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            throw new RuntimeException(failure.getCause());
        } finally { if (!task.isDone()) task.cancel(false); }
    }

    private Object ownNewInstance(JSONObject request) throws Exception {
        return mOwnedJavaResources.constructed(newInstance(request));
    }

    private void observeOwnedResourceCall(Object target, String name, Object[] args) {
        mOwnedJavaResources.called(target, name, args);
        if (target instanceof android.app.NotificationManager && args.length >= 2
                && args[0] instanceof String && args[1] instanceof Number) {
            // Explicitly scoped tags only: never capture ordinary app/other-script notification IDs.
            String tag = (String) args[0];
            if (!tag.matches("aijs\\.node24\\.[0-9a-fA-F-]{36}")) return;
            int id = ((Number) args[1]).intValue();
            String key = tag + ":" + id;
            if ("notify".equals(name) && args.length == 3) {
                Object identity = mOwnedNotificationKeys.computeIfAbsent(key, ignored -> new Object());
                android.app.NotificationManager manager = (android.app.NotificationManager) target;
                mOwnedJavaResources.track(identity, () -> manager.cancel(tag, id));
            } else if ("cancel".equals(name)) {
                Object identity = mOwnedNotificationKeys.remove(key);
                if (identity != null) mOwnedJavaResources.forget(identity);
            }
        }
    }

    private Object invokeMethod(Method method, Object target, Object[] args) throws Exception {
        // Parameterless cached JSON calls skip handleJava; keep their UI scope.
        if (Looper.myLooper() != Looper.getMainLooper()
                && (mUiThreadScope.isActive() || needsUiThread(target, method, args))) {
            return onUi(() -> invokeMethod(method, target, args));
        }
        try {
            method.setAccessible(true);
            Object result = method.invoke(target, coerce(method.getParameterTypes(), args));
            observeOwnedResourceCall(target, method.getName(), args);
            return result;
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            throw new RuntimeException("Java 方法 " + method.getName() + " 抛出异常：" + describeCause(error), cause);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("参数类型不匹配：" + method + " 参数=" + describe(args), error);
        }
    }

    private JSONObject member(JSONObject request) throws Exception {
        Class<?> type;
        boolean isStatic = request.has("cls") && !request.has("obj");
        if (isStatic) {
            type = findClass(request.optString("cls", ""));
        } else {
            Object object = objectOf(request);
            if (object == null) {
                return ok(new JSONObject().put("kind", "unknown"));
            }
            type = object.getClass();
        }
        String name = request.optString("name", "");
        if (name.isEmpty()) {
            return ok(new JSONObject().put("kind", "unknown"));
        }
        if (!isStatic) {
            Method method = findMethod(type, name, null, false);
            if (method != null) {
                return ok(new JSONObject().put("kind", "method").put("$method", mJavaMembers.bind(type, name, false)));
            }
        } else {
            Method method = findMethod(type, name, null, true);
            if (method != null) {
                return ok(new JSONObject().put("kind", "method").put("$method", mJavaMembers.bind(type, name, true)));
            }
        }
        Field field = findField(type, name, isStatic);
        if (field != null) {
            field.setAccessible(true);
            Object value = isStatic ? field.get(null) : field.get(objectOf(request));
            return ok(new JSONObject().put("kind", "field").put("value", toWire(value)));
        }
        if (isStatic) {
            // android.R.color / android.R.attr are Java nested classes, not fields.
            // Report them as a field whose value is the class, so the existing
            // prelude turns Owner.name into a classRef and `.system_accent1_500`
            // reads the nested class's static field.
            Class<?> nested = Node24JavaArguments.nestedClass(type, name);
            if (nested != null) {
                return ok(new JSONObject().put("kind", "field").put("value", toWire(nested)));
            }
        }
        return ok(new JSONObject().put("kind", "unknown"));
    }

    private Object readField(JSONObject request) throws Exception {
        boolean isStatic = request.has("cls") && !request.has("obj");
        String name = request.optString("name", "");
        Class<?> type = isStatic ? findClass(request.optString("cls", "")) : objectOf(request).getClass();
        Field field = findField(type, name, isStatic);
        if (field == null) {
            if (isStatic) {
                Class<?> nested = Node24JavaArguments.nestedClass(type, name);
                if (nested != null) return nested;
            }
            throw new NoSuchFieldException("找不到字段 " + type.getName() + "." + name);
        }
        field.setAccessible(true);
        return isStatic ? field.get(null) : field.get(objectOf(request));
    }

    private void writeField(JSONObject request) throws Exception {
        boolean isStatic = request.has("cls") && !request.has("obj");
        String name = request.optString("name", "");
        Class<?> type = isStatic ? findClass(request.optString("cls", "")) : objectOf(request).getClass();
        Field field = findField(type, name, isStatic);
        if (field == null) {
            if (isStatic && Node24JavaArguments.nestedClass(type, name) != null) {
                throw new IllegalArgumentException("不能写入嵌套类：" + type.getName() + "." + name);
            }
            throw new NoSuchFieldException("找不到字段 " + type.getName() + "." + name);
        }
        field.setAccessible(true);
        Object value = fromWire(request.opt("value"));
        field.set(isStatic ? null : objectOf(request), value);
    }

    private Class<?> findClass(String name) throws ClassNotFoundException {
        Class<?> generated = mGeneratedClasses.get(name);
        if (generated != null) return generated;
        // task-198：host.runtime.loadDex 加载的插件类优先从动态 classloader 解析。
        ClassLoader runtime = mRuntimeClassLoader;
        if (runtime != null) {
            try {
                return Class.forName(name, false, runtime);
            } catch (ClassNotFoundException ignored) {
                // 非插件类继续走应用 classloader / 反射缓存。
            }
        }
        // 成功解析的类按 job 缓存；缺失/失败不缓存，插件类晚加载时仍会重试。
        try {
            return mReflectionCache.findClass(name, mContext.getClassLoader());
        } catch (ClassNotFoundException missing) {
            Class<?> pluginClass = mPluginLoader.findClass(name);
            if (pluginClass != null) return pluginClass;
            throw missing;
        }
    }

    /** task-203：列动态 dex 类的 public 方法/字段签名（第三方探针/兼容检查用）。 */
    private org.json.JSONArray reflectMembers(String className, boolean wantMethods) throws Exception {
        if (className == null || className.isEmpty()) {
            throw new IllegalArgumentException("runtime." + (wantMethods ? "methods" : "fields") + " 需要类名");
        }
        Class<?> clazz = findClass(className);
        org.json.JSONArray result = new org.json.JSONArray();
        if (wantMethods) {
            java.lang.reflect.Method[] members = clazz.getMethods();
            for (java.lang.reflect.Method method : members) {
                StringBuilder signature = new StringBuilder(method.getName()).append('(');
                Class<?>[] parameters = method.getParameterTypes();
                for (int i = 0; i < parameters.length; i++) {
                    if (i > 0) signature.append(',');
                    signature.append(parameters[i].getSimpleName());
                }
                signature.append(')');
                result.put(signature.toString());
            }
        } else {
            for (java.lang.reflect.Field field : clazz.getFields()) {
                result.put(field.getName() + ":" + field.getType().getSimpleName());
            }
        }
        return result;
    }

    /** task-198：动态加载 dex（插件 NativeEngine/WindowDrag/NativeYolo 等）。 */
    private boolean loadRuntimeDex(String path) throws Exception {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("runtime.loadDex 需要 dex 路径");
        }
        java.io.File dex = new java.io.File(path);
        if (!dex.isFile()) {
            throw new IllegalStateException("runtime.loadDex 文件不存在：" + path);
        }
        ClassLoader parent = mContext.getClassLoader();
        java.io.File outputDir = new java.io.File(mContext.getCodeCacheDir(), "node24-dex");
        if (!outputDir.isDirectory() && !outputDir.mkdirs()) {
            throw new IllegalStateException("无法创建 dex 优化目录：" + outputDir);
        }
        synchronized (mJavaClassLock) {
            if (mClosed.get()) throw new IllegalStateException("脚本已结束");
            mRuntimeClassLoader = new dalvik.system.DexClassLoader(
                    dex.getAbsolutePath(), outputDir.getAbsolutePath(), null, parent);
            mNoArgCalls.clear();
        }
        return true;
    }

    private Constructor<?> findConstructor(Class<?> type, Object[] args) {
        Constructor<?> best = null;
        for (Constructor<?> constructor : type.getConstructors()) {
            if (compatible(constructor.getParameterTypes(), args)) {
                if (best == null || better(constructor.getParameterTypes(), best.getParameterTypes(), args)) {
                    best = constructor;
                }
            }
        }
        if (best == null) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (compatible(constructor.getParameterTypes(), args)) {
                    best = constructor;
                    break;
                }
            }
        }
        return best;
    }

    private Method findMethod(Class<?> type, String name, Object[] args, boolean staticOnly) {
        // 缓存只保存“类+方法名+static 模式”的候选数组（保持原遍历顺序与重复项），
        // 每次调用仍按本次 args 做 compatible/better，绝不缓存最终重载。
        Method[] candidates = mReflectionCache.candidateMethods(type, name, staticOnly);
        Method best = null;
        for (Method method : candidates) {
            if (args == null) {
                return method;
            }
            if (compatible(method.getParameterTypes(), args)) {
                if (best == null || better(method.getParameterTypes(), best.getParameterTypes(), args)) {
                    best = method;
                }
            }
        }
        return best;
    }

    private Field findField(Class<?> type, String name, boolean staticOnly) {
        Class<?> current = type;
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (!field.getName().equals(name)) {
                    continue;
                }
                if (staticOnly == Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    return field;
                }
            }
            current = current.getSuperclass();
        }
        try {
            Field field = type.getField(name);
            if (staticOnly == Modifier.isStatic(field.getModifiers())) {
                return field;
            }
        } catch (NoSuchFieldException ignored) {
        }
        return null;
    }

    private static boolean compatible(Class<?>[] types, Object[] args) {
        if (types.length != args.length) {
            return false;
        }
        for (int i = 0; i < types.length; i++) {
            if (!compatible(types[i], args[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean compatible(Class<?> type, Object value) {
        return Node24JavaArguments.compatible(type, value);
    }

    private static boolean better(Class<?>[] candidate, Class<?>[] current, Object[] args) {
        int candidateScore = score(candidate, args);
        int currentScore = score(current, args);
        return candidateScore < currentScore;
    }

    private static int score(Class<?>[] types, Object[] args) {
        int score = 0;
        for (int i = 0; i < types.length; i++) {
            if (args[i] != null && args[i].getClass() == box(types[i])) {
                score += 0;
            } else {
                score += 1;
            }
        }
        return score;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    // ------------------------------------------------------------------
    // wire <-> Java
    // ------------------------------------------------------------------

    private Object[] arguments(JSONArray array) throws ClassNotFoundException {
        if (array == null) {
            return new Object[0];
        }
        Object[] values = new Object[array.length()];
        for (int i = 0; i < array.length(); i++) {
            values[i] = fromWire(array.opt(i));
        }
        return values;
    }

    private Object fromWire(Object wire) throws ClassNotFoundException {
        if (wire == null || wire == JSONObject.NULL) {
            return null;
        }
        if (wire instanceof JSONObject) {
            JSONObject object = (JSONObject) wire;
            // New explicit interface-object marker. The prelude registers the whole
            // JS object as one callback (so `this` inside methods is the object);
            // a method map is also accepted for hand-written/test requests.
            if (object.has("$jsInterface")) {
                Object decoded = fromWire(object.opt("$jsInterface"));
                if (decoded instanceof NodeJsCallback) return decoded;
                if (decoded instanceof Map) {
                    final Map<?, ?> methods = (Map<?, ?>) decoded;
                    return (Node24JavaArguments.Callback) type ->
                            Node24JavaArguments.interfaceFromCallbacks(type, methods);
                }
                throw new IllegalArgumentException("$jsInterface 需要 $callback 或方法映射：" + object);
            }
            if (object.has("$callback")) {
                int id = object.optInt("$callback");
                return mJavaCallbacks.computeIfAbsent(id, ignored -> new NodeJsCallback(id, object.optLong("session")));
            }
            if (object.has("$obj")) {
                return mObjects.get(object.optInt("$obj"));
            }
            if (object.has("$class")) {
                return findClass(object.optString("$class"));
            }
            if (object.has("$arr")) {
                JSONArray array = object.optJSONArray("$arr");
                Object[] values = arguments(array);
                return values;
            }
            if (object.has("$map")) {
                JSONObject source = object.optJSONObject("$map");
                Map<String, Object> values = new java.util.LinkedHashMap<>();
                if (source != null) {
                    java.util.Iterator<String> keys = source.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        values.put(key, fromWire(source.opt(key)));
                    }
                }
                return values;
            }
            if (object.has("$null")) {
                return null;
            }
            throw new IllegalArgumentException("Node24 host 收到不支持的参数对象：" + object);
        }
        return wire;
    }

    private Object toWire(Object value) throws JSONException {
        if (value == null) {
            return JSONObject.NULL;
        }
        if (value instanceof Boolean || value instanceof Number || value instanceof String) {
            return value;
        }
        if (value instanceof Character) {
            return value.toString();
        }
        if (value.getClass().isArray()) {
            return new JSONObject().put("$obj", objectId(value))
                    .put("$class", value.getClass().getName()).put("$length", Array.getLength(value));
        }
        if (value instanceof Class) {
            return new JSONObject().put("$class", ((Class<?>) value).getName());
        }
        int id = objectId(value);
        JSONObject descriptor = new JSONObject().put("$obj", id).put("$class", value.getClass().getName());
        descriptor.put("$type", mJavaMembers.typeId(value.getClass()));
        if (value instanceof android.view.View) descriptor.put("$view", true);
        return descriptor;
    }

    private JSONObject ok(Object value) throws Exception {
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("value", value == null ? JSONObject.NULL : value);
        return result;
    }

    private JSONObject error(String message) {
        try {
            return new JSONObject().put("ok", false).put("error", message == null ? "未知错误" : message);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private synchronized int objectId(Object value) {
        Integer existing = mObjectIds.get(value);
        if (existing != null) {
            return existing;
        }
        int id = mNextObjectId++;
        mObjects.put(id, value);
        mObjectIds.put(value, id);
        return id;
    }

    private Object objectOf(JSONObject request) {
        int id = request.optInt("$obj", request.optInt("obj", -1));
        if (id <= 0) {
            Object direct = request.opt("obj");
            return direct instanceof JSONObject ? null : direct;
        }
        return mObjects.get(id);
    }

    // ------------------------------------------------------------------
    // task-63 keep-alive / overlay 状态（反射 B 的 NodeUiHost）
    // ------------------------------------------------------------------

    /** prelude 的 keepRunning 是否置位（task-65：绝不抛）。 */
    public boolean isKeepRunning() {
        try {
            return mKeepRunning;
        } catch (Throwable error) {
            noteHelperFailure("isKeepRunning", error);
            return false;
        }
    }

    /** 最近一次 kind=ui 请求距今毫秒（从未出现返回 Long.MAX_VALUE；task-65：绝不抛）。 */
    public long lastUiRequestAgeMs() {
        try {
            long at = mLastUiRequestAt;
            return at == 0L ? Long.MAX_VALUE : android.os.SystemClock.elapsedRealtime() - at;
        } catch (Throwable error) {
            noteHelperFailure("lastUiRequestAgeMs", error);
            return Long.MAX_VALUE;
        }
    }

    /** 最近一次 kind=keepalive 请求距今毫秒（从未出现返回 Long.MAX_VALUE；task-65：绝不抛）。 */
    public long lastKeepAliveAgeMs() {
        try {
            long at = mLastKeepAliveAt;
            return at == 0L ? Long.MAX_VALUE : android.os.SystemClock.elapsedRealtime() - at;
        } catch (Throwable error) {
            noteHelperFailure("lastKeepAliveAgeMs", error);
            return Long.MAX_VALUE;
        }
    }

    /** task-76：主进程 NodeUiActivity 是否正打开（B 的 NodeUiHost.hasNodeUiActivity()，缺失返回 false）。 */
    public boolean hasNodeUiActivity() {
        try {
            Object value = invokeStaticUiHost(new String[]{"hasNodeUiActivity"});
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable error) {
            noteHelperFailure("hasNodeUiActivity", error);
            return false;
        }
    }

    /** task-64 兜底：最近 windowMs 内是否出现过 kind=ui 请求（即使 keepRunning/hasOverlay 时序没赶上）。 */
    public boolean hasRecentUiRequest(long windowMs) {
        try {
            return lastUiRequestAgeMs() <= Math.max(0L, windowMs);
        } catch (Throwable error) {
            noteHelperFailure("hasRecentUiRequest", error);
            return false;
        }
    }

    /** 最近 windowMs 内是否收到过 keepalive（prelude keepRunning）。 */
    public boolean hasRecentKeepAlive(long windowMs) {
        try {
            return lastKeepAliveAgeMs() <= Math.max(0L, windowMs);
        } catch (Throwable error) {
            noteHelperFailure("hasRecentKeepAlive", error);
            return false;
        }
    }

    /** B(task-63) 提供的 NodeUiHost.hasOverlay() 等静态查询（类/方法缺失时返回 false）。 */
    public boolean hasUiOverlay() {
        try {
            if (Node24LocalOverlay.hasOverlay()) return true;
            Object value = invokeStaticUiHost(new String[]{"hasOverlay", "isOverlayShowing", "isVisible", "isShowing"});
            return value instanceof Boolean && (Boolean) value;
        } catch (Throwable error) {
            noteHelperFailure("hasUiOverlay", error);
            return false;
        }
    }

    /** B(task-63) 的 overlay 关闭通知（consumeCloseRequested/isClosed/isCloseRequested 任一为 true）。 */
    public boolean isUiOverlayClosed() {
        try {
            for (String name : new String[]{"consumeCloseRequested", "isCloseRequested", "isClosed"}) {
                Object value = invokeStaticUiHost(new String[]{name});
                if (value instanceof Boolean && (Boolean) value) {
                    return true;
                }
            }
            return false;
        } catch (Throwable error) {
            noteHelperFailure("isUiOverlayClosed", error);
            return false;
        }
    }

    /** 退出时清理 overlay：优先走 NodeUiHost 的 close op，其次 close/closeAll 静态方法。 */
    public boolean closeUiOverlay() {
        // task-201：先清 :node24 本地 overlay，再清主进程回退 overlay。
        try { Node24LocalOverlay.closeAll(); } catch (Throwable error) {
            cleanupFailure("local overlays", error);
        }
        // task-164：floating_window 的 overlay 由主进程 NodeUiBridgeService 管理（不在 NodeUiHost.sOverlayView），
        // close op 清不到它；这里显式下发 floatingWindow closeAll，脚本结束/取消/启动前都清干净。
        try {
            // task-186：清理阶段用 fire-and-forget 关闭浮窗；NodeUiHost.overlay 会同步等待
            // 主进程 reply（最长 15s），BACK 关 UI 后主进程正在收尾，同步等待会把清理拖到 15s。
            // task-188：带上本 host owner，避免停掉一个并行 job 时清掉其它 job 的浮窗。
            com.stardust.autojs.engine.node24.NodeUiActivityClient.get()
                    .closeOverlaysIfBound(mOverlayOwner);
        } catch (Throwable error) {
            cleanupFailure("remote overlays", error);
        }
        // task-218：脚本/第三方工程直接 WindowManager.addView 的 Node View 不在两套 overlay 管理里，
        // 这里反射 WindowManagerGlobal.mViews 清掉 NodeUiCanvasView / tag=node-port-overlay。
        try {
            mLastDirectOverlayCount = Node24DirectOverlayCleanup.closeAllInProcess();
        } catch (Throwable error) {
            cleanupFailure("direct overlays", error);
        }
        // OverlayRegistry 里可能还有本 owner 的 NODE_UI/NODE_TEXT 等登记项，按 owner 统一关闭。
        try {
            OverlayRegistry.closeByOwner(mOwnerEngineId, "node24_close_ui");
        } catch (Throwable error) {
            cleanupFailure("overlay registry", error);
        }
        try {
            JSONObject request = new JSONObject().put("kind", "ui").put("op", "close")
                    .put("projectDir", mProjectDir == null ? "" : mProjectDir);
            handleUiJson(request);
            return true;
        } catch (Throwable error) {
            cleanupFailure("scoped UI close", error);
        }
        return false;
    }

    /** task-218：Node24Service 在 post-execute 日志里打印 direct 清理数量。 */
    public int getLastDirectOverlayCount() {
        return mLastDirectOverlayCount;
    }

    private Object invokeStaticUiHost(String[] names) {
        try {
            Class<?> host = Class.forName("com.stardust.autojs.engine.node24.NodeUiHost",
                    false, mContext.getClassLoader());
            for (String name : names) {
                try {
                    Method method = host.getMethod(name);
                    if (Modifier.isStatic(method.getModifiers())) {
                        return method.invoke(null);
                    }
                    return method.invoke(host.getConstructor().newInstance());
                } catch (NoSuchMethodException missing) {
                    if (mMissingUiHelpers.add("NodeUiHost." + name)) {
                        Log.w(TAG, "NodeUiHost method missing: " + name);
                    }
                    // 试下一个名字
                }
            }
        } catch (ClassNotFoundException notReady) {
            if (mMissingUiHelpers.add("<class NodeUiHost>")) {
                Log.w(TAG, "NodeUiHost class not found in :node24 (UI helpers unavailable): " + notReady);
            }
        } catch (Throwable error) {
            noteHelperFailure("invokeStaticUiHost", error);
        }
        return null;
    }

    /** task-65：记录缺失/失败的 helper（同类只记一次），让 logcat 能看出是哪一步反射失败。 */
    private void noteHelperFailure(String helper, Throwable error) {
        try {
            if (mMissingUiHelpers.add(helper)) {
                Log.w(TAG, "NodeUiHost helper failed: " + helper + " -> "
                        + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        } catch (Throwable ignored) {
        }
    }

    /** task-195：进程内记录的正常退出请求 code；未请求返回 -1。 */
    public int exitRequestedCode() {
        return mExitRequested ? mExitRequestedCode : -1;
    }

    public String scriptError() { return mScriptError; }

    /** task-65：给 Service 日志用的安全状态串（逐项 try/catch，任何一项失败都不影响 hold 判定）。 */
    public String describeState() {

        StringBuilder builder = new StringBuilder(96);
        builder.append("keepRunning=");
        try { builder.append(isKeepRunning()); } catch (Throwable error) { builder.append("ERR"); }
        builder.append(" hasOverlay=");
        try { builder.append(hasUiOverlay()); } catch (Throwable error) { builder.append("ERR"); }
        builder.append(" nodeUiActivity=");
        try { builder.append(hasNodeUiActivity()); } catch (Throwable error) { builder.append("ERR"); }
        builder.append(" uiAgeMs=");
        try { builder.append(lastUiRequestAgeMs()); } catch (Throwable error) { builder.append("ERR"); }
        builder.append(" keepAliveAgeMs=");
        try { builder.append(lastKeepAliveAgeMs()); } catch (Throwable error) { builder.append("ERR"); }
        builder.append(" helpers=").append(mMissingUiHelpers);
        return builder.toString();
    }

    /**
     * 只取消仍在等待系统投屏授权的页面（脚本取消时由 Service 调用）；已建立的投屏由 close 释放。
     */
    public void cancelPendingProjection() {
        try {
            mProjection.cancelPending();
        } catch (Throwable error) {
            Log.w(TAG, "cancel pending projection failed", error);
        }
    }

    /** Detach native UI consumers before publishing cancellation to their callbacks. */
    public void beginCallbackShutdown() {
        if (mCallbacksClosing) return;
        try {
            mOwnedJavaResources.detachViews();
            if (!mOwnedJavaResources.viewsDetached()) {
                throw new IllegalStateException("Owned window detachment failed", mOwnedJavaResources.firstFailure());
            }
            com.stardust.autojs.engine.node24.NodeUiLocalSession.closeBeforeCallbackShutdown(mOverlayOwner);
        } catch (RuntimeException failure) {
            cleanupFailure("close Node UI before callback shutdown", failure);
            return;
        }
        mCallbacksClosing = true;
        if (mWidgetRegistration != null) mWidgetRegistration.close();
    }

    public boolean requiresProcessRecycle() { return mCleanupIncomplete || mOwnedJavaResources.failureCount() != 0; }

    private void cleanupFailure(String stage, Throwable error) {
        mCleanupIncomplete = true;
        Log.w(TAG, "Cleanup incomplete: " + stage, error);
    }

    private void cleanupStep(String stage, Runnable action) {
        try { action.run(); } catch (Throwable error) { cleanupFailure(stage, error); }
    }

    /**
     * 任务结束/取消/异常统一清理：停投屏、释放 OCR 会话、回收 Bitmap 句柄、解绑主进程自动化客户端。
     * 幂等；Service 在 finally 中无条件调用。不触碰进程级 {@code RemoteAutomationBridge}。
     */
    public void close() {
        beginCallbackShutdown();
        if (!mCallbacksClosing) return; // Keep callbacks intact until the unsafe process is retired.
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }
        if (mWidgetRegistration != null) { mWidgetRegistration.close(); mWidgetRegistration = null; }
        cleanupStep("owned Java resources", mOwnedJavaResources::close);
        mOwnedNotificationKeys.clear();
        cleanupStep("no-arg cache", mNoArgCalls::close);
        cleanupStep("remote UI owner", () -> com.stardust.autojs.engine.node24.NodeUiActivityClient.get().cleanupOwnerIfBound(mOverlayOwner));
        cleanupStep("resource context", mAndroidContext::release);
        cleanupStep("Java member cache", mJavaMembers::close);
        synchronized (mFileObservers) {
            for (android.os.FileObserver observer : mFileObservers.values()) cleanupStep("file observer", observer::stopWatching);
            mFileObservers.clear();
        }
        for (Integer pid : mShellGroups) killShellGroup(pid);
        mShellGroups.clear();
        cleanupStep("Java executor", () -> mJavaExecutor.shutdownNow());
        cleanupStep("plugins", mPluginLoader::unloadAll);
        synchronized (mJavaClassLock) {
            Node24GeneratedClasses.remove(mOverlayOwner);
            for (Class<?> generated : mGeneratedClasses.values()) {
                try { generated.getField("__dispatcher").set(null, null); }
                catch (ReflectiveOperationException failure) { Log.w(TAG, "clear generated dispatcher", failure); }
            }
            mGeneratedClasses.clear();
        }
        mJavaCallbacks.clear();
        try {
            shutdownMediaThread();
        } catch (Throwable error) {
            cleanupFailure("close media thread failed", error);
        }
        try {
            if (mAccessibilityEventReceiver != null) {
                mContext.unregisterReceiver(mAccessibilityEventReceiver);
                mAccessibilityEventReceiver = null;
            }
            if (mNotificationEventReceiver != null) {
                mContext.unregisterReceiver(mNotificationEventReceiver);
                mNotificationEventReceiver = null;
            }
        } catch (Throwable error) {
            cleanupFailure("close task-156 receivers failed", error);
        }
        if (mNotificationListenerReceiver != null) {
            try { mContext.unregisterReceiver(mNotificationListenerReceiver); }
            catch (Throwable error) { cleanupFailure("close notification listener receiver failed", error); }
            mNotificationListenerReceiver = null;
        }
        try {
            mEngineBridge.close();
        } catch (Throwable error) {
            cleanupFailure("close engine bridge failed", error);
        }
        try {
            closeSensorEvents();
        } catch (Throwable error) {
            cleanupFailure("close sensors failed", error);
        }
        try {
            mProjection.close();
        } catch (Throwable error) {
            cleanupFailure("close projection failed", error);
        }
        try {
            mAutomation.close();
        } catch (Throwable error) {
            cleanupFailure("close automation failed", error);
        }
        try {
            for (OcrSession session : new ArrayList<>(mOcrSessions.values())) {
                try {
                    session.release();
                } catch (Throwable error) {
                    cleanupFailure("close OCR session failed", error);
                }
            }
        } finally {
            mOcrSessions.clear();
        }
        try {
            for (YoloSession session : new ArrayList<>(mYoloSessions.values())) {
                try {
                    session.detector.close();
                } catch (Throwable error) {
                    cleanupFailure("close YOLO detector failed", error);
                }
            }
        } finally {
            mYoloSessions.clear();
        }
        try {
            if (mYolo != null) {
                mYolo.close();
                mYolo = null;
            }
        } catch (Throwable error) {
            cleanupFailure("close YOLO runtime failed", error);
        }
        try {
            mImages.close();
        } catch (Throwable error) {
            cleanupFailure("close images failed", error);
        }
        try {
            mReflectionCache.close();
        } catch (Throwable error) {
            cleanupFailure("close reflection cache failed", error);
        }
    }

    private static void killShellGroup(int pid) {
        try { android.system.Os.kill(-pid, android.system.OsConstants.SIGKILL); }
        catch (android.system.ErrnoException absent) {
            if (absent.errno != android.system.OsConstants.ESRCH) Log.w(TAG, "close owned shell group " + pid, absent);
        }
    }

    // ------------------------------------------------------------------
    // app / 宿主能力（最小面）
    // ------------------------------------------------------------------

    private Object appAction(JSONObject request) throws Exception {
        String action = request.optString("action", "");
        JSONArray args = request.optJSONArray("args");
        Object[] values = arguments(args);
        switch (action) {
            case "openUrl": {
                String url = values.length > 0 && values[0] != null ? String.valueOf(values[0]) : "";
                if (url.isEmpty()) {
                    throw new IllegalArgumentException("app.openUrl 需要 url");
                }
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
                return true;
            }
            case "startActivity": {
                String target = values.length > 0 && values[0] != null ? String.valueOf(values[0]) : "";
                if (target.isEmpty()) {
                    throw new IllegalArgumentException("app.startActivity 需要组件名或包名");
                }
                startActivity(target);
                return true;
            }
            case "getPackageName":
                return mContext.getPackageName();
            case "versionName": {
                try {
                    return mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), 0).versionName;
                } catch (Throwable error) {
                    return "";
                }
            }
            case "versionCode": {
                try {
                    return mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), 0).versionCode;
                } catch (Throwable error) {
                    return 0;
                }
            }
            case "getClipboard": {
                final Object[] holder = new Object[1];
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                new android.os.Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        android.content.ClipboardManager manager = (android.content.ClipboardManager)
                                mContext.getSystemService(Context.CLIPBOARD_SERVICE);
                        holder[0] = manager == null || manager.getText() == null
                                ? "" : manager.getText().toString();
                    } finally {
                        latch.countDown();
                    }
                });
                latch.await(2, java.util.concurrent.TimeUnit.SECONDS);
                return holder[0] == null ? "" : holder[0];
            }
            case "setClipboard": {
                final String text = values.length > 0 && values[0] != null ? String.valueOf(values[0]) : "";
                new android.os.Handler(Looper.getMainLooper()).post(() -> {
                    android.content.ClipboardManager manager = (android.content.ClipboardManager)
                            mContext.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (manager != null) manager.setPrimaryClip(
                            android.content.ClipData.newPlainText("Auto.js", text));
                });
                return true;
            }
            case "sendBroadcast": {
                String broadcastAction = values.length > 0 && values[0] != null ? String.valueOf(values[0]) : "";
                if (broadcastAction.isEmpty()) throw new IllegalArgumentException("app.sendBroadcast 需要 action");
                Intent intent = new Intent(broadcastAction);
                intent.setPackage(mContext.getPackageName());
                mContext.sendBroadcast(intent);
                return true;
            }
            case "isInstalled": {
                String pkg = values.length > 0 && values[0] != null ? String.valueOf(values[0]) : "";
                try { mContext.getPackageManager().getPackageInfo(pkg, 0); return true; }
                catch (Throwable error) { return false; }
            }
            case "getAppName": {
                try {
                    return String.valueOf(mContext.getPackageManager().getApplicationLabel(
                            mContext.getPackageManager().getApplicationInfo(mContext.getPackageName(), 0)));
                } catch (Throwable error) {
                    return "";
                }
            }
            case "viewFile": {
                String path = values.length > 0 && values[0] != null ? String.valueOf(values[0]) : "";
                if (path.isEmpty()) {
                    throw new IllegalArgumentException("app.viewFile 需要文件路径");
                }
                File file = new File(path);
                if (!file.isFile()) {
                    throw new IOException("app.viewFile 文件不存在或不是普通文件：" + path);
                }
                if (!file.canRead()) {
                    throw new IOException("app.viewFile 文件不可读：" + path);
                }
                // 返回 IntentUtil 的真实结果：没有可处理 ACTION_VIEW 的应用时为 false，
                // FileProvider/Activity 抛出的异常继续向上抛，由 host envelope 报中文错误。
                return IntentUtil.viewFile(mContext, file.getAbsolutePath(),
                        mContext.getPackageName() + ".fileprovider");
            }
            case "toast": {
                final String text = values.length > 0 && values[0] != null ? String.valueOf(values[0]) : "";
                final Context context = mContext;
                new android.os.Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(context, text, Toast.LENGTH_SHORT).show());
                return true;
            }
            default:
                throw new IllegalArgumentException("Node24 app 模块暂不支持：" + action
                        + "（支持 openUrl/startActivity/getPackageName/toast/versionName/versionCode/"
                        + "getClipboard/setClipboard/sendBroadcast/isInstalled/getAppName/viewFile）");
        }
    }

    /**
     * engines 域：Node 进程内没有引擎注册表，所有 myEngine/exec/emit/stop 都同步转发到
     * 主进程 app-private {@code NodeEngineBridgeService}，由真实 {@link ScriptEngineService} 处理。
     * 返回服务端原始 {@code {ok,value}}；失败时抛异常，由 invoke() 统一包成中文错误。
     */
    private JSONObject handleEngineRequest(JSONObject request) throws Exception {
        JSONObject args = request.optJSONObject("args");
        JSONObject bridgeRequest = new JSONObject()
                .put("action", request.optString("action", ""))
                .put("args", args == null ? new JSONObject() : args);
        JSONObject response = mEngineBridge.call(bridgeRequest);
        if (!response.optBoolean("ok", false)) {
            throw new IllegalStateException(response.optString("error", "主进程引擎桥调用失败"));
        }
        return response;
    }

    /** task-133 P1：device/files/storages/events/engines/threads 能力域。 */
    /**
     * task-179：PowerManager.wakeUp；非系统应用通常没有 DEVICE_POWER，SecurityException 时回退
     * ACQUIRE_CAUSES_WAKEUP 的短时 wake lock（需要 WAKE_LOCK 普通权限），失败给明确中文。
     */
    private Object deviceWakeUp() throws Exception {
        android.os.PowerManager power = (android.os.PowerManager)
                mContext.getSystemService(Context.POWER_SERVICE);
        if (power == null) {
            throw new IllegalStateException("唤醒屏幕失败：PowerManager 不可用");
        }
        boolean hasWakeLockPermission = mContext.checkSelfPermission(
                android.Manifest.permission.WAKE_LOCK) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        JSONObject result = new JSONObject();
        String firstError = null;
        try {
            // wakeUp 在公开 SDK stub 里不可见（编译期找不到符号），只能运行期反射：
            // 优先双参 (long, String)，其次单参 (long)；任何失败都回退 wake lock。
            java.lang.reflect.Method wakeUpMethod = null;
            try {
                wakeUpMethod = android.os.PowerManager.class
                        .getMethod("wakeUp", long.class, String.class);
            } catch (NoSuchMethodException ignore) {
            }
            if (wakeUpMethod == null) {
                wakeUpMethod = android.os.PowerManager.class.getMethod("wakeUp", long.class);
            }
            Object[] params = wakeUpMethod.getParameterTypes().length == 2
                    ? new Object[]{android.os.SystemClock.uptimeMillis(), "AutoJsPro:node24:wakeUp"}
                    : new Object[]{android.os.SystemClock.uptimeMillis()};
            wakeUpMethod.invoke(power, params);
            result.put("method", "wakeUp");
        } catch (Throwable wakeError) {
            Throwable cause = wakeError instanceof java.lang.reflect.InvocationTargetException
                    && wakeError.getCause() != null ? wakeError.getCause() : wakeError;
            firstError = cause.getMessage() == null ? cause.toString() : cause.getMessage();
            if (!hasWakeLockPermission) {
                throw new IllegalStateException("唤醒屏幕失败：缺少 WAKE_LOCK 权限（请在应用信息/权限中允许「保持唤醒」后重试）；系统错误：" + firstError);
            }
            try {
                int flags = android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP
                        | android.os.PowerManager.ON_AFTER_RELEASE;
                android.os.PowerManager.WakeLock lock = power.newWakeLock(flags, "AutoJsPro:node24:wakeUp");
                lock.acquire(3000L);
                result.put("method", "wakeLock");
            } catch (Throwable lockError) {
                String lockMessage = lockError.getMessage() == null ? lockError.toString() : lockError.getMessage();
                throw new IllegalStateException("唤醒屏幕失败：系统拒绝 PowerManager.wakeUp（" + firstError
                        + "），备用 wake lock 也失败（" + lockMessage
                        + "）；Android 12+ 后台唤醒可能受限，请在前台运行或允许「保持唤醒」");
            }
        }
        result.put("wakeUp", true).put("screenOn", power.isInteractive())
                .put("viaWakeLockPermission", hasWakeLockPermission);
        return result;
    }

    private Object domainAction(String kind, JSONObject request) throws Exception {
        String action = request.optString("action", "");
        JSONObject args = request.optJSONObject("args");
        if (args == null) args = new JSONObject();
        switch (kind) {
            case "device": {
                // task-179：power_manager 宿主动作。
                if ("wakeUp".equals(action)) {
                    return deviceWakeUp();
                }
                if ("isScreenOn".equals(action)) {
                    android.os.PowerManager power = (android.os.PowerManager)
                            mContext.getSystemService(Context.POWER_SERVICE);
                    return power != null && power.isInteractive();
                }
                JSONObject value = new JSONObject();
                android.util.DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
                value.put("width", metrics.widthPixels).put("height", metrics.heightPixels)
                        .put("density", metrics.density).put("densityDpi", metrics.densityDpi)
                        .put("sdkInt", android.os.Build.VERSION.SDK_INT)
                        .put("release", android.os.Build.VERSION.RELEASE)
                        .put("brand", android.os.Build.BRAND).put("model", android.os.Build.MODEL)
                        .put("manufacturer", android.os.Build.MANUFACTURER)
                        .put("product", android.os.Build.PRODUCT)
                        .put("packageName", mContext.getPackageName())
                        .put("language", java.util.Locale.getDefault().getLanguage())
                        .put("country", java.util.Locale.getDefault().getCountry());
                return value;
            }
            case "files": {
                String raw = args.optString("path", "");
                File file = resolveFile(raw);
                switch (action) {
                    case "observe": {
                        if (!file.exists()) throw new java.io.FileNotFoundException(file.getPath());
                        synchronized (mFileObservers) {
                            if (mFileObservers.size() >= 128) throw new IllegalStateException("Too many file observers");
                            final int token = mNextFileObserver++;
                            android.os.FileObserver observer = new android.os.FileObserver(file.getPath(), android.os.FileObserver.ALL_EVENTS) {
                                @Override public void onEvent(int event, String path) {
                                    if (mClosed.get()) return;
                                    for (String name : com.stardust.autojs.core.io.ScriptFileObserver.eventNames(event)) {
                                        try {
                                            enqueueEventValue("file_observer:" + token,
                                                    new JSONObject().put("event", name).put("path", path == null ? JSONObject.NULL : path));
                                        } catch (org.json.JSONException error) { Log.w(TAG, "file observer event", error); }
                                    }
                                }
                            };
                            mFileObservers.put(token, observer);
                            observer.startWatching();
                            return token;
                        }
                    }
                    case "startWatching":
                    case "stopWatching":
                        synchronized (mFileObservers) {
                            android.os.FileObserver observer = mFileObservers.get(args.optInt("token", -1));
                            if (observer == null) throw new IllegalArgumentException("Unknown file observer");
                            if ("startWatching".equals(action)) observer.startWatching();
                            else observer.stopWatching();
                            return true;
                        }
                    case "exists": return file.exists();
                    case "isFile": return file.isFile();
                    case "isDir": return file.isDirectory();
                    case "mkdir":
                        if (file.isDirectory()) return true;
                        return file.mkdirs();
                    case "remove":
                        deleteTree(file);
                        return !file.exists();
                    case "list": {
                        JSONArray list = new JSONArray();
                        File[] children = file.isDirectory() ? file.listFiles() : null;
                        if (children != null) {
                            java.util.Arrays.sort(children);
                            for (File child : children) list.put(child.getName());
                        }
                        return list;
                    }
                    case "stat": {
                        JSONObject stat = new JSONObject();
                        stat.put("exists", file.exists()).put("isFile", file.isFile())
                                .put("isDirectory", file.isDirectory())
                                .put("size", file.isFile() ? file.length() : 0)
                                .put("lastModified", file.lastModified())
                                .put("path", file.getPath());
                        return stat;
                    }
                    case "readText":
                        return new String(java.nio.file.Files.readAllBytes(file.toPath()),
                                java.nio.charset.StandardCharsets.UTF_8);
                    case "writeText": {
                        File parent = file.getParentFile();
                        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                            throw new java.io.IOException("无法创建目录：" + parent);
                        }
                        java.nio.file.Files.write(file.toPath(),
                                args.optString("text", "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        return true;
                    }
                    case "read": {
                        return new String(java.nio.file.Files.readAllBytes(file.toPath()),
                                java.nio.charset.StandardCharsets.UTF_8);
                    }
                    default:
                        throw new IllegalArgumentException("files 暂不支持：" + action
                                + "（支持 exists/isFile/isDir/mkdir/remove/list/stat/read/readText/writeText）");
                }
            }
            case "storages": {
                String name = args.optString("name", "default");
                android.os.Bundle values = new android.os.Bundle();
                values.putString("key", args.optString("key", ""));
                values.putString("value", args.optString("value", ""));
                android.os.Bundle result = mContext.getContentResolver().call(
                        android.net.Uri.parse("content://" + mContext.getPackageName() + ".node24.storage"),
                        action, name, values);
                if (result == null) throw new IllegalStateException("Node storage provider unavailable");
                if ("get".equals(action)) return result.getString("value");
                return result.getBoolean("ok", false);
            }
            case "events":
                if ("emit".equals(action)) {
                    enqueueEventValue(args.optString("type", ""), args.opt("payload"));
                    return true;
                }
                throw new IllegalArgumentException("events.emit 支持 type/payload");
            case "engines":
                throw new IllegalArgumentException("engines 请使用 handleEngineRequest（跨进程引擎注册表在主进程）");
            case "threads":
                throw new IllegalArgumentException("Node24 暂不支持 threads.start（请用 worker_threads/child_process）");
            default:
                throw new IllegalArgumentException("不支持的领域：" + kind);
        }
    }

    // ------------------------------------------------------------------
    // Sensors（Pro Node sensors.getSensor/SensorDelay 的宿主侧实现）
    // ------------------------------------------------------------------

    private JSONObject handleSensors(JSONObject request) throws Exception {
        String op = request.optString("op", "");
        switch (op) {
            case "get": {
                Sensor sensor = resolveSensor(request);
                return ok(sensor == null ? null : sensorInfo(sensor));
            }
            case "register": {
                Sensor sensor = resolveSensor(request);
                if (sensor == null) {
                    return ok(null);
                }
                SensorManager manager = sensorManagerOrNull();
                if (manager == null) {
                    throw new IllegalStateException("SensorManager 不可用");
                }
                int delay = parseSensorDelay(request.opt("delay"));
                SensorRegistration registration;
                synchronized (mSensorRegistrations) {
                    registration = new SensorRegistration(mNextSensorToken++, sensor);
                }
                boolean registered;
                try {
                    registered = manager.registerListener(registration, sensor, delay);
                } catch (Throwable error) {
                    throw new IllegalStateException("注册传感器监听失败："
                            + describeCause(error), error);
                }
                if (!registered) {
                    throw new IllegalStateException("注册传感器监听失败：" + sensor.getName()
                            + "（type=" + sensor.getType() + "）");
                }
                registration.delay = delay;
                synchronized (mSensorRegistrations) {
                    mSensorRegistrations.put(registration.token, registration);
                }
                JSONObject value = sensorInfo(sensor);
                value.put("token", registration.token).put("delay", delay);
                return ok(value);
            }
            case "unregister": {
                int token = request.optInt("token", -1);
                SensorRegistration registration;
                synchronized (mSensorRegistrations) {
                    registration = mSensorRegistrations.remove(token);
                }
                if (registration != null) {
                    SensorManager manager = sensorManagerOrNull();
                    if (manager != null) {
                        manager.unregisterListener(registration);
                    }
                }
                return ok(true);
            }
            case "unregisterAll":
                closeSensorEvents();
                return ok(true);
            default:
                return error("Node24 sensors 不支持的操作：" + op);
        }
    }

    /** 名字/数字 → Android Sensor；缺硬件、名字未知或 SensorManager 不可用时返回 null。 */
    private Sensor resolveSensor(JSONObject request) {
        SensorManager manager = sensorManagerOrNull();
        if (manager == null) {
            return null;
        }
        int type = -1;
        Object rawType = request.opt("type");
        if (rawType instanceof Number) {
            double numeric = ((Number) rawType).doubleValue();
            if (numeric != Math.floor(numeric)) {
                return null;
            }
            type = (int) numeric;
        } else if (rawType instanceof String) {
            try {
                type = Integer.parseInt(((String) rawType).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        String name = request.optString("name", "");
        if (type < 0 && !name.isEmpty()) {
            Integer mapped = sensorTypeForName(name);
            if (mapped == null) {
                return null;
            }
            type = mapped;
        }
        if (type < 0) {
            return null;
        }
        try {
            return manager.getDefaultSensor(type);
        } catch (Throwable error) {
            Log.w(TAG, "getDefaultSensor failed type=" + type, error);
            return null;
        }
    }

    private SensorManager sensorManagerOrNull() {
        try {
            return (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        } catch (Throwable error) {
            Log.w(TAG, "SensorManager unavailable", error);
            return null;
        }
    }

    /**
     * Pro SensorDelay 是 0..3（Fastest/Game/Ui/Normal），恰好等于
     * SensorManager.SENSOR_DELAY_*。非法 delay 明确失败，不静默降级成伪事件。
     */
    static int parseSensorDelay(Object value) {
        int delay;
        if (value == null || value == JSONObject.NULL) {
            delay = 3;
        } else if (value instanceof Number) {
            double numeric = ((Number) value).doubleValue();
            if (numeric != Math.floor(numeric)) {
                throw new IllegalArgumentException("sensors.enableSensorEvent delay 非法：" + value
                        + "（请使用 0=Fastest/1=Game/2=Ui/3=Normal）");
            }
            delay = (int) numeric;
        } else {
            try {
                delay = Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("sensors.enableSensorEvent delay 非法：" + value
                        + "（请使用 0=Fastest/1=Game/2=Ui/3=Normal）");
            }
        }
        if (delay < 0 || delay > 3) {
            throw new IllegalArgumentException("sensors.enableSensorEvent delay 非法：" + value
                    + "（请使用 0=Fastest/1=Game/2=Ui/3=Normal）");
        }
        switch (delay) {
            case 0:
                return SensorManager.SENSOR_DELAY_FASTEST;
            case 1:
                return SensorManager.SENSOR_DELAY_GAME;
            case 2:
                return SensorManager.SENSOR_DELAY_UI;
            default:
                return SensorManager.SENSOR_DELAY_NORMAL;
        }
    }

    /** 与 Pro 的 Sensor.TYPE_&lt;NAME&gt; 查找对齐；额外支持 TYPE_ 前缀、下划线/空格/连字符。 */
    static Integer sensorTypeForName(String name) {
        if (name == null) {
            return null;
        }
        String value = name.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.regionMatches(true, 0, "TYPE_", 0, 5)) {
            value = value.substring(5);
        }
        if (value.matches("\\d+")) {
            try {
                return Integer.valueOf(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        String canonical = value.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
        if (canonical.isEmpty()) {
            return null;
        }
        Integer known = SENSOR_TYPES.get(canonical);
        if (known != null) {
            return known;
        }
        try {
            String fieldName = "TYPE_" + value.toUpperCase(Locale.US)
                    .replace('-', '_').replace(' ', '_');
            Field field = Sensor.class.getField(fieldName);
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class) {
                return field.getInt(null);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static JSONObject sensorInfo(Sensor sensor) throws JSONException {
        JSONObject info = new JSONObject();
        info.put("name", sensor.getName() == null ? "" : sensor.getName());
        info.put("type", sensor.getType());
        info.put("vendor", sensor.getVendor() == null ? "" : sensor.getVendor());
        info.put("version", sensor.getVersion());
        info.put("power", (double) sensor.getPower());
        info.put("maxRange", (double) sensor.getMaximumRange());
        info.put("resolution", (double) sensor.getResolution());
        info.put("minDelay", sensor.getMinDelay());
        return info;
    }

    /** close/cancel 兜底：注销所有仍注册的 SensorEventListener（幂等）。 */
    private void closeSensorEvents() {
        ArrayList<SensorRegistration> registrations;
        synchronized (mSensorRegistrations) {
            registrations = new ArrayList<>(mSensorRegistrations.values());
            mSensorRegistrations.clear();
        }
        SensorManager manager = sensorManagerOrNull();
        for (SensorRegistration registration : registrations) {
            try {
                if (manager != null) {
                    manager.unregisterListener(registration);
                }
            } catch (Throwable error) {
                Log.w(TAG, "unregister sensor failed token=" + registration.token, error);
            }
        }
    }

    /** 每个 token 对应一个真实 Android SensorEventListener；回调只入队，由 Node 轮询取走。 */
    private final class SensorRegistration implements SensorEventListener {
        final int token;
        final Sensor sensor;
        int delay;

        SensorRegistration(int token, Sensor sensor) {
            this.token = token;
            this.sensor = sensor;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event == null) {
                return;
            }
            try {
                JSONArray values = new JSONArray();
                if (event.values != null) {
                    for (float value : event.values) {
                        values.put((double) value);
                    }
                }
                JSONObject payload = new JSONObject()
                        .put("token", token)
                        .put("sensorType", sensor.getType())
                        .put("timestamp", event.timestamp)
                        .put("accuracy", event.accuracy)
                        .put("values", values);
                enqueueEvent(SENSOR_EVENT_CHANGE, payload);
            } catch (Throwable error) {
                Log.w(TAG, "sensor change enqueue failed token=" + token, error);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor changed, int accuracy) {
            try {
                JSONObject payload = new JSONObject()
                        .put("token", token)
                        .put("sensorType", sensor.getType())
                        .put("accuracy", accuracy);
                enqueueEvent(SENSOR_EVENT_ACCURACY, payload);
            } catch (Throwable error) {
                Log.w(TAG, "sensor accuracy enqueue failed token=" + token, error);
            }
        }
    }

    private File resolveFile(String raw) throws Exception {
        if (raw == null || raw.isEmpty()) throw new IOException("路径不能为空");
        File file = new File(raw);
        if (file.isAbsolute()) return file;
        File base = mProjectDir == null || mProjectDir.isEmpty()
                ? mContext.getFilesDir() : new File(mProjectDir);
        return new File(base, raw);
    }

    private static void deleteTree(File file) throws Exception {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        java.nio.file.Files.deleteIfExists(file.toPath());
    }

    private void startActivity(String target) {
        Intent intent;
        if (target.contains("/")) {
            String[] parts = target.split("/", 2);
            intent = new Intent().setClassName(parts[0], parts[1]);
        } else if (target.contains(".")) {
            Intent launch = mContext.getPackageManager().getLaunchIntentForPackage(target);
            intent = launch != null ? launch
                    : new Intent().setClassName(mContext.getPackageName(), target);
        } else {
            intent = new Intent().setClassName(mContext.getPackageName(), target);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }

    /**
     * JS 侧数字统一是 double：按 Java 方法参数类型做一次数值收窄/数组转换，
     * 否则反射 invoke 会因 Integer vs Double 直接 IllegalArgumentException。
     */
    private static Object[] coerce(Class<?>[] types, Object[] args) {
        if (args == null) {
            return new Object[0];
        }
        Object[] values = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            values[i] = coerce(types[i], args[i]);
        }
        return values;
    }

    private static Object coerce(Class<?> type, Object value) {
        return Node24JavaArguments.coerce(type, value);
    }

    // ------------------------------------------------------------------
    // task-158：media（MediaPlayer 播放/音量/本地音频扫描）
    // ------------------------------------------------------------------

    private Object handleMedia(JSONObject request) throws Exception {
        String action = request.optString("action", "");
        switch (action) {
            case "play":
                return playMusic(request);
            case "pause":
                return onMediaThread(new java.util.concurrent.Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        if (mMusicPlayer != null && mMusicPlayer.isPlaying()) {
                            mMusicPlayer.pause();
                        }
                        return mMusicPlayer != null && !mMusicPlayer.isPlaying();
                    }
                });
            case "resume":
                return onMediaThread(new java.util.concurrent.Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        if (mMusicPlayer != null) {
                            mMusicPlayer.start();
                        }
                        return mMusicPlayer != null && mMusicPlayer.isPlaying();
                    }
                });
            case "stop":
                releaseMusicPlayer();
                return true;
            case "isPlaying":
                return onMediaThread(new java.util.concurrent.Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        return mMusicPlayer != null && mMusicPlayer.isPlaying();
                    }
                });
            case "duration":
                return onMediaThread(new java.util.concurrent.Callable<Integer>() {
                    @Override
                    public Integer call() {
                        return mMusicPlayer == null ? 0 : mMusicPlayer.getDuration();
                    }
                });
            case "position":
                return onMediaThread(new java.util.concurrent.Callable<Integer>() {
                    @Override
                    public Integer call() {
                        return mMusicPlayer == null ? 0 : mMusicPlayer.getCurrentPosition();
                    }
                });
            case "seek":
                return onMediaThread(new java.util.concurrent.Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        if (mMusicPlayer == null) {
                            throw new IllegalStateException("当前没有正在播放的音乐");
                        }
                        mMusicPlayer.seekTo(request.optInt("position", 0));
                        return true;
                    }
                });
            case "getVolume":
                return mediaVolume(request.optString("stream", "music"), null);
            case "setVolume":
                return mediaVolume(request.optString("stream", "music"),
                        Integer.valueOf(request.optInt("volume", 0)));
            case "scanAudio":
                return scanAudioFiles(request.optString("dir", ""), request.optInt("limit", 200));
            default:
                throw new IllegalArgumentException("media 不支持 action：" + action);
        }
    }

    private Object playMusic(final JSONObject request) throws Exception {
        final String path = request.optString("path", "");
        if (path.isEmpty()) {
            throw new IllegalArgumentException("media.play 需要 path");
        }
        // task-171：支持 http(s) URL / file:// 与本地绝对路径；远程媒体由 MediaPlayer 直接拉流。
        final boolean remote = path.startsWith("http://") || path.startsWith("https://");
        final String mediaPath;
        if (remote) {
            mediaPath = path;
        } else if (path.startsWith("file://")) {
            mediaPath = path.substring("file://".length());
        } else {
            mediaPath = path;
        }
        if (!remote) {
            final File file = new File(mediaPath);
            if (!file.isAbsolute()) {
                throw new IllegalArgumentException("media.play 需要绝对路径或 http(s) URL：" + path);
            }
            if (!file.isFile()) {
                throw new IllegalArgumentException("音频文件不存在：" + path);
            }
        }
        final float volume = (float) Math.max(0d, Math.min(1d, request.optDouble("volume", 1d)));
        final boolean looping = request.optBoolean("looping", false);
        return onMediaThread(new java.util.concurrent.Callable<JSONObject>() {
            @Override
            public JSONObject call() throws Exception {
                releaseMusicPlayerOnThread();
                android.media.MediaPlayer player = new android.media.MediaPlayer();
                try {
                    player.setDataSource(mediaPath);
                    player.setVolume(volume, volume);
                    player.setLooping(looping);
                    player.prepare();
                    player.start();
                } catch (Throwable error) {
                    try { player.release(); } catch (Throwable ignored) { }
                    throw new IllegalStateException("创建 MediaPlayer 失败（" + mediaPath + "）："
                            + (error.getMessage() == null ? error.toString() : error.getMessage()), error);
                }
                mMusicPlayer = player;
                mMusicPath = mediaPath;
                return new JSONObject().put("playing", true).put("path", mediaPath)
                        .put("duration", player.getDuration()).put("looping", looping);
            }
        });
    }

    private void releaseMusicPlayer() {
        try {
            onMediaThread(new java.util.concurrent.Callable<Boolean>() {
                @Override
                public Boolean call() {
                    releaseMusicPlayerOnThread();
                    return true;
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "release media player failed", error);
        }
    }

    /** 只能在 mMediaThread 上调用。 */
    private void releaseMusicPlayerOnThread() {
        android.media.MediaPlayer player = mMusicPlayer;
        mMusicPlayer = null;
        mMusicPath = null;
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
        }
    }

    private void ensureMediaHandler() {
        if (mMediaHandler != null) {
            return;
        }
        synchronized (this) {
            if (mMediaHandler != null) {
                return;
            }
            mMediaThread = new android.os.HandlerThread("node24-media");
            mMediaThread.start();
            mMediaHandler = new android.os.Handler(mMediaThread.getLooper());
        }
    }

    private <T> T onMediaThread(final java.util.concurrent.Callable<T> callable) throws Exception {
        ensureMediaHandler();
        final Object[] result = new Object[2];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        boolean posted = mMediaHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    result[0] = callable.call();
                } catch (Throwable error) {
                    result[1] = error;
                } finally {
                    latch.countDown();
                }
            }
        });
        if (!posted) {
            throw new IllegalStateException("media 宿主线程不可用");
        }
        if (!latch.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("media 操作超时（15 秒）");
        }
        Throwable failure = (Throwable) result[1];
        if (failure instanceof Exception) {
            throw (Exception) failure;
        }
        if (failure != null) {
            throw new IllegalStateException(failure.getMessage() == null
                    ? failure.toString() : failure.getMessage(), failure);
        }
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }

    private void shutdownMediaThread() {
        releaseMusicPlayer();
        android.os.HandlerThread thread = mMediaThread;
        mMediaThread = null;
        mMediaHandler = null;
        if (thread != null) {
            try { thread.quitSafely(); } catch (Throwable ignored) { }
        }
    }

    private JSONObject mediaVolume(String streamName, Integer target) {
        android.media.AudioManager manager =
                (android.media.AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("AudioManager 不可用");
        }
        int stream = audioStreamOf(streamName);
        int max = manager.getStreamMaxVolume(stream);
        if (target != null) {
            manager.setStreamVolume(stream, Math.max(0, Math.min(max, target.intValue())), 0);
        }
        JSONObject value = new JSONObject();
        try {
            value.put("stream", stream).put("volume", manager.getStreamVolume(stream)).put("max", max);
        } catch (Throwable ignored) {
        }
        return value;
    }

    private static int audioStreamOf(String name) {
        if ("alarm".equals(name)) return android.media.AudioManager.STREAM_ALARM;
        if ("voice_call".equals(name) || "voice".equals(name)) return android.media.AudioManager.STREAM_VOICE_CALL;
        if ("ring".equals(name)) return android.media.AudioManager.STREAM_RING;
        if ("notification".equals(name)) return android.media.AudioManager.STREAM_NOTIFICATION;
        if ("system".equals(name)) return android.media.AudioManager.STREAM_SYSTEM;
        if ("dtmf".equals(name)) return android.media.AudioManager.STREAM_DTMF;
        return android.media.AudioManager.STREAM_MUSIC;
    }

    /**
     * 本地音频基础扫描。
     *
     * <p>默认走 MediaStore（不直接 listFiles sdcard 目录）：K40/LineageOS 上
     * {@code File.listFiles()} 遇到非法 UTF-8 文件名会在 unix FileSystem.list0 里
     * NewStringUTF 直接让进程 SIGABRT（系统框架 bug，错误信息形如 "input is not valid
     * Modified UTF-8"）。MediaStore 由 provider 提供已解码的 DATA/DISPLAY_NAME，不会踩到。
     *
     * <p>显式传 dir 时才做文件系统递归（调用方自担该目录内有非法文件名，冒烟用受控目录）。
     */
    private JSONArray scanAudioFiles(String rootDir, int limit) {
        int max = Math.max(1, Math.min(1000, limit));
        if (rootDir != null && !rootDir.isEmpty()) {
            return scanAudioDirLegacy(new File(rootDir), max);
        }
        return scanAudioMediaStore(max);
    }

    private JSONArray scanAudioMediaStore(int max) {
        JSONArray result = new JSONArray();
        android.database.Cursor cursor = null;
        try {
            String[] projection = {
                    android.provider.MediaStore.Audio.Media._ID,
                    android.provider.MediaStore.Audio.Media.DATA,
                    android.provider.MediaStore.Audio.Media.DISPLAY_NAME,
                    android.provider.MediaStore.Audio.Media.SIZE,
                    android.provider.MediaStore.Audio.Media.DURATION,
                    android.provider.MediaStore.Audio.Media.TITLE
            };
            cursor = mContext.getContentResolver().query(
                    android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    android.provider.MediaStore.Audio.Media.IS_MUSIC + "!=0",
                    null,
                    android.provider.MediaStore.Audio.Media.DATE_ADDED + " DESC");
            while (cursor != null && cursor.moveToNext() && result.length() < max) {
                JSONObject item = new JSONObject();
                String data = cursor.getString(1);
                String displayName = cursor.getString(2);
                item.put("id", cursor.getLong(0))
                        .put("path", sanitizeUtf16(data))
                        .put("name", sanitizeUtf16(displayName))
                        .put("size", cursor.getLong(3))
                        .put("duration", cursor.getLong(4))
                        .put("title", sanitizeUtf16(cursor.getString(5)));
                result.put(item);
            }
        } catch (Throwable error) {
            Log.w(TAG, "scanAudio via MediaStore failed", error);
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Throwable ignored) { }
            }
        }
        return result;
    }

    /** 显式目录扫描：文件系统递归 + 扩展名过滤（受控目录用，避免扫 sdcard 根）。 */
    private JSONArray scanAudioDirLegacy(File root, int max) {
        JSONArray result = new JSONArray();
        java.util.Set<String> extensions = new java.util.HashSet<>(java.util.Arrays.asList(
                "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma"));
        scanAudioDir(root, extensions, result, max, 0);
        return result;
    }

    private void scanAudioDir(File dir, java.util.Set<String> extensions, JSONArray result,
                              int max, int depth) {
        if (dir == null || !dir.isDirectory() || result.length() >= max || depth > 4) {
            return;
        }
        File[] children;
        try {
            children = dir.listFiles();
        } catch (Throwable error) {
            Log.w(TAG, "scanAudio listFiles failed: " + dir, error);
            return;
        }
        if (children == null) {
            return;
        }
        java.util.Arrays.sort(children);
        for (File child : children) {
            if (result.length() >= max) {
                return;
            }
            if (child.isDirectory()) {
                scanAudioDir(child, extensions, result, max, depth + 1);
                continue;
            }
            String name = child.getName();
            int dot = name.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!extensions.contains(extension)) {
                continue;
            }
            try {
                result.put(new JSONObject().put("path", sanitizeUtf16(child.getAbsolutePath()))
                        .put("name", sanitizeUtf16(name)).put("size", child.length()));
            } catch (Throwable ignored) {
            }
        }
    }

    /** 把孤立代理项替换为 '?'，保证 Java String 能安全经 JNI 返回 Node。 */
    private static String sanitizeUtf16(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                    builder.append(c).append(value.charAt(++i));
                } else {
                    builder.append('?');
                }
            } else if (Character.isLowSurrogate(c)) {
                builder.append('?');
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------
    // task-158：plugins（已装插件列出/信息/启动）
    // ------------------------------------------------------------------

    private Object handlePlugins(JSONObject request) throws Exception {
        String action = request.optString("action", "");
        android.content.pm.PackageManager pm = mContext.getPackageManager();
        switch (action) {
            case "list":
                return listPlugins(pm);
            case "isInstalled":
                return pluginInfo(pm, request.optString("packageName", ""), false) != null;
            case "info": {
                JSONObject info = pluginInfo(pm, request.optString("packageName", ""), true);
                return info;
            }
            case "load": {
                JSONObject record = mPluginLoader.load(request.getString("packageName"));
                record.put("javaObject", toWire(mPluginLoader.instance(request.getString("packageName"))));
                return record;
            }
            case "unload":
                mNoArgCalls.close();
                mReflectionCache.close();
                return new JSONObject().put("unloaded", mPluginLoader.unload(request.getString("packageName")));
            case "unloadAll":
                mNoArgCalls.close();
                mReflectionCache.close();
                return new JSONObject().put("unloaded", mPluginLoader.unloadAll());
            case "launch":
                return launchPlugin(pm, request.optString("packageName", ""));
            default:
                throw new IllegalArgumentException("plugins 不支持 action：" + action);
        }
    }

    private JSONArray listPlugins(android.content.pm.PackageManager pm) throws Exception {
        JSONArray result = new JSONArray();
        for (String packageName : KNOWN_PLUGIN_PACKAGES) {
            JSONObject info = pluginInfo(pm, packageName, false);
            if (info != null) {
                result.put(info);
            }
        }
        return result;
    }

    /** installed=false 时未安装返回 null；requireInstalled=true 时未安装抛中文提示。 */
    private JSONObject pluginInfo(android.content.pm.PackageManager pm, String packageName,
                                  boolean requireInstalled) throws Exception {
        if (packageName == null || packageName.isEmpty()) {
            throw new IllegalArgumentException("plugins 需要 packageName");
        }
        android.content.pm.PackageInfo packageInfo;
        try {
            packageInfo = pm.getPackageInfo(packageName,
                    android.content.pm.PackageManager.GET_SERVICES);
        } catch (android.content.pm.PackageManager.NameNotFoundException notFound) {
            if (requireInstalled) {
                throw new IllegalStateException("插件未安装：" + packageName
                        + "（可从「插件」页安装，或把插件 APK 发到设备后重试）");
            }
            return null;
        }
        JSONObject info = new JSONObject();
        try {
            String label = packageName;
            try {
                android.content.pm.ApplicationInfo applicationInfo =
                        pm.getApplicationInfo(packageName, 0);
                label = String.valueOf(pm.getApplicationLabel(applicationInfo));
            } catch (Throwable ignored) {
            }
            info.put("packageName", packageName)
                    .put("label", label)
                    .put("versionName", packageInfo.versionName == null ? "" : packageInfo.versionName)
                    .put("versionCode", packageInfo.getLongVersionCode())
                    .put("installed", true);
            android.content.Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
            if (launchIntent != null && launchIntent.getComponent() != null) {
                info.put("launcher", launchIntent.getComponent().flattenToShortString());
            } else {
                info.put("launcher", JSONObject.NULL);
            }
            JSONArray services = new JSONArray();
            if (packageInfo.services != null) {
                for (android.content.pm.ServiceInfo service : packageInfo.services) {
                    if (service.exported && !service.name.startsWith(packageName)) {
                        services.put(service.name);
                    }
                }
            }
            info.put("exportedServices", services);
        } catch (Throwable ignored) {
        }
        return info;
    }

    private boolean launchPlugin(android.content.pm.PackageManager pm, String packageName)
            throws Exception {
        pluginInfo(pm, packageName, true);
        android.content.Intent intent = pm.getLaunchIntentForPackage(packageName);
        if (intent == null) {
            throw new IllegalStateException("插件 " + packageName
                    + " 没有可启动的界面（它是能力插件，由主程序/脚本调用）");
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
        return true;
    }

    // ------------------------------------------------------------------
    // task-156：无障碍事件订阅与通知 action 点击
    // ------------------------------------------------------------------

    private Object handleAccessibility(JSONObject request) throws Exception {
        String action = request.optString("action", "");
        switch (action) {
            case "subscribe":
                ensureAccessibilityEventReceiver();
                mContext.sendBroadcast(new Intent(ACTION_NODE_ACCESSIBILITY_SUBSCRIBE)
                        .setPackage(mContext.getPackageName()));
                Log.i(TAG, "accessibility events subscribed");
                return true;
            case "unsubscribe":
                mContext.sendBroadcast(new Intent(ACTION_NODE_ACCESSIBILITY_UNSUBSCRIBE)
                        .setPackage(mContext.getPackageName()));
                return true;
            case "enableKeyEvents": {
                boolean enabled = request.optBoolean("enabled", true);
                mContext.sendBroadcast(new Intent(ACTION_NODE_ACCESSIBILITY_KEY_EVENTS)
                        .setPackage(mContext.getPackageName()).putExtra("enabled", enabled));
                Log.i(TAG, "accessibility key events requested enabled=" + enabled);
                return true;
            }
            case "lockScreen": {
                if (android.os.Build.VERSION.SDK_INT < 28) {
                    throw new IllegalStateException("accessibility.lockScreen 需要 Android 9 (API 28) 及以上；当前 API "
                            + android.os.Build.VERSION.SDK_INT);
                }
                mContext.sendBroadcast(new Intent(ACTION_NODE_ACCESSIBILITY_LOCK_SCREEN)
                        .setPackage(mContext.getPackageName()));
                Log.i(TAG, "accessibility lock screen requested");
                return true;
            }
            default:
                throw new IllegalArgumentException("accessibility 不支持 action：" + action);
        }
    }

    /** 主进程 AccessibilityService 转发过来的事件 -> Node 轮询队列。 */
    private void ensureAccessibilityEventReceiver() {
        if (mAccessibilityEventReceiver != null) {
            return;
        }
        android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }
                String json = intent.getStringExtra("json");
                if (json == null || json.isEmpty()) {
                    return;
                }
                try {
                    enqueueEvent(EVENT_NODE_ACCESSIBILITY, new JSONObject(json));
                } catch (Throwable error) {
                    Log.w(TAG, "bad accessibility event json", error);
                }
            }
        };
        android.content.IntentFilter filter =
                new android.content.IntentFilter(ACTION_NODE_ACCESSIBILITY_EVENT);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            mContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            mContext.registerReceiver(receiver, filter);
        }
        mAccessibilityEventReceiver = receiver;
    }

    private Object handleNodeNotification(JSONObject request) throws Exception {
        String action = request.optString("action", "");
        android.app.NotificationManager manager = (android.app.NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("NotificationManager 不可用");
        }
        switch (action) {
            case "notify":
                return notifyNodeNotification(manager, request);
            case "cancel":
                manager.cancel(request.optInt("id", 0));
                return true;
            case "cancelAll":
                manager.cancelAll();
                return true;
            case "requestListeningNotifications": {
                if (!hasNotificationListenerAccess()) {
                    try {
                        Intent settings = new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(settings);
                    } catch (Throwable error) {
                        Log.w(TAG, "open notification listener settings failed", error);
                    }
                    throw new IllegalStateException("尚未开启通知使用权：已打开系统设置，请授权 AI.js Pro 的通知使用权后重试");
                }
                ensureNotificationListenerReceiver();
                mContext.sendBroadcast(new Intent(ACTION_NODE_NOTIFICATION_LISTENER_SUBSCRIBE)
                        .setPackage(mContext.getPackageName()));
                Log.i(TAG, "notification listener subscribed");
                return new JSONObject().put("listening", true);
            }
            case "stopListeningNotifications":
                mContext.sendBroadcast(new Intent(ACTION_NODE_NOTIFICATION_LISTENER_UNSUBSCRIBE)
                        .setPackage(mContext.getPackageName()));
                return true;
            default:
                throw new IllegalArgumentException("notification 不支持 action：" + action);
        }
    }

    private Object notifyNodeNotification(android.app.NotificationManager manager, JSONObject request)
            throws Exception {
        int id = request.optInt("id", 0);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new android.app.NotificationChannel(
                    NODE_NOTIFICATION_CHANNEL, "Node24",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT));
        }
        int smallIcon = resolveNotificationIcon();
        if (smallIcon == 0) {
            throw new IllegalStateException("找不到通知 smallIcon 资源（ic_launcher）");
        }
        android.app.Notification.Builder builder = android.os.Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(mContext, NODE_NOTIFICATION_CHANNEL)
                : new android.app.Notification.Builder(mContext);
        builder.setSmallIcon(smallIcon);
        if (request.has("title")) {
            builder.setContentTitle(request.optString("title", ""));
        }
        if (request.has("text")) {
            builder.setContentText(request.optString("text", ""));
        }
        if (request.has("ticker")) {
            builder.setTicker(request.optString("ticker", ""));
        }
        builder.setOngoing(request.optBoolean("ongoing", false));
        builder.setAutoCancel(request.optBoolean("autoCancel", true));
        // 平台 Notification.Builder 没有 setSilent（NotificationCompat 才有）；silent 先忽略。
        builder.setContentIntent(notificationPendingIntent(id, -1, ""));
        JSONArray actions = request.optJSONArray("actions");
        if (actions != null) {
            for (int i = 0; i < actions.length(); i++) {
                JSONObject action = actions.optJSONObject(i);
                if (action == null) {
                    continue;
                }
                String title = action.optString("title", "");
                if (title.isEmpty()) {
                    continue;
                }
                builder.addAction(new android.app.Notification.Action.Builder(
                        smallIcon, title, notificationPendingIntent(id, i, title)).build());
            }
        }
        ensureNotificationEventReceiver();
        manager.notify(id, builder.build());
        Log.i(TAG, "notification posted id=" + id + " actions=" + (actions == null ? 0 : actions.length()));
        return new JSONObject().put("id", id);
    }

    private android.app.PendingIntent notificationPendingIntent(int id, int actionIndex, String title) {
        Intent intent = new Intent(ACTION_NODE_NOTIFICATION_EVENT)
                .setPackage(mContext.getPackageName())
                .putExtra("id", id)
                .putExtra("action", actionIndex)
                .putExtra("title", title == null ? "" : title);
        int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
        }
        return android.app.PendingIntent.getBroadcast(
                mContext, mNotificationPendingSeq++, intent, flags);
    }

    private int resolveNotificationIcon() {
        try {
            int icon = mContext.getResources()
                    .getIdentifier("ic_launcher", "mipmap", mContext.getPackageName());
            if (icon == 0) {
                icon = mContext.getResources()
                        .getIdentifier("ic_launcher", "drawable", mContext.getPackageName());
            }
            if (icon == 0) {
                icon = mContext.getApplicationInfo().icon;
            }
            return icon;
        } catch (Throwable error) {
            return 0;
        }
    }

    /** 通知内容/按钮点击广播 -> Node 轮询队列（action=-1 表示内容点击）。 */
    private void ensureNotificationEventReceiver() {
        if (mNotificationEventReceiver != null) {
            return;
        }
        android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }
                try {
                    JSONObject payload = new JSONObject()
                            .put("id", intent.getIntExtra("id", 0))
                            .put("action", intent.getIntExtra("action", -1))
                            .put("title", intent.getStringExtra("title"));
                    enqueueEvent(EVENT_NODE_NOTIFICATION, payload);
                } catch (Throwable error) {
                    Log.w(TAG, "bad notification click event", error);
                }
            }
        };
        android.content.IntentFilter filter =
                new android.content.IntentFilter(ACTION_NODE_NOTIFICATION_EVENT);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            mContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            mContext.registerReceiver(receiver, filter);
        }
        mNotificationEventReceiver = receiver;
    }

    /** task-171：当前 app 是否出现在系统通知使用权列表里。 */
    private boolean hasNotificationListenerAccess() {
        try {
            String enabled = android.provider.Settings.Secure.getString(mContext.getContentResolver(),
                    "enabled_notification_listeners");
            return enabled != null && enabled.contains(mContext.getPackageName());
        } catch (Throwable error) {
            Log.w(TAG, "read notification listener access failed", error);
            return false;
        }
    }

    /** 主进程 NotificationListenerService 转发来的通知 -> Node 轮询队列。 */
    private void ensureNotificationListenerReceiver() {
        if (mNotificationListenerReceiver != null) {
            return;
        }
        android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }
                try {
                    String json = intent.getStringExtra("json");
                    JSONObject payload = json == null ? new JSONObject() : new JSONObject(json);
                    enqueueEvent(EVENT_NODE_NOTIFICATION_LISTENER, payload);
                } catch (Throwable error) {
                    Log.w(TAG, "bad notification listener event", error);
                }
            }
        };
        android.content.IntentFilter filter =
                new android.content.IntentFilter(ACTION_NODE_NOTIFICATION_LISTENER_EVENT);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            mContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            mContext.registerReceiver(receiver, filter);
        }
        mNotificationListenerReceiver = receiver;
    }

    // ------------------------------------------------------------------
    // 轮询式反向事件通道（task-58）
    // ------------------------------------------------------------------

    /** Java 侧（B 的 UI/生命周期桥、主进程 host 桥）投递事件到本 Node 执行的任务队列。 */
    public void enqueueEvent(String type, JSONObject payload) {
        enqueueEventValue(type, payload);
    }

    private void enqueueEventValue(String type, Object payload) {
        if (mClosed.get() || type == null || type.isEmpty()) {
            return;
        }
        try {
            JSONObject event = new JSONObject().put("type", type)
                    .put("payload", payload == null ? JSONObject.NULL : payload);
            if ("__ajs_java_result".equals(type)) {
                mJavaResults.offer(event);
                return;
            }
            mEvents.offer(event);
            while (mEvents.size() > MAX_EVENTS) {
                mEvents.poll();
            }
        } catch (Throwable error) {
            Log.w(TAG, "enqueue event failed: " + type, error);
        }
    }

    /** Node 侧 {op:"poll"}：一次取走最多 64 条事件。 */
    private JSONObject pollEvents() {
        JSONArray events = new JSONArray();
        JSONObject event;
        while (events.length() < 64 && (event = mJavaResults.poll()) != null) {
            events.put(event);
        }
        while (events.length() < 64 && (event = mEvents.poll()) != null) {
            events.put(event);
        }
        try {
            return new JSONObject().put("$events", events).put("$more", !mEvents.isEmpty() || !mJavaResults.isEmpty());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    /** 当前队列里待派发事件数（测试/诊断用）。 */
    public int pendingEventCount() {
        return mEvents.size() + mJavaResults.size();
    }

    private static String describe(Object[] args) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            Object value = args[i];
            builder.append(value == null ? "null" : value.getClass().getName());
        }
        return builder.toString();
    }

    private static String describeCause(Throwable error) {
        Throwable cause = error;
        if (error instanceof InvocationTargetException && error.getCause() != null) {
            cause = error.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
