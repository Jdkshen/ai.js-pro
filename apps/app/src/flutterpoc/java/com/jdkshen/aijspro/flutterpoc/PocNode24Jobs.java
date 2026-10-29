package com.jdkshen.aijspro.flutterpoc;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import com.stardust.app.GlobalAppContext;

import org.autojs.source.engine.android.Node24Service;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * task-167：主进程侧的 Node24 uiScript 任务登记表。
 *
 * <p>背景：Node UI/overlay 脚本真正执行在 {@code :node24}（Node24Service 单任务 keep-alive），
 * {@code :script} 宿主侧的 RUN 早已结束，因此任务页/停止全部只看到空列表；本类通过
 * Node24Service 广播（started/finished/cancelled）在**主进程**维护运行中任务，并在任务页/停止全部时
 * 通过控制广播 cancel/cancelAll 让 :node24 取消（取消后 Node24Service 会关 UI 并结束进程）。</p>
 */
final class PocNode24Jobs {

    private static final String TAG = "PocNode24Jobs";
    static final String TOKEN_PREFIX = "node24:";

    private static final ConcurrentHashMap<String, JSONObject> RUNNING = new ConcurrentHashMap<>();
    private static volatile boolean installed;

    private PocNode24Jobs() {
    }

    /** 首次使用/进程启动时注册状态广播接收器（注册失败只影响任务页展示，不影响停止桥）。 */
    static synchronized void installIfNeeded(Context context) {
        if (installed) {
            return;
        }
        Context app = context;
        if (app == null) {
            app = GlobalAppContext.get();
        }
        if (app == null) {
            return;
        }
        final Context target = app.getApplicationContext() == null ? app : app.getApplicationContext();
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (intent == null) {
                        return;
                    }
                    String state = intent.getStringExtra(Node24Service.EXTRA_STATE);
                    String id = intent.getStringExtra(Node24Service.EXTRA_ID);
                    if (state == null || id == null || id.isEmpty()) {
                        return;
                    }
                    if ("started".equals(state) || "stopping".equals(state)) {
                        try {
                            JSONObject task = new JSONObject()
                                    .put("token", TOKEN_PREFIX + id)
                                    .put("kind", "script")
                                    .put("name", intent.getStringExtra(Node24Service.EXTRA_FILENAME))
                                    .put("path", intent.getStringExtra(Node24Service.EXTRA_FILENAME))
                                    .put("engine", "Node24")
                                    .put("executionState", intent.getStringExtra("executionState"))
                                    .put("detail", "stopping".equals(state) ? "正在停止"
                                            : intent.getBooleanExtra(Node24Service.EXTRA_UI_SCRIPT, false)
                                                ? "运行中 · Node UI" : "运行中")
                                    .put("nextTime", 0)
                                    .put("startedAt", intent.getLongExtra(Node24Service.EXTRA_STARTED_AT,
                                            System.currentTimeMillis()));
                            RUNNING.put(id, task);
                            Log.i(TAG, "node24 job registered id=" + id);
                        } catch (Throwable error) {
                            Log.w(TAG, "record node24 job failed", error);
                        }
                    } else {
                        RUNNING.remove(id);
                        Log.i(TAG, "node24 job removed id=" + id + " state=" + state);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(Node24Service.ACTION_NODE24_STATE);
            if (Build.VERSION.SDK_INT >= 33) {
                target.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                target.registerReceiver(receiver, filter);
            }
            installed = true;
            Log.i(TAG, "node24 job registry installed");
            // 进程重启/登记晚于任务启动时，向 :node24 主动 query 一次补登记。
            sendControl("query", "");
        } catch (Throwable error) {
            Log.w(TAG, "install node24 job registry failed", error);
        }
    }

    /** 运行中的 Node24 任务快照（供 PocRuntime.tasks 合并）。:node24 进程不在时自动清空。 */
    static JSONArray snapshot() {
        JSONArray result = new JSONArray();
        try {
            pruneIfProcessGone();
        } catch (Throwable error) {
            Log.w(TAG, "prune node24 jobs failed", error);
        }
        for (Map.Entry<String, JSONObject> entry : RUNNING.entrySet()) {
            result.put(entry.getValue());
        }
        return result;
    }

    /** :node24 进程被杀（取消/崩溃）时广播可能来不及发，这里按进程存活兜底清空。 */
    private static void pruneIfProcessGone() {
        if (RUNNING.isEmpty()) {
            return;
        }
        Context context = GlobalAppContext.get();
        if (context == null) {
            return;
        }
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            return;
        }
        boolean alive = false;
        try {
            for (ActivityManager.RunningAppProcessInfo info : manager.getRunningAppProcesses()) {
                if (info != null && (context.getPackageName() + ":node24").equals(info.processName)) {
                    alive = true;
                    break;
                }
            }
        } catch (Throwable error) {
            return;
        }
        if (!alive) {
            RUNNING.clear();
        }
    }

    /** 按展示 token（node24:<id>）取消；返回是否已下发（实际取消由 :node24 执行）。 */
    static boolean cancel(String token) {
        if (token == null) {
            return false;
        }
        String id = token.startsWith(TOKEN_PREFIX) ? token.substring(TOKEN_PREFIX.length()) : token;
        if (id.isEmpty()) {
            return false;
        }
        RUNNING.remove(id);
        sendControl("cancel", id);
        return true;
    }

    /** 停止全部：取消 :node24 当前 keep-alive 任务（Node24Service 取消后会关 UI 并结束进程）。 */
    static void cancelAll() {
        RUNNING.clear();
        sendControl("cancelAll", "");
    }

    private static void sendControl(String command, String id) {
        Context context = GlobalAppContext.get();
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(Node24Service.ACTION_NODE24_CONTROL)
                    .setPackage(context.getPackageName());
            intent.putExtra(Node24Service.EXTRA_COMMAND, command);
            if (id != null && !id.isEmpty()) {
                intent.putExtra(Node24Service.EXTRA_ID, id);
            }
            context.sendBroadcast(intent);
            Log.i(TAG, "node24 control sent command=" + command + " id=" + id);
        } catch (Throwable error) {
            Log.w(TAG, "send node24 control failed", error);
        }
    }
}
