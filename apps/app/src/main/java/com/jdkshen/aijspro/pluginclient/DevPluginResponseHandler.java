package com.jdkshen.aijspro.pluginclient;

import android.annotation.SuppressLint;
import android.text.TextUtils;
import android.widget.Toast;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.stardust.app.GlobalAppContext;
import com.stardust.autojs.execution.ScriptExecution;
import com.stardust.autojs.engine.ScriptEngine;
import com.stardust.autojs.project.ProjectLauncher;
import com.stardust.autojs.script.StringScriptSource;
import com.stardust.io.Zip;
import com.stardust.pio.PFiles;
import com.stardust.util.MD5;

import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.model.script.Scripts;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.HashMap;

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
        if (cacheDir.exists()) {
            if (cacheDir.isDirectory()) {
                PFiles.deleteFilesOfDir(cacheDir);
            } else {
                cacheDir.delete();
                cacheDir.mkdirs();
            }
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
        if (TextUtils.isEmpty(name)) {
            name = "[" + viewId + "]";
        } else {
            name = PFiles.getNameWithoutExtension(name);
        }
        mScriptExecutions.put(viewId, Scripts.INSTANCE.run(new StringScriptSource("[remote]" + name, script)));
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
            e.printStackTrace();
            GlobalAppContext.toast(R.string.text_invalid_project);
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
        int count = AutoJs.getInstance().getScriptEngineService().stopAll();
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
