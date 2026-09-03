package org.autojs.autojs.ui.imgui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.autojs.autojs.theme.AppThemePalette;
import org.autojs.autojs.theme.AppThemeRepository;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
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
    private volatile Process mRunningProcess;
    private AppThemePalette mPalette;

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

        int padding = Math.round(12f * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
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
        setContentView(root);
        applyWindowSystemUi();
        appendLine("AI.js Pro 终端");
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
        mCommand.setEnabled(false);
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
        StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", command);
            builder.directory(directory);
            builder.redirectErrorStream(true);
            builder.environment().put("HOME", directory.getAbsolutePath());
            builder.environment().put("TERM", "dumb");
            mRunningProcess = builder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(mRunningProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            int exitCode = mRunningProcess.waitFor();
            if (exitCode != 0) output.append("[退出码 ").append(exitCode).append("]\n");
        } catch (Exception error) {
            output.append(error.getMessage()).append('\n');
        } finally {
            mRunningProcess = null;
        }
        String result = output.toString();
        runOnUiThread(() -> {
            if (!result.isEmpty()) appendLine(result.endsWith("\n")
                    ? result.substring(0, result.length() - 1) : result);
            appendPrompt();
            mCommand.setEnabled(true);
            mCommand.requestFocus();
        });
    }

    private void appendPrompt() {
        appendLine(mWorkingDirectory.getAbsolutePath() + " $");
    }

    private void appendLine(String text) {
        if (mConsole.length() > 0) mConsole.append("\n");
        mConsole.append(text);
        mScrollView.post(() -> mScrollView.fullScroll(ScrollView.FOCUS_DOWN));
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

    private void applyWindowSystemUi() {
        int flags = getWindow().getDecorView().getSystemUiVisibility();
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
        Process process = mRunningProcess;
        if (process != null) process.destroy();
        mExecutor.shutdownNow();
        super.onDestroy();
    }
}
