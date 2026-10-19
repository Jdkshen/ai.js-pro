package com.jdkshen.aijspro.ui.log;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.jdkshen.aijspro.log.UnifiedConsoleLogBridge;
import com.jdkshen.aijspro.theme.AppThemePalette;
import com.jdkshen.aijspro.theme.AppThemeRepository;
import com.jdkshen.aijspro.ui.BaseActivity;

import java.util.List;

/**
 * 原生日志页（统一日志源版）。
 *
 * <p>历史：本类以前只是转发到 Miuix 的 {@code MiuixLogActivity}（Compose/ConsoleView，只读
 * GlobalConsole 与运行日志文件）。现在统一到 {@code HostConsoleSink} 这一份数据源：
 * 主进程日志（GlobalConsole appender 旁路，token=main）+ {@code :script} 的 103 转发 +
 * {@code :native} 的 103 转发，全部进同一个 2000 行环；本页与 Flutter 运行日志页看到的是
 * 同一批行（同时间/同内容/同等级/同格式）。
 *
 * <p>取舍（为什么不直接打开 Flutter LogPage）：
 * <ul>
 *   <li>Flutter 界面只有一个 {@code GlassFlutterActivity}，没有给原生侧暴露「打开日志页」的
 *       Intent/路由契约（只有 PocBridge 的 logs/liveLogs 数据接口），原生直接跳转需要新增
 *       Flutter 侧路由桥，超出本次 Flutter 侧不改动的范围；</li>
 *   <li>Miuix 日志页是 {@code apps/app/src/miuix} 源集的 Kotlin/Compose 实现，绑定的是
 *       ConsoleView+ScriptRunLog，不在本任务的 Java 写作用域；</li>
 *   <li>因此这里用 Java 实现一个功能等价的原生统一日志页（等级过滤/复制/自动跟随/选择复制），
 *       所有菜单入口仍走 {@link LogActivity}，用户看到的就是统一流；Miuix 页保留但不再是主入口。
 *       后续若 Flutter 侧提供 openLogPage 路由，可把本页替换成直接跳 Flutter。</li>
 * </ul>
 *
 * <p>非 flutterPoc 构建（没有 HostConsoleSink）保留旧行为：转发到 Miuix 日志页。
 */
public class LogActivity extends BaseActivity {

    private static final String TAG = "LogActivity";
    private static final int MAX_LINES = 2000;
    private static final String[] LEVEL_NAMES = {"Verbose", "Debug", "Info", "Warn", "Error", "Assert"};
    private static final int[] LEVEL_VALUES = {
            Log.VERBOSE, Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR, Log.ASSERT};

