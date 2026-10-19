package com.jdkshen.aijspro.timing;


import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

import com.stardust.app.GlobalAppContext;

import com.jdkshen.aijspro.App;
import com.jdkshen.aijspro.storage.database.IntentTaskDatabase;
import com.jdkshen.aijspro.storage.database.ModelChange;
import com.jdkshen.aijspro.storage.database.TimedTaskDatabase;
import com.jdkshen.aijspro.tool.Observers;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.reactivex.Flowable;
import io.reactivex.Observable;

/**
 * Created by Stardust on 2017/11/27.
 */
//TODO rx
public class TimedTaskManager {

    private static TimedTaskManager sInstance;
    private Context mContext;
    private TimedTaskDatabase mTimedTaskDatabase;
    private IntentTaskDatabase mIntentTaskDatabase;

    public static TimedTaskManager getInstance() {
        if (sInstance == null) {
            sInstance = new TimedTaskManager(GlobalAppContext.get());
        }
        return sInstance;
    }

    @SuppressLint("CheckResult")
    public TimedTaskManager(Context context) {
        mContext = context;
        mTimedTaskDatabase = new TimedTaskDatabase(context);
        mIntentTaskDatabase = new IntentTaskDatabase(context);
    }

    @SuppressLint("CheckResult")
    public void notifyTaskFinished(long id) {
        TimedTask task = getTimedTask(id);
        if (task == null)
            return;
        if (task.isDisposable()) {
            mTimedTaskDatabase.delete(task)
                    .subscribe(Observers.emptyConsumer(), Throwable::printStackTrace);
        } else {
            task.setScheduled(false);
            mTimedTaskDatabase.update(task)
                    .subscribe(Observers.emptyConsumer(), Throwable::printStackTrace);
        }
    }

    @SuppressLint("CheckResult")
    public void removeTask(TimedTask timedTask) {
        cancelTimedTask(timedTask);
        mTimedTaskDatabase.delete(timedTask)
                .subscribe(Observers.emptyConsumer(), Throwable::printStackTrace);
    }

    /**
     * Script APIs return only after SQLite has assigned the real id, then hand the
     * scheduler work to the main process (JobManager is intentionally only initialized there).
     */
    public void addTaskSync(TimedTask task) {
        long id = mTimedTaskDatabase.insert(task).blockingFirst();
        task.setId(id);
        scheduleTimedTask(task);
    }

    public void addTaskSync(IntentTask task) {
        long id = mIntentTaskDatabase.insert(task).blockingFirst();
        task.setId(id);
        if (!TextUtils.isEmpty(task.getAction())) {
            registerIntentTask(task);
        }
    }

    public void removeTaskSync(TimedTask task) {
        mTimedTaskDatabase.delete(task).blockingFirst();
        cancelTimedTask(task);
    }

    public void removeTaskSync(IntentTask task) {
        String action = task.getAction();
        mIntentTaskDatabase.delete(task).blockingFirst();
        if (!TextUtils.isEmpty(action)) {
            unregisterIntentTask(action);
        }
    }

    @SuppressLint("CheckResult")
    public void addTask(TimedTask timedTask) {
        mTimedTaskDatabase.insert(timedTask)
                .subscribe(id -> {
                    timedTask.setId(id);
                    scheduleTimedTask(timedTask);
                }, Throwable::printStackTrace);
    }

    @SuppressLint("CheckResult")
    public void addTask(IntentTask intentTask) {
        mIntentTaskDatabase.insert(intentTask)
                .subscribe(i -> {
                    if (!TextUtils.isEmpty(intentTask.getAction())) {
                        registerIntentTask(intentTask);
                    }
                }, Throwable::printStackTrace);
    }

    @SuppressLint("CheckResult")
    public void removeTask(IntentTask intentTask) {
        mIntentTaskDatabase.delete(intentTask)
                .subscribe(i -> {
                    if (!TextUtils.isEmpty(intentTask.getAction())) {
                        unregisterIntentTask(intentTask.getAction());
                    }
                }, Throwable::printStackTrace);
    }

    public Flowable<TimedTask> getAllTasks() {
        return mTimedTaskDatabase.queryAllAsFlowable();
    }

    public Flowable<IntentTask> getIntentTaskOfAction(String action) {
        return mIntentTaskDatabase.query("action = ?", action);
    }


    public Observable<ModelChange<TimedTask>> getTimeTaskChanges() {
        return mTimedTaskDatabase.getModelChange();
    }

