package com.stardust.autojs.runtime.api;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.util.Log;

import com.stardust.autojs.annotation.ScriptInterface;
import com.stardust.util.IntentUtil;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Created by Stardust on 2017/4/2.
 */

public class AppUtils {

    private Context mContext;
    private volatile WeakReference<Activity> mCurrentActivity = new WeakReference<>(null);
    private final String mFileProviderAuthority;

    public AppUtils(Context context) {
        mContext = context;
        mFileProviderAuthority = null;
    }

    public AppUtils(Context context, String fileProviderAuthority) {
        mContext = context;
        mFileProviderAuthority = fileProviderAuthority;
    }

    @ScriptInterface
    public boolean launchPackage(String packageName) {
        try {
            PackageManager packageManager = mContext.getPackageManager();
            mContext.startActivity(packageManager.getLaunchIntentForPackage(packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            return false;
        }

    }

    @ScriptInterface
    public void sendLocalBroadcastSync(Intent intent) {
        LocalBroadcastManager.getInstance(mContext).sendBroadcastSync(intent);
    }

    @ScriptInterface
    public boolean launchApp(String appName) {
        String pkg = getPackageName(appName);
        if (pkg == null)
            return false;
        return launchPackage(pkg);
    }

    @ScriptInterface
    public String getPackageName(String appName) {
        PackageManager packageManager = mContext.getPackageManager();
        List<ApplicationInfo> installedApplications = packageManager.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo applicationInfo : installedApplications) {
            if (packageManager.getApplicationLabel(applicationInfo).toString().equals(appName)) {
                return applicationInfo.packageName;
            }
        }
        return null;
    }

    @ScriptInterface
    public String getAppName(String packageName) {
        PackageManager packageManager = mContext.getPackageManager();
        try {
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, 0);
            CharSequence appName = packageManager.getApplicationLabel(applicationInfo);
            return appName == null ? null : appName.toString();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * 已安装应用列表。注意：**不要**改回 getInstalledPackages —— 脚本侧的 `app.getInstalledPackages()`
     * 需要返回 JS 数组（见 `__app__.js`），而 Java 方法名被占位后 JS 层就无法覆盖同名成员。
     */
    @ScriptInterface
    public List<PackageInfo> getInstalledPackageList() {
        PackageManager packageManager = mContext.getPackageManager();
        List<PackageInfo> packages = packageManager.getInstalledPackages(0);
        for (PackageInfo info : packages) {
            if (info != null && info.applicationInfo != null) {
                info.applicationInfo = new LabeledApplicationInfo(info.applicationInfo, packageManager);
            }
        }
        return packages;
    }

    /**
     * Auto.js Pro 的 `app.getApkInfo(path)`：解析 APK 文件返回 PackageInfo。
     * 已补上 sourceDir/publicSourceDir，因此 `apkInfo.applicationInfo.label`、
     * `apkInfo.applicationInfo.loadIcon(pm)` 都能直接拿到。
     *
     * @param path APK 文件路径
     * @return 解析失败时返回 null
     */
    @ScriptInterface
    public PackageInfo getApkInfo(String path) {
        PackageManager packageManager = mContext.getPackageManager();
        PackageInfo info = packageManager.getPackageArchiveInfo(path, 0);
        if (info != null && info.applicationInfo != null) {
            info.applicationInfo.sourceDir = path;
            info.applicationInfo.publicSourceDir = path;
            info.applicationInfo = new LabeledApplicationInfo(info.applicationInfo, packageManager);
        }
        return info;
    }

    /**
     * Android 的 ApplicationInfo 只有 `loadLabel(pm)`，而 Auto.js Pro 的脚本习惯直接读
     * `applicationInfo.label`；这里给 applicationInfo 挂上一个带 `getLabel()` 的子类。
     */
    public static class LabeledApplicationInfo extends ApplicationInfo {

        private final PackageManager mPackageManager;

        LabeledApplicationInfo(ApplicationInfo source, PackageManager packageManager) {
            super(source);
            mPackageManager = packageManager;
        }

        /** 对应 Pro 示例里的 `apkInfo.applicationInfo.label`。 */
        public CharSequence getLabel() {
            return loadLabel(mPackageManager);
        }
    }

    @ScriptInterface
    public boolean openAppSetting(String packageName) {
        return IntentUtil.goToAppDetailSettings(mContext, packageName);
    }

    @ScriptInterface
    public String getFileProviderAuthority() {
        return mFileProviderAuthority;
    }

    @ScriptInterface
    public String getAppInfo(String packageName) {
        org.json.JSONObject info = new org.json.JSONObject();
        try {
            info.put("packageName", packageName);
            PackageManager pm = mContext.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            info.put("label", pm.getApplicationLabel(ai).toString());
            android.content.pm.PackageInfo packageInfo = pm.getPackageInfo(packageName, 0);
            info.put("versionName", packageInfo.versionName == null ? "" : packageInfo.versionName);
            info.put("versionCode", packageInfo.versionCode);
        } catch (Exception e) {
            try {
                info.put("packageName", packageName);
                info.put("label", "");
            } catch (org.json.JSONException ignored) {
            }
        }
        return info.toString();
    }

    @Nullable
    public Activity getCurrentActivity() {
        Log.d("App", "getCurrentActivity: " + mCurrentActivity.get());
        return mCurrentActivity.get();
    }

    @ScriptInterface
    public void uninstall(String packageName) {
        mContext.startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @ScriptInterface
    public void viewFile(String path) {
        if (path == null)
            throw new NullPointerException("path == null");
        IntentUtil.viewFile(mContext, path, mFileProviderAuthority);
    }

    @ScriptInterface
    public void editFile(String path) {
        if (path == null)
            throw new NullPointerException("path == null");
        IntentUtil.editFile(mContext, path, mFileProviderAuthority);
    }

    @ScriptInterface
    public void openUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        mContext.startActivity(new Intent(Intent.ACTION_VIEW)
                .setData(Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    public void setCurrentActivity(Activity currentActivity) {
        mCurrentActivity = new WeakReference<>(currentActivity);
        Log.d("App", "setCurrentActivity: " + currentActivity);
    }
}
