package com.stardust.util;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.FileProvider;

import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import com.stardust.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONObject;


public class IntentUtil {

    private static final String TAG = "IntentUtil";
    private static final String VIEWER_CACHE_DIR = "viewer-cache";
    private static final int VIEWER_CACHE_MAX_FILES = 20;
    private static final long VIEWER_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L;
    private static final AtomicLong VIEWER_CACHE_SEQ = new AtomicLong(System.currentTimeMillis());

    /** app.viewFile(path,{fresh:true}) 的结果；保留 copied/fallback 原因，避免静默回退。 */
    @Keep
    public static final class ViewerResult {
        public final boolean opened;
        public final boolean fresh;
        public final boolean fallback;
        public final String viewerKey;
        public final String path;
        public final String fallbackReason;
        public final String mimeType;

        public ViewerResult(boolean opened, boolean fresh, boolean fallback, String viewerKey,
                            String path, String fallbackReason, String mimeType) {
            this.opened = opened;
            this.fresh = fresh;
            this.fallback = fallback;
            this.viewerKey = viewerKey;
            this.path = path;
            this.fallbackReason = fallbackReason;
            this.mimeType = mimeType;
        }

        public boolean isOpened() { return opened; }
        public boolean isFresh() { return fresh; }
        public boolean isFallback() { return fallback; }
        public String getViewerKey() { return viewerKey; }
        public String getPath() { return path; }
        public String getFallbackReason() { return fallbackReason; }
        public String getMimeType() { return mimeType; }

        /** 给 Node/QuickJS 的 JSON 结果；Rhino 仍可直接读 getter。 */
        public String toJson() {
            try {
                JSONObject json = new JSONObject();
                json.put("opened", opened);
                json.put("fresh", fresh);
                json.put("fallback", fallback);
                json.put("viewerKey", viewerKey == null ? JSONObject.NULL : viewerKey);
                json.put("path", path == null ? JSONObject.NULL : path);
                json.put("fallbackReason", fallbackReason == null ? JSONObject.NULL : fallbackReason);
                json.put("mimeType", mimeType == null ? JSONObject.NULL : mimeType);
                return json.toString();
            } catch (Throwable error) {
                return "{\"opened\":false,\"fresh\":false,\"fallback\":true,"
                        + "\"fallbackReason\":\"JSON encode failed\"}";
            }
        }
    }

    /**
     * 给**图片**的 content URI 追加一个版本参数 `v=<长度>-<修改时间>`。
     *
     * <p>为什么需要：`app.viewFile()` 走系统 ACTION_VIEW，本应用不负责解码/显示；同一个路径
     * 经 FileProvider 得到的 content URI 在文件被重写前后**完全一样**，外部查看器按 URI/路径缓存时
     * 就会「改了图还显示旧图」（真实反馈：同名重写 PNG 后 viewFile 仍是上一版，换文件名才正常）。
     * 这里只改我们交给外部查看器的 URI，让它有机会把重写后的图片当成新资源；
     * FileProvider 解析路径时忽略 query，所以读取仍然正常。
     *
     * <p>边界：这**不能**清除外部查看器自己的缓存，也不能保证所有查看器都按完整 URI 做键。
     */
    private static Uri withImageVersion(Uri uri, String path, String mimeType) {
        if (uri == null || path == null || mimeType == null || !mimeType.startsWith("image/")) {
            return uri;
        }
        try {
            File file = new File(path);
            if (!file.isFile()) {
                return uri;
            }
            return uri.buildUpon()
                    .appendQueryParameter("v", file.length() + "-" + file.lastModified())
                    .build();
        } catch (Exception e) {
            return uri;
        }
    }

    public static boolean chatWithQQ(Context context, String qq) {
        try {
            String url = "mqqwpa://im/chat?chat_type=wpa&uin=" + qq;
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception exception) {
            Log.w("IntentUtil", "IntentUtil 捕获异常（原 printStackTrace；堆栈即定位）", exception);
            return false;
        }
    }

