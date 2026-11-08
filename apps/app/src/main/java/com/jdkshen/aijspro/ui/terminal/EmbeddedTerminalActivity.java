package com.jdkshen.aijspro.ui.terminal;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.jdkshen.aijspro.theme.AppThemePalette;
import com.jdkshen.aijspro.theme.AppThemeRepository;
import com.jdkshen.aijspro.mcp.McpAndroidTerminal;
import com.jdkshen.aijspro.mcp.McpTerminalJobs;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A small, service-free command terminal that works on current Android. */
public final class EmbeddedTerminalActivity extends Activity {

    public static final String EXTRA_WORKING_DIRECTORY = "working_directory";
    public static final String EXTRA_INITIAL_COMMAND = "initial_command";

    private static final String BACKEND_APP = "app";
    private static final String BACKEND_SHIZUKU = "shizuku";

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService mStatusExecutor = Executors.newSingleThreadExecutor();
    private TextView mConsole;
    private EditText mCommand;
    private ScrollView mScrollView;
    private File mWorkingDirectory;
    private McpTerminalJobs mTerminal;
    private volatile String mJobId;
    private volatile boolean mRunning;
    private Button mStop;
    private Spinner mBackend;
    private TextView mBackendStatus;
    private AppThemePalette mPalette;
    private LinearLayout mRootLayout;
    private TextView mRunStatus;
    private android.widget.HorizontalScrollView mQuickBar;
    private Button mClear;
    private Button mCopy;
    private final java.util.List<Button> mThemeButtons = new java.util.ArrayList<>();
    /** 控制台是否跟随最新输出（用户上翻时暂停，滚回底部恢复）。 */
    private boolean mFollowTail = true;
    private boolean mTruncationNoted = false;
    /** 命令历史（↑/↓ 回溯），最多 {@link #HISTORY_LIMIT} 条。 */
    private final java.util.List<String> mHistory = new java.util.ArrayList<>();
    private int mHistoryCursor = -1;
    private String mDraft = "";
    private static final int HISTORY_LIMIT = 50;
    private static final int CONSOLE_MAX_CHARS = 200_000;
    private static final String HISTORY_PREFS = "terminal_history";
    private final android.os.Handler mStatusHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private long mRunStartedAt;
    private java.lang.Runnable mStatusTick;
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

