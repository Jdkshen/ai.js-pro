package com.jdkshen.aijspro.ui.service;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import com.jdkshen.aijspro.BuildConfig;
import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.external.foreground.ForegroundService;
import com.jdkshen.aijspro.ui.BaseActivity;
import com.jdkshen.aijspro.ui.floating.FloatyWindowManger;
import com.jdkshen.aijspro.tool.AccessibilityServiceTool;
import com.stardust.app.GlobalAppContext;
import com.stardust.notification.NotificationListenerService;

/**
 * Material 3 core-service management page, mirrors the Auto.js Pro
 * "核心服务" screen: accessibility, floating window, foreground service,
 * notification access and battery optimization.
 */
public class ServiceStatusActivity extends BaseActivity {

    private SwitchCompat mAccessibilitySwitch;
    private SwitchCompat mFloatingSwitch;
    private SwitchCompat mForegroundSwitch;
    private boolean mUpdating = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (BuildConfig.MIUIX_PILOT) {
            startActivity(new Intent().setClassName(this,
                    "com.jdkshen.aijspro.ui.service.MiuixServiceActivity"));
            finish();
            return;
        }
        setContentView(R.layout.activity_service_status);
        BaseActivity.setToolbarAsBack(this, R.id.toolbar, getString(R.string.text_quick_service));
        mAccessibilitySwitch = findViewById(R.id.row_switch_accessibility);
        mFloatingSwitch = findViewById(R.id.row_switch_floating);
        mForegroundSwitch = findViewById(R.id.row_switch_foreground);
        ((TextView) findViewById(R.id.row_title_accessibility)).setText(R.string.text_accessibility_service);
        ((TextView) findViewById(R.id.row_title_floating)).setText(R.string.text_floating_window);
        ((TextView) findViewById(R.id.row_title_foreground)).setText(R.string.text_service_foreground);
        ((TextView) findViewById(R.id.row_title_notification)).setText(R.string.text_notification_permission);
        ((TextView) findViewById(R.id.row_title_battery)).setText(R.string.text_battery_optimization);
        ((TextView) findViewById(R.id.service_version)).setText(getString(R.string.text_version_footer, BuildConfig.VERSION_NAME));

        mAccessibilitySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (mUpdating) {
                return;
            }
            AccessibilityServiceTool.enableAccessibilityService();
            refresh();
        });
        mFloatingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (mUpdating) {
                return;
            }
            if (isChecked) {
                FloatyWindowManger.showCircularMenuIfNeeded();
                Pref.setFloatingMenuShown(true);
            } else {
                FloatyWindowManger.hideCircularMenu();
                Pref.setFloatingMenuShown(false);
            }
            refresh();
        });
        mForegroundSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (mUpdating) {
                return;
            }
            if (isChecked) {
                ForegroundService.start(GlobalAppContext.get());
                Pref.setForegroundServiceEnabled(true);
            } else {
                ForegroundService.stop(GlobalAppContext.get());
                Pref.setForegroundServiceEnabled(false);
            }
            refresh();
        });
        findViewById(R.id.row_notification).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        findViewById(R.id.row_battery).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:" + getPackageName())));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (mAccessibilitySwitch == null) return;
        mUpdating = true;
        boolean accessibility = AccessibilityServiceTool.isAccessibilityServiceEnabled(this);
        boolean floating = FloatyWindowManger.isCircularMenuShowing() || Pref.isFloatingMenuShown();
        boolean foreground = Pref.isForegroundServiceEnabled();
        boolean notification = NotificationListenerService.Companion.getInstance() != null;
        mAccessibilitySwitch.setChecked(accessibility);
        mFloatingSwitch.setChecked(floating);
        mForegroundSwitch.setChecked(foreground);
        ((TextView) findViewById(R.id.row_subtitle_accessibility)).setText(
                accessibility ? getString(R.string.text_on) : getString(R.string.text_off));
        ((TextView) findViewById(R.id.row_subtitle_floating)).setText(
                getString(R.string.text_service_floating_subtitle));
        ((TextView) findViewById(R.id.row_subtitle_foreground)).setText(
                foreground ? getString(R.string.text_on) : getString(R.string.text_off));
        ((TextView) findViewById(R.id.row_subtitle_notification)).setText(
                notification ? getString(R.string.text_on) : getString(R.string.text_off));
        ((TextView) findViewById(R.id.row_subtitle_battery)).setText(
                getString(R.string.text_service_battery_subtitle));
        mUpdating = false;
    }
}
