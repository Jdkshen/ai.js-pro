package com.jdkshen.aijspro.flutterpoc;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.external.fileprovider.AppFileProvider;
import com.jdkshen.aijspro.model.sample.SampleFiles;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 文件桥：新建/改名/删除（本地回收站）/回收站列表与恢复/分享/其他应用打开/在编辑器打开。
 *
 * <p>所有写操作都限制在脚本目录内（示例目录只读），沿用重建线 WorkspaceFiles 的契约与
 * 隐私回收站布局（{@code .autojs-glass-trash/<token>/payload} + 应用私有 records），
 * 界面侧（browser.dart / settings_page.dart 回收站）不需要任何改动。
 */
final class PocWorkspace {

    private static final String TRASH = ".autojs-glass-trash";
    /** 剪贴板放应用私有偏好（跨 Activity 保留，重启后仍可粘贴）。 */
    private static final String PREFS = "flutter-glass";
    private static final String KEY_CLIPBOARD = "workspace.clipboard";
    private static final String EDITOR_ACTIVITY = "com.jdkshen.aijspro.ui.editor.ProCodeEditorActivity";
    private static final int MAX_NAME = 128;

    private final Activity activity;
    private final File scripts;
    private final File samples;
    private final File records;

    PocWorkspace(Activity activity) throws IOException {
        this.activity = activity;
        this.scripts = new File(Pref.getScriptDirPath()).getCanonicalFile();
        this.samples = SampleFiles.sampleRoot(activity).getCanonicalFile();
        this.records = new File(activity.getFilesDir(), "workspace-trash-records").getCanonicalFile();
    }

    Object execute(String method, JSONObject args) throws Exception {
        switch (method) {
            case "createFile":
                return create(args, false);
            case "createFolder":
                return create(args, true);
            case "createProject":
                return project(args);
            case "rename":
                return rename(args);
            case "delete":
                return trash(args);
            case "deletePaths":
                return trashMany(args);
            case "fileClipboard":
                return clipboard(args);
            case "pastePaths":
                return paste(args);
            case "trashList":
                return trashList();
            case "restoreTrash":
                return restore(args);
            case "workspaceContext":
                return directoryContext(new File(args.getString("path")));
            case "share":
            case "shareFile":
                return share(args);
            case "openWith":
                return openWith(args);
            case "aceOpenFile":
                return openInEditor(args);
            default:
                throw new UnsupportedOperationException("POC 尚未实现文件桥方法：" + method);
        }
    }

    // ---- 新建 ------------------------------------------------------------

    private Object create(JSONObject args, boolean folder) throws Exception {
        File parent = writableDirectory(args.getString("path"));
        File destination = absent(parent, validName(args.getString("name")));
        if (folder) {
            if (!destination.mkdir()) {
                throw new IOException("创建文件夹失败");
            }
        } else {
            JSONObject context = directoryContext(parent);
            String engine = engine(args);
            if (context.optBoolean("locked") && !"node".equals(engine)) {
                throw new IOException("当前 Node 项目使用 Node.js，请选择 Node.js 文件模板");
            }
            if (!destination.getName().matches("(?i).+\\.(js|mjs)")) {
                throw new IOException("脚本文件名必须以 .js 或 .mjs 结尾");
            }
            writeSmall(destination, hello(engine));
        }
        return new JSONObject().put("path", destination.getPath());
    }

    private Object project(JSONObject args) throws Exception {
        File parent = writableDirectory(args.getString("path"));
        String projectName = validName(args.getString("name"));
        String engine = engine(args);
        File destination = absent(parent, projectName);
        String mainName = "node".equals(engine) ? "main.node.js" : "main.js";
        File staging = new File(parent, ".poc-tmp-" + token());
        if (!staging.mkdir()) {
            throw new IOException("无法创建项目临时目录");
        }
        try {
            JSONObject config = new JSONObject()
                    .put("name", projectName)
                    .put("main", mainName)
                    .put("packageName", "org.local.project.p" + token())
                    .put("versionCode", 1)
                    .put("versionName", "1.0.0")
                    .put("assets", new JSONArray())
                    .put("scripts", new JSONObject())
                    .put("plugins", new JSONObject())
                    .put("encryptLevel", 0)
                    .put("useFeatures", new JSONArray())
                    .put("launchConfig", new JSONObject().put("displaySplash", true)
                            .put("hideLogs", false).put("splashText", "").put("stableMode", false))
                    .put("optimization", new JSONObject().put("removeOpenCv", false)
                            .put("unusedResources", false).put("obfuscateComponents", false))
                    .put("permissionConfig", new JSONObject()
                            .put("manifestPermissions", new JSONArray())
                            .put("requestListOnStartup", new JSONArray()));
            if ("node".equals(engine)) {
                config.put("type", "node").put("features", new JSONObject().put("nodejs", "auto"));
            }
            writeSmall(new File(staging, "project.json"), config.toString(2));
            writeSmall(new File(staging, mainName), hello(engine));
            moveNew(staging, destination);
        } finally {
            removeTree(staging);
        }
        File main = new File(destination, mainName);
        return new JSONObject().put("path", main.getPath())
                .put("dir", destination.getPath()).put("mainPath", main.getPath());
    }

