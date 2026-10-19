package com.jdkshen.aijspro.ui.splash;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import androidx.annotation.Nullable;

import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.ui.BaseActivity;
import com.jdkshen.aijspro.ui.main.MainActivity;

/**
 * Created by Stardust on 2017/7/7.
 */
public class SplashActivity extends BaseActivity {


    private static final String LOG_TAG = SplashActivity.class.getSimpleName();
    private static final long INIT_TIMEOUT = 800;

    private boolean mAlreadyEnterNextActivity = false;
    private boolean mPaused;
    private Handler mHandler;

    @Override
    protected boolean shouldApplyThemeColorToStatusBar() {
        return false;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        syncStatusBarWithSplashSurface();
        init();
        mHandler.postDelayed(SplashActivity.this::enterNextActivity, INIT_TIMEOUT);
    }

    private void syncStatusBarWithSplashSurface() {
        getWindow().setStatusBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    decorView.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        // Older MIUI builds may ignore the standard Android flag during startup.
        try {
            getWindow().getClass()
                    .getMethod("setExtraFlags", int.class, int.class)
                    .invoke(getWindow(), 0x20, 0x20);
        } catch (ReflectiveOperationException ignored) {
            // The standard flag above remains authoritative on non-MIUI devices.
        }
    }

    private void init() {
        setContentView(R.layout.activity_splash);
        mHandler = new Handler();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPaused = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mPaused) {
            mPaused = false;
            enterNextActivity();
        }
    }

    void enterNextActivity() {
        if (mAlreadyEnterNextActivity)
            return;
        if (mPaused) {
            return;
        }
        mAlreadyEnterNextActivity = true;
        android.content.Intent next = null;
        // Flutter 玻璃界面主入口（-PflutterPoc 的调试源集把该资源置为 true）。
        // 常规构建里资源为 false，行为与原来完全一致；类名用字符串是为了让
        // 生产源集不依赖只在 POC 调试源集里存在的 GlassFlutterActivity。
        if (getResources().getBoolean(R.bool.flutter_main_entry)) {
            next = new android.content.Intent().setClassName(this,
                    "com.jdkshen.aijspro.flutterpoc.GlassFlutterActivity");
        }
        try {
            if (next != null) {
                startActivity(next);
            } else {
                startActivity(new android.content.Intent(this, MainActivity.class));
            }
        } catch (android.content.ActivityNotFoundException e) {
            // POC 源集缺失时不能把应用留在空白 Splash：退回原生主界面。
            startActivity(new android.content.Intent(this, MainActivity.class));
        }
        finish();
    }

}
