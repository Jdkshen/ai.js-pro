package com.jdkshen.aijspro.samples;

import android.app.Activity;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;

import com.jdkshen.aijspro.autojs.AutoJs;
import com.stardust.autojs.execution.ExecutionConfig;
import com.stardust.autojs.execution.ScriptExecution;
import com.stardust.autojs.execution.ScriptExecutionListener;
import com.stardust.autojs.execution.ScriptExecutionTask;
import com.stardust.autojs.script.JavaScriptFileSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Temporary engineering harness: runs a batch of the complete Auto.js Pro bundled-sample set
 * imported into assets/sample (Rhino 引擎 / Node 引擎) and records per-sample results as JSON for offline triage.
 *
 * <p>Not part of the product surface: launched explicitly via
 * {@code am start -n com.jdkshen.aijspro/.samples.ProSampleSweepActivity --ei from 0 --ei size 60 --ei round 0}.
 */
public class ProSampleSweepActivity extends Activity {

    private static final String TAG = "ProSampleSweep";
    private static final String ASSET_ROOT = "sample";
    private static final long SAMPLE_TIMEOUT_MILLIS = 8000L;

    /** Samples that could mutate global device state or register persistent work. */
    private static final String[] BLOCKED = {
        "English/Android 7.0+ tap and gesture/RootAutomator2.js",
        "English/Application/Uninstall app.js",
        "English/Application/application tools.js",
        "English/Console/Save log.js",
        "English/Device/Adjust device brightness.js",
        "English/Device/Adjust device volume.js",
        "English/FileSystem/Delete all empty folders.js",
        "English/FileSystem/File encoding conversion (advanced).js",
        "English/FileSystem/File encoding conversion.js",
        "English/FileSystem/Read and write text files.js",
        "English/FileSystem/Write text file.js",
        "English/HTTP Networking/FileDownload.js",
        "English/ImagesAndColors/DownloadNetworkImage.js",
        "English/ImagesAndColors/ImageMatching/FindAndDrawTheQuestionMarkBlock.js",
        "English/ImagesAndColors/TakeScreenshotAndSave.js",
        "English/Node.js/Accessibility/key_and_other_actions.js",
        "English/Node.js/Accessibility/take_screenshot.js",
        "English/Node.js/power_manager.js",
        "English/Node.js/root-automator2.js",
        "English/Node.js/settings.js",
        "English/Node.js/work_manager.js",
        "English/Script Engine/Stop all running scripts.js",
        "English/Settings/Settings.js",
        "English/Shell/AccessDataData.js",
        "English/Shell/Freeze Youtube.js",
        "English/Shell/StopBackgroundProcesses.js",
        "English/Shell/UnfreezeAndOpenYoutube.js",
        "English/Task/ExtractMp3FromVideo.js",
        "English/Task/QueryAndCancelTasks.js",
        "English/Task/RunEvery10minutes.js",
        "English/Task/TimedTask-BroadcastTask.js",
        "English/Task/TimedTasks-DailyTasks.js",
        "English/Task/TimedTasks-OneTimeTasks.js",
        "English/Task/TimedTasks-WeeklyTasks.js",
        "中文/HTTP网络请求/文件下载.js",
        "中文/Node.js/root-automator2.js",
        "中文/Node.js/任务.js",
        "中文/Node.js/无障碍/使用无障碍权限截图.js",
        "中文/Node.js/无障碍/模拟按键与其他动作.js",
        "中文/Node.js/电量优化与设备唤醒.js",
        "中文/Node.js/设置.js",
        "中文/Shell命令/结束所有后台进程.js",
        "中文/Shell命令/访问data data目录.js",
        "中文/任务/定时任务-一次性任务.js",
        "中文/任务/定时任务-广播任务.js",
        "中文/任务/定时任务-查询与取消.js",
        "中文/任务/定时任务-每10分钟运行.js",
        "中文/任务/定时任务-每周任务.js",
        "中文/任务/定时任务-每日任务.js",
        "中文/任务/视频文件提取mp3文件.js",
        "中文/图片与图色处理/截图并保存.js",
        "中文/图片与图色处理/找图/找出问号方块并画出位置.js",
        "中文/图片与图色处理/获取网络图片并保存.js",
        "中文/安卓7.0+点按和手势/RootAutomator2.js",
        "中文/安卓7.0+点按和手势/RootAutomator双指捏合.js",
        "中文/应用/卸载应用.js",
        "中文/应用/应用工具.js",
        "中文/控制台/保存日志.js",
        "中文/文件读写/写入文本文件.js",
        "中文/文件读写/删除所有空文件夹.js",
        "中文/文件读写/文件编码转换(高级).js",
        "中文/文件读写/文件编码转换.js",
        "中文/文件读写/读写文本文件.js",
        "中文/脚本引擎/停止所有正在运行的脚本.js",
        "中文/设备与设备信息/调整设备亮度.js",
        "中文/设备与设备信息/调整设备音量.js",
        "中文/设置/设置.js",
        "English/Application/Automatically rename all apk files.js",
        "中文/应用/自动重命名所有apk文件.js",
};
    /** React/Vue/webpack support files that are not Auto.js samples. */
    private static final String[] NON_AUTOJS = {
        "English/Node.js/UI/React/src/App.js",
        "English/Node.js/UI/React/src/App.test.js",
        "English/Node.js/UI/React/src/index.js",
        "English/Node.js/UI/React/src/reportWebVitals.js",
        "English/Node.js/UI/React/src/setupTests.js",
        "English/Node.js/UI/React/webpack.config.js",
        "English/Node.js/UI/Vue/Vue3+CLI/babel.config.js",
        "English/Node.js/UI/Vue/Vue3+CLI/src/main.js",
        "English/Node.js/UI/Vue/Vue3+CLI/vue.config.js",
        "English/Node.js/UI/Vue/Vue3+CLI/webpack.config.js",
        "中文/Node.js/Node.js中使用UI/React/src/App.js",
        "中文/Node.js/Node.js中使用UI/React/src/App.test.js",
        "中文/Node.js/Node.js中使用UI/React/src/index.js",
        "中文/Node.js/Node.js中使用UI/React/src/reportWebVitals.js",
        "中文/Node.js/Node.js中使用UI/React/src/setupTests.js",
        "中文/Node.js/Node.js中使用UI/React/webpack.config.js",
        "中文/Node.js/Node.js中使用UI/Vue/Vue3+CLI/babel.config.js",
        "中文/Node.js/Node.js中使用UI/Vue/Vue3+CLI/src/main.js",
        "中文/Node.js/Node.js中使用UI/Vue/Vue3+CLI/vue.config.js",
        "中文/Node.js/Node.js中使用UI/Vue/Vue3+CLI/webpack.config.js",
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final TextView text = new TextView(this);
        text.setText("Auto.js Pro sample sweep…");
        setContentView(text);
        final int from = getIntent().getIntExtra("from", 0);
        final int size = getIntent().getIntExtra("size", 60);
        final int round = getIntent().getIntExtra("round", 0);
        new Thread(() -> {
            try {
                JSONObject report = runBatch(from, size);
                writeReport(round, report);
                text.setText("done: " + report.optInt("executed") + " samples, failures " + report.optJSONArray("failures").length());
            } catch (Throwable error) {
                Log.e(TAG, "sweep failed", error);
            }
        }, "pro-sample-sweep").start();
    }

