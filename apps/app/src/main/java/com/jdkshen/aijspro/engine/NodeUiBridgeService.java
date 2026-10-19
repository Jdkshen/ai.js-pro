package com.jdkshen.aijspro.engine;

import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * task-76：主进程 Node UI Activity 的 Binder 服务（:node24 的 NodeUiActivityClient 绑定它）。
 *
 * <p>协议见 {@link NodeUiProtocol}：MSG_SHOW 启动 NodeUiActivity；MSG_UPDATE 转 Activity.handleUpdate；
 * MSG_CLOSE 关闭；Activity 关闭经 CloseListener 以 MSG_EVENT(type=destroy) 回 :node24。
 */
public class NodeUiBridgeService extends Service {

    private static final String TAG = "NodeUiBridgeService";
    private static final Map<String, Messenger> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, Messenger> DIALOG_SESSIONS = new ConcurrentHashMap<>();
    private static volatile NodeUiBridgeService sDialogInstance;
    private static final Map<String, android.view.View> OVERLAYS = new ConcurrentHashMap<>();
    private static final Map<String, android.view.WindowManager.LayoutParams> OVERLAY_PARAMS = new ConcurrentHashMap<>();
    private static int sOverlaySeq = 1;
    // task-157：XML overlay 内容/触摸事件/拖拽
    private static final Map<String, android.view.View> OVERLAY_CONTENTS = new ConcurrentHashMap<>();
    private static final Map<String, java.util.ArrayDeque<org.json.JSONObject>> OVERLAY_EVENTS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> OVERLAY_DRAG_ANCHORS = new ConcurrentHashMap<>();
    private static final Map<String, float[]> OVERLAY_DOWN = new ConcurrentHashMap<>();
    private static volatile NodeUiBridgeService sInstance;
    private final Handler mMain = new Handler(Looper.getMainLooper());
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
                        android.view.ContextThemeWrapper themed = new android.view.ContextThemeWrapper(
                                this, android.R.style.Theme_Material_Light_NoActionBar);
                        content = com.stardust.autojs.engine.node24.NodeUiHost.inflateInlineLayout(themed, xml);
                        if (content == null) throw new IllegalStateException("floating_window XML inflate 结果为空");
                        android.widget.FrameLayout root = new android.widget.FrameLayout(this) {
                            @Override public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
                                recordOverlayTouch(overlayId, ev, rootHolder[0], OVERLAY_CONTENTS.get(overlayId));
                                return super.dispatchTouchEvent(ev);
                            }
                        };
                        rootHolder[0] = root;
                        root.addView(content, new android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
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
                    int width = request.optInt("width", 0);
                    int height = request.optInt("height", 0);
                    android.view.WindowManager.LayoutParams params = new android.view.WindowManager.LayoutParams(
                            width > 0 ? width : android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                            height > 0 ? height : android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                                    ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                    : android.view.WindowManager.LayoutParams.TYPE_PHONE,
                            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            android.graphics.PixelFormat.TRANSLUCENT);
                    params.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                    params.flags |= request.optInt("flags", 0);
                    params.x = request.optInt("x", 100);
                    params.y = request.optInt("y", 200);
                    manager.addView(view, params);
                    OVERLAYS.put(overlayId, view);
                    OVERLAY_PARAMS.put(overlayId, params);
                    OVERLAY_CONTENTS.put(overlayId, content);
                    OVERLAY_EVENTS.put(overlayId, new java.util.ArrayDeque<>());
                    result = new org.json.JSONObject().put("ok", true).put("overlayId", overlayId)
                            .put("text", text).put("x", params.x).put("y", params.y)
                            .put("views", viewsJson).toString();
                    Log.i(TAG, "overlay show id=" + overlayId + " xml=" + !xml.isEmpty() + " views=" + viewsJson);
                } else if ("viewCommand".equals(action)) {
                    String id = request.optString("overlayId", "");
                    int viewId = request.optInt("viewId", 0);
                    android.view.View content = OVERLAY_CONTENTS.get(id);
                    android.view.View target = content == null || viewId == 0 ? null : content.findViewById(viewId);
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
                        case "setVisibility": target.setVisibility(request.optInt("value", 0)); break;
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
                    for (Map.Entry<String, android.view.View> entry : OVERLAYS.entrySet()) {
                        try { manager.removeView(entry.getValue()); closed++; } catch (Throwable ignored) { }
                    }
                    OVERLAYS.clear(); OVERLAY_PARAMS.clear(); OVERLAY_CONTENTS.clear();
                    OVERLAY_EVENTS.clear(); OVERLAY_DRAG_ANCHORS.clear();
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
    private void recordOverlayTouch(String overlayId, android.view.MotionEvent ev, android.view.View root, android.view.View content) {
        try {
            if (root == null || content == null) return;
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
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                OVERLAY_DOWN.put(overlayId, new float[]{rawX, rawY, ev.getEventTime()});
                int hit = findClickableViewId(content, rawX, rawY);
                if (hit != 0) event.put("viewId", hit);
            } else if (action == android.view.MotionEvent.ACTION_MOVE && anchorId != null && anchorId != 0) {
                float[] down = OVERLAY_DOWN.get(overlayId);
                if (down != null && inViewBounds(content, anchorId, down[0], down[1])) {
                    android.view.WindowManager.LayoutParams params = OVERLAY_PARAMS.get(overlayId);
                    android.view.WindowManager manager = (android.view.WindowManager) getSystemService(android.content.Context.WINDOW_SERVICE);
                    if (params != null) {
                        params.x = Math.round(params.x + (rawX - down[0]));
                        params.y = Math.round(params.y + (rawY - down[1]));
                        down[0] = rawX; down[1] = rawY;
                        android.view.View view = OVERLAYS.get(overlayId);
                        if (view != null) manager.updateViewLayout(view, params);
                        event.put("type", "drag").put("x", params.x).put("y", params.y);
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
                            queue.addLast(new org.json.JSONObject().put("type", "click")
                                    .put("overlayId", overlayId).put("viewId", hit).put("x", rawX).put("y", rawY));
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
        try {
            NodeUiActivity.setCloseListener(session -> dispatchClosed(session));
        } catch (Throwable error) {
            Log.w(TAG, "setCloseListener failed", error);
        }
    }

    @Override public void onDestroy() {
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

    public static void startInMain(Context context) {
        try {
            context.startService(new Intent(context, NodeUiBridgeService.class));
        } catch (Throwable error) {
            Log.w(TAG, "start service failed", error);
        }
    }
}
