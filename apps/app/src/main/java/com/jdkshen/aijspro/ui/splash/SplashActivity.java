package com.jdkshen.aijspro.ui.splash;

import android.os.Bundle;
import android.os.Handler;
import androidx.annotation.Nullable;

import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.ui.BaseActivity;
import com.jdkshen.aijspro.theme.AppThemeRepository;
import com.jdkshen.aijspro.theme.HostUiAppearance;

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
        HostUiAppearance.apply(getWindow(), AppThemeRepository.get(this).isDark());
    }

    private void init() {
        setContentView(R.layout.activity_splash);
        boolean dark = AppThemeRepository.get(this).isDark();
        findViewById(R.id.splash_surface).setBackgroundColor(HostUiAppearance.surface(dark));
        ((android.widget.TextView) findViewById(R.id.splash_title))
                .setTextColor(dark ? 0xFFE6EEF0 : 0xFF1C2022);
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

    @Override protected void onDestroy() {
        if (mHandler != null) mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
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
