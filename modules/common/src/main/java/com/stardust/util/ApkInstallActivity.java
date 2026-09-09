package com.stardust.util;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import com.stardust.R;

/**
 * Bridges an APK install through Android 8+'s per-app "unknown sources" permission.
 * The downloaded APK path never leaves this non-exported activity.
 */
public class ApkInstallActivity extends Activity {

    private static final String EXTRA_APK_PATH = "apk_path";
    private static final String EXTRA_FILE_PROVIDER_AUTHORITY = "file_provider_authority";
    private static final String STATE_WAITING_FOR_SETTINGS = "waiting_for_settings";
    private static final String STATE_SKIP_FIRST_RESUME = "skip_first_resume";

    private String mApkPath;
    private String mFileProviderAuthority;
    private boolean mWaitingForSettings;
    private boolean mSkipFirstResume;
    private boolean mInstallerStarted;

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
        outState.putBoolean(STATE_WAITING_FOR_SETTINGS, mWaitingForSettings);
        outState.putBoolean(STATE_SKIP_FIRST_RESUME, mSkipFirstResume);
        super.onSaveInstanceState(outState);
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
        try {
            IntentUtil.installApk(this, mApkPath, mFileProviderAuthority);
        } catch (ActivityNotFoundException | IllegalArgumentException | SecurityException error) {
            error.printStackTrace();
            showInstallErrorAndFinish();
            return;
        }
        finish();
    }

    private void showInstallErrorAndFinish() {
        Toast.makeText(this, R.string.error_activity_not_found_for_apk_installing,
                Toast.LENGTH_SHORT).show();
        finish();
    }
}
