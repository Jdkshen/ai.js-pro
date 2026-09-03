package org.autojs.autojs.ui.splash;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import androidx.annotation.Nullable;

import org.autojs.autojs.R;
import org.autojs.autojs.ui.BaseActivity;
import org.autojs.autojs.ui.imgui.ImGuiWorkspaceActivity;

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
        startActivity(new android.content.Intent(this, ImGuiWorkspaceActivity.class));
        finish();
    }

}