    // ---- 改名 / 删除 / 恢复 ----------------------------------------------

    private Object rename(JSONObject args) throws Exception {
        File source = checked(args.getString("path"), scripts, false);
        if (!source.exists()) {
            throw new IOException("原文件已经不存在");
        }
        String newName = validName(args.getString("name"));
        if (source.getName().equals(newName)) {
            return new JSONObject().put("path", source.getPath());
        }
        File destination = absent(source.getParentFile(), newName);
        moveNew(source, destination);
        return new JSONObject().put("path", destination.getPath());
    }

    private Object trash(JSONObject args) throws Exception {
        File source = checked(args.getString("path"), scripts, false);
        if (!source.exists()) {
            throw new IOException("文件已经不存在");
        }
        File root = trashRoot();
        if (!records.isDirectory() && !records.mkdirs()) {
            throw new IOException("无法创建私有回收记录");
        }
        String token = token();
        File container = new File(root, token);
        File record = new File(records, token + ".json");
        File payload = new File(container, "payload");
        if (!container.mkdir()) {
            throw new IOException("无法创建回收目录");
        }
        boolean moved = false;
        try {
            JSONObject value = new JSONObject()
                    .put("token", token)
                    .put("name", source.getName())
                    .put("originalPath", source.getPath())
                    .put("deletedAt", System.currentTimeMillis())
                    .put("directory", source.isDirectory());
            writeSmall(record, value.toString());
            moveNew(source, payload);
            moved = true;
            return new JSONObject().put("path", source.getPath()).put("trashPath", payload.getPath());
        } finally {
            if (!moved) {
                record.delete();
                removeTree(container);
            }
        }
    }

    private File trashRoot() throws IOException {
        File root = new File(scripts, TRASH);
        if (!root.isDirectory() && !root.mkdir()) {
            throw new IOException("无法创建本地回收站");
        }
        return root;
    }

    private JSONObject record(String token) throws Exception {
        if (!token.matches("[a-f0-9]{32}")) {
            throw new IOException("无效的回收记录");
        }
        File file = new File(records, token + ".json");
        if (!file.isFile() || file.length() > 8192) {
            throw new IOException("回收记录不存在");
        }
        JSONObject value = new JSONObject(readSmall(file));
        if (!token.equals(value.getString("token"))) {
            throw new IOException("回收记录不一致");
        }
        File original = checked(value.getString("originalPath"), scripts, false);
        if (!original.getName().equals(value.getString("name"))) {
            throw new IOException("回收路径不一致");
        }
        return value;
    }

    private File payload(String token) throws IOException {
        File payload = new File(new File(trashRoot(), token), "payload");
        if (!payload.exists()) {
            throw new IOException("回收文件不存在");
        }
        return payload;
    }

    private JSONArray trashList() throws Exception {
        JSONArray result = new JSONArray();
        if (!records.isDirectory()) {
            return result;
        }
        File[] files = records.listFiles();
        if (files == null) {
            throw new IOException("无法读取回收记录");
        }
        List<JSONObject> values = new ArrayList<>();
        for (File file : files) {
            String token = file.getName().replaceFirst("\\.json$", "");
            if (!token.matches("[a-f0-9]{32}")) {
                continue;
            }
            try {
                JSONObject value = record(token);
                payload(token);
                values.add(value);
            } catch (Exception missingOrDamaged) {
                // 不完整的记录既不能展示也不能篡改数据。
            }
        }
        values.sort((a, b) -> Long.compare(b.optLong("deletedAt"), a.optLong("deletedAt")));
        for (JSONObject value : values) {
            result.put(value);
        }
        return result;
    }

