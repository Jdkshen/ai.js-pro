package com.jdkshen.aijspro.ui.terminal;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.jdkshen.aijspro.theme.AppThemePalette;
import com.jdkshen.aijspro.theme.AppThemeRepository;
import com.jdkshen.aijspro.mcp.McpAndroidTerminal;
import com.jdkshen.aijspro.mcp.McpTerminalJobs;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A small, service-free command terminal that works on current Android. */
public final class EmbeddedTerminalActivity extends Activity {

    public static final String EXTRA_WORKING_DIRECTORY = "working_directory";
    public static final String EXTRA_INITIAL_COMMAND = "initial_command";

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private TextView mConsole;
    private EditText mCommand;
    private ScrollView mScrollView;
    private File mWorkingDirectory;
    private McpTerminalJobs mTerminal;
    private volatile String mJobId;
    private volatile boolean mRunning;
    private Button mStop;
    private AppThemePalette mPalette;
    private LinearLayout mRootLayout;
    private final AppThemeRepository.ThemeListener mThemeListener = this::applyThemePalette;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPalette = AppThemeRepository.get(this).getPalette();
        getWindow().setStatusBarColor(mPalette.statusBar);
        getWindow().setNavigationBarColor(mPalette.navigationBar);

        String requestedPath = getIntent().getStringExtra(EXTRA_WORKING_DIRECTORY);
        File requestedDirectory = requestedPath == null ? null : new File(requestedPath);
        mWorkingDirectory = requestedDirectory != null && requestedDirectory.isDirectory()
                ? requestedDirectory : getFilesDir();
        McpAndroidTerminal backend = new McpAndroidTerminal(this);
        mTerminal = new McpTerminalJobs(new File("/"), backend::launch, 1, 4, 256 * 1024);

        int padding = Math.round(12f * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        mRootLayout = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(mPalette.terminalBackground);

        mScrollView = new ScrollView(this);
        mScrollView.setFillViewport(true);
        mConsole = new TextView(this);
        mConsole.setTextColor(mPalette.terminalForeground);
        mConsole.setTextSize(14f);
        mConsole.setTypeface(Typeface.MONOSPACE);
        mConsole.setTextIsSelectable(true);
        mConsole.setPadding(0, 0, 0, padding);
        mScrollView.addView(mConsole, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(mScrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mCommand = new EditText(this);
        mCommand.setSingleLine(true);
        mCommand.setTextColor(mPalette.terminalForeground);
        mCommand.setHintTextColor(mPalette.textSecondary);
        mCommand.setTypeface(Typeface.MONOSPACE);
        mCommand.setTextSize(15f);
        mCommand.setHint("输入命令…");
        mCommand.setBackgroundColor(mPalette.surfaceSecondary);
        mCommand.setImeOptions(EditorInfo.IME_ACTION_SEND);
        mCommand.setOnEditorActionListener((view, actionId, event) -> {
            boolean enter = actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER);
            if (!enter) return false;
            submitCommand();
            return true;
        });
        root.addView(mCommand, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mStop = new Button(this);
        mStop.setText("停止当前命令");
        mStop.setEnabled(false);
        mStop.setOnClickListener(view -> {
            String id = mJobId;
            if (id != null) mTerminal.stop(id);
        });
        root.addView(mStop, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        applyWindowSystemUi();
        appendLine("AI.js Pro 终端");
        appendLine("实时输出 · 输入后发送换行 · 停止会结束当前命令及子进程\n默认超时 1 小时；编译器需单独配置。D8/Smali 可用 $AIJS_APK / $AIJS_BUILD_MAIN 调用。");
        appendPrompt();
        mCommand.requestFocus();
        String initialCommand = getIntent().getStringExtra(EXTRA_INITIAL_COMMAND);
        if (!TextUtils.isEmpty(initialCommand)) {
            mCommand.setText(initialCommand);
            mCommand.setSelection(initialCommand.length());
        }
    }

    private void submitCommand() {
        String command = mCommand.getText().toString().trim();
        if (TextUtils.isEmpty(command)) return;
        mCommand.setText("");
        appendLine(command);
        if (mRunning) {
            try {
                String id = mJobId;
                if (id == null) appendLine("命令启动中，请稍后输入");
                else mTerminal.write(id, command + "\n", false);
            } catch (Exception error) { appendLine("输入失败：" + error.getMessage()); }
            return;
        }
        if ("clear".equals(command)) {
            mConsole.setText("");
            appendPrompt();
            return;
        }
        if ("exit".equals(command)) {
            finish();
            return;
        }
        if (command.equals("cd") || command.startsWith("cd ")) {
            changeDirectory(command.length() == 2 ? "" : command.substring(3).trim());
            appendPrompt();
            return;
        }
        mRunning = true;
        mCommand.setHint("向运行中的命令输入…");
        File directory = mWorkingDirectory;
        mExecutor.execute(() -> executeShellCommand(command, directory));
    }

    private void changeDirectory(String value) {
        String path = stripQuotes(value);
        File destination = path.isEmpty() ? getFilesDir()
                : new File(path).isAbsolute() ? new File(path) : new File(mWorkingDirectory, path);
        try {
            destination = destination.getCanonicalFile();
            if (destination.isDirectory()) mWorkingDirectory = destination;
            else appendLine("cd: 目录不存在: " + path);
        } catch (Exception error) {
            appendLine("cd: " + error.getMessage());
        }
    }

    private void executeShellCommand(String command, File directory) {
        try {
            JsonObject state = mTerminal.execute(command, directory.getCanonicalPath(), Collections.emptyMap(), 3600);
            mJobId = state.get("jobId").getAsString();
            postTerminal(() -> mStop.setEnabled(true));
            long offset = 0;
            do {
                state = mTerminal.read(mJobId, offset, 16384, 1000);
                if (state.get("truncated").getAsBoolean()) postTerminal(() -> appendLine("[旧输出已截断；完整编译日志请重定向到文件]"));
                String text = state.get("output").getAsString();
                if (!text.isEmpty()) postTerminal(() -> appendOutput(text));
                offset = state.get("nextOffset").getAsLong();
                if (state.get("processActive").getAsBoolean() && !text.isEmpty()) Thread.sleep(50);
            } while (state.get("processActive").getAsBoolean() || state.get("hasMore").getAsBoolean());
            String status = state.get("status").getAsString();
            if (!"SUCCEEDED".equals(status)) {
                String message = "\n[" + status + (state.has("exitCode") ? " 退出码 " + state.get("exitCode").getAsInt() : "")
                        + (state.has("error") ? " " + state.get("error").getAsString() : "") + "]";
                postTerminal(() -> appendLine(message));
            }
        } catch (Exception error) {
            postTerminal(() -> appendLine(String.valueOf(error.getMessage())));
        } finally {
            mJobId = null;
            mRunning = false;
        }
        postTerminal(() -> {
            appendPrompt();
            mCommand.setHint("输入命令…");
            mStop.setEnabled(false);
            mCommand.requestFocus();
        });
    }

    private void postTerminal(Runnable action) {
        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) action.run(); });
    }

