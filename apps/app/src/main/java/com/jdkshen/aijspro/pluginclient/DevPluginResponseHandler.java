package com.jdkshen.aijspro.pluginclient;

import android.annotation.SuppressLint;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.stardust.app.GlobalAppContext;
import com.stardust.autojs.execution.ExecutionConfig;
import com.stardust.autojs.execution.ScriptExecution;
import com.stardust.autojs.engine.ScriptEngine;
import com.stardust.autojs.project.ProjectLauncher;
import com.stardust.autojs.script.JavaScriptFileSource;
import com.stardust.autojs.script.JavaScriptSource;
import com.stardust.autojs.script.StringScriptSource;
import com.stardust.io.Zip;
import com.stardust.pio.PFiles;
import com.stardust.util.MD5;

import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.external.HostScripts;
import com.jdkshen.aijspro.model.script.Scripts;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.HashMap;

import org.json.JSONObject;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

/**
 * Created by Stardust on 2017/5/11.
 */

public class DevPluginResponseHandler implements Handler {


    private Router mRouter = new Router.RootRouter("type")
            .handler("command", new Router("command")
                    .handler("run", data -> {
                        String script = data.get("script").getAsString();
                        String name = getName(data);
                        String id = data.get("id").getAsString();
                        runScript(id, name, script);
                        return true;
                    })
                    .handler("stop", data -> {
                        String id = data.get("id").getAsString();
                        stopScript(id);
                        return true;
                    })
                    .handler("save", data -> {
                        String script = data.get("script").getAsString();
                        String name = getName(data);
                        saveScript(name, script);
                        return true;
                    })
                    .handler("rerun", data -> {
                        String id = data.get("id").getAsString();
                        String script = data.get("script").getAsString();
                        String name = getName(data);
                        stopScript(id);
                        runScript(id, name, script);
                        return true;
                    })
                    .handler("stopAll", data -> {
                        stopAllScripts();
                        return true;
                    }))
            .handler("bytes_command", new Router("command")
                    .handler("run_project", data -> {
                        launchProject(data.get("id").getAsString(), data.get("dir").getAsString());
                        return true;
                    })
                    .handler("save_project", data -> {
                        saveProject(data.get("name").getAsString(), data.get("dir").getAsString());
                        return true;
                    }));


    private HashMap<String, ScriptExecution> mScriptExecutions = new HashMap<>();
    private final File mCacheDir;