    private Object restore(JSONObject args) throws Exception {
        String token = args.getString("token");
        JSONObject value = record(token);
        File source = payload(token);
        File destination = checked(value.getString("originalPath"), scripts, false);
        if (destination.getParentFile() == null || !destination.getParentFile().isDirectory()) {
            throw new IOException("原父目录已不存在，请先恢复父目录");
        }
        if (destination.exists()) {
            throw new IOException("原位置已有同名文件，回收文件保持不变");
        }
        moveNew(source, destination);
        new File(records, token + ".json").delete();
        removeTree(source.getParentFile());
        return new JSONObject().put("path", destination.getPath());
    }

    // ---- 批量删除 / 剪贴板（复制移动） -----------------------------------

    /** 批量移入回收站；逐条容错，返回 {deleted:[原路径], errors:[说明]}。 */
    private Object trashMany(JSONObject args) throws Exception {
        JSONArray paths = args.optJSONArray("paths");
        if (paths == null || paths.length() == 0) {
            throw new IOException("没有选择文件");
        }
        JSONArray deleted = new JSONArray();
        JSONArray errors = new JSONArray();
        for (int i = 0; i < paths.length(); i++) {
            try {
                trash(new JSONObject().put("path", paths.getString(i)));
                deleted.put(paths.getString(i));
            } catch (Exception error) {
                errors.put(paths.optString(i) + "：" + (error.getMessage() == null
                        ? error.toString() : error.getMessage()));
            }
        }
        return new JSONObject().put("deleted", deleted).put("errors", errors);
    }

    /** 文件剪贴板：{mode?: "copy"|"move", paths?: [...], clear?: true} → {mode, paths, count}。 */
    private Object clipboard(JSONObject args) throws Exception {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (args.optBoolean("clear", false)) {
            prefs.edit().remove(KEY_CLIPBOARD).apply();
            return clipboardState("", new JSONArray());
        }
        JSONArray paths = args.optJSONArray("paths");
        String mode = args.optString("mode", "");
        if (paths != null && !mode.isEmpty()) {
            if (!"copy".equals(mode) && !"move".equals(mode)) {
                throw new IOException("未知的剪贴板模式");
            }
            JSONArray clean = new JSONArray();
            for (int i = 0; i < paths.length(); i++) {
                File source = checked(paths.getString(i), scripts, false);
                if (!source.exists()) {
                    throw new IOException("文件已经不存在：" + source.getName());
                }
                clean.put(source.getPath());
            }
            if (clean.length() == 0) {
                throw new IOException("没有选择文件");
            }
            JSONObject value = new JSONObject().put("mode", mode).put("paths", clean)
                    .put("at", System.currentTimeMillis());
            prefs.edit().putString(KEY_CLIPBOARD, value.toString()).apply();
            return clipboardState(mode, clean);
        }
        String raw = prefs.getString(KEY_CLIPBOARD, "");
        if (raw == null || raw.isEmpty()) {
            return clipboardState("", new JSONArray());
        }
        try {
            JSONObject value = new JSONObject(raw);
            JSONArray stored = value.optJSONArray("paths");
            JSONArray alive = new JSONArray();
            if (stored != null) {
                for (int i = 0; i < stored.length(); i++) {
                    File file = new File(stored.optString(i)).getCanonicalFile();
                    if (within(file, scripts) && file.exists()) {
                        alive.put(file.getPath());
                    }
                }
            }
            return clipboardState(value.optString("mode", ""), alive);
        } catch (Exception damaged) {
            prefs.edit().remove(KEY_CLIPBOARD).apply();
            return clipboardState("", new JSONArray());
        }
    }

    private JSONObject clipboardState(String mode, JSONArray paths) throws Exception {
        return new JSONObject().put("mode", mode == null ? "" : mode)
                .put("paths", paths == null ? new JSONArray() : paths)
                .put("count", paths == null ? 0 : paths.length());
    }

