package com.jdkshen.aijspro.timing;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.evernote.android.job.Job;
import com.evernote.android.job.JobManager;
import com.evernote.android.job.JobRequest;

import com.jdkshen.aijspro.external.HostScripts;
import com.jdkshen.aijspro.external.ScriptIntents;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;


/**
 * Created by Stardust on 2017/11/27.
 */

public class TimedTaskScheduler {

    private static final String LOG_TAG = "TimedTaskScheduler";
    private static final long SCHEDULE_TASK_MIN_TIME = TimeUnit.DAYS.toMillis(2);

    private static final String JOB_TAG_CHECK_TASKS = "checkTasks";
    private static boolean sInitialized;


    @SuppressLint("CheckResult")
    public static void checkTasks(Context context, boolean force) {
        Log.d(LOG_TAG, "check tasks: force = " + force);
        TimedTaskManager.getInstance().getAllTasks()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(timedTask -> scheduleTaskIfNeeded(context, timedTask, force));
    }

    public static void scheduleTaskIfNeeded(Context context, TimedTask timedTask, boolean force) {
        long millis = timedTask.getNextTime();
        if ((!force && timedTask.isScheduled()) || millis - System.currentTimeMillis() > SCHEDULE_TASK_MIN_TIME) {
            return;
        }
        scheduleTask(context, timedTask, millis, force);
        TimedTaskManager.getInstance()
                .notifyTaskScheduled(timedTask);
    }

    private synchronized static void scheduleTask(Context context, TimedTask timedTask, long millis, boolean force) {
        if (!force && timedTask.isScheduled()) {
            return;
        }
        long timeWindow = millis - System.currentTimeMillis();
        timedTask.setScheduled(true);
        TimedTaskManager.getInstance().updateTaskWithoutReScheduling(timedTask);
        if (timeWindow <= 0) {
            runTask(context, timedTask);
            return;
        }
        cancel(timedTask);
        Log.d(LOG_TAG, "schedule task: task = " + timedTask + ", millis = " + millis + ", timeWindow = " + timeWindow);
        new JobRequest.Builder(String.valueOf(timedTask.getId()))
                .setExact(timeWindow)
                .build()
                .schedule();
    }

    public static void cancel(TimedTask timedTask) {
        int cancelCount = JobManager.instance().cancelAllForTag(String.valueOf(timedTask.getId()));
        Log.d(LOG_TAG, "cancel task: task = " + timedTask + ", cancel = " + cancelCount);
    }

    public static synchronized void init(@NotNull Context context) {
        if (sInitialized) {
            return;
        }
        sInitialized = true;
        JobManager.create(context).addJobCreator(tag -> {
            if (tag.equals(JOB_TAG_CHECK_TASKS)) {
                return new CheckTasksJob(context);
            } else {
                return new TimedTaskJob(context);
            }
        });
        new JobRequest.Builder(JOB_TAG_CHECK_TASKS)
                .setPeriodic(TimeUnit.MINUTES.toMillis(20))
                // Application can be created in more than one process. Keep one checker instead
                // of accumulating a new periodic job on every process/app restart.
                .setUpdateCurrent(true)
                .build()
                .scheduleAsync();
        checkTasks(context, true);
    }

    private static void runTask(Context context, TimedTask task) {
        Log.d(LOG_TAG, "run task: task = " + task);
        if (!runInHostProcess(context, task)) {
            // 旧路径（flag 保护）：非 flutterPoc 构建、:script 路由开关关闭、宿主同步绑定失败，
            // 或任务带非默认 delay/loop 配置（宿主 RUN 协议暂不携带 ExecutionConfig）时仍在主进程引擎执行。
            Intent intent = task.createIntent();
            ScriptIntents.handleIntent(context, intent);
        }
        TimedTaskManager.getInstance().notifyTaskFinished(task.getId());
    }

    /**
     * Phase1：定时任务到点后优先进 {@code :script} 宿主进程执行（与 Flutter 页「运行」同一套路由）。
     *
     * <p>触发出来的实例以 {@code script:<id>} 出现在宿主任务列表（PocRuntime.tasks() 已把
     * PocScriptHost.runningTasks() 合并进来），所以任务页 ✕ / 「停止全部」/ 音量上键都能停到它；
     * 派发是异步的（RUN 入队），不会阻塞 JobScheduler 的回调线程。
     *
     * @return true 表示已交给宿主链路（PocRuntime 内部还会处理 auto 模式/native 项目/回退）；
     *         false 表示调用方必须回退旧路径
     */
    private static boolean runInHostProcess(Context context, TimedTask task) {
        if (task == null || !HostScripts.isAvailable()) {
            return false;
        }
        if (task.getDelay() > 0 || task.getLoopTimes() > 1 || task.getInterval() > 0) {
            // 定时任务设置页目前恒用默认 ExecutionConfig；只有历史数据库里可能留着非默认的
            // 延迟/循环配置。宿主 RUN 协议表达不了这些参数，宁可回退主进程也不要悄悄丢配置。
            Log.i(LOG_TAG, "任务带 delay/loop 配置，回退主进程执行：" + task);
            return false;
        }
        String path = task.getScriptPath();
        return path != null && !path.isEmpty() && HostScripts.runScript(context, path);
    }

    private static class TimedTaskJob extends Job {

        private final Context mContext;

        TimedTaskJob(Context context) {
            mContext = context;
        }

        @NonNull
        @Override
        protected Result onRunJob(@NonNull Params params) {
            long id = Long.parseLong(params.getTag());
            TimedTask task = TimedTaskManager.getInstance().getTimedTask(id);
            Log.d(LOG_TAG, "onRunJob: id = " + id + ", task = " + task);
            if (task == null) {
                return Result.FAILURE;
            }
            runTask(mContext, task);
            return Result.SUCCESS;
        }
    }

    private static class CheckTasksJob extends Job {
        private final Context mContext;

        CheckTasksJob(Context context) {
            mContext = context;
        }

        @NonNull
        @Override
        protected Result onRunJob(@NonNull Params params) {
            checkTasks(mContext, false);
            return Result.SUCCESS;
        }
    }


}
