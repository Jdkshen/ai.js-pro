package com.jdkshen.aijspro.pluginclient;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Pro 的 {@code DebugAuthConfirmActivity} 等价物：电脑端请求授权时弹的确认框。
 *
 * <p>界面显示 {@code md5(token)} 大写、每 2 字符一段、{@code ':'} 分隔的确认码
 * （与电脑端显示的应当一致），用户点「允许」后这台电脑被记住；20 秒不操作、
 * 点「拒绝」、返回键、或 Activity 被系统销毁都按拒绝处理。
 *
 * <p>用代码搭界面（不引布局资源），主题在 AndroidManifest 里指定为系统对话框主题。
 */
public class ProRpcAuthActivity extends Activity {

    public static final String EXTRA_TOKEN = "pro_rpc_auth_token";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTimeout = () -> finishWith(false);

    private String mToken;
    private boolean mCompleted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mToken = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_TOKEN);
        if (mToken == null || mToken.isEmpty()) {
            finish();
            return;
        }
        setFinishOnTouchOutside(false);
        setContentView(buildContentView());
        mHandler.postDelayed(mTimeout, ProRpcAuthorizer.TIMEOUT_MILLIS);
    }

    @Override
    public void onBackPressed() {
        finishWith(false);
    }

    @Override
    protected void onDestroy() {
        if (!mCompleted) {
            finishWith(false);
        }
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private View buildContentView() {
        int padding = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("允许电脑连入调试？");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, matchWidth());

        TextView message = new TextView(this);
        message.setText("电脑端显示的确认码应与下面一致：");
        root.addView(message, matchWidthWithTopMargin(dp(12)));

        TextView code = new TextView(this);
        code.setText(ProRpcAuthorizer.displayCode(mToken));
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextIsSelectable(true);
        code.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams codeParams = matchWidthWithTopMargin(dp(12));
        codeParams.bottomMargin = dp(12);
        root.addView(code, codeParams);

        TextView hint = new TextView(this);
        hint.setText("20 秒内未确认将自动拒绝；确认后这台电脑会被记住。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        root.addView(hint, matchWidth());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        Button deny = new Button(this);
        deny.setText("拒绝");
        deny.setOnClickListener(view -> finishWith(false));
        Button allow = new Button(this);
        allow.setText("允许");
        allow.setOnClickListener(view -> finishWith(true));
        buttons.addView(deny);
        buttons.addView(allow);
        root.addView(buttons, matchWidthWithTopMargin(dp(16)));
        return root;
    }

    private void finishWith(boolean accepted) {
        if (mCompleted) {
            return;
        }
        mCompleted = true;
        mHandler.removeCallbacks(mTimeout);
        if (mToken != null && !mToken.isEmpty()) {
            ProRpcAuthorizer.complete(mToken, accepted);
        }
        finish();
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWidthWithTopMargin(int topMargin) {
        LinearLayout.LayoutParams params = matchWidth();
        params.topMargin = topMargin;
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