    /** 粘贴剪贴板到 target 目录；复制保留剪贴板，移动成功后清空。 */
    private Object paste(JSONObject args) throws Exception {
        File target = writableDirectory(args.getString("path"));
        JSONObject state = (JSONObject) clipboard(new JSONObject());
        String mode = state.optString("mode", "");
        JSONArray sources = state.optJSONArray("paths");
        if (mode.isEmpty() || sources == null || sources.length() == 0) {
            throw new IOException("剪贴板是空的，请先复制或剪切文件");
        }
        boolean moving = "move".equals(mode);
        JSONArray pasted = new JSONArray();
        JSONArray errors = new JSONArray();
        for (int i = 0; i < sources.length(); i++) {
            String raw = sources.optString(i);
            try {
                File source = checked(raw, scripts, false);
                if (!source.exists()) {
                    errors.put(source.getName() + "：原文件已不存在");
                    continue;
                }
                if (moving && (source.equals(target) || within(target, source))) {
                    errors.put(source.getName() + "：不能移动到自身或子目录");
                    continue;
                }
                File destination = availableName(target, source.getName());
                if (moving) {
                    moveNew(source, destination);
                } else {
                    copyTree(source, destination);
                }
                pasted.put(destination.getPath());
            } catch (Exception error) {
                errors.put(new File(raw).getName() + "：" + (error.getMessage() == null
                        ? error.toString() : error.getMessage()));
            }
        }
        if (moving && pasted.length() > 0) {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_CLIPBOARD).apply();
        }
        return new JSONObject().put("pasted", pasted).put("errors", errors)
                .put("mode", mode).put("cleared", moving && pasted.length() > 0);
    }

    /** 目标目录里不重名的名字：同名时用 "名字 (1)" / "名字 (1).ext"。 */
    private static File availableName(File parent, String name) throws IOException {
        File candidate = new File(parent, name).getCanonicalFile();
        if (!candidate.exists()) {
            return candidate;
        }
        String base = name;
        String extension = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            extension = name.substring(dot);
        }
        for (int index = 1; index < 1000; index++) {
            candidate = new File(parent, base + " (" + index + ")" + extension).getCanonicalFile();
            if (!candidate.exists()) {
                return candidate;
            }
        }
        throw new IOException("目标目录里同名文件过多");
    }

    // ---- 打开 / 分享 -----------------------------------------------------

    private Object openInEditor(JSONObject args) throws Exception {
        File file = new File(args.getString("path")).getCanonicalFile();
        if (!within(file, scripts) && !within(file, samples)) {
            throw new IOException("文件不在工作区内");
        }
        if (!file.exists()) {
            return new JSONObject().put("path", file.getPath()).put("exists", false);
        }
        if (!file.isFile()) {
            throw new IOException("只能打开文件");
        }
        activity.startActivity(new Intent().setClassName(activity, EDITOR_ACTIVITY)
                .putExtra("path", file.getPath()));
        return new JSONObject().put("path", file.getPath())
                .put("exists", true).put("directory", false);
    }

    private Object share(JSONObject args) throws Exception {
        File file = readable(args.getString("path"));
        if (file.isFile()) {
            shareUri(file, mimeOf(file.getName()));
        } else {
            shareUri(zipForShare(file), "application/zip");
        }
        return Boolean.TRUE;
    }

    private Object openWith(JSONObject args) throws Exception {
        File file = readable(args.getString("path"));
        if (!file.isFile()) {
            throw new IOException("只能对文件使用其他应用打开");
        }
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(AppFileProvider.getUriForFile(activity, file), mimeOf(file.getName()))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
        return Boolean.TRUE;
    }

    private void shareUri(File file, String mime) {
        Uri uri = AppFileProvider.getUriForFile(activity, file);
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(Intent.createChooser(intent, "分享"));
    }

    private File zipForShare(File directory) throws IOException {
        File cache = new File(activity.getCacheDir(), "workspace-share");
        if (!cache.isDirectory() && !cache.mkdirs()) {
            throw new IOException("无法创建分享缓存目录");
        }
        File zip = new File(cache, directory.getName() + "-" + token() + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            zipTree(directory, directory.getName(), out, 0);
        }
        if (zip.length() > 128L * 1024 * 1024) {
            zip.delete();
            throw new IOException("目录超过 128MB，请进入子目录后分享文件");
        }
        return zip;
    }

    private void zipTree(File file, String path, ZipOutputStream zip, int depth) throws IOException {
        if (depth > 32) {
            throw new IOException("目录层级过深");
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                throw new IOException("无法读取目录：" + file.getName());
            }
            zip.putNextEntry(new ZipEntry(path + "/"));
            zip.closeEntry();
            for (File child : children) {
                zipTree(child, path + "/" + child.getName(), zip, depth + 1);
            }
            return;
        }
        zip.putNextEntry(new ZipEntry(path));
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                zip.write(buffer, 0, count);
            }
        }
        zip.closeEntry();
    }

    private String mimeOf(String name) {
        String extension = name.contains(".")
                ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mime == null ? "application/octet-stream" : mime;
    }

    // ---- 上下文 / 校验 ---------------------------------------------------

    private JSONObject directoryContext(File directory) throws Exception {
        File current = directory.getCanonicalFile();
        while (current != null && within(current, scripts)) {
            File config = new File(current, "project.json");
            if (config.isFile()) {
                if (config.length() > 2 * 1024 * 1024) {
                    throw new IOException("项目配置过大");
                }
                JSONObject project = new JSONObject(readSmall(config));
                boolean node = "node".equals(project.optString("type"));
                return new JSONObject().put("engine", node ? "node" : "rhino")
                        .put("locked", node).put("projectPath", current.getPath());
            }
            if (current.equals(scripts)) {
                break;
            }
            current = current.getParentFile();
        }
        return new JSONObject().put("engine", "rhino").put("locked", false);
    }

    private File readable(String path) throws Exception {
        File file = new File(path).getCanonicalFile();
        if (within(file, scripts) || within(file, samples)) {
            return file;
        }
        throw new IOException("文件不在工作区内");
    }

    private File writableDirectory(String path) throws Exception {
        File directory = checked(path, scripts, true);
        if (!directory.isDirectory()) {
            throw new IOException("目标目录不存在");
        }
        return directory;
    }

    private File checked(String path, File root, boolean allowRoot) throws Exception {
        if (path == null || path.trim().isEmpty()) {
            throw new IOException("缺少路径");
        }
        File file = new File(path).getCanonicalFile();
        if (file.equals(root)) {
            if (!allowRoot) {
                throw new IOException("不能修改工作区根目录");
            }
            return file;
        }
        if (!within(file, root)) {
            throw new IOException("路径不在工作区内");
        }
        if (file.equals(new File(root, TRASH))) {
            throw new IOException("不能直接修改回收站");
        }
        return file;
    }

    private static boolean within(File file, File root) {
        String path = file.getPath();
        String base = root.getPath();
        return path.equals(base) || path.startsWith(base + File.separator);
    }

    private File absent(File parent, String name) throws IOException {
        File destination = new File(parent, name).getCanonicalFile();
        if (!destination.getParentFile().equals(parent.getCanonicalFile())) {
            throw new IOException("文件名无效");
        }
        if (destination.exists()) {
            throw new IOException("同名文件或文件夹已存在，未覆盖原数据");
        }
        return destination;
    }

    private static String validName(String raw) throws IOException {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw new IOException("文件名不能为空且不超过 " + MAX_NAME + " 个字符");
        }
        if (name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")
                || name.indexOf('\0') >= 0) {
            throw new IOException("文件名包含无效字符");
        }
        return name;
    }

    private static String engine(JSONObject args) throws IOException {
        String engine = args.optString("engine", "rhino");
        if (!"rhino".equals(engine) && !"node".equals(engine)) {
            throw new IOException("未知脚本引擎");
        }
        return engine;
    }

    private static String hello(String engine) {
        return ("node".equals(engine) ? "\"nodejs\";\n\n" : "")
                + "console.log('Hello, AI.js Pro');\n";
    }

    private static void moveNew(File source, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目标目录");
        }
        if (source.renameTo(destination)) {
            return;
        }
        copyTree(source, destination);
        if (!deleteTree(source)) {
            throw new IOException("移动完成但原文件清理失败");
        }
    }

    private static void copyTree(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.mkdir() && !target.isDirectory()) {
                throw new IOException("无法创建目录：" + target);
            }
            File[] children = source.listFiles();
            if (children == null) {
                throw new IOException("无法读取目录：" + source);
            }
            for (File child : children) {
                copyTree(child, new File(target, child.getName()));
            }
            return;
        }
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        }
    }

    private static boolean deleteTree(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteTree(child);
                }
            }
        }
        return file.delete();
    }

    private static void removeTree(File file) {
        deleteTree(file);
    }

    private static String readSmall(File file) throws IOException {
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
                if (out.size() > 2 * 1024 * 1024) {
                    throw new IOException("文件过大");
                }
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeSmall(File file, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}