        mBackendStatus = new TextView(this);
        mBackendStatus.setTextColor(mPalette.textSecondary);
        mBackendStatus.setTextSize(12f);
        mBackendStatus.setText("Shizuku：检测中…");
        mBackendStatus.setPadding(0, 0, 0, Math.max(4, padding / 2));
        root.addView(mBackendStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mBackend = new Spinner(this);
        ArrayAdapter<String> backendAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"后端：应用 UID (app)", "后端：Shizuku (shizuku)"}) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                if (view instanceof TextView) ((TextView) view).setTextColor(mPalette.textPrimary);
                return view;
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                view.setBackgroundColor(mPalette.surfaceSecondary);
                if (view instanceof TextView) ((TextView) view).setTextColor(mPalette.textPrimary);
                return view;
            }
        };
        backendAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mBackend.setAdapter(backendAdapter);
        mBackend.setSelection(0, false);
        mBackend.setBackgroundTintList(android.content.res.ColorStateList.valueOf(mPalette.surfaceSecondary));
        root.addView(mBackend, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 运行状态行：空闲给 cwd，运行中给 jobId + 计时，结束后给退出码 + 耗时。
        mRunStatus = new TextView(this);
        mRunStatus.setSingleLine(true);
        mRunStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mRunStatus.setTextColor(mPalette.textSecondary);
        mRunStatus.setTextSize(12f);
        mRunStatus.setPadding(0, Math.max(4, padding / 2), 0, 0);
        root.addView(mRunStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));


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
        // 快捷命令：点一下就填入并执行（横滑，避免挤占控制台高度）。
        mQuickBar = new android.widget.HorizontalScrollView(this);
        mQuickBar.setHorizontalScrollBarEnabled(false);
        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        String[][] quicks = {
                {"pwd", "pwd"}, {"ls -l", "ls -l"}, {"id", "id"},
                {"日志", "logcat -d -t 200"},
                {"前台", "dumpsys window | grep -i mCurrentFocus"},
                {"端口", "ss -tlnp"},
        };
        for (String[] quick : quicks) {
            Button chip = terminalButton(quick[0]);
            final String payload = quick[1];
            chip.setOnClickListener(v -> { if (!mRunning) { mCommand.setText(payload);
                mCommand.setSelection(payload.length()); submitCommand(); } });
            quickRow.addView(chip, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        mQuickBar.addView(quickRow);
        root.addView(mQuickBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(mCommand, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // ↑/↓ 回溯历史命令（运行中不打断输入转发）。
        mCommand.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return browseHistory(1);    // 更旧
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return browseHistory(-1);  // 更新/回到草稿
            return false;
        });
        loadHistory();
        // 手机上软键盘没有方向键：长按输入框弹历史（`.4` 复核 I8）。
        mCommand.setOnLongClickListener(v -> { showHistoryDialog(); return true; });

        // 用户在控制台里上翻时暂停跟随，滚回底部自动恢复。
        mScrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldX, oldY) ->
                mFollowTail = !mScrollView.canScrollVertically(1));

        mBackend.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (mRunning) return;
                // shell UID cannot enter the app's private default working directory.
                // Other requested paths stay intact so access errors remain visible.
                if (position == 1 && mWorkingDirectory.equals(getFilesDir())) {
                    mWorkingDirectory = new File("/");
                    appendLine("Shizuku 默认工作目录：/（授权身份不能访问应用私有目录）");
                }
                updateBackendHint();
                refreshShizukuStatus();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        mStop = new Button(this);
        mStop.setText("停止当前命令");
        mStop.setEnabled(false);
        mStop.setOnClickListener(view -> {
            String id = mJobId;
            if (id != null) mTerminal.stop(id);
        });
        root.addView(mStop, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));


        // 操作行：清空 / 复制全部。
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        mClear = terminalButton("清空");
        mClear.setOnClickListener(v -> { mConsole.setText(""); mFollowTail = true;
            mTruncationNoted = false; showIdleStatus(); appendPrompt(); });
        mCopy = terminalButton("复制全部");
        mCopy.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("terminal", mConsole.getText()));
                appendLine("[已复制全部输出到剪贴板]");
            }
        });
        actions.addView(mClear, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actions.addView(mCopy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        applyWindowSystemUi();
        appendLine("AI.js Pro 终端");
        appendLine("实时输出 · 每条命令独立进程组 · 停止会结束当前命令及子进程");
        appendLine("后端 app（默认）= 应用 UID，可调用 rish/i 函数入口；shizuku = Shizuku 授权身份，需已运行并授权，不会回退 app。");
        appendLine("默认超时 1 小时；编译器需单独配置。D8/Smali 可用 $AIJS_APK / $AIJS_BUILD_MAIN 调用。");
        appendLine("公网安装 C/C++：aijs-toolchain install cpp；使用 app 后端，编译器不计入主 APK。");
        updateBackendHint();
        showIdleStatus();
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
        // 运行中的输入是给正在跑的程序当 stdin 的，不进命令历史（`.4` 复核 I2）。
        if (!mRunning) rememberCommand(command);
        mCommand.setText("");
        mHistoryCursor = -1;
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
        mBackend.setEnabled(false);
        mCommand.setHint("向运行中的命令输入…");
        File directory = mWorkingDirectory;
        String backend = selectedBackend();
        mExecutor.execute(() -> executeShellCommand(command, directory, backend));
    }

    private void changeDirectory(String value) {
        String path = stripQuotes(value);
        File destination = path.isEmpty() ? (BACKEND_SHIZUKU.equals(selectedBackend()) ? new File("/") : getFilesDir())
                : new File(path).isAbsolute() ? new File(path) : new File(mWorkingDirectory, path);
        try {
            destination = destination.getCanonicalFile();
            if (destination.isDirectory()) { mWorkingDirectory = destination; showIdleStatus(); }
            else appendLine("cd: 目录不存在: " + path);
        } catch (Exception error) {
            appendLine("cd: " + error.getMessage());
        }
    }

    private void executeShellCommand(String command, File directory, String backend) {
        try {
            JsonObject state = mTerminal.execute(command, directory.getCanonicalPath(),
                    Collections.emptyMap(), 3600, backend);
            mJobId = state.get("jobId").getAsString();
            mRunStartedAt = System.currentTimeMillis();
            postTerminal(() -> { mStop.setEnabled(true); startStatusTicker(); });
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
            final int exitCode = state.has("exitCode") ? state.get("exitCode").getAsInt() : -1;
            final long elapsedMs = System.currentTimeMillis() - mRunStartedAt;
            final boolean ok = "SUCCEEDED".equals(status) && exitCode <= 0;
            postTerminal(() -> {
                stopStatusTicker();
                if (mRunStatus != null) {
                    mRunStatus.setText("上次命令 · " + (ok ? "成功" : (exitCode > 0 ? "非零退出" : "失败 " + status))
                            + (exitCode >= 0 ? " · exit=" + exitCode : "")
                            + " · " + String.format(java.util.Locale.US, "%.2f s", elapsedMs / 1000.0)
                            + " · cwd " + mWorkingDirectory.getAbsolutePath());
                }
            });
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
            updateBackendHint();
            stopStatusTicker();
            mStop.setEnabled(false);
            mBackend.setEnabled(true);
            mCommand.requestFocus();
        });
    }

    private String selectedBackend() {
        return mBackend != null && mBackend.getSelectedItemPosition() == 1 ? BACKEND_SHIZUKU : BACKEND_APP;
    }

    private void updateBackendHint() {
        if (mCommand == null) return;
        mCommand.setHint(BACKEND_SHIZUKU.equals(selectedBackend())
                ? "输入 Shizuku 命令（需已运行并授权）…" : "输入命令…");
    }

    private void refreshShizukuStatus() {
        if (mBackendStatus == null) return;
        mBackendStatus.setText("Shizuku：检测中…");
        try {
            mStatusExecutor.execute(() -> {
                try {
                    JsonObject state = getShizukuState();
                    postTerminal(() -> renderShizukuStatus(state));
                } catch (Exception error) {
                    postTerminal(() -> mBackendStatus.setText(
                            "Shizuku：状态不可用（" + error.getClass().getSimpleName() + "）"));
                }
            });
        } catch (Exception ignored) {
            // Activity is finishing and the status executor has been shut down.
        }
    }

    /** getState reads the real Shizuku client state; it never fabricates a fixed UID such as 2000. */
    private JsonObject getShizukuState() {
        return McpAndroidTerminal.shizukuState(this);
    }

    private void renderShizukuStatus(JsonObject state) {
        boolean sdkSupported = jsonBoolean(state, "sdkSupported", true);
        boolean alive = jsonBoolean(state, "binderAlive", jsonBoolean(state, "available", false));
        Boolean granted = jsonBooleanOrNull(state, "permissionGranted", "granted");
        String uid = firstJsonString(state, "backendUid", "uid");
        String error = firstJsonString(state, "lastError", "error");
        StringBuilder text = new StringBuilder("Shizuku：");
        if (!sdkSupported) {
            text.append("系统版本不支持");
        } else if (!alive) {
            text.append("未连接（请先启动 Shizuku）");
        } else {
            text.append("已连接 · ");
            if (granted == null) {
                text.append("授权状态未知");
            } else if (granted) {
                text.append("已授权");
                text.append(uid == null ? " · UID 未知" : " · UID " + uid);
            } else {
                text.append("未授权");
            }
        }
        if (error != null && !error.isEmpty()) text.append(" · ").append(error);
        text.append('\n').append("当前后端：");
        if (BACKEND_SHIZUKU.equals(selectedBackend())) {
            text.append("Shizuku（shizuku）");
            if (!alive) {
                text.append(" · 当前不可用，不会回退 app");
            } else if (granted == null) {
                text.append(" · 授权状态未知，失败时不会回退 app");
            } else if (!granted) {
                text.append(" · 当前未授权，不会回退 app");
            }
        } else {
            text.append("应用 UID（app）");
        }
        mBackendStatus.setText(text.toString());
    }

    private static boolean jsonBoolean(JsonObject object, String key, boolean fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static Boolean jsonBooleanOrNull(JsonObject object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            if (!object.has(key) || object.get(key).isJsonNull()) continue;
            try {
                return object.get(key).getAsBoolean();
            } catch (RuntimeException ignored) {
                // Try the next alias.
            }
        }
        return null;
    }

    private static String firstJsonString(JsonObject object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value == null || value.isJsonNull()) continue;
            try {
                String text = value.isJsonPrimitive() ? value.getAsString() : value.toString();
                if (!TextUtils.isEmpty(text) && !"null".equals(text)) return text;
            } catch (RuntimeException ignored) {
                // Try the next alias.
            }
        }
        return null;
    }

    private void postTerminal(Runnable action) {
        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) action.run(); });
    }

    /** 终端里的统一样式按钮（跟随主题）。 */
    private Button terminalButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setTextColor(mPalette.textPrimary);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(mPalette.surfaceSecondary));
        mThemeButtons.add(button);
        return button;
    }

    /** 长按输入框时的历史选择（软键盘也能用）。 */
    private void showHistoryDialog() {
        if (mHistory.isEmpty()) { appendLine("[暂无命令历史]"); return; }
        final String[] items = mHistory.toArray(new String[0]);
        new android.app.AlertDialog.Builder(this)
                .setTitle("命令历史（最近 " + items.length + " 条）")
                .setItems(items, (dialog, which) -> {
                    mCommand.setText(items[which]);
                    mCommand.setSelection(items[which].length());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** ↑/↓ 浏览历史；返回 true 表示已消费按键。 */
    /**
     * ↑/↓ 浏览历史。`mHistory` 是「新→旧」（0=最近一条），因此 **↑ = +1（更旧）**、**↓ = -1（更新）**；
     * `-1` 表示草稿槽（进入浏览前的输入内容）。`.4` 复核 I1：旧实现方向反了，↓ 还会直接清空输入。
     */
    private boolean browseHistory(int delta) {
        if (mHistory.isEmpty()) return false;
        if (mHistoryCursor < 0) {
            mDraft = mCommand.getText().toString();
            mHistoryCursor = -1;
        }
        int next = mHistoryCursor + delta;
        if (next < -1) next = -1;
        if (next > mHistory.size() - 1) next = mHistory.size() - 1;
        mHistoryCursor = next;
        String value = mHistoryCursor < 0 ? mDraft : mHistory.get(mHistoryCursor);
        mCommand.setText(value);
        mCommand.setSelection(value.length());
        return true;
    }

    private void rememberCommand(String command) {
        if (command.isEmpty()) return;
        mHistory.remove(command);
        mHistory.add(0, command);
        while (mHistory.size() > HISTORY_LIMIT) mHistory.remove(mHistory.size() - 1);
        mHistoryCursor = -1;
        getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE).edit()
                .putString("entries", TextUtils.join("\n", mHistory)).apply();
    }

    private void loadHistory() {
        String stored = getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE).getString("entries", "");
        if (TextUtils.isEmpty(stored)) return;
        for (String line : stored.split("\n")) {
            if (!line.trim().isEmpty() && !mHistory.contains(line)) mHistory.add(line);
        }
        while (mHistory.size() > HISTORY_LIMIT) mHistory.remove(mHistory.size() - 1);
    }

    /** 运行中每 200ms 刷新一次计时。 */
    private void startStatusTicker() {
        stopStatusTicker();
        mStatusTick = new Runnable() {
            @Override public void run() {
                if (!mRunning || mRunStatus == null) return;
                long ms = System.currentTimeMillis() - mRunStartedAt;
                mRunStatus.setText("运行中 · job=" + (mJobId == null ? "启动中" : mJobId)
                        + " · " + String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)
                        + " · " + mWorkingDirectory.getAbsolutePath());
                mStatusHandler.postDelayed(this, 200L);
            }
        };
        mStatusHandler.post(mStatusTick);
    }

    private void stopStatusTicker() {
        if (mStatusTick != null) { mStatusHandler.removeCallbacks(mStatusTick); mStatusTick = null; }
    }

    private void showIdleStatus() {
        if (mRunStatus != null) {
            mRunStatus.setText("空闲 · 后端 " + selectedBackend() + " · cwd " + mWorkingDirectory.getAbsolutePath());
        }
    }

    private void appendOutput(String text) {
        mConsole.append(text);
        // 只保留尾部一段：超出上限时截断，并一次性提示（避免刷屏）。
        if (mConsole.length() > CONSOLE_MAX_CHARS) {
            // 一次多丢（降到上限一半）避免每块输出都做 O(200k) 重排（`.4` 复核 I4）。
            int keep = CONSOLE_MAX_CHARS / 2;
            mConsole.setText(mConsole.getText().subSequence(mConsole.length() - keep, mConsole.length()));
            if (!mTruncationNoted) {
                mTruncationNoted = true;
                // 提示放状态行：它不随日志滚动，不会被截断掉（`.4` 复核 I3）。
                if (mRunStatus != null) {
                    mRunStatus.setText("控制台已截断，仅保留最近约 " + (CONSOLE_MAX_CHARS / 2 / 1000) + "k 字符");
                }
            }
        }
        // 仅当用户本来就在底部时才跟随；上翻阅读时不打扰（post 里再判一次，避免排队期间被抢回底部 —— I5）。
        if (mFollowTail) {
            mScrollView.post(() -> { if (mFollowTail) mScrollView.fullScroll(ScrollView.FOCUS_DOWN); });
        }
    }

    private void appendPrompt() {
        appendLine(mWorkingDirectory.getAbsolutePath() + " [" + selectedBackend() + "] $");
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
        mBackend.setBackgroundTintList(android.content.res.ColorStateList.valueOf(palette.surfaceSecondary));
        if (mBackend.getAdapter() instanceof android.widget.BaseAdapter)
            ((android.widget.BaseAdapter) mBackend.getAdapter()).notifyDataSetChanged();
        mBackendStatus.setTextColor(palette.textSecondary);
        if (mRunStatus != null) mRunStatus.setTextColor(palette.textSecondary);
        for (Button button : mThemeButtons) {
            button.setTextColor(palette.textPrimary);
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(palette.surfaceSecondary));
        }
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
        refreshShizukuStatus();
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
                    ? flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    : flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            flags = mPalette.isDark
                    ? flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    : flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @Override
    protected void onDestroy() {
        stopStatusTicker();
        if (mTerminal != null) mTerminal.close();
        mExecutor.shutdownNow();
        mStatusExecutor.shutdownNow();
        super.onDestroy();
    }
}