    private JSONObject runBatch(int from, int size) throws Exception {
        AssetManager assets = getAssets();
        List<String> files = new ArrayList<>();
        collect(assets, ASSET_ROOT, files);
        File fixtures = new File(getCacheDir(), "sample-sweep");
        for (String relative : files) {
            File target = new File(fixtures, relative);
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Cannot create fixture dir");
            }
            try {
                copy(assets, ASSET_ROOT + "/" + relative, target);
            } catch (FileNotFoundException emptyAssetDirectory) {
                // aapt does not package empty directories; treat them as nothing to copy
                Log.d(TAG, "empty asset directory: " + relative);
            }
        }
        List<String> scripts = new ArrayList<>();
        for (String relative : files) {
            if (relative.endsWith(".js")) {
                scripts.add(relative);
            }
        }
        Collections.sort(scripts);
        int total = scripts.size();
        int start = Math.max(0, Math.min(from, total));
        int end = Math.min(total, start + size);
        JSONArray results = new JSONArray();
        JSONArray failures = new JSONArray();
        long batchDeadline = SystemClock.elapsedRealtime() + 360_000L;
        int stoppedAt = end;
        for (int index = start; index < end; index++) {
            if (SystemClock.elapsedRealtime() > batchDeadline) {
                stoppedAt = index;
                Log.w(TAG, "batch wall-clock cap reached at " + index);
                break;
            }
            String relative = scripts.get(index);
            writeProgress(from, size, index, relative, results);
            Log.i(TAG, "sample " + index + "/" + end + " " + relative);
            JSONObject record = new JSONObject().put("path", relative);
            if (isSkipped(relative)) {
                record.put("state", "SKIPPED").put("message", skipReason(relative));
                results.put(record);
                continue;
            }
            String source = readAsset(assets, ASSET_ROOT + "/" + relative);
            String mode = mode(source);
            if ("ui".equals(mode)) {
                record.put("state", "SKIPPED_UI").put("message", "ui script skipped by sweep harness");
                results.put(record);
                continue;
            }
            String engine = "node".equals(mode) ? "NODE24_SOURCE" : "quickjs".equals(mode) ? "QUICKJS_SOURCE" : "RHINO_NEXT";
            record.put("engine", engine);
            String rewritten = rewrite(source, mode);
            File scriptFile = new File(fixtures, relative);
            try (OutputStream output = new FileOutputStream(scriptFile)) {
                output.write(rewritten.getBytes(StandardCharsets.UTF_8));
            }
            long began = SystemClock.elapsedRealtime();
            JSONObject outcome = execute(new JavaScriptFileSource(scriptFile));
            record.put("state", outcome.optString("state"))
                    .put("message", truncate(outcome.optString("message"), 300))
                    .put("durationMs", SystemClock.elapsedRealtime() - began);
            results.put(record);
            if (!"SUCCESS".equals(record.optString("state"))) {
                failures.put(new JSONObject().put("path", relative).put("engine", engine)
                        .put("state", record.optString("state")).put("message", record.optString("message")));
            }
        }
        return new JSONObject().put("result", "PASS").put("total", total).put("from", start).put("to", stoppedAt)
                .put("done", stoppedAt >= total).put("truncated", stoppedAt < end)
                .put("executed", Math.max(0, stoppedAt - start)).put("failures", failures).put("results", results);
    }

    private JSONObject execute(JavaScriptFileSource source) throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final JSONObject outcome = new JSONObject().put("state", "PENDING");
        final ScriptExecution[] holder = new ScriptExecution[1];
        ScriptExecutionListener listener = new ScriptExecutionListener() {
            @Override public void onStart(ScriptExecution execution) { }
            @Override public void onSuccess(ScriptExecution execution, Object result) {
                try { outcome.put("state", "SUCCESS"); } catch (Exception ignored) { }
                done.countDown();
            }
            @Override public void onException(ScriptExecution execution, Throwable error) {
                try { outcome.put("state", "ERROR").put("message", String.valueOf(error)); } catch (Exception ignored) { }
                done.countDown();
            }
        };
        ExecutionConfig config = new ExecutionConfig();
        File parent = source.getFile().getParentFile();
        if (parent != null) {
            config.setWorkingDirectory(parent.getAbsolutePath());
        }
        try {
            ScriptExecutionTask task = new ScriptExecutionTask(source, listener, config);
            holder[0] = AutoJs.getInstance().getScriptEngineService().execute(task);
        } catch (Throwable error) {
            return new JSONObject().put("state", "ENGINE_ERROR").put("message", String.valueOf(error));
        }
        if (!done.await(SAMPLE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            ScriptExecution execution = holder[0];
            if (execution != null && execution.getEngine() != null) {
                try { execution.getEngine().forceStop(); } catch (Throwable ignored) { }
            }
            return new JSONObject().put("state", "TIMEOUT").put("message", "sweep timeout");
        }
        return outcome;
    }

    private void writeProgress(int from, int size, int index, String path, JSONArray results) {
        try {
            File directory = new File(getExternalFilesDir(null), "pro-sample-sweep");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                return;
            }
            JSONObject progress = new JSONObject()
                    .put("from", from).put("size", size).put("index", index).put("path", path)
                    .put("results", results);
            try (OutputStream output = new FileOutputStream(new File(directory, "progress.json"))) {
                output.write(progress.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private void writeReport(int round, JSONObject report) {
        try {
            File directory = new File(getExternalFilesDir(null), "pro-sample-sweep");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Cannot create report dir");
            }
            try (OutputStream output = new FileOutputStream(new File(directory, "round-" + round + ".json"))) {
                output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable error) {
            Log.e(TAG, "cannot write sweep report", error);
        }
    }

    private static String mode(String source) {
        for (String line : source.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("\"nodejs\"")) return "node";
            if (trimmed.startsWith("\"quickjs\"")) return "quickjs";
            if (trimmed.startsWith("\"ui\"")) return "ui";
            if (trimmed.startsWith("\"auto\"")) return "rhino";
            return "rhino";
        }
        return "rhino";
    }

    private static String rewrite(String source, String mode) {
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if ("node".equals(mode)) {
                lines[i] = "// @engine node24";
            } else if ("quickjs".equals(mode)) {
                lines[i] = "// @engine quickjs";
            } else if (trimmed.startsWith("\"auto\"")) {
                lines[i] = "// auto mode stripped for the automated sweep";
            }
            break;
        }
        return String.join("\n", lines);
    }

    private boolean isSkipped(String relative) {
        for (String entry : BLOCKED) {
            if (entry.equals(relative)) return true;
        }
        for (String entry : NON_AUTOJS) {
            if (entry.equals(relative)) return true;
        }
        return false;
    }

    private String skipReason(String relative) {
        for (String entry : BLOCKED) {
            if (entry.equals(relative)) return "blocked by sweep risk list";
        }
        return "non-AutoJs support file";
    }

    private static String truncate(String value, int limit) {
        if (value == null) return "";
        String flat = value.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit);
    }

    private static void collect(AssetManager assets, String path, List<String> files) throws IOException {
        String[] children = assets.list(path);
        if (children == null) return;
        for (String child : children) {
            String childPath = path + "/" + child;
            String[] grand = assets.list(childPath);
            if (grand != null && grand.length > 0) {
                collect(assets, childPath, files);
            } else {
                files.add(childPath.substring(ASSET_ROOT.length() + 1));
            }
        }
    }

    private static void copy(AssetManager assets, String path, File target) throws IOException {
        try (InputStream input = assets.open(path); OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static String readAsset(AssetManager assets, String path) throws IOException {
        try (InputStream input = assets.open(path); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