    private TextView mTitleView;
    private TextView mLevelView;
    private TextView mLogText;
    private ScrollView mLogScroll;
    private SpannableStringBuilder mBuffer;
    private UnifiedConsoleLogBridge.Listener mListener;
    private AppThemePalette mPalette;
    private int mLevel = Log.VERBOSE;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!UnifiedConsoleLogBridge.isAvailable() || !UnifiedConsoleLogBridge.isMainProcess()) {
            // task-177：Miuix 运行日志页已随源集退役。普通构建没有统一日志源、
            // :script/:native 进程里统一环为空（103 都转发到主进程）：直接结束并明确提示，
            // 不再回退 Miuix 页面；Flutter 构建的日志入口都走 Flutter LogPage。
            Toast.makeText(this, "统一日志源未就绪，请从 Flutter 界面打开运行日志", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        mPalette = AppThemeRepository.get(this).getPalette();
        buildUi();
        UnifiedConsoleLogBridge.ensureMainTap();
        mListener = line -> {
            if (line.level >= mLevel) {
                appendLine(line, true);
            }
        };
        UnifiedConsoleLogBridge.addListener(mListener);
        renderSnapshot();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(mPalette.surfacePrimary);
        root.setFitsSystemWindows(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), 0, dp(8), 0);
        header.setBackgroundColor(mPalette.surfaceSecondary);
        mTitleView = new TextView(this);
        mTitleView.setText("日志（统一流）");
        mTitleView.setTextSize(16f);
        mTitleView.setTextColor(mPalette.textPrimary);
        header.addView(mTitleView, new LinearLayout.LayoutParams(0, dp(48), 1f));

        mLevelView = new TextView(this);
        mLevelView.setText(LEVEL_NAMES[0] + " ▾");
        mLevelView.setTextSize(14f);
        mLevelView.setTextColor(mPalette.accent);
        mLevelView.setGravity(Gravity.CENTER);
        mLevelView.setPadding(dp(10), 0, dp(10), 0);
        mLevelView.setOnClickListener(v -> showLevelMenu());
        header.addView(mLevelView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));

        TextView clear = headerAction("清空", v -> {
            UnifiedConsoleLogBridge.clear();
            if (mBuffer != null) mBuffer.clear();
            if (mLogText != null) mLogText.setText("");
        });
        header.addView(clear);
        TextView copy = headerAction("复制", v -> copyAll());
        header.addView(copy);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        mLogText = new TextView(this);
        mLogText.setTextSize(12f);
        mLogText.setTypeface(Typeface.MONOSPACE);
        mLogText.setTextColor(mPalette.textPrimary);
        mLogText.setTextIsSelectable(true);
        mLogText.setPadding(dp(10), dp(6), dp(10), dp(6));
        mBuffer = new SpannableStringBuilder();

        mLogScroll = new ScrollView(this);
        mLogScroll.setFillViewport(true);
        mLogScroll.setBackgroundColor(mPalette.surfacePrimary);
        mLogScroll.addView(mLogText, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(mLogScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private TextView headerAction(String label, View.OnClickListener listener) {
        TextView action = new TextView(this);
        action.setText(label);
        action.setTextSize(14f);
        action.setTextColor(mPalette.textSecondary);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(10), 0, dp(10), 0);
        action.setOnClickListener(listener);
        return action;
    }

    private void showLevelMenu() {
        PopupMenu menu = new PopupMenu(this, mLevelView);
        for (String name : LEVEL_NAMES) {
            menu.getMenu().add(name);
        }
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle() == null ? "" : item.getTitle().toString();
            for (int i = 0; i < LEVEL_NAMES.length; i++) {
                if (LEVEL_NAMES[i].equals(title)) {
                    mLevel = LEVEL_VALUES[i];
                    mLevelView.setText(LEVEL_NAMES[i] + " ▾");
                    renderSnapshot();
                    break;
                }
            }
            return true;
        });
        menu.show();
    }

    /** 先渲染统一环里的历史（按等级过滤），之后靠 listener 增量追加。 */
    private void renderSnapshot() {
        if (mBuffer == null) return;
        mBuffer.clear();
        List<UnifiedConsoleLogBridge.Line> lines = UnifiedConsoleLogBridge.snapshot(MAX_LINES);
        for (UnifiedConsoleLogBridge.Line line : lines) {
            if (line.level >= mLevel) {
                appendLine(line, false);
            }
        }
        if (mLogText != null) mLogText.setText(mBuffer);
        scrollToBottom();
    }

    private void appendLine(UnifiedConsoleLogBridge.Line line, boolean mayScroll) {
        if (mBuffer == null) return;
        boolean follow = mayScroll && isFollowingBottom();
        if (mBuffer.length() > 0) mBuffer.append('\n');
        int start = mBuffer.length();
        mBuffer.append(line.formatted());
        mBuffer.setSpan(new ForegroundColorSpan(levelColor(line.level)),
                start, mBuffer.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        trimBuffer();
        if (mLogText != null) mLogText.setText(mBuffer);
        if (follow) scrollToBottom();
    }

    private boolean isFollowingBottom() {
        if (mLogScroll == null) return true;
        View child = mLogScroll.getChildAt(0);
        if (child == null) return true;
        return child.getBottom() - (mLogScroll.getHeight() + mLogScroll.getScrollY()) < dp(48);
    }

    private void scrollToBottom() {
        if (mLogScroll == null) return;
        mLogScroll.post(() -> {
            if (mLogScroll != null) mLogScroll.fullScroll(View.FOCUS_DOWN);
        });
    }

    private void trimBuffer() {
        if (mBuffer == null) return;
        int lines = 0;
        for (int i = 0; i < mBuffer.length(); i++) {
            if (mBuffer.charAt(i) == '\n') lines++;
        }
        while (lines > MAX_LINES) {
            int cut = mBuffer.toString().indexOf('\n');
            if (cut < 0) break;
            mBuffer.delete(0, cut + 1);
            lines--;
        }
    }

    private int levelColor(int level) {
        switch (level) {
            case Log.VERBOSE:
                return 0xFF9E9E9E;
            case Log.DEBUG:
                return 0xFF64B5F6;
            case Log.WARN:
                return 0xFFFFB74D;
            case Log.ERROR:
                return 0xFFEF5350;
            case Log.ASSERT:
                return 0xFFBA68C8;
            case Log.INFO:
            default:
                return 0xFF81C784;
        }
    }

    /** 复制当前显示的全部行（与 Flutter 日志页的复制行为一致）。 */
    private void copyAll() {
        if (mBuffer == null) return;
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null) return;
            manager.setPrimaryClip(ClipData.newPlainText("log", mBuffer.toString()));
            Toast.makeText(this, "已复制日志", Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            Log.w(TAG, "copy log failed", error);
        }
    }

    @Override
    protected void onDestroy() {
        if (mListener != null) {
            UnifiedConsoleLogBridge.removeListener(mListener);
            mListener = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
