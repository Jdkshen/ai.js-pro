package com.jdkshen.aijspro.ui.editor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.afollestad.materialdialogs.MaterialDialog;
import com.stardust.autojs.core.console.ConsoleImpl;
import com.stardust.autojs.core.console.ConsoleView;
import com.stardust.autojs.core.ui.inflater.DynamicLayoutInflater;
import com.stardust.autojs.core.ui.inflater.ResourceParser;
import com.stardust.autojs.execution.ScriptExecution;
import com.stardust.autojs.rhino.debug.DebugCallback;
import com.stardust.autojs.rhino.debug.Debugger;
import com.stardust.autojs.rhino.debug.Dim;
import com.stardust.util.ClipboardUtil;

import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.model.indices.ClassSearchingItem;
import com.jdkshen.aijspro.model.script.ScriptFile;
import com.jdkshen.aijspro.model.script.Scripts;
import com.jdkshen.aijspro.external.fileprovider.AppFileProvider;
import com.jdkshen.aijspro.ui.edit.ClassSearchDialogBuilder;
import com.jdkshen.aijspro.ui.edit.debug.DebuggerSingleton;
import com.jdkshen.aijspro.ui.edit.editor.CodeEditor;
import com.jdkshen.aijspro.ui.edit.theme.Theme;
import com.jdkshen.aijspro.ui.floating.FloatyWindowManger;
import com.jdkshen.aijspro.ui.terminal.EmbeddedTerminalActivity;
import com.jdkshen.aijspro.ui.project.BuildActivity;
import com.jdkshen.aijspro.ui.project.ProjectConfigActivity;
import com.jdkshen.aijspro.theme.AppThemePalette;
import com.jdkshen.aijspro.theme.AppThemeRepository;
import com.jdkshen.aijspro.theme.ConsoleThemeHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.text.Collator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Pro-style multi-file script workspace used by the Miuix application shell. */
public final class ProCodeEditorActivity extends Activity implements DebugCallback {

    private static final String EXTRA_PATH = "path";
    private static final String EXTRA_SAMPLE_ASSET_PATH = "sample_asset_path";
    private static final String EDITOR_PREFS = "pro_code_editor";
    private static final String PREF_OPEN_TABS = "open_tabs";
    private static final String PREF_ACTIVE_TAB = "active_tab";
    private static final String PREF_TEXT_SIZE = "text_size_sp";
    private static final int MAX_RESTORED_TABS = 12;
    private final List<EditorTab> mTabs = new ArrayList<>();
    private final Set<String> mExpandedDirectories = new HashSet<>();

    private DrawerLayout mDrawerLayout;
    private LinearLayout mMainLayout;
    private View mToolRow;
    private View mTabRow;
    private LinearLayout mWorkspaceDrawer;
    private int mWorkspaceDrawerWidth;
    private LinearLayout mTabBar;
    private LinearLayout mTreeContainer;
    private TextView mTreeRootLabel;
    private FrameLayout mEditorContainer;
    private View mEditorShield;
    private LinearLayout mShortcutBar;
    private LinearLayout mLogPanel;
    private LinearLayout mDebugBar;
    private View mLogTool;
    private View mUndoTool;
    private View mRedoTool;
    private View mSaveTool;
    private ConsoleImpl mConsole;
    private ConsoleView mConsoleView;
    private TextView mLogLevelView;
    private EditorTab mActiveTab;
    private File mWorkspaceRoot;
    private File mProjectRoot;
    private String mSampleAssetDirectory;
    private boolean mLogExpanded;
    private boolean mDebugInterrupted;
    private int mLogLevel = Log.VERBOSE;
    private boolean mFileTreeDirty = true;
    private AppThemePalette mPalette;
    private final AppThemeRepository.ThemeListener mThemeListener = this::applyThemePalette;

    private Debugger mDebugger;
    private ScriptExecution mExecution;

