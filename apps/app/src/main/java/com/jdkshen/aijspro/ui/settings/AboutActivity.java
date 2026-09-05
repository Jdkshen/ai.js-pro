package com.jdkshen.aijspro.ui.settings;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import com.afollestad.materialdialogs.MaterialDialog;
import com.jdkshen.aijspro.tool.IntentTool;
import com.jdkshen.aijspro.ui.BaseActivity;
import com.jdkshen.aijspro.theme.dialog.ThemeColorMaterialDialogBuilder;
import com.stardust.util.IntentUtil;
import com.tencent.bugly.crashreport.CrashReport;

import com.jdkshen.aijspro.BuildConfig;
import com.jdkshen.aijspro.R;

/**
 * Created by Stardust on 2017/2/2.
 */
public class AboutActivity extends BaseActivity {

    private static final String TAG = "AboutActivity";
    TextView mVersion;

    private int mLolClickCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (BuildConfig.MIUIX_PILOT) {
            startActivity(new Intent().setClassName(this,
                    "com.jdkshen.aijspro.ui.settings.MiuixAboutActivity"));
            finish();
            return;
        }
        setContentView(R.layout.activity_about);
        mVersion = findViewById(R.id.version);
        findViewById(R.id.github).setOnClickListener(v -> openGitHub());
        findViewById(R.id.qq).setOnClickListener(v -> openQQToChatWithMe());
        findViewById(R.id.email).setOnClickListener(v -> openEmailToSendMe());
        findViewById(R.id.share).setOnClickListener(v -> share());
        findViewById(R.id.icon).setOnClickListener(v -> lol());
        findViewById(R.id.developer).setOnClickListener(v -> hhh());
        setUpViews();
    }

    void setUpViews() {
        setVersionName();
        setToolbarAsBack(getString(R.string.text_about));
    }

    @SuppressLint("SetTextI18n")
    private void setVersionName() {
        mVersion.setText("Version " + BuildConfig.VERSION_NAME);
    }

    void openGitHub() {
        IntentTool.browse(this, getString(R.string.my_github));
    }

    void openQQToChatWithMe() {
        String qq = getString(R.string.qq);
        if (!IntentUtil.chatWithQQ(this, qq)) {
            Toast.makeText(this, R.string.text_mobile_qq_not_installed, Toast.LENGTH_SHORT).show();
        }
    }

    void openEmailToSendMe() {
        String email = getString(R.string.email);
        IntentUtil.sendMailTo(this, email);
    }


    void share() {
        IntentUtil.shareText(this, getString(R.string.share_app));
    }

    void lol() {
        mLolClickCount++;
        //Toast.makeText(this, R.string.text_lll, Toast.LENGTH_LONG).show();
        if (mLolClickCount >= 5) {
            crashTest();
            //showEasterEgg();
        }
    }

    private void showEasterEgg() {
        new MaterialDialog.Builder(this)
                .customView(R.layout.paint_layout, false)
                .show();
    }

    private void crashTest() {
        new ThemeColorMaterialDialogBuilder(this)
                .title("Crash Test")
                .positiveText("Crash")
                .onPositive((dialog, which) -> {
                    CrashReport.testJavaCrash();
                }).show();
    }

    void hhh() {
        Toast.makeText(this, R.string.text_it_is_the_developer_of_app, Toast.LENGTH_LONG).show();
    }


}