    public DevPluginResponseHandler(File cacheDir) {
        mCacheDir = cacheDir;
        // Engine processes also construct this handler. Clearing the shared cache here
        // deleted scripts/projects that another process was in the middle of running.
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            throw new IllegalStateException("电脑连接缓存目录不可用：" + cacheDir);
        }
    }

    @Override
    public boolean handle(JsonObject data) {
        return mRouter.handle(data);
    }

    /** Unzips a project archive pushed by the VSCode extension (md5 already matched by the caller). */
    public Observable<File> handleBytes(JsonObject data, byte[] bytes) {
        String id = data.get("data").getAsJsonObject().get("id").getAsString();
        String idMd5 = MD5.md5(id);
        return Observable.fromCallable(() -> {
            File dir = new File(mCacheDir, idMd5);
            Zip.unzip(new ByteArrayInputStream(bytes), dir);
            deleteRemovedFiles(data, dir);
            return dir;
        })
                .subscribeOn(Schedulers.io());
    }

    /** The extension pushes incremental archives and lists removed project paths separately. */
    private void deleteRemovedFiles(JsonObject command, File dir) {
        JsonElement deleted = command.getAsJsonObject("data").get("deletedFiles");
        if (deleted == null || !deleted.isJsonArray()) {
            return;
        }
        for (JsonElement element : deleted.getAsJsonArray()) {
            String path = element.getAsString();
            if (!TextUtils.isEmpty(path)) {
                PFiles.deleteRecursively(new File(dir, path));
            }
        }
    }

    private void runScript(String viewId, String name, String script) {
        // task-150：远程运行建立真实文件 + CommonJS 模块上下文，让 require("./yolo.js") 能按工程目录解析。
        // 依赖文件是否在设备上取决于用户之前是否 save 过工程；缺失时给中文明确错误。
        String safeName = TextUtils.isEmpty(name) ? ("remote-" + viewId.substring(0, Math.min(8, viewId.length())))
                : PFiles.getNameWithoutExtension(name);
        if (TextUtils.isEmpty(safeName)) {
            safeName = "remote-" + viewId.substring(0, Math.min(8, viewId.length()));
        }
        safeName = safeName.replace('/', '_').replace('\\', '_');
        File scriptsDir = new File(Pref.getScriptDirPath());
        File projectDir = resolveRemoteProjectDir(scriptsDir, safeName, script);
        File requireBase = projectDir != null ? projectDir : scriptsDir;
        File stagingDir = new File(mCacheDir, "remote-staging/" + MD5.md5(viewId));
        // PFiles.ensureDir 只建父目录；这里要的是 stagingDir 本身，必须递归 mkdirs。
        stagingDir.mkdirs();
        File scriptFile = new File(stagingDir, safeName + ".js");
        File scriptParent = scriptFile.getParentFile();
        if (scriptParent != null && !scriptParent.isDirectory()) {
            scriptParent.mkdirs();
        }
        if (scriptParent == null || !scriptParent.isDirectory()) {
            Log.w("DevPluginRemote", "C-REMOTE 无法创建暂存目录：" + stagingDir);
            throw new IllegalStateException("远程运行暂存目录不可用：" + stagingDir);
        }
        StringScriptSource original = new StringScriptSource(name, script);
        String engine = original.getEngineName();
        if (JavaScriptSource.engineFromDirective(script) == null && name != null
                && name.toLowerCase(java.util.Locale.ROOT).endsWith(".node.js")) {
            engine = JavaScriptSource.ENGINE_NODE24;
        }
        String header = (original.getExecutionMode() & JavaScriptSource.EXECUTION_MODE_UI) != 0 ? "\"ui\";\n" : "";
        header += "// @engine " + (JavaScriptSource.ENGINE_NODE24.equals(engine) ? "node24"
                : JavaScriptSource.ENGINE_QUICKJS.equals(engine) ? "quickjs" : "rhino") + "\n";
        String prelude = remoteModulePrelude(scriptFile, requireBase);
        if (JavaScriptSource.ENGINE_NODE24.equals(engine)) {
            // Keep the Node module loader while resolving relative imports in the pushed project.
            prelude = ";__dirname=" + JSONObject.quote(requireBase.getAbsolutePath()) + ";\n"
                    + ";(function(){var nativeRequire=require;var base=__dirname;require=function(id){"
                    + "return nativeRequire(typeof id==='string' && /^\\.{1,2}\\//.test(id) ? "
                    + "requirePath(id) : id);};Object.assign(require,nativeRequire);"
                    + "function requirePath(id){return base+'/'+id;}})();\n";
        }
        PFiles.write(scriptFile, header + prelude + script);
        Log.i("DevPluginRemote", "C-REMOTE module=" + scriptFile.getAbsolutePath()
                + " __dirname=" + requireBase.getAbsolutePath()
                + " requireBase=" + requireBase.getAbsolutePath());
        ExecutionConfig config = ExecutionConfig.getDefault();
        config.setWorkingDirectory(requireBase.getAbsolutePath());
        mScriptExecutions.put(viewId, AutoJs.getInstance().getScriptEngineService()
                .execute(new JavaScriptFileSource(scriptFile), config));
    }

    /** 远程脚本相对依赖所在工程目录：先找同名目录，再按 require("./x") 的文件名扫一级子目录。 */
    private static File resolveRemoteProjectDir(File scriptsDir, String safeName, String script) {
        File sameName = new File(scriptsDir, safeName);
        if (sameName.isDirectory()) {
            return sameName;
        }
        java.util.List<String> deps = new java.util.ArrayList<>();
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("require\\(\\s*['\"]\\./([^'\"]+)['\"]\\s*\\)").matcher(script);
            while (matcher.find()) {
                String dep = matcher.group(1).trim();
                if (!dep.isEmpty()) deps.add(dep);
            }
        } catch (Throwable ignored) { }
        if (deps.isEmpty()) {
            return null;
        }
        File[] children = scriptsDir.listFiles();
        if (children == null) {
            return null;
        }
        File best = null;
        int bestScore = 0;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            int score = 0;
            for (String dep : deps) {
                if (new File(child, dep).isFile()) score++;
            }
            if (score > bestScore) { best = child; bestScore = score; }
        }
        return bestScore == deps.size() ? best : (bestScore > 0 ? best : null);
    }

    /** 远程脚本的 CommonJS 上下文：module/exports/__filename/__dirname + jvm-npm require 根目录。 */
    private static String remoteModulePrelude(File scriptFile, File requireBase) {
        String modulePath = scriptFile.getAbsolutePath().replace('\\', '/');
        String basePath = requireBase.getAbsolutePath().replace('\\', '/');
        String moduleLiteral = JSONObject.quote(modulePath);
        String baseLiteral = JSONObject.quote(basePath);
        return ";(function(){\n"
                + "var __ajsRemoteFile = " + moduleLiteral + ";\n"
                + "var __ajsRemoteBase = " + baseLiteral + ";\n"
                + "module = (typeof module === 'object' && module) ? module : {};\n"
                + "module.id = __ajsRemoteFile; module.filename = __ajsRemoteFile;\n"
                + "module.exports = module.exports || {}; module.loaded = false;\n"
                + "exports = module.exports;\n"
                + "__filename = __ajsRemoteFile; __dirname = __ajsRemoteBase;\n"
                + "try { if (typeof Module === 'function' && Module.require) { Module.require.root = __ajsRemoteBase; } } catch (e) {}\n"
                + "try { if (typeof require === 'function') { var __ajsNativeRequire = require; require = function(id) {\n"
                + "  try { return __ajsNativeRequire(id); } catch (error) {\n"
                + "    var m = String(error && error.message || error);\n"
                + "    if (m.indexOf('outside of a module') >= 0 || m.indexOf('Cannot find module') >= 0 || m.indexOf('MODULE_NOT_FOUND') >= 0) {\n"
                + "      throw new Error('远程运行缺少依赖文件：' + id + '。请先在 VS Code 把整个工程保存到手机（/sdcard/脚本），或直接在本机文件页运行工程 main.js。原始错误：' + m);\n"
                + "    }\n"
                + "    throw error;\n"
                + "  }\n"
                + "}; } } catch (e2) {}\n"
                + "})();\n";
    }


    private void launchProject(String viewId, String dir) {
        try {
            // The desktop sends this same id with its later stop command. Without keeping the
            // execution, project runs cannot be stopped individually even though stop replies OK.
            stopScript(viewId);
            ScriptExecution execution = new ProjectLauncher(dir)
                    .launch(AutoJs.getInstance().getScriptEngineService());
            mScriptExecutions.put(viewId, execution);
        } catch (Exception e) {
            throw new IllegalArgumentException("工程无法运行：" + e.getMessage(), e);
        }
    }


    boolean stopScript(String viewId) {
        ScriptExecution execution = mScriptExecutions.get(viewId);
        if (execution == null) return false;
        ScriptEngine engine = execution.getEngine();
        if (engine == null) return false;
        mScriptExecutions.remove(viewId);
        if (engine.isDestroyed()) return false;
        engine.forceStop();
        return true;
    }

    int stopAllScripts() {
        // Phase1：扩展的「停止全部」同样要覆盖 :script/:native 宿主进程里的任务；
        // stoppedCount 仍只统计主进程旧引擎的脚本（宿主任务数在主进程侧没有同步计数）。
        int count = AutoJs.getInstance().getScriptEngineService().getEngines().size();
        HostScripts.stopAllScripts(com.stardust.app.GlobalAppContext.get());
        mScriptExecutions.clear();
        return count;
    }

    private String getName(JsonObject data) {
        JsonElement element = data.get("name");
        if (element instanceof JsonNull) {
            return null;
        }
        return element.getAsString();
    }

    private void saveScript(String name, String script) {
        if (TextUtils.isEmpty(name)) {
            name = "untitled";
        }
        name = PFiles.getNameWithoutExtension(name);
        if (!name.endsWith(".js")) {
            name = name + ".js";
        }
        File file = new File(Pref.getScriptDirPath(), name);
        PFiles.ensureDir(file.getPath());
        PFiles.write(file, script);
        GlobalAppContext.toast(R.string.text_script_save_successfully);
    }


    @SuppressLint("CheckResult")
    private void saveProject(String name, String dir) {
        if (TextUtils.isEmpty(name)) {
            name = "untitled";
        }
        name = PFiles.getNameWithoutExtension(name);
        File toDir = new File(Pref.getScriptDirPath(), name);
        Observable.fromCallable(() -> {
            copyDir(new File(dir), toDir);
            return toDir.getPath();
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(dest ->
                                GlobalAppContext.toast(R.string.text_project_save_success, dest),
                        err ->
                                GlobalAppContext.toast(R.string.text_project_save_error, err.getMessage())
                        );

    }

    private void copyDir(File fromDir, File toDir) throws FileNotFoundException {
        toDir.mkdirs();
        File[] files = fromDir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                copyDir(file, new File(toDir, file.getName()));
            } else {
                FileOutputStream fos = new FileOutputStream(new File(toDir, file.getName()));
                PFiles.write(new FileInputStream(file), fos, true);
            }
        }
    }

}