    private final BroadcastReceiver mExecutionFinishedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Scripts.ACTION_ON_EXECUTION_FINISHED.equals(intent.getAction())) return;
            int line = intent.getIntExtra(Scripts.EXTRA_EXCEPTION_LINE_NUMBER, -1);
            int column = intent.getIntExtra(Scripts.EXTRA_EXCEPTION_COLUMN_NUMBER, 0);
            String message = intent.getStringExtra(Scripts.EXTRA_EXCEPTION_MESSAGE);
            if (line > 0 && activeEditor() != null) activeEditor().jumpTo(line - 1, Math.max(0, column));
            if (message != null) {
                showLogPanel();
                Toast.makeText(ProCodeEditorActivity.this, message, Toast.LENGTH_LONG).show();
            }
            finishDebugSession(false);
        }
    };

    public static Intent intent(Context context, File file) {
        return new Intent(context, ProCodeEditorActivity.class)
                .putExtra(EXTRA_PATH, file.getAbsolutePath());
    }

    public static Intent sampleIntent(Context context, File file, String sampleAssetPath) {
        return intent(context, file).putExtra(EXTRA_SAMPLE_ASSET_PATH, sampleAssetPath);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPalette = resolvePalette();
        getWindow().setStatusBarColor(mPalette.statusBar);
        getWindow().setNavigationBarColor(mPalette.navigationBar);
        String path = getIntent().getStringExtra(EXTRA_PATH);
        String sampleAssetPath = getIntent().getStringExtra(EXTRA_SAMPLE_ASSET_PATH);
        if (!TextUtils.isEmpty(sampleAssetPath)) {
            int slash = sampleAssetPath.lastIndexOf('/');
            mSampleAssetDirectory = slash > 0 ? sampleAssetPath.substring(0, slash) : "sample";
        }
        File initial = canonical(path == null ? null : new File(path));
        if (initial == null || !initial.isFile()) {
            Toast.makeText(this, "脚本文件不存在", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        resolveWorkspace(initial);
        buildWorkspaceUi();
        registerReceiver(mExecutionFinishedReceiver,
                new IntentFilter(Scripts.ACTION_ON_EXECUTION_FINISHED));
        restoreSessionAndOpen(initial);
    }

    private void resolveWorkspace(File initial) {
        File scriptRoot = canonical(new File(Pref.getScriptDirPath()));
        mWorkspaceRoot = scriptRoot != null && isWithin(initial, scriptRoot)
                ? scriptRoot : initial.getParentFile();
        mProjectRoot = findProjectRoot(initial.getParentFile());
        if (mWorkspaceRoot == null) mWorkspaceRoot = initial.getParentFile();
        if (mWorkspaceRoot != null) mExpandedDirectories.add(mWorkspaceRoot.getAbsolutePath());
    }

    private File findProjectRoot(File start) {
        File scriptRoot = canonical(new File(Pref.getScriptDirPath()));
        File current = canonical(start);
        while (current != null) {
            if (new File(current, "project.json").isFile()) return current;
            if (scriptRoot != null && current.equals(scriptRoot)) break;
            current = current.getParentFile();
        }
        return null;
    }

    private void buildWorkspaceUi() {
        mDrawerLayout = new DrawerLayout(this);
        mDrawerLayout.setBackgroundColor(mPalette.editorBackground);
        mDrawerLayout.setDrawerElevation(dp(12));
        mDrawerLayout.setScrimColor(mPalette.scrim);

        mMainLayout = new LinearLayout(this);
        mMainLayout.setOrientation(LinearLayout.VERTICAL);
        mMainLayout.setBackgroundColor(mPalette.editorBackground);
        mDrawerLayout.addView(mMainLayout, new DrawerLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mToolRow = buildToolRow();
        mMainLayout.addView(mToolRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        mTabRow = buildTabRow();
        mMainLayout.addView(mTabRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        mDebugBar = buildDebugBar();
        mDebugBar.setVisibility(View.GONE);
        mMainLayout.addView(mDebugBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        mEditorContainer = new FrameLayout(this);
        mMainLayout.addView(mEditorContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Invisible shield over the editor while the log console is open: it consumes
        // all touches so the code above cannot be edited/scrolled, but stays transparent
        // like Auto.js Pro (the editor strip above remains clearly visible).
        mEditorShield = new View(this);
        mEditorShield.setBackgroundColor(Color.TRANSPARENT);
        mEditorShield.setClickable(true);
        mEditorShield.setFocusable(true);
        mEditorShield.setFocusableInTouchMode(true);
        mEditorShield.setOnTouchListener((view, event) -> true);

        mLogPanel = buildLogPanel();
        mLogPanel.setVisibility(View.GONE);
        // Auto.js Pro shows the log console in a large bottom panel (~45% of the screen).
        int logPanelHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.45f);
        mMainLayout.addView(mLogPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, logPanelHeight));

        mShortcutBar = buildShortcutBar();
        mMainLayout.addView(mShortcutBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));

        addWorkspaceDrawer();
        setContentView(mDrawerLayout);
        mDrawerLayout.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                  oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            int height = bottom - top;
            if (width > 0 && (width != oldRight - oldLeft || height != oldBottom - oldTop)) {
                updateWorkspaceDrawerSize(width, height);
            }
        });
        applyWindowSystemUi(mPalette);
    }

    private View buildToolRow() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setBackgroundColor(mPalette.editorToolbar);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(tool(R.drawable.ic_pro_editor_file, "文件", 40, v -> openWorkspaceDrawer()));
        row.addView(tool(R.drawable.ic_pro_editor_edit, "编辑", 40, v -> showEditMenu(v)));
        row.addView(tool(R.drawable.ic_pro_editor_debug, "调试", 40, v -> showDebugMenu(v)));
        row.addView(tool(R.drawable.ic_pro_editor_terminal, "终端", 40, v -> openTerminal()));
        row.addView(tool(R.drawable.ic_pro_editor_more, "其他", 40, v -> showOtherMenu(v)));
        if (!TextUtils.isEmpty(mSampleAssetDirectory))
            row.addView(tool(R.drawable.ic_pro_editor_refresh, "重置", 40, v -> confirmResetSample()));
        View spacer = new View(this);
        row.addView(spacer, new LinearLayout.LayoutParams(dp(24), dp(44)));
        mLogTool = tool(R.drawable.ic_pro_editor_log, "日志", 32, v -> toggleLogPanel());
        row.addView(mLogTool);
        row.addView(tool(R.drawable.ic_pro_editor_play, "运行", 32, v -> runCurrent()));
        mUndoTool = tool(R.drawable.ic_pro_editor_undo, "撤销", 32, v -> {
            if (activeEditor() != null) activeEditor().undo();
            updateToolbarState();
        });
        row.addView(mUndoTool);
        mRedoTool = tool(R.drawable.ic_pro_editor_redo, "重做", 32, v -> {
            if (activeEditor() != null) activeEditor().redo();
            updateToolbarState();
        });
        row.addView(mRedoTool);
        mSaveTool = tool(R.drawable.ic_pro_editor_save, "保存", 40, v -> saveActive(true));
        row.addView(mSaveTool);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return scroll;
    }

    private View buildTabRow() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setBackgroundColor(mPalette.editorTabInactive);
        mTabBar = new LinearLayout(this);
        mTabBar.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(mTabBar, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return scroll;
    }

    private void addWorkspaceDrawer() {
        mWorkspaceDrawer = new LinearLayout(this);
        mWorkspaceDrawer.setOrientation(LinearLayout.VERTICAL);
        mWorkspaceDrawer.setBackgroundColor(mPalette.surfacePrimary);
        mWorkspaceDrawer.setPadding(dp(8), dp(8), dp(6), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("工作区", 21f, mPalette.textPrimary);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(8), 0, 0, 0);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0, workspaceHeaderHeight(), 1f));
        header.addView(action("⇥", 50, v -> mDrawerLayout.closeDrawer(GravityCompat.START)));
        mWorkspaceDrawer.addView(header);

        mTreeRootLabel = text("▾  " + (mWorkspaceRoot == null ? "脚本" : mWorkspaceRoot.getName()), 16f,
                mPalette.textPrimary);
        mTreeRootLabel.setPadding(dp(12), dp(8), dp(8), dp(8));
        mTreeRootLabel.setSingleLine(true);
        mTreeRootLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        mTreeRootLabel.setContentDescription("工作区根目录 "
                + (mWorkspaceRoot == null ? "脚本" : mWorkspaceRoot.getName()));
        LinearLayout rootRow = new LinearLayout(this);
        rootRow.setGravity(Gravity.CENTER_VERTICAL);
        int rootRowHeight = workspaceRowHeight();
        rootRow.addView(mTreeRootLabel, new LinearLayout.LayoutParams(0, rootRowHeight, 1f));
        TextView rootMore = smallAction("⋮", v -> showWorkspaceItemMenu(mWorkspaceRoot));
        rootMore.setContentDescription("工作区更多操作");
        rootRow.addView(rootMore, new LinearLayout.LayoutParams(dp(48), rootRowHeight));
        mWorkspaceDrawer.addView(rootRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rootRowHeight));

        ScrollView treeScroll = new ScrollView(this);
        treeScroll.setFillViewport(true);
        treeScroll.setVerticalScrollBarEnabled(false);
        mTreeContainer = new LinearLayout(this);
        mTreeContainer.setOrientation(LinearLayout.VERTICAL);
        treeScroll.addView(mTreeContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mWorkspaceDrawer.addView(treeScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        int displayWidth = getResources().getDisplayMetrics().widthPixels;
        int displayHeight = getResources().getDisplayMetrics().heightPixels;
        mWorkspaceDrawerWidth = workspaceDrawerWidth(displayWidth, displayHeight);
        DrawerLayout.LayoutParams params = new DrawerLayout.LayoutParams(
                mWorkspaceDrawerWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        params.gravity = GravityCompat.START;
        mDrawerLayout.addView(mWorkspaceDrawer, params);
        refreshFileTree();
    }

    private LinearLayout buildDebugBar() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), 0, dp(8), 0);
        row.setBackgroundColor(mPalette.surfaceElevated);
        TextView state = text("调试", 13f, mPalette.accent);
        state.setTag("debug_state");
        row.addView(state, new LinearLayout.LayoutParams(0, dp(42), 1f));
        row.addView(smallAction("步过", v -> debugStep(0)));
        row.addView(smallAction("步入", v -> debugStep(1)));
        row.addView(smallAction("步出", v -> debugStep(2)));
        row.addView(smallAction("继续", v -> debugStep(3)));
        row.addView(smallAction("停止", v -> stopCurrentExecution()));
        return row;
    }

    private LinearLayout buildLogPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(mPalette.surfaceSecondary);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), 0, dp(6), 0);
        mLogLevelView = text("Verbose ▾", 14f, mPalette.textPrimary);
        mLogLevelView.setGravity(Gravity.CENTER_VERTICAL);
        mLogLevelView.setOnClickListener(v -> showLogLevelMenu());
        header.addView(mLogLevelView, new LinearLayout.LayoutParams(0, dp(42), 1f));
        header.addView(smallIconAction(R.drawable.ic_pro_editor_stop, "停止", v -> stopCurrentExecution()),
                new LinearLayout.LayoutParams(dp(42), dp(42)));
        header.addView(smallIconAction(R.drawable.ic_pro_editor_delete, "清空日志", v -> mConsole.clear()),
                new LinearLayout.LayoutParams(dp(42), dp(42)));
        header.addView(smallIconAction(R.drawable.ic_pro_editor_minimize, "收起日志", v -> hideLogPanel()),
                new LinearLayout.LayoutParams(dp(42), dp(42)));
        header.addView(smallIconAction(R.drawable.ic_pro_editor_fullscreen, "展开日志", v -> toggleLogExpanded()),
                new LinearLayout.LayoutParams(dp(42), dp(42)));
        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        mConsole = AutoJs.getInstance().getGlobalConsole();
        mConsoleView = new ConsoleView(this);
        ConsoleThemeHelper.apply(mConsoleView, mPalette);
        mConsoleView.setConsole(mConsole);
        mConsoleView.setMinimumLogLevel(mLogLevel);
        View input = mConsoleView.findViewById(R.id.input_container);
        if (input != null) input.setVisibility(View.GONE);
        panel.addView(mConsoleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return panel;
    }

    private void showLogLevelMenu() {
        String[] names = {"Verbose", "Debug", "Info", "Warn", "Error", "Assert"};
        int[] levels = {Log.VERBOSE, Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR, Log.ASSERT};
        showAnchoredMenu(mLogLevelView, names, null, which -> {
                    mLogLevel = levels[which];
                    mLogLevelView.setText(names[which] + " ▾");
                    mConsoleView.setMinimumLogLevel(mLogLevel);
                });
    }

    private LinearLayout buildShortcutBar() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(mPalette.editorToolbar);
        LinearLayout fixed = new LinearLayout(this);
        fixed.setOrientation(LinearLayout.VERTICAL);
        fixed.addView(shortcutRowContent(new String[]{"ƒx", "ESC", "↑", "TAB"}),
                new LinearLayout.LayoutParams(dp(160), 0, 1f));
        fixed.addView(shortcutRowContent(new String[]{"群", "←", "↓", "→"}),
                new LinearLayout.LayoutParams(dp(160), 0, 1f));
        root.addView(fixed, new LinearLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.MATCH_PARENT));

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout symbols = new LinearLayout(this);
        symbols.setOrientation(LinearLayout.VERTICAL);
        symbols.addView(shortcutRowContent(new String[]{"(", ")", "/", "=", ",", ";", "\""}),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f));
        symbols.addView(shortcutRowContent(new String[]{"'", "{", "}", "[", "]", "`", "<", ">",
                        "-", "+", "|", ":", "_", "*"}),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f));
        scroll.addView(symbols, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return root;
    }

    private View shortcutRowContent(String[] labels) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (String label : labels) row.addView(shortcut(label));
        return row;
    }

    private View tool(String icon, String label, int widthDp, View.OnClickListener listener) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setClickable(true);
        box.setFocusable(true);
        box.setContentDescription(label);
        TextView iconView = text(icon, 15f, mPalette.iconPrimary);
        iconView.setGravity(Gravity.CENTER);
        iconView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        TextView labelView = text(label, 8.5f, mPalette.textSecondary);
        labelView.setGravity(Gravity.CENTER);
        labelView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(iconView, new LinearLayout.LayoutParams(dp(widthDp), 0, 1.15f));
        box.addView(labelView, new LinearLayout.LayoutParams(dp(widthDp), 0, .85f));
        box.setOnClickListener(listener);
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), dp(44)));
        return box;
    }

    private View tool(int iconRes, String label, int widthDp, View.OnClickListener listener) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setClickable(true);
        box.setFocusable(true);
        box.setContentDescription(label);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(mPalette.iconPrimary);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setPadding(dp(7), dp(3), dp(7), 0);
        TextView labelView = text(label, 8.5f, mPalette.textSecondary);
        labelView.setGravity(Gravity.CENTER);
        labelView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(icon, new LinearLayout.LayoutParams(dp(widthDp), 0, 1.15f));
        box.addView(labelView, new LinearLayout.LayoutParams(dp(widthDp), 0, .85f));
        box.setOnClickListener(listener);
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), dp(44)));
        return box;
    }

    private TextView shortcut(String label) {
        TextView view = text(label, label.length() > 2 ? 10f : 14f, mPalette.textPrimary);
        view.setGravity(Gravity.CENTER);
        view.setMinWidth(dp(40));
        view.setOnClickListener(v -> onShortcut(label));
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.MATCH_PARENT));
        return view;
    }

    private TextView smallAction(String label, View.OnClickListener listener) {
        TextView view = text(label, 12f, mPalette.textPrimary);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), 0, dp(8), 0);
        view.setOnClickListener(listener);
        return view;
    }

    private ImageView smallIconAction(int iconRes, String description, View.OnClickListener listener) {
        ImageView view = new ImageView(this);
        view.setImageResource(iconRes);
        view.setColorFilter(mPalette.iconPrimary);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setPadding(dp(10), dp(10), dp(10), dp(10));
        view.setContentDescription(description);
        view.setOnClickListener(listener);
        return view;
    }

    private void refreshFileTree() {
        if (mTreeContainer == null || mWorkspaceRoot == null) return;
        mTreeContainer.removeAllViews();
        addDirectoryChildren(mWorkspaceRoot, 0);
        mFileTreeDirty = false;
    }

    private void addDirectoryChildren(File directory, int depth) {
        File[] children = directory.listFiles();
        if (children == null) return;
        List<File> list = new ArrayList<>(Arrays.asList(children));
        Collator collator = Collator.getInstance(Locale.CHINA);
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(list, (left, right) -> {
            if (left.isDirectory() != right.isDirectory()) return left.isDirectory() ? -1 : 1;
            int leftGroup = workspaceNameGroup(left.getName());
            int rightGroup = workspaceNameGroup(right.getName());
            if (leftGroup != rightGroup) return Integer.compare(leftGroup, rightGroup);
            return collator.compare(left.getName(), right.getName());
        });
        for (File child : list) {
            if (!child.isDirectory() && !isEditableFile(child)) continue;
            boolean expanded = child.isDirectory() && mExpandedDirectories.contains(child.getAbsolutePath());
            LinearLayout item = new LinearLayout(this);
            item.setGravity(Gravity.CENTER_VERTICAL);
            TextView row = text((child.isDirectory() ? (expanded ? "▾  " : "›  ") : "<>  ")
                    + child.getName(), 14f, child.isDirectory()
                    ? mPalette.textPrimary : mPalette.textSecondary);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int maxIndent = Math.max(dp(28), mWorkspaceDrawerWidth - dp(168));
            row.setPadding(Math.min(dp(28 + depth * 18), maxIndent), 0, dp(8), 0);
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.setContentDescription((child.isDirectory() ? "文件夹 " : "文件 ")
                    + child.getName());
            row.setOnClickListener(v -> {
                if (child.isDirectory()) {
                    if (!mExpandedDirectories.add(child.getAbsolutePath()))
                        mExpandedDirectories.remove(child.getAbsolutePath());
                    refreshFileTree();
                } else {
                    openFile(child);
                    mDrawerLayout.closeDrawer(GravityCompat.START);
                }
            });
            int rowHeight = workspaceRowHeight();
            item.addView(row, new LinearLayout.LayoutParams(0, rowHeight, 1f));
            TextView more = smallAction("⋮", v -> showWorkspaceItemMenu(child));
            more.setTextSize(20f);
            more.setContentDescription(child.getName() + " 更多操作");
            item.addView(more, new LinearLayout.LayoutParams(dp(48), rowHeight));
            mTreeContainer.addView(item, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowHeight));
            if (expanded) addDirectoryChildren(child, depth + 1);
        }
    }

    private void showWorkspaceItemMenu(File item) {
        if (item == null) return;
        if (item.isFile()) {
            String[] actions = {"打开", "重命名", "发送", "删除", "用其他应用打开", "终端"};
            darkDialog().setTitle(item.getName()).setItems(actions, (dialog, which) -> {
                if (which == 0) { openFile(item); mDrawerLayout.closeDrawer(GravityCompat.START); }
                else if (which == 1) renameWorkspaceItem(item);
                else if (which == 2) shareWorkspaceItem(item);
                else if (which == 3) confirmDeleteWorkspaceItem(item);
                else if (which == 4) Scripts.INSTANCE.openByOtherApps(item);
                else openTerminalAt(item.getParentFile(), null);
            }).show();
            return;
        }
        boolean workspaceRoot = canonical(item).equals(canonical(mWorkspaceRoot));
        List<String> actions = new ArrayList<>();
        if (!workspaceRoot) actions.add("重命名");
        actions.add("发送");
        if (!workspaceRoot) actions.add("删除");
        actions.add("新建");
        actions.add("项目");
        actions.add("打开工作区");
        actions.add("终端");
        actions.add("npm");
        darkDialog().setTitle(item.getName()).setItems(actions.toArray(new String[0]),
                (dialog, which) -> {
                    String action = actions.get(which);
                    if ("重命名".equals(action)) renameWorkspaceItem(item);
                    else if ("发送".equals(action)) shareWorkspaceItem(item);
                    else if ("删除".equals(action)) confirmDeleteWorkspaceItem(item);
                    else if ("新建".equals(action)) showCreateWorkspaceMenu(item);
                    else if ("项目".equals(action)) openProjectConfig(item);
                    else if ("打开工作区".equals(action)) switchWorkspace(item);
                    else if ("终端".equals(action)) openTerminalAt(item, null);
                    else openTerminalAt(item, "npm --help");
                }).show();
    }

    private void showCreateWorkspaceMenu(File directory) {
        darkDialog().setTitle("新建").setItems(new String[]{"文件", "文件夹"},
                (dialog, which) -> showNameInput(which == 0 ? "新建文件" : "新建文件夹", "",
                        name -> createWorkspaceItem(directory, name, which == 1))).show();
    }

    private void createWorkspaceItem(File directory, String rawName, boolean folder) {
        String name = validEntryName(rawName);
        if (name == null) return;
        if (!folder && !name.contains(".")) name += ".js";
        File target = canonical(new File(directory, name));
        if (!canonical(target.getParentFile()).equals(canonical(directory)) || target.exists()) {
            toast("名称无效或文件已存在");
            return;
        }
        try {
            boolean created = folder ? target.mkdir() : target.createNewFile();
            if (!created) throw new IOException("无法创建");
            if (folder) mExpandedDirectories.add(directory.getAbsolutePath());
            refreshFileTree();
            if (!folder) openFile(target);
        } catch (IOException error) {
            toast("创建失败：" + error.getMessage());
        }
    }

    private void renameWorkspaceItem(File item) {
        showNameInput("重命名", item.getName(), rawName -> {
            String name = validEntryName(rawName);
            if (name == null || name.equals(item.getName())) return;
            File destination = canonical(new File(item.getParentFile(), name));
            if (destination.exists() || !canonical(destination.getParentFile())
                    .equals(canonical(item.getParentFile()))) {
                toast("名称无效或文件已存在");
                return;
            }
            File old = canonical(item);
            if (!item.renameTo(destination)) {
                toast("重命名失败");
                return;
            }
            remapOpenTabs(old, destination);
            if (mExpandedDirectories.remove(old.getAbsolutePath()))
                mExpandedDirectories.add(destination.getAbsolutePath());
            mProjectRoot = mActiveTab == null ? null : findProjectRoot(mActiveTab.file.getParentFile());
            refreshFileTree();
            refreshTabs();
        });
    }

    private void confirmDeleteWorkspaceItem(File item) {
        darkDialog().setTitle("删除" + (item.isDirectory() ? "文件夹" : "文件"))
                .setMessage("确定删除“" + item.getName() + "”？"
                        + (item.isDirectory() ? "\n文件夹中的全部内容也会被删除。" : ""))
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    File target = canonical(item);
                    if (!FileUtils.deleteQuietly(target)) {
                        toast("删除失败");
                        return;
                    }
                    removeDeletedTabs(target);
                    refreshFileTree();
                }).show();
    }

    private void removeDeletedTabs(File deleted) {
        List<EditorTab> removed = new ArrayList<>();
        for (EditorTab tab : mTabs) if (isWithin(tab.file, deleted)) removed.add(tab);
        for (EditorTab tab : removed) {
            tab.editor.destroy();
            mTabs.remove(tab);
        }
        if (mTabs.isEmpty()) finish();
        else selectTab(mTabs.get(Math.max(0, mTabs.size() - 1)));
    }

    private void remapOpenTabs(File oldPath, File newPath) {
        for (EditorTab tab : mTabs) {
            if (!isWithin(tab.file, oldPath)) continue;
            String relative = oldPath.equals(tab.file) ? ""
                    : tab.file.getAbsolutePath().substring(oldPath.getAbsolutePath().length() + 1);
            tab.file = canonical(relative.isEmpty() ? newPath : new File(newPath, relative));
        }
    }

    private void shareWorkspaceItem(File item) {
        if (item.isFile()) {
            Scripts.INSTANCE.send(new ScriptFile(item));
            return;
        }
        new Thread(() -> {
            File archive = new File(getCacheDir(), "share-" + item.getName() + ".zip");
            try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
                zipDirectory(item, item.getName(), output);
                runOnUiThread(() -> {
                    Uri uri = AppFileProvider.getUriForFile(this, archive);
                    Intent share = new Intent(Intent.ACTION_SEND)
                            .setType("application/zip")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(share, "发送项目"));
                });
            } catch (IOException error) {
                runOnUiThread(() -> toast("打包发送失败：" + error.getMessage()));
            }
        }, "pro-editor-share").start();
    }

    private void zipDirectory(File file, String entryName, ZipOutputStream output) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null || children.length == 0) {
                output.putNextEntry(new ZipEntry(entryName + "/"));
                output.closeEntry();
                return;
            }
            for (File child : children) zipDirectory(child, entryName + "/" + child.getName(), output);
            return;
        }
        output.putNextEntry(new ZipEntry(entryName));
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) >= 0) output.write(buffer, 0, length);
        }
        output.closeEntry();
    }

    private void switchWorkspace(File directory) {
        mWorkspaceRoot = canonical(directory);
        mExpandedDirectories.clear();
        mExpandedDirectories.add(mWorkspaceRoot.getAbsolutePath());
        if (mTreeRootLabel != null) mTreeRootLabel.setText("▾  " + mWorkspaceRoot.getName());
        refreshFileTree();
    }

    private void openProjectConfig(File directory) {
        File project = findProjectRoot(directory);
        if (project == null || !isWithin(directory, project)) {
            toast("该目录不是 AI.js Pro 项目");
            return;
        }
        startActivity(new Intent(this, ProjectConfigActivity.class)
                .putExtra(ProjectConfigActivity.EXTRA_DIRECTORY, project.getAbsolutePath()));
    }

    private void showNameInput(String title, String initial, NameCallback callback) {
        EditText input = dialogInput("名称");
        input.setText(initial);
        input.setSelectAllOnFocus(true);
        darkDialog().setTitle(title).setView(input).setNegativeButton("取消", null)
                .setPositiveButton("确定", (dialog, which) ->
                        callback.onName(input.getText().toString())).show();
    }

    private String validEntryName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)
                || name.contains("/") || name.contains("\\")) {
            toast("请输入有效名称");
            return null;
        }
        return name;
    }

    private boolean isEditableFile(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".js") || name.endsWith(".auto") || name.endsWith(".json")
                || name.endsWith(".xml") || name.endsWith(".txt") || name.endsWith(".md")
                || name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".css")
                || name.endsWith(".html");
    }

    private void openFile(File requested) {
        File file = canonical(requested);
        if (file == null || !file.isFile()) {
            toast("文件不存在");
            return;
        }
        for (EditorTab tab : mTabs) {
            if (tab.file.equals(file)) {
                selectTab(tab);
                return;
            }
        }
        // Read the file off the main thread so large files do not block the first frame;
        // build the editor UI back on the main thread.
        Thread worker = new Thread(() -> {
            String source;
            try {
                source = FileUtils.readFileToString(file, "UTF-8");
            } catch (IOException error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "读取失败：" + error.getMessage(), Toast.LENGTH_LONG).show());
                return;
            }
            String finalSource = source;
            runOnUiThread(() -> attachFileTab(file, finalSource));
        });
        worker.start();
    }

    private void restoreSessionAndOpen(File initial) {
        SharedPreferences prefs = editorPreferences();
        List<File> files = new ArrayList<>();
        String encoded = prefs.getString(PREF_OPEN_TABS, "");
        if (!TextUtils.isEmpty(encoded)) {
            try {
                JSONArray array = new JSONArray(encoded);
                for (int i = 0; i < array.length() && files.size() < MAX_RESTORED_TABS; i++) {
                    File candidate = canonical(new File(array.optString(i, "")));
                    if (candidate != null && candidate.isFile()
                            && isWithin(candidate, mWorkspaceRoot) && !files.contains(candidate)) {
                        files.add(candidate);
                    }
                }
            } catch (Exception ignored) { }
        }
        if (!files.contains(initial)) files.add(initial);
        // The path carried by the launch intent is an explicit user selection. Restore the
        // previous tabs around it, but never let the previously active tab steal focus from
        // the file that was just tapped in the file list or search results.
        final String activePath = initial.getAbsolutePath();
        Thread worker = new Thread(() -> {
            List<File> loadedFiles = new ArrayList<>();
            List<String> loadedSources = new ArrayList<>();
            for (File file : files) {
                try {
                    loadedSources.add(FileUtils.readFileToString(file, "UTF-8"));
                    loadedFiles.add(file);
                } catch (IOException ignored) { }
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                EditorTab preferred = null;
                for (int i = 0; i < loadedFiles.size(); i++) {
                    attachFileTab(loadedFiles.get(i), loadedSources.get(i));
                    if (loadedFiles.get(i).getAbsolutePath().equals(activePath)) preferred = mActiveTab;
                }
                if (preferred != null) selectTab(preferred);
            });
        }, "pro-editor-restore");
        worker.start();
    }

    private SharedPreferences editorPreferences() {
        return getSharedPreferences(EDITOR_PREFS, MODE_PRIVATE);
    }

    private String viewStateKey(String kind, File file) {
        return kind + "_" + Integer.toHexString(file.getAbsolutePath().hashCode());
    }

    private void saveEditorSession() {
        if (mTabs.isEmpty()) return;
        JSONArray tabs = new JSONArray();
        SharedPreferences.Editor preferences = editorPreferences().edit();
        for (EditorTab tab : mTabs) {
            if (tab.file.isFile()) tabs.put(tab.file.getAbsolutePath());
            saveEditorViewState(tab, preferences);
        }
        preferences.putString(PREF_OPEN_TABS, tabs.toString());
        if (mActiveTab != null) preferences.putString(PREF_ACTIVE_TAB,
                mActiveTab.file.getAbsolutePath());
        if (mActiveTab != null) {
            float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
            preferences.putFloat(PREF_TEXT_SIZE,
                    mActiveTab.editor.getCodeEditText().getTextSize() / Math.max(1f, scaledDensity));
        }
        preferences.apply();
    }

    private void saveEditorViewState(EditorTab tab, SharedPreferences.Editor preferences) {
        if (tab == null || tab.editor == null) return;
        EditText editText = tab.editor.getCodeEditText();
        preferences.putInt(viewStateKey("cursor", tab.file),
                Math.max(0, editText.getSelectionStart()));
        preferences.putInt(viewStateKey("scroll_x", tab.file), tab.editor.getScrollX());
        preferences.putInt(viewStateKey("scroll_y", tab.file), tab.editor.getScrollY());
    }

    private void restoreEditorViewState(EditorTab tab) {
        if (tab == null || tab.viewStateRestored) return;
        tab.viewStateRestored = true;
        SharedPreferences prefs = editorPreferences();
        int cursor = prefs.getInt(viewStateKey("cursor", tab.file), 0);
        int scrollX = prefs.getInt(viewStateKey("scroll_x", tab.file), 0);
        int scrollY = prefs.getInt(viewStateKey("scroll_y", tab.file), 0);
        tab.editor.post(() -> {
            if (!mTabs.contains(tab)) return;
            EditText editText = tab.editor.getCodeEditText();
            editText.setSelection(Math.max(0, Math.min(editText.length(), cursor)));
            tab.editor.scrollTo(Math.max(0, scrollX), Math.max(0, scrollY));
        });
    }

    private void attachFileTab(File file, String source) {
        CodeEditor editor = new CodeEditor(this);
        Theme theme = Theme.fromAssetsJson(this, mPalette.isDark
                ? "editor/theme/dark_plus.json" : "editor/theme/light_plus.json");
        if (theme != null) editor.setTheme(theme);
        editor.getCodeEditText().setTextSize(TypedValue.COMPLEX_UNIT_SP,
                editorPreferences().getFloat(PREF_TEXT_SIZE, 17f));
        editor.setInitialText(source);
        EditorTab tab = new EditorTab(file, editor, source);
        editor.getCodeEditText().addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                if (tab == mActiveTab && !tab.dirtyMarkerShown) {
                    tab.dirtyMarkerShown = true;
                    updateActiveTabLabel();
                }
                updateToolbarState();
            }
        });
        mTabs.add(tab);
        selectTab(tab);
    }

    private void selectTab(EditorTab tab) {
        mActiveTab = tab;
        mEditorContainer.removeAllViews();
        if (tab.editor.getParent() instanceof ViewGroup)
            ((ViewGroup) tab.editor.getParent()).removeView(tab.editor);
        mEditorContainer.addView(tab.editor, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applyBreakpointListener(tab);
        refreshTabs();
        restoreEditorViewState(tab);
        updateToolbarState();
    }

    private void refreshTabs() {
        mTabBar.removeAllViews();
        for (EditorTab tab : mTabs) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            TextView view = text(tab.file.getName() + (hasUnsavedChanges(tab) ? " •" : ""), 14f,
                    tab == mActiveTab ? mPalette.textPrimary : mPalette.textSecondary);
            view.setGravity(Gravity.CENTER);
            view.setPadding(dp(16), 0, dp(16), 0);
            view.setOnClickListener(v -> selectTab(tab));
            view.setOnLongClickListener(v -> {
                requestCloseTab(tab);
                return true;
            });
            cell.addView(view, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f));
            View indicator = new View(this);
            indicator.setBackgroundColor(tab == mActiveTab
                    ? mPalette.accent : Color.TRANSPARENT);
            cell.addView(indicator, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));
            mTabBar.addView(cell, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
        }
    }

    private void updateActiveTabLabel() {
        if (mTabBar != null && mActiveTab != null) refreshTabs();
    }

    private void updateToolbarState() {
        CodeEditor editor = activeEditor();
        setToolEnabled(mUndoTool, editor != null && editor.canUndo());
        setToolEnabled(mRedoTool, editor != null && editor.canRedo());
        setToolEnabled(mSaveTool, mActiveTab != null && hasUnsavedChanges(mActiveTab));
    }

    private void setToolEnabled(View tool, boolean enabled) {
        if (tool == null) return;
        tool.setEnabled(enabled);
        tool.setAlpha(enabled ? 1f : 0.38f);
    }

    private void requestCloseTab(EditorTab tab) {
        if (!hasUnsavedChanges(tab)) {
            closeTab(tab);
            return;
        }
        darkDialog().setTitle("保存修改？")
                .setMessage(tab.file.getName())
                .setNegativeButton("不保存", (dialog, which) -> closeTab(tab))
                .setNeutralButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    if (saveTab(tab, false)) closeTab(tab);
                }).show();
    }

    private void closeTab(EditorTab tab) {
        int index = mTabs.indexOf(tab);
        tab.editor.destroy();
        mTabs.remove(tab);
        if (mTabs.isEmpty()) {
            finish();
            return;
        }
        selectTab(mTabs.get(Math.max(0, Math.min(index, mTabs.size() - 1))));
    }

    private void openWorkspaceDrawer() {
        if (mFileTreeDirty) refreshFileTree();
        mDrawerLayout.openDrawer(GravityCompat.START);
    }

    private int workspaceNameGroup(String name) {
        if (name.startsWith(".")) return 0;
        for (int i = 0; i < name.length(); i++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(name.charAt(i));
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) return 1;
        }
        return 2;
    }

    private void showEditMenu(View anchor) {
        String[] items = {"查找/替换", "跳转", "复制", "删除", "移动", "折叠", "格式化代码"};
        boolean[] submenus = {false, true, true, true, true, true, false};
        showAnchoredMenu(anchor, items, submenus, which -> {
            CodeEditor editor = activeEditor();
            if (editor == null) return;
            switch (which) {
                case 0: showFindReplace(); break;
                case 1: showJumpMenu(anchor); break;
                case 2: showCopyMenu(anchor); break;
                case 3: showDeleteMenu(anchor); break;
                case 4: showMoveLineMenu(anchor); break;
                case 5: showFoldMenu(anchor); break;
                case 6:
                    editor.getCodeEditText().unfoldAll();
                    editor.beautifyCode();
                    break;
            }
        });
    }

    private void showCopyMenu(View anchor) {
        showAnchoredMenu(anchor, new String[]{"复制", "复制全部", "复制行"}, null,
                which -> {
                    if (which == 0) copySelection();
                    else if (which == 1) {
                        ClipboardUtil.setClip(this, activeEditor().getText());
                        toast("已复制全部");
                    } else activeEditor().copyLine();
                });
    }

    private void copySelection() {
        EditText edit = activeEditor().getCodeEditText();
        int start = Math.min(edit.getSelectionStart(), edit.getSelectionEnd());
        int end = Math.max(edit.getSelectionStart(), edit.getSelectionEnd());
        if (start == end) {
            toast("请先选中内容");
            return;
        }
        ClipboardUtil.setClip(this, edit.getText().subSequence(start, end));
        toast("已复制");
    }

    private void showDeleteMenu(View anchor) {
        showAnchoredMenu(anchor, new String[]{"删除", "删除行", "清空"}, null,
                which -> {
                    if (which == 0) deleteSelectionOrCharacter();
                    else if (which == 1) activeEditor().deleteLine();
                    else darkDialog().setTitle("清空编辑器？")
                            .setMessage("此操作可以在保存前撤销。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("清空", (d, w) -> activeEditor().setText(""))
                            .show();
                });
    }

    private void deleteSelectionOrCharacter() {
        EditText edit = activeEditor().getCodeEditText();
        int start = Math.min(edit.getSelectionStart(), edit.getSelectionEnd());
        int end = Math.max(edit.getSelectionStart(), edit.getSelectionEnd());
        if (start == end && end < edit.length()) end++;
        if (start != end) edit.getText().delete(start, end);
    }

    private void showFoldMenu(View anchor) {
        showAnchoredMenu(anchor, new String[]{"折叠", "全部折叠", "全部展开"}, null,
                which -> {
                    if (which == 0) activeEditor().getCodeEditText().toggleFoldAtSelection();
                    else if (which == 1) activeEditor().getCodeEditText().foldAllTopLevel();
                    else activeEditor().getCodeEditText().unfoldAll();
                });
    }

    private void showFindReplace() {
        if (activeEditor() == null) return;
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(8), dp(18), dp(8));
        EditText query = dialogInput("查找");
        EditText replacement = dialogInput("替换");
        CheckBox regex = new CheckBox(this);
        regex.setText("正则表达式");
        regex.setTextColor(mPalette.textPrimary);
        CheckBox replaceMode = new CheckBox(this);
        replaceMode.setText("替换");
        replaceMode.setTextColor(mPalette.textPrimary);
        CheckBox replaceAllMode = new CheckBox(this);
        replaceAllMode.setText("全部替换");
        replaceAllMode.setTextColor(mPalette.textPrimary);
        replaceMode.setOnCheckedChangeListener((button, checked) -> {
            if (checked) replaceAllMode.setChecked(false);
        });
        replaceAllMode.setOnCheckedChangeListener((button, checked) -> {
            if (checked) replaceMode.setChecked(false);
        });
        body.addView(query);
        body.addView(regex);
        body.addView(replacement);
        body.addView(replaceMode);
        body.addView(replaceAllMode);
        TextView note = text("正则语法与JavaScript中相同, 可使用$1~9代替被捕获的匹配",
                11f, mPalette.textSecondary);
        note.setPadding(0, dp(6), 0, dp(4));
        body.addView(note);
        AlertDialog dialog = darkDialog().setTitle("查找/替换")
                .setView(body).setNegativeButton("取消", null)
                .setPositiveButton("确定", (ignored, which) -> {
                    String value = query.getText().toString();
                    if (replaceAllMode.isChecked()) replaceAll(value,
                            replacement.getText().toString(), regex.isChecked());
                    else if (replaceMode.isChecked()) replaceOne(value,
                            replacement.getText().toString(), regex.isChecked());
                    else find(value, regex.isChecked(), 1);
                }).create();
        dialog.setOnShowListener(ignored -> query.requestFocus());
        dialog.show();
    }

    private void find(String query, boolean regex, int direction) {
        if (TextUtils.isEmpty(query) || activeEditor() == null) return;
        try {
            activeEditor().find(query, regex);
            if (direction < 0) activeEditor().findPrev();
        } catch (CodeEditor.CheckedPatternSyntaxException error) {
            toast("正则表达式错误");
        }
    }

    private void replaceOne(String query, String replacement, boolean regex) {
        if (TextUtils.isEmpty(query) || activeEditor() == null) return;
        try {
            activeEditor().replace(query, replacement, regex);
            activeEditor().replaceSelection();
        } catch (CodeEditor.CheckedPatternSyntaxException error) {
            toast("正则表达式错误");
        }
    }

    private void replaceAll(String query, String replacement, boolean regex) {
        if (TextUtils.isEmpty(query) || activeEditor() == null) return;
        try {
            activeEditor().replaceAll(query, replacement, regex);
        } catch (CodeEditor.CheckedPatternSyntaxException error) {
            toast("正则表达式错误");
        }
    }

    private void showJumpMenu(View anchor) {
        String[] items = {"跳转到行", "转到文件开始", "转到文件末尾", "转到行首", "转到行尾"};
        showAnchoredMenu(anchor, items, null, which -> {
            CodeEditor editor = activeEditor();
            if (editor == null) return;
            if (which == 0) showJumpLineDialog();
            else if (which == 1) editor.jumpToStart();
            else if (which == 2) editor.jumpToEnd();
            else if (which == 3) editor.jumpToLineStart();
            else editor.jumpToLineEnd();
        });
    }

    private void showJumpLineDialog() {
        final EditText input = dialogInput("行号");
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        int lines = countLines(activeEditor().getText());
        darkDialog().setTitle("跳转到行（1 - " + lines + "）")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("跳转", (dialog, which) -> {
                    try {
                        int line = Integer.parseInt(input.getText().toString());
                        activeEditor().jumpTo(Math.max(0, Math.min(lines - 1, line - 1)), 0);
                    } catch (Exception ignored) { }
                }).show();
    }

    private void showMoveLineMenu(View anchor) {
        showAnchoredMenu(anchor, new String[]{"上移行", "下移行"}, null,
                which -> moveCurrentLine(which == 0 ? -1 : 1));
    }

    private void moveCurrentLine(int direction) {
        if (activeEditor() == null) return;
        EditText edit = activeEditor().getCodeEditText();
        Layout layout = edit.getLayout();
        if (layout == null) return;
        int line = layout.getLineForOffset(edit.getSelectionStart());
        int target = line + direction;
        if (target < 0 || target >= layout.getLineCount()) return;
        String[] lines = activeEditor().getText().split("\\n", -1);
        String swap = lines[line]; lines[line] = lines[target]; lines[target] = swap;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) result.append('\n');
            result.append(lines[i]);
        }
        activeEditor().setText(result.toString());
        activeEditor().jumpTo(target, 0);
    }

    private String expandedText(EditorTab tab) {
        return tab.editor.getText();
    }

    private void showDebugMenu(View anchor) {
        String[] items = {"强制停止", "强制停止所有脚本", "断点", "启动调试",
                "删除所有断点", "悬浮运行"};
        showAnchoredMenu(anchor, items, null, which -> {
            if (activeEditor() == null) return;
            if (which == 0) stopCurrentExecution();
            else if (which == 1) AutoJs.getInstance().getScriptEngineService().stopAllAndToast();
            else if (which == 2) activeEditor().addOrRemoveBreakpointAtCurrentLine();
            else if (which == 3) startDebugger();
            else if (which == 4) {
                for (EditorTab tab : mTabs) tab.editor.removeAllBreakpoints();
                if (mDebugger != null) mDebugger.clearAllBreakpoints();
            } else floatingRun();
        });
    }

    private void startDebugger() {
        if (mActiveTab == null || !saveActive(false)) return;
        finishDebugSession(false);
        try {
            mDebugger = DebuggerSingleton.get();
            mDebugger.setWeakDebugCallback(new WeakReference<>(this));
            applyBreakpointListener(mActiveTab);
            mActiveTab.editor.setRedoUndoEnabled(false);
            mExecution = Scripts.INSTANCE.runWithBroadcastSender(mActiveTab.file);
            if (mExecution == null) {
                toast("调试器启动失败");
                return;
            }
            mDebugger.attach(mExecution);
            mDebugBar.setVisibility(View.VISIBLE);
            setDebugState("调试已启动 · 等待断点");
            showLogPanel();
        } catch (Exception error) {
            finishDebugSession(false);
            Toast.makeText(this, "调试失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void applyBreakpointListener(EditorTab tab) {
        tab.editor.setBreakpointChangeListener(new CodeEditor.BreakpointChangeListener() {
            @Override public void onBreakpointChange(int line, boolean enabled) {
                if (mDebugger != null && mDebugger.isAttached() && tab == mActiveTab)
                    mDebugger.breakpoint(line + 1, enabled);
            }
            @Override public void onAllBreakpointRemoved(int count) {
                if (mDebugger != null && mDebugger.isAttached()) mDebugger.clearAllBreakpoints();
            }
        });
    }

    private void debugStep(int type) {
        if (mDebugger == null || !mDebugger.isAttached() || !mDebugInterrupted) {
            toast("脚本尚未停在断点");
            return;
        }
        mDebugInterrupted = false;
        setDebugState("调试运行中");
        if (type == 0) mDebugger.stepOver();
        else if (type == 1) mDebugger.stepInto();
        else if (type == 2) mDebugger.stepOut();
        else mDebugger.resume();
    }

    private void stopCurrentExecution() {
        if (mExecution != null && mExecution.getEngine() != null) mExecution.getEngine().forceStop();
        else toast("当前没有由编辑器启动的脚本");
        finishDebugSession(false);
    }

    private void finishDebugSession(boolean detachOnly) {
        if (mDebugger != null && mDebugger.isAttached()) mDebugger.detach();
        if (mDebugger != null) mDebugger.setWeakDebugCallback(null);
        for (EditorTab tab : mTabs) {
            tab.editor.setDebuggingLine(-1);
            tab.editor.setRedoUndoEnabled(true);
        }
        mDebugInterrupted = false;
        if (mDebugBar != null) mDebugBar.setVisibility(View.GONE);
        if (!detachOnly) mExecution = null;
    }

    private void setDebugState(String value) {
        View state = mDebugBar.findViewWithTag("debug_state");
        if (state instanceof TextView) ((TextView) state).setText(value);
    }

    @Override
    public void updateSourceText(Dim.SourceInfo sourceInfo) {
        if (mActiveTab == null) return;
        sourceInfo.removeAllBreakpoints();
        for (CodeEditor.Breakpoint breakpoint : mActiveTab.editor.getBreakpoints().values()) {
            int line = breakpoint.line + 1;
            if (sourceInfo.breakableLine(line)) sourceInfo.breakpoint(line, breakpoint.enabled);
        }
    }

    @Override
    public void enterInterrupt(Dim.StackFrame frame, String threadName, String message) {
        runOnUiThread(() -> {
            File sourceFile = fileFromDebuggerUrl(frame.getUrl());
            if (sourceFile != null && sourceFile.isFile()) openFile(sourceFile);
            if (activeEditor() != null) {
                int line = Math.max(0, frame.getLineNumber() - 1);
                activeEditor().setDebuggingLine(line);
                activeEditor().jumpTo(line, 0);
            }
            mDebugInterrupted = true;
            mDebugBar.setVisibility(View.VISIBLE);
            setDebugState("断点 · " + (sourceFile == null ? threadName : sourceFile.getName())
                    + ":" + frame.getLineNumber());
            if (message != null) toast(message);
        });
    }

    private File fileFromDebuggerUrl(String url) {
        if (TextUtils.isEmpty(url)) return null;
        try {
            if (url.startsWith("file:")) return canonical(new File(Uri.parse(url).getPath()));
            return canonical(new File(url));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void floatingRun() {
        if (!saveActive(false)) return;
        boolean shown = FloatyWindowManger.showCircularMenu();
        runCurrent();
        if (shown) moveTaskToBack(true);
        else toast("悬浮窗权限未开启，脚本已在当前界面运行");
    }

    private void openTerminal() {
        File directory = mActiveTab == null ? mWorkspaceRoot : mActiveTab.file.getParentFile();
        openTerminalAt(directory, null);
    }

    private void openTerminalAt(File directory, String initialCommand) {
        Intent intent = new Intent(this, EmbeddedTerminalActivity.class)
                .putExtra(EmbeddedTerminalActivity.EXTRA_WORKING_DIRECTORY,
                        directory == null ? null : directory.getAbsolutePath());
        if (!TextUtils.isEmpty(initialCommand))
            intent.putExtra(EmbeddedTerminalActivity.EXTRA_INITIAL_COMMAND, initialCommand);
        startActivity(intent);
    }

    private void showOtherMenu(View anchor) {
        String[] items = {"项目", "打包单文件", "搜索Java包/类", "信息", "字体大小",
                "界面主题", "用其他应用打开", "设计"};
        showAnchoredMenu(anchor, items, null, which -> {
            if (mActiveTab == null) return;
            if (which == 0) openProjectConfig();
            else if (which == 1) buildApk();
            else if (which == 2) searchJavaClass();
            else if (which == 3) showEditorInfo();
            else if (which == 4) selectTextSize();
            else if (which == 5) selectTheme();
            else if (which == 6) Scripts.INSTANCE.openByOtherApps(mActiveTab.file);
            else showDesigner();
        });
    }

    private void openProjectConfig() {
        if (mProjectRoot == null) {
            toast("当前文件不属于 AI.js Pro 项目");
            return;
        }
        startActivity(new Intent(this, ProjectConfigActivity.class)
                .putExtra(ProjectConfigActivity.EXTRA_DIRECTORY, mProjectRoot.getAbsolutePath()));
    }

    private void buildApk() {
        if (!saveActive(false)) return;
        File source = mProjectRoot == null ? mActiveTab.file : mProjectRoot;
        startActivity(new Intent(this, BuildActivity.class)
                .putExtra(BuildActivity.EXTRA_SOURCE, source.getAbsolutePath()));
    }

    private void searchJavaClass() {
        new ClassSearchDialogBuilder(this)
                .setQuery("")
                .itemClick((dialog, item, position) -> showJavaClassAction(dialog, item))
                .title("搜索Java包/类")
                .show();
    }

    private void showJavaClassAction(MaterialDialog searchDialog, ClassSearchingItem item) {
        darkDialog().setTitle(item.getLabel()).setItems(new String[]{"导入", "复制", "打开文档"},
                (dialog, which) -> {
                    if (which == 0) {
                        activeEditor().insert(0, item.getImportText() + ";\n");
                        searchDialog.dismiss();
                    } else if (which == 1) {
                        ClipboardUtil.setClip(this, item.getImportText());
                        toast("已复制");
                    } else {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(item.getUrl())));
                    }
                }).show();
    }

    private void showEditorInfo() {
        String source = expandedText(mActiveTab);
        String message = "文件：" + mActiveTab.file.getAbsolutePath()
                + "\n行数：" + countLines(source)
                + "\n字符：" + source.length()
                + "\n已打开标签：" + mTabs.size()
                + "\n工作区：" + (mWorkspaceRoot == null ? "" : mWorkspaceRoot.getAbsolutePath());
        darkDialog().setTitle("信息").setMessage(message).setPositiveButton("确定", null).show();
    }

    private void confirmResetSample() {
        if (mActiveTab == null || TextUtils.isEmpty(mSampleAssetDirectory)) return;
        String assetPath = sampleAssetPathFor(mActiveTab.file);
        if (assetPath == null) {
            toast("当前文件不是内置示例");
            return;
        }
        darkDialog().setTitle("重置当前示例？")
                .setMessage("将用 APK 内置版本覆盖“" + mActiveTab.file.getName()
                        + "”的已保存和未保存修改。")
                .setNegativeButton("取消", null)
                .setPositiveButton("重置", (dialog, which) -> resetActiveSample(assetPath))
                .show();
    }

    private String sampleAssetPathFor(File file) {
        File canonicalFile = canonical(file);
        File canonicalRoot = canonical(mWorkspaceRoot);
        if (canonicalFile == null || canonicalRoot == null || !isWithin(canonicalFile, canonicalRoot))
            return null;
        String rootPath = canonicalRoot.getAbsolutePath();
        String filePath = canonicalFile.getAbsolutePath();
        if (filePath.length() <= rootPath.length()) return null;
        String relative = filePath.substring(rootPath.length() + 1)
                .replace(File.separatorChar, '/');
        return mSampleAssetDirectory + "/" + relative;
    }

    private void resetActiveSample(String assetPath) {
        EditorTab tab = mActiveTab;
        if (tab == null) return;
        tab.editor.getCodeEditText().unfoldAll();
        try (InputStream input = getAssets().open(assetPath);
             FileOutputStream output = new FileOutputStream(tab.file, false)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
            String source = FileUtils.readFileToString(tab.file, "UTF-8");
            tab.editor.setInitialText(source);
            tab.savedText = source;
            tab.dirtyMarkerShown = false;
            tab.editor.markTextAsSaved();
            refreshTabs();
            toast("已恢复内置示例");
        } catch (IOException error) {
            Toast.makeText(this, "重置失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void selectTextSize() {
        String[] sizes = {"12", "14", "15", "16", "18", "20", "22"};
        darkDialog().setTitle("字体大小").setItems(sizes, (dialog, which) -> {
            float size = Float.parseFloat(sizes[which]);
            for (EditorTab tab : mTabs) tab.editor.getCodeEditText().setTextSize(size);
            editorPreferences().edit().putFloat(PREF_TEXT_SIZE, size).apply();
        }).show();
    }

    private void selectTheme() {
        AppThemeRepository repository = AppThemeRepository.get(this);
        String[] modes = {"跟随系统", "浅色", "深色"};
        darkDialog().setTitle("界面主题").setSingleChoiceItems(modes,
                repository.getThemeMode(), (dialog, which) -> {
                    repository.setThemeMode(which);
                    dialog.dismiss();
                }).show();
    }

    private void showDesigner() {
        if (mActiveTab == null) return;
        String xml = extractUiLayout(expandedText(mActiveTab));
        if (xml == null) {
            toast("没有找到 ui.layout(...) 或 setViewFromXml(...) 布局");
            return;
        }
        try {
            DynamicLayoutInflater inflater = new DynamicLayoutInflater(
                    new ResourceParser(new com.stardust.autojs.core.ui.inflater.util.Drawables()));
            inflater.setContext(this);
            View preview = inflater.inflate(xml);
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(mPalette.windowBackground);
            LinearLayout bar = new LinearLayout(this);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(dp(12), 0, dp(6), 0);
            bar.setBackgroundColor(mPalette.editorToolbar);
            TextView title = text("设计预览 · " + mActiveTab.file.getName() + "（点此关闭）", 15f, mPalette.textPrimary);
            bar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));
            root.addView(bar);
            FrameLayout stage = new FrameLayout(this);
            stage.setPadding(dp(8), dp(8), dp(8), dp(8));
            stage.addView(preview, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(stage, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            Dialog dialog = new Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar);
            dialog.setContentView(root);
            title.setOnClickListener(v -> dialog.dismiss());
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        } catch (Exception error) {
            darkDialog().setTitle("设计预览失败")
                    .setMessage(error.getMessage()).setPositiveButton("确定", null).show();
        }
    }

    private String extractUiLayout(String source) {
        int uiLayoutCall = source.indexOf("ui.layout");
        int xmlViewCall = source.indexOf("setViewFromXml");
        int call;
        if (uiLayoutCall < 0) call = xmlViewCall;
        else if (xmlViewCall < 0) call = uiLayoutCall;
        else call = Math.min(uiLayoutCall, xmlViewCall);
        if (call < 0) return null;
        int start = source.indexOf('<', call);
        if (start < 0) return null;
        int closeCall = source.indexOf(");", start);
        if (closeCall < 0) closeCall = source.length();
        int end = source.lastIndexOf('>', closeCall);
        return end <= start ? null : source.substring(start, end + 1);
    }

    private void toggleLogPanel() {
        if (mLogPanel.getVisibility() == View.VISIBLE) hideLogPanel(); else showLogPanel();
    }

    private void setTabBarEnabled(boolean enabled) {
        if (mTabBar == null) return;
        for (int i = 0; i < mTabBar.getChildCount(); i++) {
            View child = mTabBar.getChildAt(i);
            child.setEnabled(enabled);
            child.setClickable(enabled);
            child.setFocusable(enabled);
        }
    }

    private void showLogPanel() {
        if (mLogPanel.getVisibility() == View.VISIBLE) return;
        mLogExpanded = false;
        LinearLayout.LayoutParams editorParams = (LinearLayout.LayoutParams) mEditorContainer.getLayoutParams();
        editorParams.height = dp(135);
        editorParams.weight = 0f;
        mEditorContainer.setLayoutParams(editorParams);
        LinearLayout.LayoutParams logParams = (LinearLayout.LayoutParams) mLogPanel.getLayoutParams();
        logParams.height = 0;
        logParams.weight = 1f;
        mLogPanel.setLayoutParams(logParams);
        mShortcutBar.setVisibility(View.GONE);
        setToolSelected(mLogTool, true);
        mLogPanel.setVisibility(View.VISIBLE);
        // While the log console is open, block all interaction with the editor above
        // (like Auto.js Pro): a translucent shield consumes touches over the editor area.
        if (mEditorShield.getParent() == null && mEditorContainer != null) {
            mEditorContainer.addView(mEditorShield, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        setTabBarEnabled(false);
    }

    private void hideLogPanel() {
        mLogExpanded = false;
        if (mEditorShield.getParent() instanceof ViewGroup) {
            ((ViewGroup) mEditorShield.getParent()).removeView(mEditorShield);
        }
        setTabBarEnabled(true);
        mLogPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams editorParams = (LinearLayout.LayoutParams) mEditorContainer.getLayoutParams();
        editorParams.height = 0;
        editorParams.weight = 1f;
        mEditorContainer.setLayoutParams(editorParams);
        mEditorContainer.setVisibility(View.VISIBLE);
        mShortcutBar.setVisibility(View.VISIBLE);
        setToolSelected(mLogTool, false);
    }

    private void setToolSelected(View tool, boolean selected) {
        if (tool == null) return;
        if (!selected) {
            tool.setBackgroundColor(Color.TRANSPARENT);
            return;
        }
        GradientDrawable background = new GradientDrawable();
        background.setColor(mPalette.editorTabActive);
        background.setCornerRadius(dp(22));
        tool.setBackground(background);
    }

    private void toggleLogExpanded() {
        mLogExpanded = !mLogExpanded;
        mEditorContainer.setVisibility(mLogExpanded ? View.GONE : View.VISIBLE);
        if (!mLogExpanded) {
            LinearLayout.LayoutParams editorParams =
                    (LinearLayout.LayoutParams) mEditorContainer.getLayoutParams();
            editorParams.height = dp(135);
            editorParams.weight = 0f;
            mEditorContainer.setLayoutParams(editorParams);
        }
    }

    private void runCurrent() {
        if (mActiveTab == null || !saveActive(false)) return;
        try {
            mExecution = Scripts.INSTANCE.runWithBroadcastSender(mActiveTab.file);
            if (mExecution != null) {
                toast("脚本已启动");
                showLogPanel();
            }
        } catch (Exception error) {
            Toast.makeText(this, "运行失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean saveActive(boolean showToast) {
        return mActiveTab != null && saveTab(mActiveTab, showToast);
    }

    private boolean saveTab(EditorTab tab, boolean showToast) {
        try {
            String source = expandedText(tab);
            FileUtils.writeStringToFile(tab.file, source, "UTF-8");
            tab.savedText = source;
            tab.dirtyMarkerShown = false;
            tab.editor.markTextAsSaved();
            refreshTabs();
            updateToolbarState();
            if (showToast) toast("已保存");
            return true;
        } catch (IOException error) {
            Toast.makeText(this, "保存失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void saveAllAndFinish() {
        for (EditorTab tab : mTabs) if (hasUnsavedChanges(tab) && !saveTab(tab, false)) return;
        finish();
    }

    private boolean hasUnsavedChanges(EditorTab tab) {
        return !tab.savedText.equals(expandedText(tab));
    }

    private boolean hasAnyUnsavedChanges() {
        for (EditorTab tab : mTabs) if (hasUnsavedChanges(tab)) return true;
        return false;
    }

    private CodeEditor activeEditor() {
        return mActiveTab == null ? null : mActiveTab.editor;
    }

    private void onShortcut(String label) {
        CodeEditor editor = activeEditor();
        if (editor == null) return;
        if ("ƒx".equals(label) || "群".equals(label)) {
            showSnippetMenu();
        } else if ("ESC".equals(label)) {
            View focus = getCurrentFocus();
            if (focus != null) ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(focus.getWindowToken(), 0);
        } else if ("TAB".equals(label)) {
            editor.insert("    ");
        } else if ("←".equals(label)) {
            moveCursorHorizontal(-1);
        } else if ("→".equals(label)) {
            moveCursorHorizontal(1);
        } else if ("↑".equals(label)) {
            moveCursorVertical(-1);
        } else if ("↓".equals(label)) {
            moveCursorVertical(1);
        } else if ("Home".equals(label)) {
            editor.jumpToLineStart();
        } else if ("End".equals(label)) {
            editor.jumpToLineEnd();
        } else {
            editor.insert(label);
        }
    }

    private void moveCursorHorizontal(int delta) {
        EditText edit = activeEditor().getCodeEditText();
        int target = Math.max(0, Math.min(edit.length(), edit.getSelectionStart() + delta));
        edit.setSelection(target);
    }

    private void moveCursorVertical(int delta) {
        EditText edit = activeEditor().getCodeEditText();
        Layout layout = edit.getLayout();
        if (layout == null) return;
        int current = layout.getLineForOffset(edit.getSelectionStart());
        int targetLine = Math.max(0, Math.min(layout.getLineCount() - 1, current + delta));
        int column = edit.getSelectionStart() - layout.getLineStart(current);
        int end = Math.max(layout.getLineStart(targetLine), layout.getLineEnd(targetLine) - 1);
        edit.setSelection(Math.min(layout.getLineStart(targetLine) + column, end));
    }

    private void showSnippetMenu() {
        String[] names = {"function", "if", "for", "while", "try/catch", "console.log"};
        String[] snippets = {"function name() {\n    \n}", "if (condition) {\n    \n}",
                "for (let i = 0; i < length; i++) {\n    \n}", "while (condition) {\n    \n}",
                "try {\n    \n} catch (error) {\n    console.error(error);\n}", "console.log();"};
        darkDialog().setTitle("代码片段").setItems(names,
                (dialog, which) -> activeEditor().insert(snippets[which])).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mFileTreeDirty = true;
        AppThemeRepository repository = AppThemeRepository.get(this);
        repository.addListener(mThemeListener);
        applyThemePalette(resolvePalette());
    }

    /**
     * Theme palette aligned with the rest of the app (Miuix/MainActivity surfaces).
     * Those surfaces derive darkness from {@link Pref#isNightModeEnabled()}, while
     * {@link AppThemeRepository} follows the system uiMode, which can be stale on some
     * devices (e.g. MIUI leaves the app configuration in light mode). Trust the app's own
     * night-mode switch first so the editor matches the main UI in both light and dark.
     */
    private AppThemePalette resolvePalette() {
        AppThemeRepository repository = AppThemeRepository.get(this);
        AppThemePalette base = repository.getPalette();
        boolean appDark = Pref.isNightModeEnabled();
        if (base.isDark != appDark) {
            return appDark ? AppThemePalette.dark(repository.getAccentColor())
                    : AppThemePalette.light(repository.getAccentColor());
        }
        return base;
    }

    @Override
    protected void onPause() {
        saveEditorSession();
        AppThemeRepository.get(this).removeListener(mThemeListener);
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        AppThemeRepository repository = AppThemeRepository.get(this);
        repository.refreshSystemAppearance();
        applyThemePalette(resolvePalette());
    }

    private void applyThemePalette(AppThemePalette palette) {
        if (palette == null) return;
        AppThemePalette previous = mPalette;
        mPalette = palette;
        applyWindowSystemUi(palette);
        if (mDrawerLayout == null) return;

        recolorTextTree(mDrawerLayout, previous, palette);
        tintImageTree(mDrawerLayout, palette.iconPrimary);
        mDrawerLayout.setBackgroundColor(palette.editorBackground);
        mDrawerLayout.setScrimColor(palette.scrim);
        if (mMainLayout != null) mMainLayout.setBackgroundColor(palette.editorBackground);
        if (mToolRow != null) mToolRow.setBackgroundColor(palette.editorToolbar);
        if (mTabRow != null) mTabRow.setBackgroundColor(palette.editorTabInactive);
        if (mWorkspaceDrawer != null) mWorkspaceDrawer.setBackgroundColor(palette.surfacePrimary);
        if (mDebugBar != null) mDebugBar.setBackgroundColor(palette.surfaceElevated);
        if (mLogPanel != null) mLogPanel.setBackgroundColor(palette.surfaceSecondary);
        if (mShortcutBar != null) mShortcutBar.setBackgroundColor(palette.editorToolbar);
        if (mConsoleView != null) ConsoleThemeHelper.apply(mConsoleView, palette);

        Theme editorTheme = Theme.fromAssetsJson(this, palette.isDark
                ? "editor/theme/dark_plus.json" : "editor/theme/light_plus.json");
        if (editorTheme != null) {
            for (EditorTab tab : mTabs) tab.editor.setTheme(editorTheme);
        }
        refreshTabs();
        refreshFileTree();
        if (mLogPanel != null && mLogPanel.getVisibility() == View.VISIBLE) {
            setToolSelected(mLogTool, true);
        }
    }

    private void recolorTextTree(View view, AppThemePalette previous, AppThemePalette current) {
        if (view instanceof TextView && previous != null) {
            TextView textView = (TextView) view;
            int color = textView.getCurrentTextColor();
            if (color == previous.textPrimary) textView.setTextColor(current.textPrimary);
            else if (color == previous.textSecondary) textView.setTextColor(current.textSecondary);
            else if (color == previous.textDisabled) textView.setTextColor(current.textDisabled);
            else if (color == previous.iconPrimary) textView.setTextColor(current.iconPrimary);
            else if (color == previous.accent) textView.setTextColor(current.accent);
            else if (color == previous.danger) textView.setTextColor(current.danger);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                recolorTextTree(group.getChildAt(i), previous, current);
            }
        }
    }

    private void tintImageTree(View view, int color) {
        if (view instanceof ImageView) ((ImageView) view).setColorFilter(color);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                tintImageTree(group.getChildAt(i), color);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (mDrawerLayout != null && mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
            mDrawerLayout.closeDrawer(GravityCompat.START);
            return;
        }
        if (mLogPanel != null && mLogPanel.getVisibility() == View.VISIBLE) {
            hideLogPanel();
            return;
        }
        if (!hasAnyUnsavedChanges()) {
            super.onBackPressed();
            return;
        }
        darkDialog().setTitle("保存工作区修改？")
                .setMessage("有文件尚未保存")
                .setNegativeButton("不保存", (dialog, which) -> finish())
                .setNeutralButton("取消", null)
                .setPositiveButton("全部保存", (dialog, which) -> saveAllAndFinish())
                .show();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(mExecutionFinishedReceiver); } catch (Exception ignored) { }
        finishDebugSession(true);
        for (EditorTab tab : mTabs) tab.editor.destroy();
        super.onDestroy();
    }

    private AlertDialog.Builder darkDialog() {
        return new AlertDialog.Builder(this, mPalette.isDark
                ? AlertDialog.THEME_DEVICE_DEFAULT_DARK
                : AlertDialog.THEME_DEVICE_DEFAULT_LIGHT);
    }

    private void showAnchoredMenu(View anchor, String[] items, boolean[] hasSubmenu,
                                  MenuClickListener listener) {
        if (anchor == null || items == null || items.length == 0) return;
        hideKeyboardForMenu();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(4), 0, dp(4));
        GradientDrawable background = new GradientDrawable();
        background.setColor(mPalette.surfaceElevated);
        background.setCornerRadius(dp(4));
        content.setBackground(background);

        PopupWindow popup = new PopupWindow(content, dp(196),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(8));
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(10), 0);
            row.setBackgroundColor(Color.TRANSPARENT);
            TextView label = text(items[i], 16f, mPalette.textPrimary);
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.setSingleLine(true);
            row.addView(label, new LinearLayout.LayoutParams(0, dp(52), 1f));
            if (hasSubmenu != null && index < hasSubmenu.length && hasSubmenu[index]) {
                TextView arrow = text("›", 22f, mPalette.textSecondary);
                arrow.setGravity(Gravity.CENTER);
                row.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(52)));
            }
            row.setOnClickListener(v -> {
                popup.dismiss();
                listener.onClick(index);
            });
            content.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        }
        boolean toolbarAnchor = anchor instanceof LinearLayout
                && anchor.getContentDescription() != null;
        if (toolbarAnchor) setToolSelected(anchor, true);
        popup.setOnDismissListener(() -> {
            if (toolbarAnchor && anchor != mLogTool) setToolSelected(anchor, false);
        });
        popup.showAsDropDown(anchor, -dp(8), 0);
    }

    private void hideKeyboardForMenu() {
        View focus = getCurrentFocus();
        if (focus != null) {
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(focus.getWindowToken(), 0);
            focus.clearFocus();
        }
        if (mDrawerLayout != null) mDrawerLayout.requestFocus();
    }

    private void applyWindowSystemUi(AppThemePalette palette) {
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
    }

    private EditText dialogInput(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setTextColor(mPalette.textPrimary);
        input.setHintTextColor(mPalette.textSecondary);
        return input;
    }

    private Button dialogButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(11f);
        button.setTextColor(mPalette.textPrimary);
        button.setAllCaps(false);
        button.setLayoutParams(new LinearLayout.LayoutParams(0, dp(44), 1f));
        return button;
    }

    private TextView action(String label, int widthDp, View.OnClickListener listener) {
        TextView view = text(label, 26f, mPalette.iconPrimary);
        view.setGravity(Gravity.CENTER);
        view.setOnClickListener(listener);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp), dp(56)));
        return view;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int countLines(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lines++;
        return lines;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int workspaceDrawerWidth(int fullWidth, int fullHeight) {
        if (fullWidth <= 0) return dp(330);
        float density = Math.max(1f, getResources().getDisplayMetrics().density);
        float widthDp = fullWidth / density;
        float fontScale = Math.max(1f, getResources().getConfiguration().fontScale);
        boolean landscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;

        float fraction;
        if (landscape) {
            fraction = widthDp >= 840f ? 0.48f : 0.62f;
        } else if (widthDp >= 600f) {
            fraction = 0.62f;
        } else {
            fraction = fontScale >= 1.30f ? 0.90f : 0.84f;
        }

        int maximum = dp(landscape ? 520 : 480);
        int minimum = Math.min(dp(280), fullWidth);
        int edgeSpace = Math.min(dp(56), Math.round(fullWidth * 0.16f));
        int maximumAvailable = Math.max(1, fullWidth - edgeSpace);
        int target = Math.min(Math.round(fullWidth * fraction), maximum);
        return Math.max(Math.min(minimum, maximumAvailable), Math.min(target, maximumAvailable));
    }

    private int workspaceRowHeight() {
        float fontScale = getResources().getConfiguration().fontScale;
        if (fontScale >= 1.50f) return dp(56);
        if (fontScale >= 1.20f) return dp(48);
        return dp(40);
    }

    private int workspaceHeaderHeight() {
        float fontScale = getResources().getConfiguration().fontScale;
        if (fontScale >= 1.50f) return dp(64);
        if (fontScale >= 1.20f) return dp(56);
        return dp(48);
    }

    private void updateWorkspaceDrawerSize(int fullWidth, int fullHeight) {
        if (mWorkspaceDrawer == null) return;
        int targetWidth = workspaceDrawerWidth(fullWidth, fullHeight);
        if (targetWidth == mWorkspaceDrawerWidth) return;
        mWorkspaceDrawerWidth = targetWidth;
        ViewGroup.LayoutParams rawParams = mWorkspaceDrawer.getLayoutParams();
        if (rawParams instanceof DrawerLayout.LayoutParams) {
            rawParams.width = targetWidth;
            mWorkspaceDrawer.setLayoutParams(rawParams);
            refreshFileTree();
        }
    }

    private File canonical(File file) {
        if (file == null) return null;
        try { return file.getCanonicalFile(); }
        catch (IOException ignored) { return file.getAbsoluteFile(); }
    }

    private boolean isWithin(File file, File root) {
        if (file == null || root == null) return false;
        String path = canonical(file).getAbsolutePath();
        String rootPath = canonical(root).getAbsolutePath();
        return path.equals(rootPath) || path.startsWith(rootPath + File.separator);
    }

    private interface NameCallback {
        void onName(String name);
    }

    private interface MenuClickListener {
        void onClick(int index);
    }

    private static final class EditorTab {
        File file;
        final CodeEditor editor;
        String savedText;
        boolean dirtyMarkerShown;
        boolean viewStateRestored;

        EditorTab(File file, CodeEditor editor, String savedText) {
            this.file = file;
            this.editor = editor;
            this.savedText = savedText;
        }
    }

}
