package com.jdkshen.aijspro.ui.log;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import android.view.View;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import com.stardust.autojs.core.console.ConsoleView;
import com.stardust.autojs.core.console.ConsoleImpl;

import com.jdkshen.aijspro.BuildConfig;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.theme.AppThemePalette;
import com.jdkshen.aijspro.theme.AppThemeRepository;
import com.jdkshen.aijspro.theme.ConsoleThemeHelper;
import com.jdkshen.aijspro.ui.BaseActivity;

public class LogActivity extends BaseActivity {

    ConsoleView mConsoleView;

    private ConsoleImpl mConsoleImpl;
    private final AppThemeRepository.ThemeListener mThemeListener = this::applyPalette;

    @Override
    protected boolean shouldApplyThemeColorToStatusBar() {
        return false;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (BuildConfig.MIUIX_PILOT) {
            startActivity(new Intent().setClassName(this,
                    "com.jdkshen.aijspro.ui.log.MiuixLogActivity"));
            finish();
            return;
        }
        applyDayNightMode();
        setContentView(R.layout.activity_log);
        mConsoleView = findViewById(R.id.console);
        findViewById(R.id.fab).setOnClickListener(v -> clearConsole());
        setupViews();
    }

    void setupViews() {
        setToolbarAsBack(getString(R.string.text_log));
        mConsoleImpl = AutoJs.getInstance().getGlobalConsole();
        applyPalette(AppThemeRepository.get(this).getPalette());
        mConsoleView.setConsole(mConsoleImpl);
        mConsoleView.findViewById(R.id.input_container).setVisibility(View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppThemeRepository repository = AppThemeRepository.get(this);
        repository.addListener(mThemeListener);
        applyPalette(repository.getPalette());
    }

    @Override
    protected void onPause() {
        AppThemeRepository.get(this).removeListener(mThemeListener);
        super.onPause();
    }

    private void applyPalette(AppThemePalette palette) {
        if (palette == null) return;
        applyPaletteChrome(palette);
        if (mConsoleView != null) ConsoleThemeHelper.apply(mConsoleView, palette);
    }

    private void applyPaletteChrome(AppThemePalette palette) {
        View content = findViewById(android.R.id.content);
        if (content != null) content.setBackgroundColor(palette.windowBackground);
        getWindow().setStatusBarColor(palette.statusBar);
        getWindow().setNavigationBarColor(palette.navigationBar);
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            flags = palette.isDark
                    ? flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    : flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            flags = palette.isDark
                    ? flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    : flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setBackgroundColor(palette.toolbarBackground);
            toolbar.setTitleTextColor(palette.textPrimary);
            if (toolbar.getNavigationIcon() != null) {
                toolbar.getNavigationIcon().setColorFilter(palette.iconPrimary,
                        PorterDuff.Mode.SRC_IN);
            }
        }
        FloatingActionButton fab = findViewById(R.id.fab);
        if (fab != null) {
            fab.setBackgroundTintList(ColorStateList.valueOf(palette.fabBackground));
            fab.setColorFilter(palette.fabForeground);
        }
    }

    void clearConsole() {
        mConsoleImpl.clear();
    }
}