    public static boolean joinQQGroup(Context context, String key) {
        Intent intent = new Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setData(Uri.parse("mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26k%3D" + key));
        try {
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }


    public static boolean sendMailTo(Context context, String sendTo, @Nullable String title, @Nullable String content) {
        try {
            Uri uri = Uri.parse("mailto:" + sendTo);
            String[] email = {sendTo};
            Intent intent = new Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(Intent.EXTRA_CC, email);
            if (title != null)
                intent.putExtra(Intent.EXTRA_SUBJECT, title);
            if (content != null)
                intent.putExtra(Intent.EXTRA_TEXT, content);
            context.startActivity(Intent.createChooser(intent, ""));
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w("IntentUtil", "IntentUtil 捕获异常（原 printStackTrace；堆栈即定位）", e);
            return false;
        }
    }

    public static boolean sendMailTo(Context context, String sendTo) {
        return sendMailTo(context, sendTo, null, null);
    }

    public static boolean browse(Context context, String link) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException ignored) {
            return false;
        }

    }

    public static boolean shareText(Context context, String text) {
        try {
            context.startActivity(new Intent(Intent.ACTION_SEND)
                    .putExtra(Intent.EXTRA_TEXT, text)
                    .setType("text/plain"));
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w("IntentUtil", "IntentUtil 捕获异常（原 printStackTrace；堆栈即定位）", e);
            return false;
        }
    }

    public static boolean goToAppDetailSettings(Context context, String packageName) {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.addCategory(Intent.CATEGORY_DEFAULT);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.setData(Uri.parse("package:" + packageName));
            context.startActivity(i);
            return true;
        } catch (ActivityNotFoundException ignored) {
            return false;
        }
    }

    public static boolean goToAppDetailSettings(Context context) {
        return goToAppDetailSettings(context, context.getPackageName());
    }

    public static void installApk(Context context, String path, String fileProviderAuthority) throws ActivityNotFoundException {
        Uri uri = getUriOfFile(context, path, fileProviderAuthority);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        context.startActivity(intent);
    }

    public static void installApkOrToast(Context context, String path, String fileProviderAuthority) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ApkInstallActivity.start(context, path, fileProviderAuthority);
            return;
        }
        try {
            installApk(context, path, fileProviderAuthority);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            Log.w("IntentUtil", "IntentUtil 捕获异常（原 printStackTrace；堆栈即定位）", e);
            Toast.makeText(context, R.string.error_activity_not_found_for_apk_installing, Toast.LENGTH_SHORT).show();
        }
    }

    public static boolean viewFile(Context context, String path, String fileProviderAuthority) {
        String mimeType = MimeTypes.fromFileOr(path, "*/*");
        return viewFile(context, path, mimeType, fileProviderAuthority);
    }

    /** 供 Rhino/Node 直接调用的 JSON 结果（无第二参默认行为不变）。 */
    @Keep
    public static String viewFileResultJson(Context context, String path, String fileProviderAuthority, boolean fresh) {
        return viewFileResult(context, path, null, fileProviderAuthority, fresh).toJson();
    }

    public static Uri getUriOfFile(Context context, String path, String fileProviderAuthority) {
        Uri uri;
        if (fileProviderAuthority == null) {
            uri = Uri.parse("file://" + path);
        } else {
            uri = FileProvider.getUriForFile(context, fileProviderAuthority, new File(path));
        }
        return uri;
    }

    public static boolean viewFile(Context context, Uri uri, String mimeType, String fileProviderAuthority) {
        if (uri.getScheme().equals("file")) {
            return viewFile(context, uri.getPath(), mimeType, fileProviderAuthority);
        } else {
            try {
                context.startActivity(new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, mimeType)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
                return true;
            } catch (Exception e) {
                Log.w("IntentUtil", "IntentUtil 捕获异常（原 printStackTrace；堆栈即定位）", e);
                return false;
            }
        }
    }

    public static boolean viewFile(Context context, String path, String mimeType, String fileProviderAuthority) {
        try {
            Uri uri = getUriOfFile(context, path, fileProviderAuthority);
            uri = withImageVersion(uri, path, mimeType);
            Log.d(TAG, "viewFile uri=" + uri + " mime=" + mimeType);
            context.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mimeType)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w("IntentUtil", "IntentUtil 捕获异常（原 printStackTrace；堆栈即定位）", e);
            return false;
        }
    }

    /**
     * fresh=true：先复制到 cacheDir/viewer-cache 的唯一文件名，再用该副本的 content URI 打开。
     * 复制失败（或副本 URI 打不开）会回退到原路径，并在 ViewerResult 中如实标 fallback/reason。
     */
    public static ViewerResult viewFileResult(Context context, String path, @Nullable String mimeType,
                                              String fileProviderAuthority, boolean fresh) {
        String resolvedMime = mimeType == null ? MimeTypes.fromFileOr(path, "*/*") : mimeType;
        File source = new File(path);
        String fallbackReason = null;

        if (fresh) {
            try {
                File copy = copyToViewerCache(context, source);
                boolean opened = openViewer(context, copy, resolvedMime, fileProviderAuthority, false);
                return new ViewerResult(opened, true, false, copy.getName(),
                        copy.getAbsolutePath(), null, resolvedMime);
            } catch (Throwable error) {
                fallbackReason = shortError(error);
                Log.w(TAG, "viewFile fresh copy failed, fallback to original path: " + fallbackReason, error);
            }
        }

        try {
            boolean opened = openViewer(context, source, resolvedMime, fileProviderAuthority, true);
            return new ViewerResult(opened, false, fallbackReason != null, null,
                    source.getAbsolutePath(), fallbackReason, resolvedMime);
        } catch (Throwable error) {
            String reason = fallbackReason != null ? fallbackReason : shortError(error);
            Log.w(TAG, "viewFile open failed: " + reason, error);
            return new ViewerResult(false, false, fallbackReason != null, null,
                    source.getAbsolutePath(), reason, resolvedMime);
        }
    }

    private static boolean openViewer(Context context, File file, String mimeType,
                                      String fileProviderAuthority, boolean withVersion) {
        Uri uri = getUriOfFile(context, file.getAbsolutePath(), fileProviderAuthority);
        if (withVersion) {
            uri = withImageVersion(uri, file.getAbsolutePath(), mimeType);
        }
        Log.d(TAG, "viewFile uri=" + uri + " mime=" + mimeType);
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mimeType)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            return true;
        } catch (ActivityNotFoundException error) {
            Log.w("IntentUtil", "IntentUtil 捕获异常（原 printStackTrace；堆栈即定位）", error);
            return false;
        }
    }

    /**
     * 把源文件复制成 viewer-cache 下的唯一副本。
     * 文件名：原名-长度-修改时间-序.<ext>；复制前清理 >24h 或超过 20 个的旧副本。
     */
    private static File copyToViewerCache(Context context, File source) throws IOException {
        if (!source.isFile()) {
            throw new IOException("源文件不存在或不是普通文件");
        }
        if (!source.canRead()) {
            throw new IOException("源文件不可读");
        }
        File cacheRoot = context.getCacheDir();
        if (cacheRoot == null) {
            throw new IOException("cacheDir 不可用");
        }
        File cacheDir = new File(cacheRoot, VIEWER_CACHE_DIR);
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs() && !cacheDir.isDirectory()) {
            throw new IOException("viewer-cache 目录创建失败");
        }
        cleanupViewerCache(cacheDir);

        String name = source.getName();
        String extension = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            extension = name.substring(dot);
            name = name.substring(0, dot);
        }
        name = name.replaceAll("[\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (name.isEmpty()) {
            name = "file";
        }
        if (name.length() > 80) {
            name = name.substring(0, 80);
        }
        extension = extension.replaceAll("[\\/:*?\"<>|\\p{Cntrl}]", "_");
        String copyName = name + "-" + source.length() + "-" + source.lastModified()
                + "-" + VIEWER_CACHE_SEQ.getAndIncrement() + extension;
        File target = new File(cacheDir, copyName);
        FileInputStream input = null;
        FileOutputStream output = null;
        try {
            input = new FileInputStream(source);
            output = new FileOutputStream(target);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            output.flush();
        } catch (IOException error) {
            // Don't leave a half-written cache entry behind.
            try { if (target.exists()) target.delete(); } catch (Throwable ignored) { }
            throw error;
        } finally {
            try { if (output != null) output.close(); } catch (Throwable ignored) { }
            try { if (input != null) input.close(); } catch (Throwable ignored) { }
        }
        if (!target.isFile() || target.length() != source.length()) {
            try { target.delete(); } catch (Throwable ignored) { }
            throw new IOException("副本长度校验失败");
        }
        return target;
    }

    private static void cleanupViewerCache(File cacheDir) {
        try {
            File[] entries = cacheDir.listFiles();
            if (entries == null) {
                return;
            }
            List<File> files = new ArrayList<>();
            for (File entry : entries) {
                if (entry.isFile()) {
                    files.add(entry);
                }
            }
            long now = System.currentTimeMillis();
            files.sort(Comparator.comparingLong(File::lastModified));
            List<File> expired = new ArrayList<>();
            for (File file : files) {
                if (now - file.lastModified() > VIEWER_CACHE_MAX_AGE_MS) {
                    expired.add(file);
                }
            }
            for (File file : expired) {
                if (file.delete()) files.remove(file);
            }
            while (files.size() >= VIEWER_CACHE_MAX_FILES) {
                File oldest = files.remove(0);
                try { oldest.delete(); } catch (Throwable ignored) { }
            }
        } catch (Throwable error) {
            Log.w(TAG, "viewer-cache cleanup failed (ignored): " + shortError(error));
        }
    }

    private static String shortError(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    public static boolean editFile(Context context, String path, String fileProviderAuthority) {
        try {
            String mimeType = MimeTypes.fromFileOr(path, "*/*");
            Uri uri = getUriOfFile(context, path, fileProviderAuthority);
            context.startActivity(new Intent(Intent.ACTION_EDIT)
                    .setDataAndType(uri, mimeType)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w("IntentUtil", "IntentUtil 捕获异常（原 printStackTrace；堆栈即定位）", e);
            return false;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public static void requestAppUsagePermission(Context context) {
        Intent intent = new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w("IntentUtil", "IntentUtil 捕获异常（原 printStackTrace；堆栈即定位）", e);
        }
    }
}