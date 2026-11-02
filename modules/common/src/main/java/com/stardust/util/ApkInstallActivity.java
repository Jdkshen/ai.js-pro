package com.stardust.util;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.stardust.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

/**
 * Bridges an APK install through Android 8+'s per-app "unknown sources" permission.
 * The downloaded APK path never leaves this non-exported activity.
 *
 * <p>优先走 {@link PackageInstaller} 会话安装：只把 APK 文件交给系统，不依赖 FileProvider 的
 * content:// URI。真机上曾出现 {@code FileProvider.getUriForFile} 抛
 * "Missing android.support.FILE_PROVIDER_PATHS meta-data"，导致内置更新根本打不开安装器；
 * 会话安装没有该依赖，覆盖升级与全新安装都适用。会话不可用时回退旧的 FileProvider 流程。
 */
public class ApkInstallActivity extends Activity {

    private static final String EXTRA_APK_PATH = "apk_path";
    private static final String EXTRA_FILE_PROVIDER_AUTHORITY = "file_provider_authority";
    private static final String STATE_WAITING_FOR_SETTINGS = "waiting_for_settings";
    private static final String STATE_SKIP_FIRST_RESUME = "skip_first_resume";
    private static final String ACTION_INSTALL_STATUS = "com.stardust.util.APK_INSTALL_STATUS";

    private String mApkPath;
    private String mFileProviderAuthority;
    private boolean mWaitingForSettings;
    private boolean mSkipFirstResume;
    private boolean mInstallerStarted;
    private BroadcastReceiver mSessionReceiver;
    private int mSessionId = -1;

    public static void start(Context context, String apkPath, String fileProviderAuthority) {
        Intent intent = new Intent(context, ApkInstallActivity.class)
                .putExtra(EXTRA_APK_PATH, apkPath)
                .putExtra(EXTRA_FILE_PROVIDER_AUTHORITY, fileProviderAuthority);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mApkPath = getIntent().getStringExtra(EXTRA_APK_PATH);
        mFileProviderAuthority = getIntent().getStringExtra(EXTRA_FILE_PROVIDER_AUTHORITY);
        if (savedInstanceState != null) {
            mWaitingForSettings = savedInstanceState.getBoolean(STATE_WAITING_FOR_SETTINGS);
            mSkipFirstResume = savedInstanceState.getBoolean(STATE_SKIP_FIRST_RESUME);
        }
        if (mApkPath == null || mApkPath.trim().isEmpty()) {
            showInstallErrorAndFinish();
            return;
        }
        continueInstall();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!mWaitingForSettings || isFinishing()) {
            return;
        }
        if (mSkipFirstResume) {
            mSkipFirstResume = false;
            return;
        }
        mWaitingForSettings = false;
        if (canInstallPackages()) {
            startInstaller();
        } else {
            Toast.makeText(this, R.string.text_allow_install_unknown_apps, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_WAITING_FOR_SETTINGS, mWaitingForSettings);
        outState.putBoolean(STATE_SKIP_FIRST_RESUME, mSkipFirstResume);
    }

    @Override
    protected void onDestroy() {
        if (mSessionReceiver != null) {
            try {
                unregisterReceiver(mSessionReceiver);
            } catch (RuntimeException ignored) {
            }
            mSessionReceiver = null;
        }
        super.onDestroy();
    }

    private void continueInstall() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || canInstallPackages()) {
            startInstaller();
            return;
        }
        try {
            mWaitingForSettings = true;
            mSkipFirstResume = true;
            Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivity(settingsIntent);
        } catch (ActivityNotFoundException error) {
            mWaitingForSettings = false;
            showInstallErrorAndFinish();
        }
    }

    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || getPackageManager().canRequestPackageInstalls();
    }

    private void startInstaller() {
        if (mInstallerStarted) {
            return;
        }
        mInstallerStarted = true;
        // 先试会话安装：不依赖 FileProvider。
        if (startSessionInstall()) {
            return;
        }
        try {
            IntentUtil.installApk(this, mApkPath, mFileProviderAuthority);
        } catch (ActivityNotFoundException | IllegalArgumentException | SecurityException error) {
            error.printStackTrace();
            showInstallErrorAndFinish();
            return;
        }
        finish();
    }

    /**
     * 用 PackageInstaller 会话提交安装。返回 true 表示已提交，结果通过广播回传；
     * false 时调用方回退到旧的 FileProvider/ACTION_VIEW 流程。
     */
    private boolean startSessionInstall() {
        File apk = new File(mApkPath);
        if (!apk.isFile() || apk.length() == 0) {
            return false;
        }
        PackageInstaller installer;
        try {
            installer = getPackageManager().getPackageInstaller();
        } catch (RuntimeException error) {
            return false;
        }
        try {
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setSize(apk.length());
            mSessionId = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(mSessionId)) {
                try (FileInputStream input = new FileInputStream(apk);
                     OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                    session.fsync(output);
                }
                mSessionReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        handleSessionStatus(intent);
                    }
                };
                IntentFilter filter = new IntentFilter(ACTION_INSTALL_STATUS);
                ContextCompat.registerReceiver(this, mSessionReceiver, filter,
                        ContextCompat.RECEIVER_NOT_EXPORTED);
                Intent statusIntent = new Intent(ACTION_INSTALL_STATUS).setPackage(getPackageName());
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags |= PendingIntent.FLAG_MUTABLE;
                }
                PendingIntent pendingIntent = PendingIntent.getBroadcast(this, mSessionId, statusIntent, flags);
                session.commit(pendingIntent.getIntentSender());
            }
            return true;
        } catch (Exception | LinkageError error) {
            error.printStackTrace();
            if (mSessionId >= 0) {
                try {
                    installer.abandonSession(mSessionId);
                } catch (RuntimeException ignored) {
                }
            }
            mSessionId = -1;
            return false;
        }
    }

    private void handleSessionStatus(Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(confirm);
                } catch (ActivityNotFoundException | SecurityException error) {
                    error.printStackTrace();
                    showInstallErrorAndFinish();
                    return;
                }
                finish();
            } else {
                showInstallErrorAndFinish();
            }
            return;
        }
        if (status != PackageInstaller.STATUS_SUCCESS) {
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            android.util.Log.w("ApkInstallActivity",
                    "PackageInstaller status=" + status + " " + (message == null ? "" : message));
            Toast.makeText(this, R.string.error_activity_not_found_for_apk_installing,
                    Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private void showInstallErrorAndFinish() {
        Toast.makeText(this, R.string.error_activity_not_found_for_apk_installing,
                Toast.LENGTH_SHORT).show();
        finish();
    }
}
