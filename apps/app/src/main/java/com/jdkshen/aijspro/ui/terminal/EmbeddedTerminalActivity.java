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
        root.addView(mCommand, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
        setContentView(root);
        applyWindowSystemUi();
        appendLine("AI.js Pro 终端");
        appendLine("实时输出 · 每条命令独立进程组 · 停止会结束当前命令及子进程");
        appendLine("后端 app（默认）= 应用 UID，可调用 rish/i 函数入口；shizuku = Shizuku 授权身份，需已运行并授权，不会回退 app。");
        appendLine("默认超时 1 小时；编译器需单独配置。D8/Smali 可用 $AIJS_APK / $AIJS_BUILD_MAIN 调用。");
        appendLine("C/C++ 可按需下载：aijs-toolchain help；使用 app 后端，编译器不计入主 APK。");
        updateBackendHint();
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
            if (destination.isDirectory()) mWorkingDirectory = destination;
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
            updateBackendHint();
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

    private void appendOutput(String text) {
        mConsole.append(text);
        if (mConsole.length() > 65536) {
            mConsole.setText(mConsole.getText().subSequence(mConsole.length() - 65536, mConsole.length()));
        }
        mScrollView.post(() -> mScrollView.fullScroll(ScrollView.FOCUS_DOWN));
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
        if (mTerminal != null) mTerminal.close();
        mExecutor.shutdownNow();
        mStatusExecutor.shutdownNow();
        super.onDestroy();
    }
}