    private void appendOutput(String text) {
        mConsole.append(text);
        if (mConsole.length() > 65536) {
            mConsole.setText(mConsole.getText().subSequence(mConsole.length() - 65536, mConsole.length()));
        }
        mScrollView.post(() -> mScrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void appendPrompt() {
        appendLine(mWorkingDirectory.getAbsolutePath() + " $");
    }

    private void appendLine(String text) {
        appendOutput((mConsole.length() > 0 ? "\n" : "") + text + "\n");
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private void applyThemePalette(AppThemePalette palette) {
        mPalette = palette;
        if (mRootLayout == null) return;
        mRootLayout.setBackgroundColor(palette.terminalBackground);
        mConsole.setTextColor(palette.terminalForeground);
        mCommand.setTextColor(palette.terminalForeground);
        mCommand.setHintTextColor(palette.textSecondary);
        mCommand.setBackgroundColor(palette.surfaceSecondary);
        mStop.setTextColor(palette.textPrimary);
        mStop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(palette.surfaceSecondary));
        getWindow().setStatusBarColor(palette.statusBar);
        getWindow().setNavigationBarColor(palette.navigationBar);
        applyWindowSystemUi();
    }

    @Override protected void onResume() {
        super.onResume();
        AppThemeRepository repository = AppThemeRepository.get(this);
        repository.addListener(mThemeListener);
        applyThemePalette(repository.getPalette());
    }

    @Override protected void onPause() {
        AppThemeRepository.get(this).removeListener(mThemeListener);
        super.onPause();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        AppThemeRepository repository = AppThemeRepository.get(this);
        repository.refreshSystemAppearance();
        applyThemePalette(repository.getPalette());
    }

    private void applyWindowSystemUi() {
        // 从「不隐藏系统栏」的干净状态起步（残留的 FULLSCREEN/IMMERSIVE 会让状态栏永不显示）
        int flags = com.jdkshen.aijspro.theme.AppThemePalette.withoutHiddenSystemBars(
                getWindow().getDecorView().getSystemUiVisibility());
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            flags = mPalette.isDark
                    ? flags & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    : flags | android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            flags = mPalette.isDark
                    ? flags & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    : flags | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @Override
    protected void onDestroy() {
        if (mTerminal != null) mTerminal.close();
        mExecutor.shutdownNow();
        super.onDestroy();
    }
}
