package com.jdkshen.aijspro.ui.splash;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import androidx.annotation.Nullable;

import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.ui.BaseActivity;

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
        // task-177「去掉 Miuix，统一 Flutter 界面」：唯一界面线，冷启动无条件直达 Flutter
        // 玻璃界面，不再按开关回退 MainActivity/Miuix 主界面。
        // 类名用字符串是为了让 main 源集不编译依赖只在 -PflutterPoc 调试源集里存在的
        // GlassFlutterActivity；缺少 Flutter 源集的构建给明确提示，而不是回到 Miuix。
        android.content.Intent next = new android.content.Intent().setClassName(this,
                "com.jdkshen.aijspro.flutterpoc.GlassFlutterActivity");
        try {
            startActivity(next);
        } catch (android.content.ActivityNotFoundException e) {
            // 不带 -PflutterPoc=true 的构建没有 Flutter 源集：明确告知，不留空白 Splash。
            android.widget.Toast.makeText(this,
                    "当前构建未包含 Flutter 界面（需要 -PflutterPoc=true）",
                    android.widget.Toast.LENGTH_LONG).show();
        }
        finish();
    }

}
