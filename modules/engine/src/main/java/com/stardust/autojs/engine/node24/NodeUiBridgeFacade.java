package com.stardust.autojs.engine.node24;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Node24 对话框的异步事件通道。
 *
 * <p>主进程 {@code NodeUiDialogActivity} 的一次性结果仍走
 * {@code MSG_DIALOG}（{@link NodeUiActivityClient#dialog(JSONObject)} 同步等待）；但
 * {@code dialogs.buildDialog/showDialog} 必须“显示后立即返回”，之后的选择/输入/取消
 * 事件不能再用一次 JSON 结果表达。这里用应用内显式广播把事件送回 :node24：
 * <ul>
 *   <li>Activity -&gt; :node24：{@link #ACTION_DIALOG_EVENT}，入队后由 prelude 轮询
 *       {@link #pollEvents(String)} 取走，按 token 路由到 JS 的 Dialog 对象；</li>
 *   <li>:node24 -&gt; Activity：{@link #sendControl(Context, String, String, String)}，
 *       用于 {@code setProgress} / {@code dismiss}。</li>
 * </ul>
 *
 * <p>没有修改 NodeUiHost / NodeUiActivity / HostBridge；本类由 prelude 通过
 * {@code $autojs.java} 的普通 Java 反射调用。广播只在本应用内（setPackage +
 * API 33+ RECEIVER_NOT_EXPORTED），token 由 Node 进程随机生成，避免其它应用伪造事件。
 */
public final class NodeUiBridgeFacade {

    private static final String TAG = "NodeUiBridgeFacade";
    public static final String ACTION_DIALOG_EVENT =
            "com.jdkshen.aijspro.action.NODE24_DIALOG_EVENT";
    public static final String ACTION_DIALOG_CONTROL =
            "com.jdkshen.aijspro.action.NODE24_DIALOG_CONTROL";
    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_EVENT = "event";
    public static final String EXTRA_PAYLOAD = "payload";
    public static final String EXTRA_OP = "op";
    public static final String EXTRA_VALUE = "value";

    private static final int MAX_EVENTS = 256;
    private static final ConcurrentLinkedQueue<JSONObject> EVENTS = new ConcurrentLinkedQueue<>();
    private static final Object LOCK = new Object();
    private static BroadcastReceiver sReceiver;
    private static boolean sRegistered;

    private NodeUiBridgeFacade() {
    }

    /** 在 :node24 进程注册一次性事件接收器；可重复调用。 */
    public static boolean ensureEvents(Context context) {
        if (context == null) {
            return false;
        }
        synchronized (LOCK) {
            if (sRegistered) {
                return true;
            }
            try {
                final Context app = context.getApplicationContext();
                if (app == null) {
                    return false;
                }
                sReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context receiverContext, Intent intent) {
                        if (intent == null
                                || !ACTION_DIALOG_EVENT.equals(intent.getAction())) {
                            return;
                        }
                        String token = intent.getStringExtra(EXTRA_TOKEN);
                        String eventType = intent.getStringExtra(EXTRA_EVENT);
                        if (token == null || token.isEmpty()
                                || eventType == null || eventType.isEmpty()) {
                            return;
                        }
                        JSONObject event = new JSONObject();
                        try {
                            event.put("token", token);
                            event.put("type", eventType);
                            String rawPayload = intent.getStringExtra(EXTRA_PAYLOAD);
                            event.put("payload", rawPayload == null || rawPayload.isEmpty()
                                    ? new JSONObject() : new JSONObject(rawPayload));
                        } catch (Throwable error) {
                            Log.w(TAG, "忽略非法对话框事件 payload", error);
                            return;
                        }
                        while (EVENTS.size() >= MAX_EVENTS) {
                            EVENTS.poll();
                        }
                        EVENTS.add(event);
                    }
                };
                IntentFilter filter = new IntentFilter(ACTION_DIALOG_EVENT);
                if (Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(sReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    app.registerReceiver(sReceiver, filter);
                }
                sRegistered = true;
                Log.i(TAG, "对话框事件通道已注册");
                return true;
            } catch (Throwable error) {
                Log.w(TAG, "注册对话框事件通道失败", error);
                sReceiver = null;
                return false;
            }
        }
    }

    /** 取走队列中的事件；token 为空时返回全部。返回 JSON 数组字符串。 */
    public static String pollEvents(String token) {
        JSONArray result = new JSONArray();
        for (;;) {
            JSONObject event = EVENTS.poll();
            if (event == null) {
                break;
            }
            if (token == null || token.isEmpty()
                    || token.equals(event.optString("token", ""))) {
                result.put(event);
            }
        }
        return result.toString();
    }

    /** :node24 -&gt; 主进程 Activity 的进度/关闭控制。 */
    public static boolean sendControl(Context context, String token, String op, String value) {
        if (context == null || token == null || token.isEmpty()
                || op == null || op.isEmpty()) {
            return false;
        }
        try {
            Context app = context.getApplicationContext();
            if (app == null) {
                app = context;
            }
            Intent intent = new Intent(ACTION_DIALOG_CONTROL);
            intent.setPackage(app.getPackageName());
            intent.putExtra(EXTRA_TOKEN, token);
            intent.putExtra(EXTRA_OP, op);
            intent.putExtra(EXTRA_VALUE, value == null ? "" : value);
            app.sendBroadcast(intent);
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "发送对话框控制失败 op=" + op, error);
            return false;
        }
    }
}