    @SuppressLint("CheckResult")
    public void notifyTaskScheduled(TimedTask timedTask) {
        timedTask.setScheduled(true);
        mTimedTaskDatabase.update(timedTask)
                .subscribe(Observers.emptyConsumer(), Throwable::printStackTrace);

    }

    public List<TimedTask> getAllTasksAsList() {
        return mTimedTaskDatabase.queryAll();
    }

    public TimedTask getTimedTask(long taskId) {
        return mTimedTaskDatabase.queryById(taskId);
    }

    @SuppressLint("CheckResult")
    public void updateTask(TimedTask task) {
        mTimedTaskDatabase.update(task)
                .subscribe(Observers.emptyConsumer(), Throwable::printStackTrace);
        TimedTaskScheduler.cancel(task);
        TimedTaskScheduler.scheduleTaskIfNeeded(mContext, task, false);
    }

    @SuppressLint("CheckResult")
    public void updateTaskWithoutReScheduling(TimedTask task) {
        mTimedTaskDatabase.update(task)
                .subscribe(Observers.emptyConsumer(), Throwable::printStackTrace);
    }

    @SuppressLint("CheckResult")
    public void updateTask(IntentTask task) {
        mIntentTaskDatabase.update(task)
                .subscribe(i -> {
                    if (i > 0 && !TextUtils.isEmpty(task.getAction())) {
                        registerIntentTask(task);
                    }
                }, Throwable::printStackTrace);
    }

    public long countTasks() {
        return mTimedTaskDatabase.count();
    }

    public List<IntentTask> getAllIntentTasksAsList() {
        return mIntentTaskDatabase.queryAll();
    }

    public Observable<ModelChange<IntentTask>> getIntentTaskChanges() {
        return mIntentTaskDatabase.getModelChange();
    }

    public IntentTask getIntentTask(long intentTaskId) {
        return mIntentTaskDatabase.queryById(intentTaskId);
    }

    public Flowable<IntentTask> getAllIntentTasks() {
        return mIntentTaskDatabase.queryAllAsFlowable();
    }

    // ---------------- 主进程调度/广播注册桥 ----------------

    /**
     * 定时任务调度和 JobManager 只存在于主进程；:script/:native 进程只能把 id 交给
     * {@link TaskSchedulerBridgeReceiver}，否则会抛 JobManager 未初始化。
     */
    private void scheduleTimedTask(TimedTask task) {
        if (isMainProcess()) {
            TimedTaskScheduler.scheduleTaskIfNeeded(mContext, task, false);
        } else {
            TaskSchedulerBridgeReceiver.scheduleTimedTask(mContext, task.getId());
        }
    }

    private void cancelTimedTask(TimedTask task) {
        if (isMainProcess()) {
            TimedTaskScheduler.cancel(task);
        } else {
            TaskSchedulerBridgeReceiver.cancelTimedTask(mContext, task.getId());
        }
    }

    private void registerIntentTask(IntentTask task) {
        if (isMainProcess()) {
            App.Companion.getApp().getDynamicBroadcastReceivers().register(task);
        } else {
            TaskSchedulerBridgeReceiver.registerIntentTask(
                    mContext, task.getId(), task.getAction());
        }
    }

    private void unregisterIntentTask(String action) {
        if (isMainProcess()) {
            App.Companion.getApp().getDynamicBroadcastReceivers().unregister(action);
        } else {
            TaskSchedulerBridgeReceiver.unregisterIntentTask(mContext, action);
        }
    }

    /**
     * 只有包名本身才是主进程；:script/:native 或其他隔离进程都走广播。
     * 读不到进程名时返回 false（宁可多一次主进程广播，也不在脚本进程直接碰 JobManager）。
     */
    private boolean isMainProcess() {
        String processName = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            processName = Application.getProcessName();
        }
        if (TextUtils.isEmpty(processName)) {
            processName = readProcessName();
        }
        return !TextUtils.isEmpty(processName)
                && processName.equals(mContext.getPackageName());
    }

    private static String readProcessName() {
        FileInputStream input = null;
        try {
            input = new FileInputStream("/proc/self/cmdline");
            byte[] buffer = new byte[256];
            int length = input.read(buffer);
            if (length <= 0) {
                return null;
            }
            int end = 0;
            while (end < length && buffer[end] != 0) {
                end++;
            }
            return new String(buffer, 0, end, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Throwable ignored) {
                    // ignored
                }
            }
        }
    }
}
