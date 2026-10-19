package com.jdkshen.aijspro.timing;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import com.jdkshen.aijspro.App;

/**
 * 主进程侧的定时任务/动态广播注册桥。
 *
 * <p>{@code TimedTaskScheduler} 和 {@code JobManager} 只在主进程初始化（见 {@code App.init}）。
 * Rhino/QuickJS 脚本通常跑在 {@code :script} 进程，脚本里的 {@code $work_manager} 同步 API
 * 又必须等 SQLite 提交并拿到真实 id 才返回。因此脚本进程提交数据库后，用应用内显式广播
 * 把「按 id 调度/取消」或「按 id 注册/按 action 反注册」交给本 receiver；本 receiver 不声明
 * {@code android:process}，固定运行在主进程。
 *
 * <p>广播不导出（{@code exported=false}），只有同 UID 的本应用进程能发送；协议只带 id/action，
 * 不携带脚本内容。新增定时任务时 receiver 会重新按 id 查库：如果任务已被并发删除则跳过，
 * 避免 remove 广播先到、add 广播后到时把已删除的任务重新排上。
 */
public class TaskSchedulerBridgeReceiver extends BroadcastReceiver {

    public static final String ACTION_SCHEDULE_TIMED_TASK =
            "com.jdkshen.aijspro.action.SCHEDULE_TIMED_TASK";
    public static final String ACTION_CANCEL_TIMED_TASK =
            "com.jdkshen.aijspro.action.CANCEL_TIMED_TASK";
    public static final String ACTION_REGISTER_INTENT_TASK =
            "com.jdkshen.aijspro.action.REGISTER_INTENT_TASK";
    public static final String ACTION_UNREGISTER_INTENT_TASK =
            "com.jdkshen.aijspro.action.UNREGISTER_INTENT_TASK";

    public static final String EXTRA_TASK_ID = "task_id";
    public static final String EXTRA_INTENT_ACTION = "intent_action";

    private static final String TAG = "TaskSchedulerBridge";

    public static void scheduleTimedTask(Context context, long taskId) {
        send(context, ACTION_SCHEDULE_TIMED_TASK, taskId, null);
    }

    public static void cancelTimedTask(Context context, long taskId) {
        send(context, ACTION_CANCEL_TIMED_TASK, taskId, null);
    }

    public static void registerIntentTask(Context context, long taskId, String action) {
        send(context, ACTION_REGISTER_INTENT_TASK, taskId, action);
    }

    public static void unregisterIntentTask(Context context, String action) {
        send(context, ACTION_UNREGISTER_INTENT_TASK, -1, action);
    }

    private static void send(Context context, String action, long taskId, String intentAction) {
        Intent intent = new Intent(context, TaskSchedulerBridgeReceiver.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_TASK_ID, taskId);
        if (!TextUtils.isEmpty(intentAction)) {
            intent.putExtra(EXTRA_INTENT_ACTION, intentAction);
        }
        // 显式 + exported=false：不会离开本应用 UID，也不会被外部应用伪造。
        context.sendBroadcast(intent);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        Context appContext = context.getApplicationContext();
        try {
            TimedTaskManager manager = TimedTaskManager.getInstance();
            switch (action) {
                case ACTION_SCHEDULE_TIMED_TASK: {
                    long id = intent.getLongExtra(EXTRA_TASK_ID, -1);
                    if (id < 0) {
                        return;
                    }
                    TimedTask task = manager.getTimedTask(id);
                    if (task == null) {
                        Log.w(TAG, "skip scheduling missing timed task: " + id);
                        return;
                    }
                    TimedTaskScheduler.scheduleTaskIfNeeded(appContext, task, false);
                    break;
                }
                case ACTION_CANCEL_TIMED_TASK: {
                    long id = intent.getLongExtra(EXTRA_TASK_ID, -1);
                    if (id < 0) {
                        return;
                    }
                    TimedTask task = manager.getTimedTask(id);
                    if (task == null) {
                        // removeTaskSync 先删库再发广播，主进程通常已查不到原任务；
                        // cancel 只使用 id，构造一个 id 句柄即可，绝不能因此跳过取消。
                        task = new TimedTask();
                        task.setId(id);
                    }
                    TimedTaskScheduler.cancel(task);
                    break;
                }
                case ACTION_REGISTER_INTENT_TASK: {
                    long id = intent.getLongExtra(EXTRA_TASK_ID, -1);
                    IntentTask task = id < 0 ? null : manager.getIntentTask(id);
                    if (task == null || TextUtils.isEmpty(task.getAction())) {
                        Log.w(TAG, "skip registering missing intent task: " + id);
                        return;
                    }
                    App.Companion.getApp().getDynamicBroadcastReceivers().register(task);
                    break;
                }
                case ACTION_UNREGISTER_INTENT_TASK: {
                    String intentAction = intent.getStringExtra(EXTRA_INTENT_ACTION);
                    if (!TextUtils.isEmpty(intentAction)) {
                        App.Companion.getApp().getDynamicBroadcastReceivers()
                                .unregister(intentAction);
                    }
                    break;
                }
                default:
                    Log.w(TAG, "unknown action: " + action);
                    break;
            }
        } catch (Throwable error) {
            // 这是异步桥，脚本调用已经返回；主进程不能被一次调度失败拖崩。
            Log.e(TAG, "failed to handle " + action, error);
        }
    }
}
