package com.stardust.util;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.FileProvider;

import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import com.stardust.R;

import java.io.File;


public class IntentUtil {

    private static final String TAG = "IntentUtil";

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
            exception.printStackTrace();
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
            e.printStackTrace();
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
            e.printStackTrace();
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
            e.printStackTrace();
            Toast.makeText(context, R.string.error_activity_not_found_for_apk_installing, Toast.LENGTH_SHORT).show();
        }
    }

    public static boolean viewFile(Context context, String path, String fileProviderAuthority) {
        String mimeType = MimeTypes.fromFileOr(path, "*/*");
        return viewFile(context, path, mimeType, fileProviderAuthority);
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
                e.printStackTrace();
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
            e.printStackTrace();
            return false;
        }
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
            e.printStackTrace();
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
            e.printStackTrace();
        }
    }
}
