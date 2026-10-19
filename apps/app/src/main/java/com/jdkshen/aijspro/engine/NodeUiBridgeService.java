package com.jdkshen.aijspro.engine;

import android.app.Activity;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import com.stardust.autojs.engine.node24.NodeUiActivityClient;
import com.stardust.autojs.engine.node24.NodeUiProtocol;

import org.autojs.source.engine.android.Node24Service;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * task-76：主进程 Node UI Activity 的 Binder 服务（:node24 的 NodeUiActivityClient 绑定它）。
 *
 * <p>协议见 {@link NodeUiProtocol}：MSG_SHOW 启动 NodeUiActivity；MSG_UPDATE 转 Activity.handleUpdate；
 * MSG_CLOSE 关闭；Activity 关闭经 CloseListener 以 MSG_EVENT(type=destroy) 回 :node24。
 *
 * <p>task-178：NodeUiActivity 返回键需要结束对应 Node execution。Activity 只知道 session/projectDir，
 * 本服务通过 {@link Node24Service#ACTION_NODE24_STATE} 跟踪 :node24/_1..3 的 active job id（含
 * UI script 标记与 filename），把 session/projectDir 映射到 job id；返回时下发
 * {@link Node24Service#ACTION_NODE24_CONTROL} cancel(id)，Node24Service 取消 native 执行、关 UI 并结束
 * 进程（池进程保持 warm），Node24JavaScriptEngine 收到 CANCELLED 后 :script 宿主 token 收尾。
 */
public class NodeUiBridgeService extends Service {

    private static final String TAG = "NodeUiBridgeService";
    private static final Map<String, Messenger> SESSIONS = new ConcurrentHashMap<>();
    /** task-178：:node24 当前运行 job（key=job id，state 广播维护）。 */
    private static final Map<String, NodeJob> NODE_JOBS = new ConcurrentHashMap<>();
    /** task-178：session -> Activity 的 projectDir，用于广播晚到/映射兜底。 */
    private static final Map<String, String> SESSION_PROJECTS = new ConcurrentHashMap<>();
    /** task-178：session -> Node24 job id（show 时或 started 广播到达后绑定）。 */
    private static final Map<String, String> SESSION_JOBS = new ConcurrentHashMap<>();
    private static final Map<String, Messenger> DIALOG_SESSIONS = new ConcurrentHashMap<>();
    private static volatile NodeUiBridgeService sDialogInstance;
    private static final Map<String, android.view.View> OVERLAYS = new ConcurrentHashMap<>();
    /** task-183：overlayId -> owner（Node 执行宿主）；closeAll 只清本 owner，避免并行 job 互删浮窗。 */
    private static final Map<String, String> OVERLAY_OWNERS = new ConcurrentHashMap<>();
    private static final Map<String, android.view.WindowManager.LayoutParams> OVERLAY_PARAMS = new ConcurrentHashMap<>();
    private static int sOverlaySeq = 1;
    // task-157：XML overlay 内容/触摸事件/拖拽
    private static final Map<String, android.view.View> OVERLAY_CONTENTS = new ConcurrentHashMap<>();
    private static final Map<String, java.util.ArrayDeque<org.json.JSONObject>> OVERLAY_EVENTS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> OVERLAY_DRAG_ANCHORS = new ConcurrentHashMap<>();
    /** task-200：最近一次 overlay show 结果（show 回复丢失时客户端可找回 overlayId/views）。 */
    private static volatile org.json.JSONObject LAST_OVERLAY_INFO;
    private static final Map<String, float[]> OVERLAY_DOWN = new ConcurrentHashMap<>();
    private static volatile NodeUiBridgeService sInstance;
    /** task-178：Node job 状态广播接收器（onCreate 注册，onDestroy 注销）。 */
    private BroadcastReceiver mNodeStateReceiver;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    /** task-178：Node24 状态广播里的一条活动 job；只保存返回键取消需要的字段。 */
    private static final class NodeJob {
        final String id;
        final String filename;
        final boolean uiScript;
        final long startedAt;
        NodeJob(String id, String filename, boolean uiScript, long startedAt) {
            this.id = id;
            this.filename = filename == null ? "" : filename;
            this.uiScript = uiScript;
            this.startedAt = startedAt;
        }
    }

    /**
     * task-169：AI 球菜单展开/收起时切换 Node 浮窗触摸能力。
     * 菜单展开期间浮窗置 FLAG_NOT_TOUCHABLE，菜单按钮（含 ⏹ 停止全部）永远优先收到点击；
     * 菜单收起后恢复，浮窗内 ✕/确定/console 照常可点。
     */
    public static void setOverlaysTouchable(final boolean touchable) {
        Runnable task = () -> {
            for (Map.Entry<String, android.view.WindowManager.LayoutParams> entry : OVERLAY_PARAMS.entrySet()) {
                android.view.WindowManager.LayoutParams params = entry.getValue();
                if (params == null) continue;
                if (touchable) {
                    params.flags &= ~android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                } else {
                    params.flags |= android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                }
                android.view.View view = OVERLAYS.get(entry.getKey());
                if (view != null) {
                    try {
                        android.view.WindowManager manager = (android.view.WindowManager) view.getContext()
                                .getSystemService(Context.WINDOW_SERVICE);
                        if (manager != null) manager.updateViewLayout(view, params);
                    } catch (Throwable ignored) { }
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            new Handler(Looper.getMainLooper()).post(task);
        }
    }
    private final java.util.concurrent.ExecutorService mWork = java.util.concurrent.Executors.newSingleThreadExecutor();

    private final Messenger mMessenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message msg) {
            Bundle data = msg.getData();
            if (data == null) return;
            String session = data.getString(NodeUiProtocol.K_SESSION, "");
            if (msg.what == NodeUiProtocol.MSG_SHOW) {
                if (msg.replyTo != null) SESSIONS.put(session, msg.replyTo);
                show(session, data, msg.replyTo);
            } else if (msg.what == NodeUiProtocol.MSG_UPDATE) {
                update(session, data, msg.replyTo);
            } else if (msg.what == NodeUiProtocol.MSG_WORK) {
                handleWork(msg.getData().getString(NodeUiProtocol.K_REQUEST, "{}"), msg.replyTo);
            } else if (msg.what == NodeUiProtocol.MSG_OVERLAY) {
                handleOverlay(msg.getData().getString(NodeUiProtocol.K_REQUEST, "{}"), msg.replyTo);
            } else if (msg.what == NodeUiProtocol.MSG_DIALOG) {
                showDialog(session, msg.getData().getString(NodeUiProtocol.K_REQUEST, "{}"), msg.replyTo);
            } else if (msg.what == NodeUiProtocol.MSG_VIEW) {
                handleView(data.getString(NodeUiProtocol.K_REQUEST, "{}"), msg.replyTo, session);
            } else if (msg.what == NodeUiProtocol.MSG_CLOSE) {
                close(session);
            }
        }
    });

    /** task-144：主进程真实 work_manager（反射 app 的 WorkManagerModule，TimedTaskManager 持久化+调度）。 */
    private void handleWork(final String requestJson, final Messenger replyTo) {
        mWork.execute(() -> {
            String result;
            try {
                org.json.JSONObject request = new org.json.JSONObject(requestJson == null ? "{}" : requestJson);
                String action = request.optString("action", "getAllTasks");
                Class<?> moduleClass = Class.forName("com.jdkshen.aijspro.autojs.api.timing.WorkManagerModule");
                Object module = moduleClass.getDeclaredConstructor().newInstance();
                java.util.Map<String, Object> options = new java.util.HashMap<>();
                org.json.JSONObject opts = request.optJSONObject("options");
                if (opts != null) {
                    java.util.Iterator<String> keys = opts.keys();
                    while (keys.hasNext()) { String key = keys.next(); options.put(key, opts.get(key)); }
                }
                Object value;
                switch (action) {
                    case "addDisposableTask": value = invokeModule(module, "addDisposableTask", options); break;
                    case "addDailyTask": value = invokeModule(module, "addDailyTask", options); break;
                    case "addWeeklyTask": value = invokeModule(module, "addWeeklyTask", options); break;
                    case "addBroadcastIntentTask": value = invokeModule(module, "addBroadcastIntentTask", options); break;
                    case "getTimedTask": value = invokeModule(module, "getTimedTask", request.optLong("id")); break;
                    case "removeTimedTask": value = invokeModule(module, "removeTimedTask", request.optLong("id")); break;
                    case "getIntentTask": value = invokeModule(module, "getIntentTask", request.optLong("id")); break;
                    case "removeIntentTask": value = invokeModule(module, "removeIntentTask", request.optLong("id")); break;
                    case "queryTimedTasks": value = invokeModule(module, "queryTimedTasks", options); break;
                    case "queryIntentTasks": value = invokeModule(module, "queryIntentTasks", options); break;
                    case "getAllTasks": value = invokeModule(module, "getAllTasks", null); break;
                    case "getAllIntentTasks": value = invokeModule(module, "getAllIntentTasks", null); break;
                    case "getTaskCount": value = invokeModule(module, "getTaskCount", null); break;
                    default: throw new IllegalArgumentException("work_manager 不支持：" + action);
                }
                result = new org.json.JSONObject().put("ok", true).put("value", toJsonValue(value)).toString();
            } catch (Throwable error) {
                Log.w(TAG, "work command failed", error);
                try {
                    result = new org.json.JSONObject().put("ok", false)
                            .put("error", String.valueOf(error.getCause() == null ? error.getMessage() : error.getCause().getMessage())).toString();
                } catch (Throwable ignored) { result = "{\"ok\":false,\"error\":\"work_manager 失败\"}"; }
            }
            Bundle bundle = new Bundle();
            bundle.putString(NodeUiProtocol.K_RESULT, result);
            reply(replyTo, NodeUiProtocol.MSG_WORK, "", true, null, bundle);
        });
    }

    private static Object invokeModule(Object module, String method, Object arg) throws Exception {
        java.lang.reflect.Method target = null;
        for (java.lang.reflect.Method candidate : module.getClass().getMethods()) {
            if (candidate.getName().equals(method) && candidate.getParameterCount() == (arg == null ? 0 : 1)) {
                target = candidate;
                break;
            }
        }
        if (target == null) throw new NoSuchMethodException(method);
        return arg == null ? target.invoke(module) : target.invoke(module, arg);
    }

    private static Object toJsonValue(Object value) throws Exception {
        if (value == null) return org.json.JSONObject.NULL;
        if (value instanceof Number || value instanceof Boolean || value instanceof String) return value;
        if (value instanceof java.util.List) {
            org.json.JSONArray array = new org.json.JSONArray();
            for (Object item : (java.util.List<?>) value) { array.put(taskToJson(item)); }
            return array;
        }
        return taskToJson(value);
    }

    private static org.json.JSONObject taskToJson(Object task) throws Exception {
        org.json.JSONObject json = new org.json.JSONObject();
        for (java.lang.reflect.Method getter : task.getClass().getMethods()) {
            if (getter.getParameterCount() != 0) continue;
            String name = getter.getName();
            if (!name.startsWith("get") || "getClass".equals(name)) continue;
            try {
                Object fieldValue = getter.invoke(task);
                if (fieldValue == null || fieldValue instanceof Number || fieldValue instanceof Boolean
                        || fieldValue instanceof String) {
                    json.put(Character.toLowerCase(name.charAt(3)) + name.substring(4), fieldValue == null ? org.json.JSONObject.NULL : fieldValue);
                }
            } catch (Throwable ignored) { }
        }
        if (task instanceof com.jdkshen.aijspro.timing.TimedTask) {
            org.json.JSONArray days = new org.json.JSONArray();
            for (int day = 1; day <= 7; day++) {
                if (((com.jdkshen.aijspro.timing.TimedTask) task).hasDayOfWeek(day)) days.put(day);
            }
            json.put("daysOfWeek", days);
        }
        return json;
    }

    /** task-142/task-157：主进程 WindowManager overlay（文本或 XML inflate）+ 触摸/拖动事件。 */
    private void handleOverlay(final String requestJson, final Messenger replyTo) {
        mMain.post(() -> {
            String result = "{\"ok\":false,\"error\":\"overlay 处理失败\"}";
            try {
                org.json.JSONObject request = new org.json.JSONObject(requestJson == null ? "{}" : requestJson);
                String action = request.optString("action", "show");
                android.view.WindowManager manager = (android.view.WindowManager)
                        getSystemService(android.content.Context.WINDOW_SERVICE);
                if ("show".equals(action)) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                            && !android.provider.Settings.canDrawOverlays(this)) {
                        throw new IllegalStateException("缺少悬浮窗权限，请在系统设置中允许「显示在其他应用上层」");
                    }
                    String xml = request.optString("xml", "").trim();
                    String text = request.optString("text", "Node24");
                    final String overlayId = "overlay-" + (sOverlaySeq++);
                    final android.widget.FrameLayout[] rootHolder = new android.widget.FrameLayout[1];
                    org.json.JSONObject viewsJson = new org.json.JSONObject();
                    android.view.View view;
                    android.view.View content;
                    if (!xml.isEmpty()) {
                        // task-199：工程布局含 Material 组件（MaterialCardView 等），必须用 Material 主题；
                        // NodeUiHost.wrapThemedContext 与 NodeUiActivity 使用同一套 Material3 主题解析。
                        android.content.Context themed = com.stardust.autojs.engine.node24.NodeUiHost
                                .wrapThemedContext(this, null);
                        content = com.stardust.autojs.engine.node24.NodeUiHost.inflateInlineLayout(themed, xml);
                        if (content == null) throw new IllegalStateException("floating_window XML inflate 结果为空");
                        android.widget.FrameLayout root = new android.widget.FrameLayout(this) {
                            @Override public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
                                recordOverlayTouch(overlayId, ev, rootHolder[0], OVERLAY_CONTENTS.get(overlayId));
                                return super.dispatchTouchEvent(ev);
                            }
                        };
                        rootHolder[0] = root;
                        // task-192：内容高度必须由内容自己决定；若给 MATCH_PARENT，首次 WRAP 测量
                        // 会按旧窗口高度截断，底部 input/ok 行落在窗口外点不到。
                        root.addView(content, new android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
                        view = root;
                        try {
                            Object views = com.stardust.autojs.engine.node24.NodeUiHost.buildInlineViews(content, xml);
                            if (views instanceof org.json.JSONObject) {
                                org.json.JSONObject object = (org.json.JSONObject) views;
                                java.util.Iterator<String> keys = object.keys();
                                while (keys.hasNext()) {
                                    String key = keys.next();
                                    viewsJson.put(key, object.optInt(key));
                                }
                            } else                             if (views instanceof java.util.Map) {
                                for (Object key : ((java.util.Map<?, ?>) views).keySet()) {
                                    Object value = ((java.util.Map<?, ?>) views).get(key);
                                    if (value instanceof Number) viewsJson.put(String.valueOf(key), ((Number) value).intValue());
                                }
                            }
                        } catch (Throwable ignored) { }
                    } else {
                        android.widget.TextView textView = new android.widget.TextView(this);
                        textView.setText(text);
                        textView.setTextSize(16);
                        textView.setTextColor(0xFFFFFFFF);
                        textView.setBackgroundColor(0xCC222222);
                        int pad = Math.round(12 * getResources().getDisplayMetrics().density);
                        textView.setPadding(pad, pad, pad, pad);
                        content = textView;
                        view = textView;
                    }
                    final android.view.View overlayContent = content;
                    int width = request.optInt("width", 0);
                    int height = request.optInt("height", 0);
                    android.view.WindowManager.LayoutParams params = new android.view.WindowManager.LayoutParams(
                            width > 0 ? width : android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                            height > 0 ? height : android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                                    ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                    : android.view.WindowManager.LayoutParams.TYPE_PHONE,
                            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    // task-168：浮窗是辅助窗，不能让遮挡区域把 AI 球/菜单的触摸吞掉；
                                    // 即使 JS focusableOnTouch 移除 NOT_FOCUSABLE，也保持非模态。
                                    | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                    | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            android.graphics.PixelFormat.TRANSLUCENT);
                    params.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                    params.flags |= request.optInt("flags", 0);
                    // task-169：AI 球菜单已展开时，新浮窗默认不可触摸，触摸让给菜单（防止 Node 浮窗叠在上层吞 ⏹）。
                    try {
                        if (com.jdkshen.aijspro.ui.floating.CircularMenuWindow.isNodeOverlayMenuExpanded()) {
                            params.flags |= android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                        }
                    } catch (Throwable ignored) { }
                    params.x = request.optInt("x", 100);
                    params.y = request.optInt("y", 200);
                    manager.addView(view, params);
                    // task-193：首次 measure 可能恰好等于旧高度，但子控件（按钮）实际 bottom 已溢出；
                    // 等真实布局完成后按内容 bottom 重新计算窗口高度，持续补偿到覆盖所有子控件。
                    final java.util.concurrent.atomic.AtomicBoolean remeasured = new java.util.concurrent.atomic.AtomicBoolean(false);
                    final Runnable remeasure = () -> {
                        if (!remeasured.compareAndSet(false, true)) return;
                        try {
                            int screenHeight = getResources().getDisplayMetrics().heightPixels;
                            int maxAvailable = screenHeight - params.y;
                            try {
                                if (android.os.Build.VERSION.SDK_INT >= 30) {
                                    android.view.WindowManager wm = (android.view.WindowManager)
                                            getSystemService(android.content.Context.WINDOW_SERVICE);
                                    android.view.WindowMetrics metrics = wm.getCurrentWindowMetrics();
                                    android.graphics.Insets insets = metrics.getWindowInsets()
                                            .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars()
                                                    | android.view.WindowInsets.Type.displayCutout());
                                    maxAvailable = metrics.getBounds().height() - params.y - insets.bottom;
                                }
                            } catch (Throwable ignored) { }
                            if (maxAvailable < 200) {
                                maxAvailable = Math.max(200, screenHeight - params.y - 80);
                            }
                            int maxWidth = getResources().getDisplayMetrics().widthPixels;
                            int widthSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                                    maxWidth, android.view.View.MeasureSpec.AT_MOST);
                            // task-193/196：不能 UNSPECIFIED（新机把内容测成 3052 高，窗口大半在屏外）；
                            // 用 AT_MOST(可用屏高) 量，最终 h = min(内容需要, 屏内可用)。
                            int heightSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                                    maxAvailable, android.view.View.MeasureSpec.AT_MOST);
                            overlayContent.measure(widthSpec, heightSpec);
                            int contentHeight = overlayContent.getMeasuredHeight();
                            int currentHeight = view.getHeight();
                            int needed = Math.min(Math.max(currentHeight, contentHeight), maxAvailable);
                            if (needed > 0 && needed != currentHeight) {
                                params.height = needed;
                                view.requestLayout();
                                manager.updateViewLayout(view, params);
                                Log.i(TAG, "overlay remeasure id=" + overlayId
                                        + " screenH=" + screenHeight + " maxAvailable=" + maxAvailable
                                        + " currentH=" + currentHeight + " contentH=" + contentHeight
                                        + " h=" + needed);
                            } else {
                                Log.i(TAG, "overlay remeasure id=" + overlayId
                                        + " screenH=" + screenHeight + " maxAvailable=" + maxAvailable
                                        + " currentH=" + currentHeight + " contentH=" + contentHeight
                                        + " h=unchanged");
                            }
                        } catch (Throwable error) {
                            Log.w(TAG, "overlay remeasure failed id=" + overlayId, error);
                        }
                    };
                    view.postDelayed(remeasure, 250L);
                    view.getViewTreeObserver().addOnGlobalLayoutListener(
                            new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                                @Override public void onGlobalLayout() {
                                    try {
                                        view.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                                    } catch (Throwable ignored) { }
                                    view.postDelayed(remeasure, 60L);
                                }
                            });
                    OVERLAYS.put(overlayId, view);
                    OVERLAY_PARAMS.put(overlayId, params);
                    OVERLAY_OWNERS.put(overlayId, request.optString("owner", ""));
                    OVERLAY_CONTENTS.put(overlayId, content);
                    OVERLAY_EVENTS.put(overlayId, new java.util.ArrayDeque<>());
                    result = new org.json.JSONObject().put("ok", true).put("overlayId", overlayId)
                            .put("text", text).put("x", params.x).put("y", params.y)
                            .put("views", viewsJson).toString();
                    try { LAST_OVERLAY_INFO = new org.json.JSONObject(result); } catch (Throwable ignored) { }
                    Log.i(TAG, "overlay show id=" + overlayId + " xml=" + !xml.isEmpty() + " views=" + viewsJson);
                } else if ("info".equals(action)) {
                    // task-200：show 的 15s 超时竞态下，客户端用 info 找回最近 overlay 结果。
                    org.json.JSONObject info = LAST_OVERLAY_INFO;
                    if (info == null) throw new IllegalStateException("还没有 overlay show 结果");
                    result = info.toString();
                } else if ("viewCommand".equals(action)) {
                    String id = request.optString("overlayId", "");
                    Log.i(TAG, "C-OVERLAY viewCommand overlay=" + id + " command=" + request.optString("command", "")
                            + " viewId=" + request.optInt("viewId", 0));
                    int viewId = request.optInt("viewId", 0);
                    android.view.View content = OVERLAY_CONTENTS.get(id);
                    android.view.View target = content == null || viewId == 0 ? null
                            : com.stardust.autojs.engine.node24.NodeUiHost.findInlineViewById(content, viewId);
                    if (target == null) throw new IllegalStateException("找不到浮窗控件：" + viewId);
                    String command = request.optString("command", "");
                    org.json.JSONObject commandResult = new org.json.JSONObject().put("ok", true);
                    switch (command) {
                        case "setText":
                            if (target instanceof android.widget.TextView) ((android.widget.TextView) target).setText(request.optString("value", ""));
                            break;
                        case "getText":
                            commandResult.put("value", target instanceof android.widget.TextView
                                    ? String.valueOf(((android.widget.TextView) target).getText()) : "");
                            break;
                        case "setVisibility":
                            target.setVisibility(request.optInt("value", 0));
                            // task-200：详情展开等高度变化后让 overlay 重新量高/relayout。
                            try {
                                android.view.View root = OVERLAYS.get(id);
                                android.view.View contentView = OVERLAY_CONTENTS.get(id);
                                if (root != null) { root.requestLayout(); }
                                if (contentView != null) { contentView.requestLayout(); }
                            } catch (Throwable ignored) { }
                            break;
                        case "setClickable":
                            target.setClickable(request.optBoolean("value", true));
                            break;
                        case "setEnabled": target.setEnabled(request.optBoolean("value", true)); break;
                        case "setChecked":
                            if (target instanceof android.widget.CompoundButton) ((android.widget.CompoundButton) target).setChecked(request.optBoolean("value", false));
                            break;
                        case "getChecked":
                            commandResult.put("value", target instanceof android.widget.CompoundButton && ((android.widget.CompoundButton) target).isChecked());
                            break;
                        case "click": target.performClick(); break;
                        case "setBackgroundColor": target.setBackgroundColor(request.optInt("value", 0)); break;
                        default: throw new IllegalArgumentException("浮窗控件不支持：" + command);
                    }
                    result = commandResult.toString();
                } else if ("enableDrag".equals(action)) {
                    OVERLAY_DRAG_ANCHORS.put(request.optString("overlayId", ""), request.optInt("viewId", 0));
                    Log.i(TAG, "C-OVERLAY enableDrag overlay=" + request.optString("overlayId", "")
                            + " viewId=" + request.optInt("viewId", 0));
                    result = new org.json.JSONObject().put("ok", true).toString();
                } else if ("move".equals(action)) {
                    String id = request.optString("overlayId", "");
                    android.view.WindowManager.LayoutParams params = OVERLAY_PARAMS.get(id);
                    if (params != null) {
                        params.x = request.optInt("x", params.x);
                        params.y = request.optInt("y", params.y);
                        android.view.View view = OVERLAYS.get(id);
                        if (view != null) manager.updateViewLayout(view, params);
                    }
                    result = new org.json.JSONObject().put("ok", true).toString();
                } else if ("addFlags".equals(action) || "removeFlags".equals(action) || "setFlags".equals(action)) {
                    String id = request.optString("overlayId", "");
                    android.view.WindowManager.LayoutParams params = OVERLAY_PARAMS.get(id);
                    int bits = request.optInt("flags", 0);
                    if (params != null) {
                        if ("addFlags".equals(action)) params.flags |= bits;
                        else if ("removeFlags".equals(action)) params.flags &= ~bits;
                        else params.flags = bits;
                        android.view.View view = OVERLAYS.get(id);
                        if (view != null) manager.updateViewLayout(view, params);
                    }
                    result = new org.json.JSONObject().put("ok", true).toString();
                } else if ("pollEvents".equals(action)) {
                    String id = request.optString("overlayId", "");
                    org.json.JSONArray events = new org.json.JSONArray();
                    java.util.ArrayDeque<org.json.JSONObject> queue = OVERLAY_EVENTS.get(id);
                    if (queue != null) synchronized (queue) { while (!queue.isEmpty()) events.put(queue.removeFirst()); }
                    result = new org.json.JSONObject().put("ok", true).put("events", events).toString();
                } else if ("close".equals(action)) {
                    String id = request.optString("overlayId", "");
                    android.view.View view = OVERLAYS.remove(id);
                    if (view != null) {
                        try { manager.removeView(view); } catch (Throwable ignored) { }
                        OVERLAY_PARAMS.remove(id);
                        OVERLAY_CONTENTS.remove(id);
                        OVERLAY_EVENTS.remove(id);
                        OVERLAY_DRAG_ANCHORS.remove(id);
                    }
                    result = new org.json.JSONObject().put("ok", true).put("closed", view != null).toString();
                } else if ("closeAll".equals(action)) {
                    int closed = 0;
                    String owner = request.optString("owner", "");
                    java.util.List<String> ids = new java.util.ArrayList<>(OVERLAYS.keySet());
                    for (String id : ids) {
                        String overlayOwner = OVERLAY_OWNERS.get(id);
                        if (!owner.isEmpty() && overlayOwner != null && !owner.equals(overlayOwner)) {
                            continue; // task-183：只关闭本 owner 的 overlay
                        }
                        android.view.View v = OVERLAYS.remove(id);
                        if (v != null) { try { manager.removeView(v); closed++; } catch (Throwable ignored) { } }
                        OVERLAY_PARAMS.remove(id);
                        OVERLAY_CONTENTS.remove(id);
                        OVERLAY_EVENTS.remove(id);
                        OVERLAY_DRAG_ANCHORS.remove(id);
                        OVERLAY_OWNERS.remove(id);
                    }
                    result = new org.json.JSONObject().put("ok", true).put("closed", closed).toString();
                } else {
                    throw new IllegalArgumentException("overlay 不支持：action=" + action);
                }
            } catch (Throwable error) {
                Log.w(TAG, "overlay failed", error);
                result = "{\"ok\":false,\"error\":\"" + escape(String.valueOf(error.getMessage())) + "\"}";
            }
            Bundle bundle = new Bundle();
            bundle.putString(NodeUiProtocol.K_RESULT, result);
            reply(replyTo, NodeUiProtocol.MSG_OVERLAY, "", true, null, bundle);
        });
    }

    /** task-157：触摸/点击/拖拽事件回传（JSON 队列，pollEvents 拉取）。 */
    /**
     * task-193：按屏幕实际坐标计算所有非 ConsoleView 后代的 bottom（相对 root 顶部）。
     * 不再递归 getTop/getBottom（weighted ConsoleView 会把行推到异常高度），
     * 而是跳过 console 子树后取真实绘制位置，得到按钮底部的 52px 溢出。
     */
    private int maxNonConsoleContentBottom(android.view.View root) {
        if (root == null) return 0;
        int[] rootLoc = new int[2];
        root.getLocationOnScreen(rootLoc);
        return maxNonConsoleBottom(root, rootLoc[1]);
    }

    private int maxNonConsoleBottom(android.view.View view, int rootTop) {
        if (view == null || view.getVisibility() == android.view.View.GONE) return 0;
        if (view instanceof com.stardust.autojs.core.console.ConsoleView) return 0;
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        int max = loc[1] - rootTop + view.getHeight();
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                max = Math.max(max, maxNonConsoleBottom(group.getChildAt(i), rootTop));
            }
        }
        return max;
    }

    private void recordOverlayTouch(String overlayId, android.view.MotionEvent ev, android.view.View root, android.view.View content) {
        try {
            if (root == null || content == null) return;
            // task-199 调试：确认 overlay 是否收到触摸（drag/click 事件链路）。
            if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                    || ev.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                Log.i(TAG, "C-OVERLAY touch overlay=" + overlayId + " action=" + ev.getActionMasked()
                        + " raw=" + Math.round(ev.getRawX()) + "," + Math.round(ev.getRawY())
                        + " flags=" + (root.getWindowToken() == null ? "no-token" : "ok"));
            }
            java.util.ArrayDeque<org.json.JSONObject> queue = OVERLAY_EVENTS.get(overlayId);
            if (queue == null) return;
            int action = ev.getActionMasked();
            float rawX = ev.getRawX(), rawY = ev.getRawY();
            String type = action == android.view.MotionEvent.ACTION_DOWN ? "down"
                    : action == android.view.MotionEvent.ACTION_MOVE ? "move"
                    : action == android.view.MotionEvent.ACTION_UP ? "up"
                    : action == android.view.MotionEvent.ACTION_CANCEL ? "cancel" : "outside";
            org.json.JSONObject event = new org.json.JSONObject().put("type", "touch")
                    .put("overlayId", overlayId).put("action", type)
                    .put("x", rawX).put("y", rawY).put("time", ev.getEventTime());
            Integer anchorId = OVERLAY_DRAG_ANCHORS.get(overlayId);
            int rawXInt = Math.round(rawX), rawYInt = Math.round(rawY);
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                OVERLAY_DOWN.put(overlayId, new float[]{rawX, rawY, ev.getEventTime()});
                int hit = findClickableViewId(content, rawX, rawY);
                if (hit != 0) event.put("viewId", hit);
            } else if (action == android.view.MotionEvent.ACTION_MOVE && anchorId != null && anchorId != 0) {
                float[] down = OVERLAY_DOWN.get(overlayId);
                boolean inAnchor = down != null && inViewBounds(content, anchorId, down[0], down[1]);
                Log.i(TAG, "C-OVERLAY drag anchor=" + anchorId + " raw=" + rawXInt + "," + rawYInt
                        + " down=" + (down == null ? "null" : (Math.round(down[0]) + "," + Math.round(down[1])))
                        + " inAnchor=" + inAnchor);
                if (inAnchor) {
                    android.view.WindowManager.LayoutParams params = OVERLAY_PARAMS.get(overlayId);
                    android.view.WindowManager manager = (android.view.WindowManager) getSystemService(android.content.Context.WINDOW_SERVICE);
                    if (params != null) {
                        params.x = Math.round(params.x + (rawX - down[0]));
                        params.y = Math.round(params.y + (rawY - down[1]));
                        down[0] = rawX; down[1] = rawY;
                        android.view.View view = OVERLAYS.get(overlayId);
                        if (view != null) manager.updateViewLayout(view, params);
                        event.put("type", "drag").put("x", params.x).put("y", params.y);
                        Log.i(TAG, "C-OVERLAY drag move x=" + params.x + " y=" + params.y);
                    }
                }
            } else if (action == android.view.MotionEvent.ACTION_UP) {
                float[] down = OVERLAY_DOWN.remove(overlayId);
                if (down != null) {
                    boolean moved = Math.abs(rawX - down[0]) + Math.abs(rawY - down[1]) > 24;
                    if (!moved) {
                        int hit = findClickableViewId(content, rawX, rawY);
                        if (hit == 0) hit = findClickableViewId(content, down[0], down[1]);
                        if (hit != 0) {
                            Log.i(TAG, "C-OVERLAY click overlay=" + overlayId + " hitView=" + hit
                                    + " raw=" + rawXInt + "," + rawYInt);
                            queue.addLast(new org.json.JSONObject().put("type", "click")
                                    .put("overlayId", overlayId).put("viewId", hit).put("x", rawX).put("y", rawY));
                        } else {
                            Log.i(TAG, "C-OVERLAY click miss overlay=" + overlayId
                                    + " raw=" + rawXInt + "," + rawYInt);
                        }
                    }
                }
            }
            synchronized (queue) {
                queue.addLast(event);
                while (queue.size() > 200) queue.removeFirst();
            }
        } catch (Throwable ignored) { }
    }

    /** task-164：优先返回可点击 view；没有可点击节点时回退到命中区域内最深的带 id view。
     * 示例里的 <img id="close"> 不是 clickable（ImageView 默认 false），旧实现永远返回 0，
     * 导致 binding.close.on("click") 收不到事件。 */
    private static int findClickableViewId(android.view.View root, float rawX, float rawY) {
        int clickable = findViewIdAt(root, rawX, rawY, true);
        return clickable != 0 ? clickable : findViewIdAt(root, rawX, rawY, false);
    }

    private static int findViewIdAt(android.view.View root, float rawX, float rawY, boolean requireClickable) {
        if (root == null || !root.isShown()) return 0;
        int[] location = new int[2];
        root.getLocationOnScreen(location);
        if (rawX < location[0] || rawY < location[1]
                || rawX > location[0] + root.getWidth() || rawY > location[1] + root.getHeight()) return 0;
        if (root.getVisibility() == android.view.View.VISIBLE
                && root.getId() != android.view.View.NO_ID
                && (!requireClickable || root.isClickable())) {
            return root.getId();
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                int hit = findViewIdAt(group.getChildAt(i), rawX, rawY, requireClickable);
                if (hit != 0) return hit;
            }
        }
        return 0;
    }

    private static boolean inViewBounds(android.view.View root, int viewId, float rawX, float rawY) {
        android.view.View target = root == null ? null : root.findViewById(viewId);
        if (target == null || !target.isShown()) return false;
        int[] location = new int[2];
        target.getLocationOnScreen(location);
        return rawX >= location[0] && rawY >= location[1]
                && rawX <= location[0] + target.getWidth() && rawY <= location[1] + target.getHeight();
    }

    private void showDialog(final String session, final String requestJson, final Messenger replyTo) {
        mMain.post(() -> {
            try {
                DIALOG_SESSIONS.put(session, replyTo);
                Intent intent = new Intent(NodeUiBridgeService.this, NodeUiDialogActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                intent.putExtra(NodeUiProtocol.K_SESSION, session);
                intent.putExtra(NodeUiProtocol.K_REQUEST, requestJson);
                startActivity(intent);
                Log.i(TAG, "dialog shown session=" + session + " request=" + requestJson);
            } catch (Throwable error) {
                DIALOG_SESSIONS.remove(session);
                Bundle bundle = new Bundle();
                bundle.putString(NodeUiProtocol.K_RESULT, "{\"ok\":false,\"error\":\"无法打开对话框：" + escape(String.valueOf(error.getMessage())) + "\"}");
                reply(replyTo, NodeUiProtocol.MSG_DIALOG, session, false, null, bundle);
            }
        });
    }

    /** NodeUiDialogActivity 用户操作后回调（同进程静态入口）。 */
    public static void reportDialogResult(String session, String resultJson) {
        NodeUiBridgeService instance = sDialogInstance;
        if (instance == null) return;
        instance.mMain.post(() -> {
            Messenger target = DIALOG_SESSIONS.remove(session);
            if (target == null) return;
            Bundle bundle = new Bundle();
            bundle.putString(NodeUiProtocol.K_RESULT, resultJson == null ? "{}" : resultJson);
            instance.reply(target, NodeUiProtocol.MSG_DIALOG, session, true, null, bundle);
        });
    }

    @Override public void onCreate() {
        super.onCreate();
        sInstance = this;
        sDialogInstance = this;
        installNodeJobReceiver();
        queryNodeJobs();
        try {
            NodeUiActivity.setCloseListener(session -> dispatchClosed(session));
        } catch (Throwable error) {
            Log.w(TAG, "setCloseListener failed", error);
        }
    }

    @Override public void onDestroy() {
        uninstallNodeJobReceiver();
        // task-164：服务销毁时清掉所有 floating overlay，避免 :node24 异常退出后浮窗常驻。
        try {
            android.view.WindowManager manager = (android.view.WindowManager)
                    getSystemService(android.content.Context.WINDOW_SERVICE);
            for (Map.Entry<String, android.view.View> entry : OVERLAYS.entrySet()) {
                try { manager.removeView(entry.getValue()); } catch (Throwable ignored) { }
            }
            OVERLAYS.clear(); OVERLAY_PARAMS.clear(); OVERLAY_CONTENTS.clear();
            OVERLAY_EVENTS.clear(); OVERLAY_DRAG_ANCHORS.clear();
        } catch (Throwable error) {
            Log.w(TAG, "onDestroy clear overlays failed", error);
        }
        mWork.shutdown();
        sInstance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return mMessenger.getBinder(); }

    private void show(final String session, Bundle data, final Messenger replyTo) {
        final String projectDir = data.getString(NodeUiProtocol.K_PROJECT, "");
        final String layoutName = data.getString(NodeUiProtocol.K_LAYOUT_NAME, "");
        final int layoutId = data.getInt(NodeUiProtocol.K_LAYOUT_ID, 0);
        final String title = data.getString(NodeUiProtocol.K_TITLE, "");
        final String className = data.getString(NodeUiProtocol.K_CLASS, "");
        mMain.post(() -> {
            try {
                // task-94：singleTask 复用；同一 session 已显示时不再重复 startActivity。
                NodeUiActivity current = NodeUiActivity.getCurrent();
                if (current != null && !current.isFinishing() && !current.isDestroyed()) {
                    Intent existing = current.getIntent();
                    String currentSession = existing == null ? "" : existing.getStringExtra(NodeUiProtocol.K_SESSION);
                    if (session != null && session.equals(currentSession)) {
                        reply(replyTo, NodeUiProtocol.MSG_RESULT, session, true, null, new Bundle());
                        return;
                    }
                }
                if (session != null) {
                    SESSION_PROJECTS.put(session, projectDir == null ? "" : projectDir);
                    SESSION_JOBS.remove(session);
                    String jobId = findNodeJobForProject(projectDir);
                    if (jobId != null) SESSION_JOBS.put(session, jobId);
                }
                Intent intent = new Intent();
                intent.setClassName(NodeUiBridgeService.this, NodeUiProtocol.ACTIVITY_CLASS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                intent.putExtra(NodeUiProtocol.K_SESSION, session);
                intent.putExtra(NodeUiProtocol.K_PROJECT, projectDir);
                intent.putExtra(NodeUiProtocol.K_LAYOUT_NAME, layoutName);
                intent.putExtra(NodeUiProtocol.K_LAYOUT_ID, layoutId);
                intent.putExtra(NodeUiProtocol.K_TITLE, title);
                intent.putExtra(NodeUiProtocol.K_CLASS, className);
                startActivity(intent);
                Log.i(TAG, "showActivity session=" + session + " project=" + projectDir
                        + " layout=" + layoutName + " id=" + layoutId + " class=" + className);
                reply(replyTo, NodeUiProtocol.MSG_RESULT, session, true, null,
                        new Bundle());
            } catch (Throwable error) {
                Log.e(TAG, "showActivity failed", error);
                reply(replyTo, NodeUiProtocol.MSG_RESULT, session, false,
                        String.valueOf(error.getMessage()), new Bundle());
            }
        });
    }

    /** task-135 Phase2：View 句柄命令转发给 NodeUiActivity.handleView（主线程，反射调用避免类型耦合）。 */
    private void handleView(final String requestJson, final Messenger replyTo, final String session) {
        mMain.post(() -> {
            String result = "{\"ok\":false,\"error\":\"NodeUiActivity 未打开\"}";
            try {
                Activity current = NodeUiActivity.getCurrent();
                if (current != null) {
                    Method method = current.getClass().getMethod("handleView", String.class);
                    Object value = method.invoke(current, requestJson);
                    if (value != null) result = String.valueOf(value);
                }
            } catch (Throwable error) {
                Log.w(TAG, "view command failed", error);
                result = "{\"ok\":false,\"error\":\"" + escape(String.valueOf(error.getMessage())) + "\"}";
            }
            Bundle bundle = new Bundle();
            bundle.putString(NodeUiProtocol.K_RESULT, result);
            reply(replyTo, NodeUiProtocol.MSG_UPDATE, session, true, null, bundle);
        });
    }

    private void close(String session) {
        mMain.post(() -> {
            try {
                Activity current = NodeUiActivity.getCurrent();
                if (current != null && !current.isFinishing()) current.finish();
            } catch (Throwable error) {
                Log.w(TAG, "close activity failed", error);
            }
        });
    }

    private void update(String session, Bundle data, Messenger replyTo) {
        final String requestJson = data.getString(NodeUiProtocol.K_REQUEST, "{}");
        mMain.post(() -> {
            String result = "{\"ok\":false,\"error\":\"NodeUiActivity.handleUpdate 尚未实现\"}";
            try {
                Object activity = NodeUiActivity.getCurrent();
                if (activity != null) {
                    Method method = activity.getClass().getMethod("handleUpdate", JSONObject.class);
                    Object value = method.invoke(activity, new JSONObject(requestJson));
                    if (value != null) result = String.valueOf(value);
                } else {
                    result = "{\"ok\":false,\"error\":\"没有打开的 Node UI Activity\"}";
                }
            } catch (Throwable error) {
                Log.w(TAG, "update failed", error);
                result = "{\"ok\":false,\"error\":\"" + escape(String.valueOf(error.getMessage())) + "\"}";
            }
            Bundle bundle = new Bundle();
            bundle.putString(NodeUiProtocol.K_RESULT, result);
            reply(replyTo, NodeUiProtocol.MSG_UPDATE, session, true, null, bundle);
        });
    }

    private void dispatchClosed(String session) {
        Messenger target = SESSIONS.remove(session);
        Bundle data = new Bundle();
        data.putString(NodeUiProtocol.K_EVENT, NodeUiProtocol.EVENT_DESTROY);
        data.putString(NodeUiProtocol.K_PAYLOAD, "{}");
        reply(target, NodeUiProtocol.MSG_EVENT, session, true, null, data);
    }

    private void reply(Messenger target, int what, String session, boolean success, String error,
                       Bundle extra) {
        if (target == null) return;
        try {
            Message message = Message.obtain(null, what);
            Bundle data = new Bundle(extra == null ? new Bundle() : extra);
            data.putString(NodeUiProtocol.K_SESSION, session);
            data.putBoolean(NodeUiProtocol.K_SUCCESS, success);
            if (error != null) data.putString(NodeUiProtocol.K_ERROR, error);
            message.setData(data);
            target.send(message);
        } catch (RemoteException error2) {
            Log.w(TAG, "reply failed", error2);
        }
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ------------------------------------------------------------------
    // task-178：Node UI 返回键 = 结束对应 Node execution
    // ------------------------------------------------------------------

    /**
     * NodeUiActivity.onBackPressed 的同进程入口：先按 session/projectDir 找到 :node24 的 active job，
     * 再下发 cancel；Activity 随后正常 finish，onDestroy 的 CloseListener 继续负责 destroy 事件。
     */
    public static void requestStopForBack(String session, String projectDir) {
        NodeUiBridgeService service = sInstance;
        if (service == null) {
            Log.w(TAG, "C-BACK: bridge service 不存在，无法 cancel Node job（Activity 仍会关闭）");
            return;
        }
        service.mMain.post(() -> service.stopNodeJobForBack(session, projectDir));
    }

    private void stopNodeJobForBack(String session, String projectDir) {
        String jobId = resolveNodeJobForBack(session, projectDir);
        if (jobId == null) {
            // show 时 started 广播可能尚未到主进程：主动 query 一次，短延迟后重试。
            queryNodeJobs();
            final String retrySession = session;
            final String retryProject = projectDir;
            mMain.postDelayed(() -> {
                String retry = resolveNodeJobForBack(retrySession, retryProject);
                if (retry != null) {
                    cancelNodeJob(retry, retrySession);
                } else {
                    Log.w(TAG, "C-BACK: 未找到对应 Node job（session=" + retrySession
                            + " project=" + retryProject + "），依赖 Node24Service UI close 兜底");
                }
            }, 350L);
            return;
        }
        cancelNodeJob(jobId, session);
    }

    private String resolveNodeJobForBack(String session, String projectDir) {
        String mapped = session == null ? null : SESSION_JOBS.get(session);
        if (mapped != null && NODE_JOBS.containsKey(mapped)) {
            return mapped;
        }
        String dir = projectDir;
        if ((dir == null || dir.isEmpty()) && session != null) {
            dir = SESSION_PROJECTS.get(session);
        }
        String found = findNodeJobForProject(dir);
        if (found != null && session != null) {
            SESSION_JOBS.put(session, found);
        }
        return found;
    }

    /** 按 projectDir 匹配 filename 的父目录，选最近启动的 uiScript job；匹配不到时宁可不 cancel 也不误杀。 */
    private static String findNodeJobForProject(String projectDir) {
        NodeJob best = null;
        for (NodeJob job : NODE_JOBS.values()) {
            if (job == null || !job.uiScript) {
                continue;
            }
            boolean matched = projectDir == null || projectDir.isEmpty()
                    || matchesProject(job.filename, projectDir);
            if (matched && (best == null || job.startedAt > best.startedAt)) {
                best = job;
            }
        }
        return best == null ? null : best.id;
    }

    private static boolean matchesProject(String filename, String projectDir) {
        if (filename == null || filename.isEmpty() || projectDir == null || projectDir.isEmpty()) {
            return false;
        }
        try {
            File script = new File(filename).getCanonicalFile();
            File project = new File(projectDir).getCanonicalFile();
            File parent = script.getParentFile();
            return parent != null && (parent.equals(project)
                    || parent.getPath().startsWith(project.getPath() + File.separator));
        } catch (Throwable ignored) {
            return filename.startsWith(projectDir);
        }
    }

    private void cancelNodeJob(String jobId, String session) {
        if (jobId == null || jobId.isEmpty()) {
            return;
        }
        if (session != null) {
            SESSION_JOBS.remove(session);
        }
        NODE_JOBS.remove(jobId);
        Intent intent = new Intent(Node24Service.ACTION_NODE24_CONTROL)
                .setPackage(getPackageName());
        intent.putExtra(Node24Service.EXTRA_COMMAND, "cancel");
        intent.putExtra(Node24Service.EXTRA_ID, jobId);
        try {
            sendBroadcast(intent);
            Log.i(TAG, "C-BACK: cancel Node job id=" + jobId + " session=" + session);
        } catch (Throwable error) {
            Log.w(TAG, "C-BACK: send Node cancel failed id=" + jobId, error);
        }
    }

    private void queryNodeJobs() {
        try {
            Context context = getApplicationContext();
            if (context == null) {
                return;
            }
            Intent intent = new Intent(Node24Service.ACTION_NODE24_CONTROL)
                    .setPackage(context.getPackageName());
            intent.putExtra(Node24Service.EXTRA_COMMAND, "query");
            context.sendBroadcast(intent);
        } catch (Throwable error) {
            Log.w(TAG, "C-BACK: query Node jobs failed", error);
        }
    }

    /** 广播维护 registry；晚到的 started 会补绑已有 session（show 早于广播时仍能按返回取消）。 */
    private void installNodeJobReceiver() {
        if (mNodeStateReceiver != null) {
            return;
        }
        mNodeStateReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }
                String state = intent.getStringExtra(Node24Service.EXTRA_STATE);
                String id = intent.getStringExtra(Node24Service.EXTRA_ID);
                if (state == null || id == null || id.isEmpty()) {
                    return;
                }
                if ("started".equals(state)) {
                    NodeJob job = new NodeJob(id,
                            intent.getStringExtra(Node24Service.EXTRA_FILENAME),
                            intent.getBooleanExtra(Node24Service.EXTRA_UI_SCRIPT, false),
                            intent.getLongExtra(Node24Service.EXTRA_STARTED_AT,
                                    System.currentTimeMillis()));
                    NODE_JOBS.put(id, job);
                    for (Map.Entry<String, String> entry : SESSION_PROJECTS.entrySet()) {
                        if (!SESSION_JOBS.containsKey(entry.getKey())
                                && matchesProject(job.filename, entry.getValue())) {
                            SESSION_JOBS.put(entry.getKey(), id);
                        }
                    }
                    Log.i(TAG, "C-BACK: node job started id=" + id
                            + " uiScript=" + job.uiScript + " file=" + job.filename);
                } else {
                    NODE_JOBS.remove(id);
                    for (Map.Entry<String, String> entry : SESSION_JOBS.entrySet()) {
                        if (id.equals(entry.getValue())) {
                            SESSION_JOBS.remove(entry.getKey());
                        }
                    }
                    Log.i(TAG, "C-BACK: node job removed id=" + id + " state=" + state);
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter(Node24Service.ACTION_NODE24_STATE);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(mNodeStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(mNodeStateReceiver, filter);
            }
            Log.i(TAG, "C-BACK: Node job registry installed");
        } catch (Throwable error) {
            Log.w(TAG, "C-BACK: register Node state receiver failed", error);
            mNodeStateReceiver = null;
        }
    }

    private void uninstallNodeJobReceiver() {
        if (mNodeStateReceiver == null) {
            return;
        }
        try {
            unregisterReceiver(mNodeStateReceiver);
        } catch (Throwable ignored) {
        }
        mNodeStateReceiver = null;
    }

    public static void startInMain(Context context) {
        try {
            context.startService(new Intent(context, NodeUiBridgeService.class));
        } catch (Throwable error) {
            Log.w(TAG, "start service failed", error);
        }
    }
}
