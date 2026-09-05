package com.jdkshen.aijspro.ui.imgui;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.webkit.MimeTypeMap;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.stardust.autojs.execution.ScriptExecution;

import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.model.script.ScriptFile;
import com.jdkshen.aijspro.model.script.Scripts;
import com.jdkshen.aijspro.model.sample.SampleFile;
import com.jdkshen.aijspro.external.ScriptIntents;
import com.jdkshen.aijspro.external.fileprovider.AppFileProvider;
import com.jdkshen.aijspro.pluginclient.DevPluginService;
import com.jdkshen.aijspro.tool.AccessibilityServiceTool;
import com.jdkshen.aijspro.tool.WifiTool;
import com.jdkshen.aijspro.ui.BaseActivity;
import com.jdkshen.aijspro.ui.doc.DocumentationActivity;
import com.jdkshen.aijspro.ui.edit.ViewSampleActivity;
import com.jdkshen.aijspro.ui.floating.FloatyWindowManger;
import com.jdkshen.aijspro.ui.log.LogActivity;
import com.jdkshen.aijspro.ui.main.task.Task;
import com.jdkshen.aijspro.ui.settings.SettingsActivity;
import com.jdkshen.aijspro.ui.update.UpdateCheckDialog;
import com.jdkshen.aijspro.ui.timing.TimedTaskSettingActivity;
import com.jdkshen.aijspro.timing.IntentTask;
import com.jdkshen.aijspro.timing.TimedTask;
import com.jdkshen.aijspro.timing.TimedTaskManager;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Hybrid main workspace. Script browsing and common actions use Dear ImGui while text-heavy
 * editors and Android system screens remain native.
 */
public final class ImGuiWorkspaceActivity extends BaseActivity implements ImGuiSurfaceView.ActionListener {

    private static final String FONT_ASSET = "fonts/NotoSansCJKsc-Regular.otf";
    private static final int ACTION_OPEN_SETTINGS = 2;
    private static final int ACTION_OPEN_LOG = 3;
    private static final int ACTION_OPEN_ACCESSIBILITY = 4;
    private static final int ACTION_OPEN_EDITOR = 5;
    private static final int ACTION_REFRESH_SCRIPTS = 10;
    private static final int ACTION_SCRIPT_UP = 11;
    private static final int ACTION_OPEN_SELECTED = 12;
    private static final int ACTION_RUN_SELECTED = 13;
    private static final int ACTION_STOP_ALL = 14;
    private static final int ACTION_CREATE_SCRIPT = 15;
    private static final int ACTION_CREATE_DIRECTORY = 16;
    private static final int ACTION_RENAME_SELECTED = 17;
    private static final int ACTION_DELETE_SELECTED = 18;
    private static final int ACTION_SEARCH_SCRIPTS = 19;
    private static final int ACTION_FILTER_SCRIPTS = 20;
    private static final int ACTION_OPEN_DOCUMENTATION = 21;
    private static final int ACTION_OPEN_WORKSPACE_MENU = 22;
    private static final int ACTION_OPEN_SAMPLE = 23;
    private static final int ACTION_RUN_SAMPLE = 24;
    private static final int ACTION_IMPORT_SAMPLE = 25;
    private static final int ACTION_SAMPLE_UP = 26;
    private static final int ACTION_SEARCH_SAMPLES = 27;
    private static final int ACTION_SORT_SCRIPTS = 28;
    private static final int ACTION_FILTER_SAMPLES = 29;
    private static final int ACTION_IMPORT_RESOURCE = 30;
    private static final int ACTION_OPEN_RESOURCE = 31;
    private static final int ACTION_UPLOAD_RESOURCE = 32;
    private static final int ACTION_FILTER_RESOURCES = 33;
    private static final int ACTION_RESOURCE_DETAILS = 34;
    private static final int ACTION_SCRIPT_BREADCRUMB = 35;
    private static final int ACTION_SAMPLE_BREADCRUMB = 36;
    private static final int ACTION_SORT_SAMPLES = 37;
    private static final int ACTION_FILE_MENU = 38;
    private static final int ACTION_MANAGE_PLUGIN = 40;
    private static final int ACTION_SEARCH_PLUGINS = 41;
    private static final int ACTION_REFRESH_PLUGINS = 42;
    private static final int ACTION_PLUGIN_HELP = 43;
    private static final int ACTION_OPEN_TASK = 51;
    private static final int ACTION_CANCEL_TASK = 52;
    private static final int ACTION_CREATE_TIMED_TASK = 53;
    private static final int ACTION_REFRESH_TASKS = 54;
    private static final int ACTION_SEARCH_TASKS = 55;
    private static final int REQUEST_INSTALL_PLUGIN = 7001;
    private static final int REQUEST_IMPORT_RESOURCE = 7002;
    private static final String PLUGIN_REGISTRY_KEY = "org.autojs.plugin.sdk.registry";
    private static final String WORKSPACE_PREFERENCES = "imgui_workspace";
    private static final String KEY_LAST_EDITED_SCRIPT = "last_edited_script";

    private File mScriptRoot;
    private File mCurrentScriptDirectory;
    private String mSearchQuery = "";
    private boolean mSearchSubdirectories;
    private boolean mSearchRegex;
    private boolean mShowHiddenFiles;
    private int mScriptFilter;
    private int mScriptSortMode;
    private boolean mScriptSortDescending;
    private String mCurrentSamplePath = "sample";
    private String mSampleSearchQuery = "";
    private boolean mSampleSearchSubdirectories;
    private boolean mSampleSearchRegex;
    private int mSampleFilter;
    private int mSampleSortMode;
    private boolean mSampleSortDescending;
    private String mResourceCategory = "";
    private String mPluginSearchQuery = "";
    private String mTaskSearchQuery = "";
    private final List<ScriptExecution> mRunningTaskEntries = new ArrayList<>();
    private final List<Object> mPendingTaskEntries = new ArrayList<>();
    private ImGuiWorkspaceDrawer mWorkspaceDrawer;
    private final com.jdkshen.aijspro.theme.AppThemeRepository.ThemeListener mThemeListener =
            palette -> applyTheme(palette);
    private final Handler mRuntimeRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRuntimeRefresh = new Runnable() {
        @Override
        public void run() {
            refreshRuntimeState();
            mRuntimeRefreshHandler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String fontPath = prepareBundledFont();
        ImGuiNativeBridge.setUiFontPath(fontPath == null ? "" : fontPath);
        mScriptRoot = canonicalFile(new File(Pref.getScriptDirPath()));
        mCurrentScriptDirectory = mScriptRoot;
        migrateBundledQuickJsSamplesOnUpgrade();

        ImGuiSurfaceView surfaceView = new ImGuiSurfaceView(this, this);
        mWorkspaceDrawer = new ImGuiWorkspaceDrawer(this, this::onImGuiAction);
        mWorkspaceDrawer.setWorkspaceContent(surfaceView);
        setContentView(mWorkspaceDrawer);
        surfaceView.requestFocus();
        checkPermission(Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE);

        // Theme integration
        com.jdkshen.aijspro.theme.AppThemeRepository.get(this).addListener(mThemeListener);
        applyTheme(com.jdkshen.aijspro.theme.AppThemeRepository.get(this).getPalette());
    }

    @Override
    protected void onResume() {
        super.onResume();
        ImGuiSurfaceView surface = findSurfaceView();
        if (surface != null) {
            surface.resumeRendering();
        }
        restoreFloatingWindowPreference();
        refreshWorkspaceState();
        mRuntimeRefreshHandler.removeCallbacks(mRuntimeRefresh);
        mRuntimeRefreshHandler.postDelayed(mRuntimeRefresh, 1000L);
        // Re-sync theme (user may have changed it in settings while in background)
        com.jdkshen.aijspro.theme.AppThemeRepository repo =
                com.jdkshen.aijspro.theme.AppThemeRepository.get(this);
        repo.addListener(mThemeListener);
        repo.refreshFromLegacyTheme();
    }

    @Override
    protected void onPause() {
        ImGuiSurfaceView surface = findSurfaceView();
        if (surface != null) {
            surface.pauseRendering();
        }
        mRuntimeRefreshHandler.removeCallbacks(mRuntimeRefresh);
        super.onPause();
        com.jdkshen.aijspro.theme.AppThemeRepository.get(this).removeListener(mThemeListener);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // System dark/light mode may have changed — refresh theme
        com.jdkshen.aijspro.theme.AppThemeRepository repo =
                com.jdkshen.aijspro.theme.AppThemeRepository.get(this);
        repo.refreshSystemAppearance();
        applyTheme(repo.getPalette());
        // Font scale or orientation changed: re-dispatch viewport metrics
        ImGuiSurfaceView surface = mWorkspaceDrawer != null ? findSurfaceView() : null;
        if (surface != null) {
            surface.dispatchViewportMetrics();
        }
    }

    /** Walk the DrawerLayout's content tree to find the ImGuiSurfaceView. */
    private ImGuiSurfaceView findSurfaceView() {
        if (mWorkspaceDrawer == null) return null;
        ViewGroup content = (ViewGroup) mWorkspaceDrawer.getChildAt(0);
        if (content == null) return null;
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child instanceof ImGuiSurfaceView) return (ImGuiSurfaceView) child;
        }
        return null;
    }

    /** Apply a complete theme palette to this Activity and all child components. */
    private void applyTheme(com.jdkshen.aijspro.theme.AppThemePalette palette) {
        if (palette == null) return;
        Window window = getWindow();
        if (window != null) {
            window.setStatusBarColor(palette.statusBar);
            window.setNavigationBarColor(palette.navigationBar);
            int flags = window.getDecorView().getSystemUiVisibility();
            if (palette.isDark) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
        if (mWorkspaceDrawer != null) {
            mWorkspaceDrawer.setBackgroundColor(palette.windowBackground);
            mWorkspaceDrawer.applyTheme(palette);
        }
        // Send palette to ImGui native layer
        ImGuiNativeBridge.setThemePalette(palette.toIntArray());
    }

    private void showThemeDialog() {
        com.jdkshen.aijspro.theme.AppThemeRepository repo =
                com.jdkshen.aijspro.theme.AppThemeRepository.get(this);
        final int[] selectedMode = {repo.getThemeMode()};
        String[] modeItems = {"跟随系统", "浅色", "深色"};
        String[] accentNames = {"青绿 (默认)", "红", "粉红", "紫", "靛蓝", "蓝", "绿", "琥珀", "橙"};
        int[] accentColors = {
                0xFF009688, 0xFFF44336, 0xFFE91E63, 0xFF9C27B0, 0xFF3F51B5,
                0xFF2196F3, 0xFF4CAF50, 0xFFFFC107, 0xFFFF9800};

        new AlertDialog.Builder(this)
                .setTitle("主题设置")
                .setItems(new String[]{
                        "显示模式: " + modeItems[selectedMode[0]],
                        "强调色"}, (dialog, which) -> {
                    if (which == 0) {
                        selectedMode[0] = (selectedMode[0] + 1) % 3;
                        repo.setThemeMode(selectedMode[0]);
                    } else if (which == 1) {
                        new AlertDialog.Builder(this)
                                .setTitle("选择强调色")
                                .setItems(accentNames, (d, i) -> repo.setAccentColor(accentColors[i]))
                                .setNegativeButton("取消", null).show();
                    }
                })
                .setNegativeButton("关闭", null).show();
    }

    @Override
    public void onBackPressed() {
        if (mWorkspaceDrawer != null && mWorkspaceDrawer.closeIfOpen()) {
            return;
        }
        if (ImGuiNativeBridge.closeWorkspaceDrawer()) {
            return;
        }
        int section = ImGuiNativeBridge.getCurrentSection();
        if (section == 0 && mCurrentScriptDirectory != null
                && !mCurrentScriptDirectory.equals(mScriptRoot)) {
            navigateToParentDirectory();
            return;
        }
        if (section == 1 && !"sample".equals(mCurrentSamplePath)) {
            navigateToParentSampleDirectory();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("退出 AI.js Pro？")
                .setMessage("左右分页请从内容区域开始滑动。")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出", (dialog, which) -> finish())
                .show();
    }

    private void refreshWorkspaceState() {
        refreshRuntimeState();
        refreshScriptEntries();
        refreshSampleEntries();
        refreshResourceEntries();
        refreshPluginEntries();
    }

    private void refreshRuntimeState() {
        boolean accessibilityEnabled = AccessibilityServiceTool.isAccessibilityServiceEnabled(this);
        boolean floatingShown = FloatyWindowManger.isCircularMenuShowing();
        boolean developerConnected = false;
        try {
            DevPluginService service = DevPluginService.getInstance();
            developerConnected = service != null && service.isConnected();
        } catch (RuntimeException ignored) {
            // The developer service may not have finished initializing yet.
        }
        int runningScripts = 0;
        try {
            runningScripts = AutoJs.getInstance().getScriptEngineService().getScriptExecutions().size();
        } catch (RuntimeException ignored) {
            // The workspace remains usable while the application runtime is still initializing.
        }
        ImGuiNativeBridge.setWorkspaceState(accessibilityEnabled, runningScripts);
        ImGuiNativeBridge.setDrawerState(accessibilityEnabled, floatingShown, developerConnected);
        if (mWorkspaceDrawer != null) {
            mWorkspaceDrawer.updateServiceState(
                    accessibilityEnabled, floatingShown, developerConnected);
        }
        refreshTaskEntries();
    }

    @Override
    public void onImGuiAction(int action) {
        switch (action) {
            case ACTION_OPEN_SETTINGS:
                startActivity(new Intent(this, SettingsActivity.class));
                break;
            case ACTION_OPEN_LOG:
                startActivity(new Intent(this, LogActivity.class));
                break;
            case ACTION_OPEN_ACCESSIBILITY:
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                break;
            case ACTION_OPEN_EDITOR:
                openRecentEditor();
                break;
            case ACTION_REFRESH_SCRIPTS:
                refreshScriptEntries();
                break;
            case ACTION_SCRIPT_UP:
                navigateToParentDirectory();
                break;
            case ACTION_OPEN_SELECTED:
                openSelectedScriptEntry();
                break;
            case ACTION_RUN_SELECTED:
                runSelectedScript();
                break;
            case ACTION_STOP_ALL:
                AutoJs.getInstance().getScriptEngineService().stopAllAndToast();
                refreshWorkspaceState();
                break;
            case ACTION_CREATE_SCRIPT:
                createEntry(false);
                break;
            case ACTION_CREATE_DIRECTORY:
                createEntry(true);
                break;
            case ACTION_RENAME_SELECTED:
                renameSelectedEntry();
                break;
            case ACTION_DELETE_SELECTED:
                confirmDeleteSelectedEntry();
                break;
            case ACTION_SEARCH_SCRIPTS:
                showSearchDialog();
                break;
            case ACTION_FILTER_SCRIPTS:
                showScriptFilterDialog();
                break;
            case ACTION_SORT_SCRIPTS:
                showScriptSortDialog();
                break;
            case ACTION_OPEN_DOCUMENTATION:
                startActivity(new Intent(this, DocumentationActivity.class));
                break;
            case ACTION_OPEN_WORKSPACE_MENU:
                showWorkspaceMenu();
                break;
            case ACTION_OPEN_SAMPLE:
                openSelectedSampleEntry();
                break;
            case ACTION_RUN_SAMPLE:
                runSelectedSample();
                break;
            case ACTION_IMPORT_SAMPLE:
                importSelectedSample();
                break;
            case ACTION_SAMPLE_UP:
                navigateToParentSampleDirectory();
                break;
            case ACTION_SEARCH_SAMPLES:
                showSampleSearchDialog();
                break;
            case ACTION_FILTER_SAMPLES:
                showSampleFilterDialog();
                break;
            case ACTION_SORT_SAMPLES:
                showSampleSortDialog();
                break;
            case ACTION_IMPORT_RESOURCE:
                importSelectedResource();
                break;
            case ACTION_OPEN_RESOURCE:
                openSelectedResource();
                break;
            case ACTION_FILTER_RESOURCES:
                showResourceCategoryDialog();
                break;
            case ACTION_UPLOAD_RESOURCE:
                chooseResourceToUpload();
                break;
            case ACTION_RESOURCE_DETAILS:
                showSelectedResourceDetails();
                break;
            case ACTION_SCRIPT_BREADCRUMB:
                navigateToScriptBreadcrumb(ImGuiNativeBridge.getSelectedBreadcrumbDepth());
                break;
            case ACTION_SAMPLE_BREADCRUMB:
                navigateToSampleBreadcrumb(ImGuiNativeBridge.getSelectedBreadcrumbDepth());
                break;
            case ACTION_FILE_MENU:
                showSelectedFileMenu();
                break;
            case ACTION_MANAGE_PLUGIN:
                manageSelectedPlugin();
                break;
            case ACTION_REFRESH_PLUGINS:
                refreshPluginEntries();
                Toast.makeText(this, "插件版本和安装状态已刷新", Toast.LENGTH_SHORT).show();
                break;
            case ACTION_SEARCH_PLUGINS:
                showPluginSearchDialog();
                break;
            case ACTION_PLUGIN_HELP:
                showPluginHelp();
                break;
            case ACTION_OPEN_TASK:
                openSelectedTask();
                break;
            case ACTION_CANCEL_TASK:
                cancelSelectedTask();
                break;
            case ACTION_CREATE_TIMED_TASK:
                chooseScriptForTimedTask();
                break;
            case ACTION_REFRESH_TASKS:
                refreshTaskEntries();
                Toast.makeText(this, "任务状态已刷新", Toast.LENGTH_SHORT).show();
                break;
            case ACTION_SEARCH_TASKS:
                showTaskSearchDialog();
                break;
            // Drawer menu actions (from ImGuiWorkspaceDrawer native drawer)
            case 60: // Accessibility
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                break;
            case 61: // Toggle floating
                toggleFloatingWindow();
                break;
            case 64: // Terminal
                openTerminal(mCurrentScriptDirectory);
                break;
            case 65: // Theme
                showThemeDialog();
                break;
            case 66: // Blog
                openWebPage("https://hyb1996.github.io/AutoJs-Docs/");
                break;
            case 67: // Forum
                openWebPage("https://www.autojs.org/");
                break;
            case 68: // Settings
                startActivity(new Intent(this, SettingsActivity.class));
                break;
            case 69: // Check update
                new UpdateCheckDialog(this).show();
                break;
            case 70: // Exit
                confirmExitApplication();
                break;
            case 71: // Open native drawer (ImGui fallback path)
                if (mWorkspaceDrawer != null) mWorkspaceDrawer.openDrawerSurface();
                break;
            default:
                Toast.makeText(this, "尚未实现的 ImGui 操作：" + action, Toast.LENGTH_SHORT).show();
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            refreshScriptEntries();
        }
    }

    private void refreshScriptEntries() {
        if (mScriptRoot == null) {
            mScriptRoot = canonicalFile(new File(Pref.getScriptDirPath()));
        }
        if (mCurrentScriptDirectory == null || !isWithinScriptRoot(mCurrentScriptDirectory)
                || !mCurrentScriptDirectory.isDirectory()) {
            mCurrentScriptDirectory = mScriptRoot;
        }
        if (!mCurrentScriptDirectory.isDirectory() && !mCurrentScriptDirectory.mkdirs()) {
            ImGuiNativeBridge.setScriptEntries("脚本目录不可用", new String[0],
                    new String[0], new boolean[0], new String[0], new String[0], new int[0]);
            return;
        }

        final String query = mSearchQuery.trim();
        final Pattern searchPattern;
        try {
            searchPattern = mSearchRegex && !query.isEmpty()
                    ? Pattern.compile(query, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : null;
        } catch (PatternSyntaxException error) {
            showFileError("正则表达式无效：" + error.getDescription());
            return;
        }
        List<File> matches = new ArrayList<>();
        collectMatchingScriptEntries(mCurrentScriptDirectory, matches, query, searchPattern,
                mSearchSubdirectories && !query.isEmpty(), 500);
        File[] files = matches.toArray(new File[0]);
        Arrays.sort(files, this::compareScriptFiles);

        String[] names = new String[files.length];
        String[] paths = new String[files.length];
        boolean[] directories = new boolean[files.length];
        String[] types = new String[files.length];
        String[] modifiedTimes = new String[files.length];
        int[] iconKinds = new int[files.length];
        SimpleDateFormat timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        for (int i = 0; i < files.length; i++) {
            File file = canonicalFile(files[i]);
            names[i] = file.getName();
            paths[i] = file.getPath();
            directories[i] = file.isDirectory();
            types[i] = scriptTypeLabel(file);
            String parentHint = recursiveResultParent(file);
            modifiedTimes[i] = (parentHint.isEmpty() ? "" : parentHint + " · ")
                    + "修改于 " + timestamp.format(new Date(file.lastModified()));
            iconKinds[i] = file.isDirectory() && isAutoJsProject(file) ? 1
                    : file.isDirectory() ? 0 : isCodeFile(file) ? 2 : 3;
        }
        ImGuiNativeBridge.setScriptEntries(scriptDirectoryLabel(), names, paths, directories,
                types, modifiedTimes, iconKinds);
    }

    private void collectMatchingScriptEntries(File directory, List<File> result, String query,
                                              Pattern searchPattern, boolean recursive, int limit) {
        if (directory == null || result.size() >= limit) return;
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (result.size() >= limit) break;
            boolean hidden = child.getName().startsWith(".") || child.isHidden();
            if (!mShowHiddenFiles && hidden) continue;
            if (matchesScriptFilter(child) && matchesScriptSearch(child.getName(), query, searchPattern)) {
                result.add(child);
            }
            if (recursive && child.isDirectory()) {
                collectMatchingScriptEntries(child, result, query, searchPattern, true, limit);
            }
        }
    }

    private boolean matchesScriptSearch(String name, String query, Pattern searchPattern) {
        if (query.isEmpty()) return true;
        if (searchPattern != null) return searchPattern.matcher(name).find();
        return name.toLowerCase(Locale.getDefault())
                .contains(query.toLowerCase(Locale.getDefault()));
    }

    private String recursiveResultParent(File file) {
        if (!mSearchSubdirectories || mSearchQuery.trim().isEmpty() || file == null) return "";
        File parent = canonicalFile(file.getParentFile());
        if (parent == null || parent.equals(mCurrentScriptDirectory)) return "";
        String base = mCurrentScriptDirectory.getPath();
        String path = parent.getPath();
        return path.startsWith(base + File.separator)
                ? path.substring(base.length() + 1).replace(File.separatorChar, '/') : "";
    }

    private boolean isAutoJsProject(File directory) {
        return new File(directory, "project.json").isFile()
                || new File(directory, "main.js").isFile()
                || new File(directory, "package.json").isFile();
    }

    private boolean matchesScriptFilter(File file) {
        switch (mScriptFilter) {
            case 1:
                return file.isDirectory() && !isAutoJsProject(file);
            case 2:
                return file.isFile() && !isCodeFile(file);
            case 3:
                return file.isFile() && isCodeFile(file);
            case 4:
                return file.isDirectory() && isAutoJsProject(file);
            default:
                return true;
        }
    }

    private boolean isCodeFile(File file) {
        if (file == null || !file.isFile()) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".js") || name.endsWith(".auto");
    }

    private int compareScriptFiles(File left, File right) {
        if (left.isDirectory() != right.isDirectory()) return left.isDirectory() ? -1 : 1;
        int compared;
        if (mScriptSortMode == 1) {
            compared = Long.compare(left.lastModified(), right.lastModified());
        } else if (mScriptSortMode == 2) {
            compared = scriptTypeLabel(left).compareToIgnoreCase(scriptTypeLabel(right));
        } else if (mScriptSortMode == 3) {
            compared = Long.compare(left.isFile() ? left.length() : 0L,
                    right.isFile() ? right.length() : 0L);
        } else {
            compared = left.getName().compareToIgnoreCase(right.getName());
        }
        if (compared == 0) compared = left.getName().compareToIgnoreCase(right.getName());
        return mScriptSortDescending ? -compared : compared;
    }

    private String scriptTypeLabel(File file) {
        if (file.isDirectory()) return isAutoJsProject(file) ? "AI.js Pro 项目" : "普通文件夹";
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".auto")) return "录制文件";
        if (name.endsWith(".js")) return "JavaScript 脚本";
        return "文件";
    }

    private void refreshSampleEntries() {
        try {
            String query = mSampleSearchQuery.trim();
            Pattern pattern = mSampleSearchRegex && !query.isEmpty()
                    ? Pattern.compile(query, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : null;
            List<String> filtered = new ArrayList<>();
            collectMatchingSampleEntries(mCurrentSamplePath, filtered, query, pattern,
                    mSampleSearchSubdirectories && !query.isEmpty(), 500);
            String[] paths = filtered.toArray(new String[0]);
            Arrays.sort(paths, this::compareSamplePaths);
            long updatedAt = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
            String modified = "修改于 " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()).format(new Date(updatedAt));
            String[] names = new String[paths.length];
            boolean[] directories = new boolean[paths.length];
            String[] types = new String[paths.length];
            String[] modifiedTimes = new String[paths.length];
            for (int i = 0; i < paths.length; i++) {
                names[i] = new File(paths[i]).getName();
                directories[i] = isSampleDirectory(paths[i]);
                types[i] = sampleTypeLabel(paths[i]);
                String parentHint = recursiveSampleResultParent(paths[i]);
                modifiedTimes[i] = (parentHint.isEmpty() ? "" : parentHint + " · ")
                        + (directories[i] ? modified : "内置资源 · " + readableAssetSize(paths[i]));
            }
            ImGuiNativeBridge.setSampleEntries(sampleDirectoryLabel(), names, paths,
                    directories, types, modifiedTimes);
        } catch (PatternSyntaxException error) {
            showFileError("正则表达式无效：" + error.getDescription());
        } catch (IOException ignored) {
            ImGuiNativeBridge.setSampleEntries("示例文件不可用", new String[0],
                    new String[0], new boolean[0], new String[0], new String[0]);
        } catch (Exception ignored) {
            ImGuiNativeBridge.setSampleEntries("示例文件不可用", new String[0],
                    new String[0], new boolean[0], new String[0], new String[0]);
        }
    }

    private void collectMatchingSampleEntries(String directory, List<String> result, String query,
                                              Pattern pattern, boolean recursive, int limit)
            throws IOException {
        if (result.size() >= limit) return;
        String[] children = getAssets().list(directory);
        if (children == null) return;
        for (String name : children) {
            if (result.size() >= limit) break;
            String path = directory + "/" + name;
            if (matchesSampleFilter(path) && matchesScriptSearch(name, query, pattern)) result.add(path);
            if (recursive && isSampleDirectory(path)) {
                collectMatchingSampleEntries(path, result, query, pattern, true, limit);
            }
        }
    }

    private boolean matchesSampleFilter(String path) {
        boolean directory = isSampleDirectory(path);
        switch (mSampleFilter) {
            case 1:
                return directory && !isSampleProject(path);
            case 2:
                return !directory && !isSampleCode(path);
            case 3:
                return !directory && isSampleCode(path);
            case 4:
                return directory && isSampleProject(path);
            default:
                return true;
        }
    }

    private boolean isSampleCode(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".js") || lower.endsWith(".auto");
    }

    private boolean isSampleProject(String path) {
        if (!isSampleDirectory(path)) return false;
        try {
            String[] children = getAssets().list(path);
            if (children == null) return false;
            for (String child : children) {
                if ("project.json".equals(child) || "main.js".equals(child)
                        || "package.json".equals(child)) return true;
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    private String sampleTypeLabel(String path) {
        if (isSampleDirectory(path)) return isSampleProject(path) ? "AI.js Pro 项目" : "文件夹";
        if (path.toLowerCase(Locale.ROOT).endsWith(".auto")) return "录制文件";
        return isSampleCode(path) ? "JavaScript 示例" : "文件";
    }

    private String recursiveSampleResultParent(String path) {
        if (!mSampleSearchSubdirectories || mSampleSearchQuery.trim().isEmpty()) return "";
        int slash = path.lastIndexOf('/');
        if (slash <= mCurrentSamplePath.length()) return "";
        return path.substring(mCurrentSamplePath.length() + 1, slash);
    }

    private boolean isSampleDirectory(String assetPath) {
        try {
            String[] children = getAssets().list(assetPath);
            return children != null && children.length > 0;
        } catch (IOException ignored) {
            return false;
        }
    }

    private int compareSampleEntries(String currentPath, String left, String right) {
        boolean leftDirectory = isSampleDirectory(currentPath + "/" + left);
        boolean rightDirectory = isSampleDirectory(currentPath + "/" + right);
        if (leftDirectory != rightDirectory) return leftDirectory ? -1 : 1;
        int compared;
        if (mSampleSortMode == 1) {
            compared = Boolean.compare(leftDirectory, rightDirectory);
        } else if (mSampleSortMode == 2) {
            compared = Long.compare(assetSizeBytes(currentPath + "/" + left),
                    assetSizeBytes(currentPath + "/" + right));
        } else {
            compared = left.compareToIgnoreCase(right);
        }
        if (compared == 0) compared = left.compareToIgnoreCase(right);
        return mSampleSortDescending ? -compared : compared;
    }

    private int compareSamplePaths(String left, String right) {
        boolean leftDirectory = isSampleDirectory(left);
        boolean rightDirectory = isSampleDirectory(right);
        if (leftDirectory != rightDirectory) return leftDirectory ? -1 : 1;
        int compared;
        if (mSampleSortMode == 2) {
            compared = Long.compare(assetSizeBytes(left), assetSizeBytes(right));
        } else {
            compared = new File(left).getName().compareToIgnoreCase(new File(right).getName());
        }
        if (compared == 0) compared = left.compareToIgnoreCase(right);
        return mSampleSortDescending ? -compared : compared;
    }

    private String sampleDirectoryLabel() {
        String relative = mCurrentSamplePath.length() <= "sample".length()
                ? "" : mCurrentSamplePath.substring("sample".length() + 1);
        String label = relative.isEmpty() ? "示例文件  >  中文"
                : "示例文件  >  中文 / " + relative;
        if (mSampleFilter > 0) {
            String[] filters = {"", "文件夹", "文件", "代码", "项目"};
            if (mSampleFilter < filters.length) label += "  ·  " + filters[mSampleFilter];
        }
        if (mSampleSearchQuery.isEmpty()) return label;
        return label + "  ·  搜索：" + mSampleSearchQuery
                + (mSampleSearchSubdirectories ? "（含子目录）" : "")
                + (mSampleSearchRegex ? "（正则）" : "");
    }

    private String readableAssetSize(String assetPath) {
        try (InputStream input = getAssets().open(assetPath)) {
            int bytes = input.available();
            return bytes >= 1024 ? String.format(Locale.getDefault(), "%.1f KB", bytes / 1024f)
                    : bytes + " B";
        } catch (IOException ignored) {
            return "未知大小";
        }
    }

    private long assetSizeBytes(String assetPath) {
        if (isSampleDirectory(assetPath)) return 0L;
        try (InputStream input = getAssets().open(assetPath)) {
            return input.available();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private void showWorkspaceMenu() {
        boolean floating = FloatyWindowManger.isCircularMenuShowing();
        boolean connected = DevPluginService.getInstance().isConnected();
        String[] items = {"无障碍服务", floating ? "关闭悬浮窗" : "开启悬浮窗", "更多服务…",
                connected ? "断开开发者调试" : "开发者调试", "终端", "主题", "官方博客",
                "官方频道 / 论坛", "设置", "检查更新", "退出"};
        new AlertDialog.Builder(this)
                .setTitle("AI.js Pro")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    else if (which == 1) toggleFloatingWindow();
                    else if (which == 2) showMoreServicesDialog();
                    else if (which == 3) toggleDeveloperConnection();
                    else if (which == 4) openTerminal(mCurrentScriptDirectory);
                    else if (which == 5) showThemeDialog();
                    else if (which == 6) openWebPage("https://hyb1996.github.io/AutoJs-Docs/");
                    else if (which == 7) openWebPage("https://www.autojs.org/");
                    else if (which == 8) startActivity(new Intent(this, SettingsActivity.class));
                    else if (which == 9) new UpdateCheckDialog(this).show();
                    else confirmExitApplication();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void toggleFloatingWindow() {
        if (FloatyWindowManger.isCircularMenuShowing()) {
            FloatyWindowManger.hideCircularMenu();
            Pref.setFloatingMenuShown(false);
            Toast.makeText(this, "悬浮窗已关闭", Toast.LENGTH_SHORT).show();
        } else {
            boolean shown = FloatyWindowManger.showCircularMenu();
            Pref.setFloatingMenuShown(shown);
            if (shown) {
                Toast.makeText(this, "悬浮窗已开启", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "悬浮窗开启失败，请检查悬浮窗权限", Toast.LENGTH_LONG).show();
            }
        }
        refreshRuntimeState();
    }

    private void showMoreServicesDialog() {
        String[] items = {"通知使用权", "使用情况访问权限", "悬浮窗权限", "电池优化设置"};
        new AlertDialog.Builder(this)
                .setTitle("更多服务")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    else if (which == 1) startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                    else if (which == 2) startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                    else if (which == 3) startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void toggleDeveloperConnection() {
        DevPluginService service = DevPluginService.getInstance();
        if (service.isConnected()) {
            service.disconnectIfNeeded();
            Toast.makeText(this, "开发者调试已断开", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("电脑 IP 或 ws:// 地址");
        input.setText(Pref.getServerAddressOrDefault(WifiTool.getRouterIp(this)));
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("开发者调试")
                .setView(input)
                .setNeutralButton("帮助", (dialog, which) ->
                        openWebPage("https://www.autojs.org/topic/968/"))
                .setNegativeButton("取消", null)
                .setPositiveButton("连接", (dialog, which) -> {
                    String host = input.getText().toString().trim();
                    if (host.isEmpty()) {
                        showFileError("服务器地址不能为空");
                        return;
                    }
                    Pref.saveServerAddress(host);
                    service.connectToServer(host).subscribe(socket ->
                                    Toast.makeText(this, "已连接开发者调试服务", Toast.LENGTH_SHORT).show(),
                            error -> showFileError("连接失败：" + error.getMessage()));
                })
                .show();
        input.requestFocus();
    }

    private void openWebPage(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException error) {
            showFileError("没有可打开网页的应用");
        }
    }

    private void openTerminal(File workingDirectory) {
        try {
            Intent intent = new Intent(this, EmbeddedTerminalActivity.class);
            if (workingDirectory != null && isWithinScriptRoot(workingDirectory)) {
                intent.putExtra(EmbeddedTerminalActivity.EXTRA_WORKING_DIRECTORY,
                        workingDirectory.getAbsolutePath());
            }
            startActivity(intent);
        } catch (RuntimeException error) {
            showFileError("终端启动失败：" + error.getMessage());
        }
    }

    private void confirmExitApplication() {
        new AlertDialog.Builder(this)
                .setTitle("退出 AI.js Pro？")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出", (dialog, which) -> finishAffinity())
                .show();
    }

    private void showScriptFilterDialog() {
        String[] filters = {"全部", "文件夹", "文件", "代码", "项目"};
        CheckBox showHidden = new CheckBox(this);
        showHidden.setText("显示隐藏文件");
        showHidden.setChecked(mShowHiddenFiles);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        showHidden.setPadding(padding, 0, padding, padding / 2);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("筛选类型")
                .setSingleChoiceItems(filters, mScriptFilter, null)
                .setView(showHidden)
                .setNegativeButton("取消", null)
                .setNeutralButton("清除搜索", (ignored, which) -> {
                    mSearchQuery = "";
                    mSearchSubdirectories = false;
                    mSearchRegex = false;
                    mScriptFilter = 0;
                    refreshScriptEntries();
                })
                .setPositiveButton("应用", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    mScriptFilter = dialog.getListView().getCheckedItemPosition();
                    if (mScriptFilter < 0) mScriptFilter = 0;
                    mShowHiddenFiles = showHidden.isChecked();
                    refreshScriptEntries();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void showScriptSortDialog() {
        String[] modes = {"名称 · 升序", "名称 · 降序", "修改时间 · 升序", "修改时间 · 降序",
                "类型 · 升序", "类型 · 降序", "大小 · 升序", "大小 · 降序"};
        int checked = mScriptSortMode * 2 + (mScriptSortDescending ? 1 : 0);
        new AlertDialog.Builder(this)
                .setTitle("排序规则")
                .setSingleChoiceItems(modes, checked, (dialog, which) -> {
                    mScriptSortMode = which / 2;
                    mScriptSortDescending = (which & 1) == 1;
                    refreshScriptEntries();
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String selectedSamplePath() {
        String path = ImGuiNativeBridge.getSelectedSamplePath();
        return path != null && path.startsWith("sample") ? path : null;
    }

    private void openSelectedSampleEntry() {
        String path = selectedSamplePath();
        if (path == null) return;
        if (isSampleDirectory(path)) {
            mCurrentSamplePath = path;
            mSampleSearchQuery = "";
            refreshSampleEntries();
        } else if (isEditableSampleAsset(path)) {
            openSampleInProEditor(path);
        } else {
            ViewSampleActivity.view(this, new SampleFile(path, getAssets()));
        }
    }

    private boolean isEditableSampleAsset(String assetPath) {
        if (assetPath == null) return false;
        String name = assetPath.toLowerCase(Locale.ROOT);
        return name.endsWith(".js") || name.endsWith(".auto")
                || name.endsWith(".json") || name.endsWith(".txt")
                || name.endsWith(".xml") || name.endsWith(".html")
                || name.endsWith(".css") || name.endsWith(".md")
                || name.endsWith(".param");
    }

    /**
     * Bundled samples are read-only assets. Mirror the selected sample's directory into an
     * app-private preview workspace so the Pro editor can provide its normal file tree, tabs,
     * save/run actions, and relative companion files without falling back to ViewSampleActivity.
     */
    private void openSampleInProEditor(String assetPath) {
        String relative = assetPath.startsWith("sample/")
                ? assetPath.substring("sample/".length()) : new File(assetPath).getName();
        File previewRoot = canonicalFile(new File(getFilesDir(), "pro-sample-workspace"));
        File target = canonicalFile(new File(previewRoot, relative.replace('/', File.separatorChar)));
        if (!isWithinDirectory(target, previewRoot)) {
            showFileError("示例路径无效");
            return;
        }

        int slash = assetPath.lastIndexOf('/');
        String assetDirectory = slash > "sample".length()
                ? assetPath.substring(0, slash) : null;
        try {
            if (assetDirectory == null) {
                if (!target.isFile()) copyAssetTree(assetPath, target);
            } else {
                File targetDirectory = target.getParentFile();
                File initialized = new File(targetDirectory, ".bundled-sample-ready");
                File legacyRoot = canonicalFile(new File(getCacheDir(), "pro-sample-workspace"));
                File legacyTarget = canonicalFile(new File(legacyRoot,
                        relative.replace('/', File.separatorChar)));
                if (!initialized.isFile() && !target.isFile()
                        && isWithinDirectory(legacyTarget, legacyRoot) && legacyTarget.isFile()) {
                    FileUtils.copyDirectory(legacyTarget.getParentFile(), targetDirectory);
                    initialized.createNewFile();
                }
                if (!initialized.isFile()) {
                    copyAssetTree(assetDirectory, targetDirectory);
                    if (!initialized.createNewFile() && !initialized.isFile()) {
                        throw new IOException("无法记录示例工作区状态");
                    }
                } else if (!target.isFile()) {
                    // A newer APK may add a file to an already edited sample directory. Import
                    // only that new file so existing user changes are never overwritten.
                    copyAssetTree(assetPath, target);
                }
            }
            if (!target.isFile()) throw new IOException("无法创建示例预览文件");
            migrateLegacyYoloAutoLine(assetPath, target);
            startActivity(ProCodeEditorActivity.sampleIntent(this, target, assetPath));
        } catch (IOException error) {
            showFileError("打开示例失败：" + error.getMessage());
        }
    }

    /**
     * The first YOLO preview shipped with an AI.js Pro accessibility bootstrap line. Preserve all
     * user edits while removing only that obsolete first line once from existing preview copies.
     */
    private void migrateLegacyYoloAutoLine(String assetPath, File target) throws IOException {
        if (assetPath == null || !assetPath.contains("/YOLO目标检测/")) return;
        String fileName = target.getName();
        if (!"YOLO单张图片识别.js".equals(fileName) && !"YOLO实时识别.js".equals(fileName)) return;
        File marker = new File(target.getParentFile(), ".yolo-auto-line-removed");
        if (marker.isFile()) return;
        String source = FileUtils.readFileToString(target, "UTF-8");
        String migrated = source.replaceFirst(
                "^\\uFEFF?[\\t ]*(?://[\\t ]*)?[\"']auto[\"'];[\\t ]*(?:\\r?\\n)?", "");
        if (!source.equals(migrated)) FileUtils.writeStringToFile(target, migrated, "UTF-8");
        if (!marker.createNewFile() && !marker.isFile()) {
            throw new IOException("无法记录 YOLO 示例迁移状态");
        }
    }

    private static boolean isWithinDirectory(File file, File directory) {
        if (file == null || directory == null) return false;
        String rootPath = canonicalFile(directory).getPath();
        String filePath = canonicalFile(file).getPath();
        return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
    }

    private void runSelectedSample() {
        String path = selectedSamplePath();
        if (path == null || isSampleDirectory(path)) return;
        Scripts.INSTANCE.run(new SampleFile(path, getAssets()).toSource());
        refreshRuntimeState();
        Toast.makeText(this, "已运行示例：" + new File(path).getName(), Toast.LENGTH_SHORT).show();
    }

    private void importSelectedSample() {
        String path = selectedSamplePath();
        if (path == null) return;
        String relative = path.length() > "sample/".length()
                ? path.substring("sample/".length()) : new File(path).getName();
        File target = canonicalFile(new File(new File(mScriptRoot, "导入示例"), relative));
        if (!isWithinScriptRoot(target)) {
            showFileError("示例目标路径无效");
            return;
        }
        if (target.exists()) {
            Toast.makeText(this, "示例已经导入：" + target.getPath(), Toast.LENGTH_LONG).show();
            if (target.isFile()) editScriptFile(target);
            return;
        }
        try {
            copyAssetTree(path, target);
            refreshScriptEntries();
            Toast.makeText(this, "已导入到：" + target.getPath(), Toast.LENGTH_LONG).show();
            if (target.isFile()) editScriptFile(target);
        } catch (IOException error) {
            showFileError("导入失败：" + error.getMessage());
        }
    }

    private void navigateToParentSampleDirectory() {
        if ("sample".equals(mCurrentSamplePath)) return;
        int slash = mCurrentSamplePath.lastIndexOf('/');
        mCurrentSamplePath = slash <= "sample".length() ? "sample"
                : mCurrentSamplePath.substring(0, slash);
        mSampleSearchQuery = "";
        refreshSampleEntries();
    }

    private void showSampleSearchDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("输入示例文件或目录名称");
        input.setText(mSampleSearchQuery);
        input.setSelectAllOnFocus(true);
        CheckBox subdirectories = new CheckBox(this);
        subdirectories.setText("搜索子文件夹");
        subdirectories.setChecked(mSampleSearchSubdirectories);
        CheckBox regex = new CheckBox(this);
        regex.setText("正则表达式");
        regex.setChecked(mSampleSearchRegex);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, 0, padding, 0);
        content.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(subdirectories);
        content.addView(regex);
        new AlertDialog.Builder(this)
                .setTitle("搜索示例")
                .setView(content)
                .setNeutralButton("清除", (dialog, which) -> {
                    mSampleSearchQuery = "";
                    mSampleSearchSubdirectories = false;
                    mSampleSearchRegex = false;
                    refreshSampleEntries();
                })
                .setNegativeButton("取消", null)
                .setPositiveButton("搜索", (dialog, which) -> {
                    mSampleSearchQuery = input.getText().toString().trim();
                    mSampleSearchSubdirectories = subdirectories.isChecked();
                    mSampleSearchRegex = regex.isChecked();
                    refreshSampleEntries();
                })
                .show();
        input.requestFocus();
    }

    private void showSampleFilterDialog() {
        String[] filters = {"全部", "文件夹", "文件", "代码", "项目"};
        new AlertDialog.Builder(this)
                .setTitle("示例类型")
                .setSingleChoiceItems(filters, mSampleFilter, (dialog, which) -> {
                    mSampleFilter = which;
                    refreshSampleEntries();
                    dialog.dismiss();
                })
                .setNeutralButton("清除搜索", (dialog, which) -> {
                    mSampleSearchQuery = "";
                    mSampleSearchSubdirectories = false;
                    mSampleSearchRegex = false;
                    mSampleFilter = 0;
                    refreshSampleEntries();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSampleSortDialog() {
        String[] modes = {"名称 · 升序", "名称 · 降序", "大小 · 升序", "大小 · 降序"};
        int checked = (mSampleSortMode == 2 ? 2 : 0) + (mSampleSortDescending ? 1 : 0);
        new AlertDialog.Builder(this)
                .setTitle("示例排序")
                .setSingleChoiceItems(modes, checked, (dialog, which) -> {
                    mSampleSortMode = which >= 2 ? 2 : 0;
                    mSampleSortDescending = (which & 1) == 1;
                    refreshSampleEntries();
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void copyAssetTree(String assetPath, File target) throws IOException {
        String[] children = getAssets().list(assetPath);
        if (children != null && children.length > 0) {
            if (!target.isDirectory() && !target.mkdirs()) {
                throw new IOException("无法创建目录 " + target.getName());
            }
            for (String child : children) {
                copyAssetTree(assetPath + "/" + child, new File(target, child));
            }
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建父目录");
        }
        try (InputStream input = getAssets().open(assetPath);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
    }

    private void collectResourceAssets(String path, List<String> result, int limit) {
        if (result.size() >= limit) return;
        try {
            String[] children = getAssets().list(path);
            if (children == null || children.length == 0) {
                if (path.endsWith(".js")) result.add(path);
                return;
            }
            Arrays.sort(children, String.CASE_INSENSITIVE_ORDER);
            for (String child : children) {
                collectResourceAssets(path + "/" + child, result, limit);
                if (result.size() >= limit) break;
            }
        } catch (IOException ignored) {
        }
    }

    private File resourceTargetFor(String assetPath) {
        String relative = assetPath.startsWith("sample/")
                ? assetPath.substring("sample/".length()) : new File(assetPath).getName();
        return canonicalFile(new File(new File(mScriptRoot, "下载资源"), relative));
    }

    private String resourceCategoryFor(String assetPath) {
        String relative = assetPath.startsWith("sample/")
                ? assetPath.substring("sample/".length()) : assetPath;
        int slash = relative.indexOf('/');
        return slash > 0 ? relative.substring(0, slash) : "其他";
    }

    private void showResourceCategoryDialog() {
        List<String> assets = new ArrayList<>();
        collectResourceAssets("sample", assets, 80);
        Set<String> categorySet = new LinkedHashSet<>();
        for (String asset : assets) categorySet.add(resourceCategoryFor(asset));
        categorySet.add("我的资源");
        List<String> categories = new ArrayList<>();
        categories.add("全部分类");
        categories.addAll(categorySet);
        int checked = mResourceCategory.isEmpty() ? 0 : categories.indexOf(mResourceCategory);
        if (checked < 0) checked = 0;
        final int initialChecked = checked;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("资源分类")
                .setSingleChoiceItems(categories.toArray(new String[0]), initialChecked, null)
                .setNeutralButton("刷新", (ignored, which) -> refreshResourceEntries())
                .setNegativeButton("取消", null)
                .setPositiveButton("应用", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    int selected = dialog.getListView().getCheckedItemPosition();
                    mResourceCategory = selected <= 0 ? "" : categories.get(selected);
                    refreshResourceEntries();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void refreshResourceEntries() {
        List<String> discovered = new ArrayList<>();
        collectResourceAssets("sample", discovered, 80);
        List<ResourceDescriptor> resources = new ArrayList<>();
        for (String asset : discovered) {
            if (!mResourceCategory.isEmpty() && !mResourceCategory.equals(resourceCategoryFor(asset))) continue;
            resources.add(new ResourceDescriptor(new File(asset).getName().replaceFirst("\\.js$", ""),
                    "AI.js Pro 内置示例脚本", "内置|" + resourceCategoryFor(asset) + "|"
                    + readableAssetSize(asset), asset, resourceTargetFor(asset).isFile()));
        }
        if (mResourceCategory.isEmpty() || "我的资源".equals(mResourceCategory)) {
            List<File> mine = new ArrayList<>();
            collectResourceFiles(userResourceDirectory(), mine, 100);
            for (File file : mine) {
                resources.add(new ResourceDescriptor(file.getName().replaceFirst("\\.js$", ""),
                        "本机上传的脚本资源", "本机|我的资源|" + readableFileSize(file),
                        "file:" + file.getPath(), true));
            }
        }
        String[] names = new String[resources.size()];
        String[] descriptions = new String[resources.size()];
        String[] metadata = new String[resources.size()];
        String[] paths = new String[resources.size()];
        boolean[] imported = new boolean[resources.size()];
        for (int i = 0; i < resources.size(); i++) {
            ResourceDescriptor resource = resources.get(i);
            names[i] = resource.name;
            descriptions[i] = resource.description;
            metadata[i] = resource.metadata;
            paths[i] = resource.path;
            imported[i] = resource.imported;
        }
        ImGuiNativeBridge.setResourceEntries(names, descriptions, metadata, paths, imported);
    }

    private File userResourceDirectory() {
        File directory = canonicalFile(new File(mScriptRoot, "我的资源"));
        if (!directory.isDirectory()) directory.mkdirs();
        return directory;
    }

    private void collectResourceFiles(File directory, List<File> result, int limit) {
        if (directory == null || !directory.isDirectory() || result.size() >= limit) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File file : files) {
            if (file.isDirectory()) collectResourceFiles(file, result, limit);
            else if (file.getName().endsWith(".js") || file.getName().endsWith(".auto")) result.add(file);
            if (result.size() >= limit) break;
        }
    }

    private String readableFileSize(File file) {
        long bytes = file.length();
        return bytes >= 1024 ? String.format(Locale.getDefault(), "%.1f KB", bytes / 1024f)
                : bytes + " B";
    }

    private void importSelectedResource() {
        String assetPath = ImGuiNativeBridge.getSelectedResourcePath();
        if (assetPath == null) return;
        if (assetPath.startsWith("file:")) {
            openSelectedResource();
            return;
        }
        if (!assetPath.startsWith("sample/")) return;
        File target = resourceTargetFor(assetPath);
        if (!isWithinScriptRoot(target)) {
            showFileError("资源目标路径无效");
            return;
        }
        try {
            if (!target.exists()) copyAssetTree(assetPath, target);
            refreshResourceEntries();
            refreshScriptEntries();
            Toast.makeText(this, "资源已保存到：" + target.getPath(), Toast.LENGTH_LONG).show();
        } catch (IOException error) {
            showFileError("资源导入失败：" + error.getMessage());
        }
    }

    private void openSelectedResource() {
        String assetPath = ImGuiNativeBridge.getSelectedResourcePath();
        if (assetPath == null) return;
        if (assetPath.startsWith("file:")) {
            File file = canonicalFile(new File(assetPath.substring("file:".length())));
            if (isWithinScriptRoot(file) && file.isFile()) editScriptFile(file);
            return;
        }
        File target = resourceTargetFor(assetPath);
        if (target.isFile()) editScriptFile(target);
        else importSelectedResource();
    }

    private void chooseResourceToUpload() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/javascript", "application/javascript", "text/plain"});
        startActivityForResult(Intent.createChooser(picker, "选择脚本资源"), REQUEST_IMPORT_RESOURCE);
    }

    private void showSelectedResourceDetails() {
        String path = ImGuiNativeBridge.getSelectedResourcePath();
        if (path == null) return;
        if (path.startsWith("file:")) {
            File file = canonicalFile(new File(path.substring("file:".length())));
            if (!isWithinScriptRoot(file) || !file.isFile()) return;
            new AlertDialog.Builder(this)
                    .setTitle(file.getName())
                    .setItems(new String[]{"本机资源 · " + readableFileSize(file) + " · 修改于 "
                                    + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                                    .format(new Date(file.lastModified())),
                                    "编辑", "运行", "分享", "从我的资源删除"}, (dialog, which) -> {
                        if (which == 1) editScriptFile(file);
                        else if (which == 2) Scripts.INSTANCE.run(new ScriptFile(file));
                        else if (which == 3) Scripts.INSTANCE.send(new ScriptFile(file));
                        else if (which == 4) confirmDeleteResource(file);
                    })
                    .setNegativeButton("关闭", null)
                    .show();
            return;
        }
        String name = new File(path).getName();
        File imported = resourceTargetFor(path);
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(new String[]{"内置 · " + resourceCategoryFor(path) + " · " + readableAssetSize(path),
                                "预览源码", "运行", imported.isFile() ? "打开已导入文件" : "导入到脚本目录"},
                        (dialog, which) -> {
                            if (which == 1) ViewSampleActivity.view(this, new SampleFile(path, getAssets()));
                            else if (which == 2) Scripts.INSTANCE.run(new SampleFile(path, getAssets()).toSource());
                            else if (which == 3 && imported.isFile()) editScriptFile(imported);
                            else if (which == 3) importSelectedResource();
                        })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void confirmDeleteResource(File file) {
        new AlertDialog.Builder(this)
                .setTitle("删除资源")
                .setMessage("确定从“我的资源”删除 " + file.getName() + "？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    if (!file.delete()) showFileError("资源删除失败");
                    refreshResourceEntries();
                    refreshScriptEntries();
                })
                .show();
    }

    private void refreshPluginEntries() {
        List<PluginDescriptor> plugins = new ArrayList<>();
        PackageManager packageManager = getPackageManager();
        try {
            for (ApplicationInfo application : packageManager.getInstalledApplications(PackageManager.GET_META_DATA)) {
                if (application.metaData == null || !application.metaData.containsKey(PLUGIN_REGISTRY_KEY)) continue;
                PackageInfo info = packageManager.getPackageInfo(application.packageName, 0);
                String label = String.valueOf(packageManager.getApplicationLabel(application));
                String query = mPluginSearchQuery.toLowerCase(Locale.getDefault());
                if (!query.isEmpty() && !label.toLowerCase(Locale.getDefault()).contains(query)
                        && !application.packageName.toLowerCase(Locale.getDefault()).contains(query)) continue;
                String version = "已安装 · " + (info.versionName == null ? info.versionCode : info.versionName);
                plugins.add(new PluginDescriptor(label, version, application.packageName, true));
            }
        } catch (RuntimeException | PackageManager.NameNotFoundException ignored) {
        }
        plugins.sort((left, right) -> left.name.compareToIgnoreCase(right.name));
        if (mPluginSearchQuery.isEmpty() || "安装本地插件 apk".contains(
                mPluginSearchQuery.toLowerCase(Locale.getDefault()))) {
            plugins.add(new PluginDescriptor("安装本地插件 APK", "选择设备中的 APK 文件", "", false));
        }
        String[] names = new String[plugins.size()];
        String[] versions = new String[plugins.size()];
        String[] packages = new String[plugins.size()];
        boolean[] installed = new boolean[plugins.size()];
        for (int i = 0; i < plugins.size(); i++) {
            PluginDescriptor plugin = plugins.get(i);
            names[i] = plugin.name;
            versions[i] = plugin.version;
            packages[i] = plugin.packageName;
            installed[i] = plugin.installed;
        }
        ImGuiNativeBridge.setPluginEntries(names, versions, packages, installed);
    }

    private void manageSelectedPlugin() {
        String packageName = ImGuiNativeBridge.getSelectedPluginPackage();
        if (packageName == null || packageName.isEmpty()) {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/vnd.android.package-archive");
            startActivityForResult(Intent.createChooser(picker, "选择插件 APK"), REQUEST_INSTALL_PLUGIN);
            return;
        }
        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo app = pm.getApplicationInfo(packageName, 0);
            String label = String.valueOf(pm.getApplicationLabel(app));
            PackageInfo info = pm.getPackageInfo(packageName, 0);
            String message = "包名：" + packageName + "\n版本：" + info.versionName
                    + " (" + info.versionCode + ")";
            new AlertDialog.Builder(this)
                    .setTitle(label)
                    .setMessage(message)
                    .setItems(new String[]{"打开插件", "应用详情", "检查更新", "卸载插件"}, (dialog, which) -> {
                        if (which == 0) {
                            Intent launch = pm.getLaunchIntentForPackage(packageName);
                            if (launch != null) startActivity(launch);
                            else Toast.makeText(this, "该插件没有独立启动页面", Toast.LENGTH_SHORT).show();
                        } else if (which == 1) {
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + packageName)));
                        } else if (which == 2) {
                            checkPluginUpdate(packageName);
                        } else {
                            startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + packageName)));
                        }
                    })
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (PackageManager.NameNotFoundException error) {
            refreshPluginEntries();
        }
    }

    private void showPluginSearchDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("插件名称或包名");
        input.setText(mPluginSearchQuery);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("搜索插件")
                .setView(input)
                .setNeutralButton("插件社区", (dialog, which) -> openPluginCommunity())
                .setNegativeButton("清除", (dialog, which) -> {
                    mPluginSearchQuery = "";
                    refreshPluginEntries();
                })
                .setPositiveButton("搜索", (dialog, which) -> {
                    mPluginSearchQuery = input.getText().toString().trim();
                    refreshPluginEntries();
                })
                .show();
        input.requestFocus();
    }

    private void showPluginHelp() {
        new AlertDialog.Builder(this)
                .setTitle("AI.js Pro 插件")
                .setItems(new String[]{"本机插件管理 · 开源版无 Pro 在线市场接口",
                                "安装本地插件 APK", "刷新插件状态", "打开插件开发说明"},
                        (dialog, which) -> {
                            if (which == 1) {
                                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                        .addCategory(Intent.CATEGORY_OPENABLE)
                                        .setType("application/vnd.android.package-archive");
                                startActivityForResult(Intent.createChooser(picker, "选择插件 APK"), REQUEST_INSTALL_PLUGIN);
                            } else if (which == 2) {
                                refreshPluginEntries();
                            } else if (which == 3) openPluginCommunity();
                        })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void openPluginCommunity() {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.autojs.org/topic/968/")));
    }

    private void checkPluginUpdate(String packageName) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName)));
        } catch (ActivityNotFoundException error) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + packageName)));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INSTALL_PLUGIN && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            Uri apk = data.getData();
            Intent install = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apk, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
        } else if (requestCode == REQUEST_IMPORT_RESOURCE && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            importResourceUri(data.getData());
        }
    }

    private void importResourceUri(Uri uri) {
        String name = queryDisplayName(uri);
        if (name == null || name.trim().isEmpty()) name = "resource-" + System.currentTimeMillis() + ".js";
        name = new File(name).getName();
        if (!name.endsWith(".js") && !name.endsWith(".auto")) {
            Toast.makeText(this, "仅支持 .js 或 .auto 脚本资源", Toast.LENGTH_LONG).show();
            return;
        }
        File target = canonicalFile(new File(userResourceDirectory(), name));
        if (target.exists()) {
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String extension = dot > 0 ? name.substring(dot) : ".js";
            target = canonicalFile(new File(userResourceDirectory(), stem + "-" + System.currentTimeMillis() + extension));
        }
        final File outputFile = target;
        try (InputStream input = getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(outputFile)) {
            if (input == null) throw new IOException("无法读取所选文件");
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) >= 0) output.write(buffer, 0, length);
            refreshResourceEntries();
            refreshScriptEntries();
            Toast.makeText(this, "已加入我的资源：" + outputFile.getName(), Toast.LENGTH_LONG).show();
        } catch (IOException error) {
            showFileError("资源上传失败：" + error.getMessage());
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException ignored) {
        }
        return uri.getLastPathSegment();
    }

    private void refreshTaskEntries() {
        mRunningTaskEntries.clear();
        mPendingTaskEntries.clear();
        try {
            Collection<ScriptExecution> executions = AutoJs.getInstance()
                    .getScriptEngineService().getScriptExecutions();
            mRunningTaskEntries.addAll(executions);
            mPendingTaskEntries.addAll(TimedTaskManager.getInstance().getAllTasksAsList());
            mPendingTaskEntries.addAll(TimedTaskManager.getInstance().getAllIntentTasksAsList());
        } catch (RuntimeException ignored) {
        }
        if (!mTaskSearchQuery.isEmpty()) {
            String query = mTaskSearchQuery.toLowerCase(Locale.getDefault());
            mRunningTaskEntries.removeIf(execution -> {
                Task.RunningTask task = new Task.RunningTask(execution);
                return !(task.getName() + " " + task.getDesc()).toLowerCase(Locale.getDefault()).contains(query);
            });
            mPendingTaskEntries.removeIf(source -> {
                Task.PendingTask task = source instanceof TimedTask
                        ? new Task.PendingTask((TimedTask) source) : new Task.PendingTask((IntentTask) source);
                return !(task.getName() + " " + task.getDesc()).toLowerCase(Locale.getDefault()).contains(query);
            });
        }
        String[] runningNames = new String[mRunningTaskEntries.size()];
        String[] runningDescriptions = new String[mRunningTaskEntries.size()];
        for (int i = 0; i < mRunningTaskEntries.size(); i++) {
            Task.RunningTask task = new Task.RunningTask(mRunningTaskEntries.get(i));
            runningNames[i] = task.getName();
            runningDescriptions[i] = task.getDesc();
        }
        String[] pendingNames = new String[mPendingTaskEntries.size()];
        String[] pendingDescriptions = new String[mPendingTaskEntries.size()];
        for (int i = 0; i < mPendingTaskEntries.size(); i++) {
            Object source = mPendingTaskEntries.get(i);
            Task.PendingTask task = source instanceof TimedTask
                    ? new Task.PendingTask((TimedTask) source)
                    : new Task.PendingTask((IntentTask) source);
            pendingNames[i] = task.getName();
            pendingDescriptions[i] = task.getDesc();
        }
        ImGuiNativeBridge.setTaskEntries(runningNames, runningDescriptions,
                pendingNames, pendingDescriptions);
    }

    private void openSelectedTask() {
        int key = ImGuiNativeBridge.getSelectedTaskKey();
        int group = key < 0 ? -1 : key / 10000;
        int index = key < 0 ? -1 : key % 10000;
        if (group == 0 && index < mRunningTaskEntries.size()) {
            Task.RunningTask task = new Task.RunningTask(mRunningTaskEntries.get(index));
            new AlertDialog.Builder(this)
                    .setTitle(task.getName())
                    .setMessage(task.getDesc())
                    .setNeutralButton("运行日志", (dialog, which) ->
                            startActivity(new Intent(this, LogActivity.class)))
                    .setNegativeButton("关闭", null)
                    .setPositiveButton("停止", (dialog, which) -> {
                        task.cancel();
                        refreshRuntimeState();
                    })
                    .show();
        } else if (group == 1 && index < mPendingTaskEntries.size()) {
            Object task = mPendingTaskEntries.get(index);
            Intent intent = new Intent(this, TimedTaskSettingActivity.class);
            if (task instanceof TimedTask) {
                intent.putExtra(TimedTaskSettingActivity.EXTRA_TASK_ID, ((TimedTask) task).getId());
            } else {
                intent.putExtra(TimedTaskSettingActivity.EXTRA_INTENT_TASK_ID, ((IntentTask) task).getId());
            }
            startActivity(intent);
        }
    }

    private void cancelSelectedTask() {
        int key = ImGuiNativeBridge.getSelectedTaskKey();
        int group = key < 0 ? -1 : key / 10000;
        int index = key < 0 ? -1 : key % 10000;
        if (group == 0 && index < mRunningTaskEntries.size()) {
            ScriptExecution execution = mRunningTaskEntries.get(index);
            if (execution.getEngine() != null) execution.getEngine().forceStop();
            refreshRuntimeState();
        } else if (group == 1 && index < mPendingTaskEntries.size()) {
            Object source = mPendingTaskEntries.get(index);
            new AlertDialog.Builder(this)
                    .setTitle("删除任务")
                    .setMessage("确定删除这个定时或事件任务？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (dialog, which) -> {
                        if (source instanceof TimedTask) {
                            TimedTaskManager.getInstance().removeTask((TimedTask) source);
                        } else {
                            TimedTaskManager.getInstance().removeTask((IntentTask) source);
                        }
                        refreshTaskEntries();
                    })
                    .show();
        }
    }

    private void chooseScriptForTimedTask() {
        new AlertDialog.Builder(this)
                .setTitle("新建任务")
                .setItems(new String[]{"定时任务", "事件 / 广播任务"},
                        (dialog, which) -> chooseScriptForTaskMode(which == 1))
                .setNegativeButton("取消", null)
                .show();
    }

    private void chooseScriptForTaskMode(boolean eventTask) {
        List<File> scripts = new ArrayList<>();
        collectScripts(mScriptRoot, scripts, 200);
        if (scripts.isEmpty()) {
            Toast.makeText(this, "请先创建一个脚本", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[scripts.size()];
        String rootPath = mScriptRoot.getPath() + File.separator;
        for (int i = 0; i < scripts.size(); i++) {
            labels[i] = scripts.get(i).getPath().replace(rootPath, "");
        }
        new AlertDialog.Builder(this)
                .setTitle(eventTask ? "选择事件触发的脚本" : "选择定时运行的脚本")
                .setItems(labels, (dialog, which) -> startActivity(
                        new Intent(this, TimedTaskSettingActivity.class)
                                .putExtra(ScriptIntents.EXTRA_KEY_PATH, scripts.get(which).getPath())
                                .putExtra(TimedTaskSettingActivity.EXTRA_CREATE_MODE,
                                        eventTask ? TimedTaskSettingActivity.CREATE_MODE_EVENT
                                                : TimedTaskSettingActivity.CREATE_MODE_TIME)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showTaskSearchDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("任务名称、脚本或触发条件");
        input.setText(mTaskSearchQuery);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("搜索任务")
                .setView(input)
                .setNeutralButton("刷新", (dialog, which) -> refreshTaskEntries())
                .setNegativeButton("清除", (dialog, which) -> {
                    mTaskSearchQuery = "";
                    refreshTaskEntries();
                })
                .setPositiveButton("搜索", (dialog, which) -> {
                    mTaskSearchQuery = input.getText().toString().trim();
                    refreshTaskEntries();
                })
                .show();
        input.requestFocus();
    }

    private void collectScripts(File directory, List<File> result, int limit) {
        if (directory == null || result.size() >= limit) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File file : files) {
            if (file.isDirectory()) collectScripts(file, result, limit);
            else if (file.getName().endsWith(".js") || file.getName().endsWith(".auto")) result.add(file);
            if (result.size() >= limit) break;
        }
    }

    private static final class ResourceDescriptor {
        final String name;
        final String description;
        final String metadata;
        final String path;
        final boolean imported;

        ResourceDescriptor(String name, String description, String metadata,
                           String path, boolean imported) {
            this.name = name;
            this.description = description;
            this.metadata = metadata;
            this.path = path;
            this.imported = imported;
        }
    }

    private static final class PluginDescriptor {
        final String name;
        final String version;
        final String packageName;
        final boolean installed;

        PluginDescriptor(String name, String version, String packageName, boolean installed) {
            this.name = name;
            this.version = version;
            this.packageName = packageName;
            this.installed = installed;
        }
    }

    private void navigateToParentDirectory() {
        if (mCurrentScriptDirectory == null || mCurrentScriptDirectory.equals(mScriptRoot)) {
            return;
        }
        File parent = canonicalFile(mCurrentScriptDirectory.getParentFile());
        if (isWithinScriptRoot(parent)) {
            mCurrentScriptDirectory = parent;
            refreshScriptEntries();
        }
    }

    private void navigateToScriptBreadcrumb(int depth) {
        if (depth < 0 || mScriptRoot == null || mCurrentScriptDirectory == null) return;
        File target = mScriptRoot;
        if (depth > 0 && !mCurrentScriptDirectory.equals(mScriptRoot)) {
            String relative = mCurrentScriptDirectory.getPath()
                    .substring(Math.min(mScriptRoot.getPath().length() + 1,
                            mCurrentScriptDirectory.getPath().length()));
            String[] parts = relative.split(java.util.regex.Pattern.quote(File.separator));
            for (int i = 0; i < Math.min(depth, parts.length); i++) target = new File(target, parts[i]);
        }
        target = canonicalFile(target);
        if (isWithinScriptRoot(target) && target.isDirectory()) {
            mCurrentScriptDirectory = target;
            mSearchQuery = "";
            refreshScriptEntries();
        }
    }

    private void navigateToSampleBreadcrumb(int depth) {
        if (depth < 0) return;
        String target = "sample";
        if (depth > 0 && mCurrentSamplePath.length() > "sample".length()) {
            String relative = mCurrentSamplePath.substring("sample".length() + 1);
            String[] parts = relative.split("/");
            StringBuilder builder = new StringBuilder("sample");
            for (int i = 0; i < Math.min(depth, parts.length); i++) builder.append('/').append(parts[i]);
            target = builder.toString();
        }
        if (isSampleDirectory(target) || "sample".equals(target)) {
            mCurrentSamplePath = target;
            mSampleSearchQuery = "";
            refreshSampleEntries();
        }
    }

    private void openSelectedScriptEntry() {
        File selected = selectedScriptEntry();
        if (selected == null) {
            return;
        }
        if (selected.isDirectory()) {
            mCurrentScriptDirectory = selected;
            refreshScriptEntries();
        } else if (isCodeFile(selected)) {
            editScriptFile(selected);
        } else {
            openGenericFile(selected);
        }
    }

    private void editScriptFile(File file) {
        File canonical = canonicalFile(file);
        if (canonical == null || !isWithinScriptRoot(canonical) || !isCodeFile(canonical)) return;
        getSharedPreferences(WORKSPACE_PREFERENCES, MODE_PRIVATE).edit()
                .putString(KEY_LAST_EDITED_SCRIPT, canonical.getPath()).apply();
        startActivity(ProCodeEditorActivity.intent(this, canonical));
    }

    private void openRecentEditor() {
        SharedPreferences preferences = getSharedPreferences(WORKSPACE_PREFERENCES, MODE_PRIVATE);
        String path = preferences.getString(KEY_LAST_EDITED_SCRIPT, "");
        File recent = path == null || path.isEmpty() ? null : canonicalFile(new File(path));
        if (recent != null && isWithinScriptRoot(recent) && isCodeFile(recent)) {
            editScriptFile(recent);
            return;
        }
        chooseScriptToEdit(mScriptRoot, "选择要编辑的脚本");
    }

    private void openDirectoryWorkspace(File directory) {
        if (directory == null || !directory.isDirectory() || !isWithinScriptRoot(directory)) return;
        File main = canonicalFile(new File(directory, "main.js"));
        if (main.isFile()) {
            editScriptFile(main);
            return;
        }
        chooseScriptToEdit(directory, "打开工作区 · " + directory.getName());
    }

    private void chooseScriptToEdit(File directory, String title) {
        List<File> scripts = new ArrayList<>();
        collectScripts(directory, scripts, 300);
        if (scripts.isEmpty()) {
            Toast.makeText(this, "该目录中没有可编辑脚本", Toast.LENGTH_SHORT).show();
            return;
        }
        String base = canonicalFile(directory).getPath() + File.separator;
        String[] labels = new String[scripts.size()];
        for (int i = 0; i < scripts.size(); i++) {
            String path = scripts.get(i).getPath();
            labels[i] = path.startsWith(base) ? path.substring(base.length()) : scripts.get(i).getName();
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels, (dialog, which) -> editScriptFile(scripts.get(which)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void openGenericFile(File selected) {
        String extension = MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(selected).toString());
        String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                extension == null ? "" : extension.toLowerCase(Locale.ROOT));
        if (mimeType == null) mimeType = "application/octet-stream";
        Uri uri = AppFileProvider.getUriForFile(this, selected);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, "打开文件"));
        } catch (ActivityNotFoundException error) {
            showFileError("没有可打开此文件类型的应用");
        }
    }

    private void showSelectedFileMenu() {
        File selected = selectedScriptEntry();
        if (selected == null) return;
        final String[] items = selected.isDirectory()
                ? new String[]{"打开", "打开工作区", "终端", "复制到…", "移动到…", "发送", "重命名", "删除"}
                : isCodeFile(selected)
                ? new String[]{"编辑", "运行", "复制到…", "移动到…", "分享", "重命名", "删除"}
                : new String[]{"打开", "复制到…", "移动到…", "分享", "重命名", "删除"};
        new AlertDialog.Builder(this)
                .setTitle(selected.getName())
                .setItems(items, (dialog, which) -> {
                    if (selected.isDirectory()) {
                        if (which == 0) openSelectedScriptEntry();
                        else if (which == 1) openDirectoryWorkspace(selected);
                        else if (which == 2) openTerminal(selected);
                        else if (which == 3) chooseFileDestination(selected, false);
                        else if (which == 4) chooseFileDestination(selected, true);
                        else if (which == 5) shareEntry(selected);
                        else if (which == 6) renameSelectedEntry();
                        else if (which == 7) confirmDeleteSelectedEntry();
                    } else if (isCodeFile(selected)) {
                        if (which == 0) openSelectedScriptEntry();
                        else if (which == 1) runSelectedScript();
                        else if (which == 2) chooseFileDestination(selected, false);
                        else if (which == 3) chooseFileDestination(selected, true);
                        else if (which == 4) shareEntry(selected);
                        else if (which == 5) renameSelectedEntry();
                        else if (which == 6) confirmDeleteSelectedEntry();
                    } else {
                        if (which == 0) openSelectedScriptEntry();
                        int offset = isCodeFile(selected) ? 1 : 0;
                        if (which == 1 + offset) chooseFileDestination(selected, false);
                        else if (which == 2 + offset) chooseFileDestination(selected, true);
                        else if (which == 3 + offset) shareEntry(selected);
                        else if (which == 4 + offset) renameSelectedEntry();
                        else if (which == 5 + offset) confirmDeleteSelectedEntry();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void chooseFileDestination(File selected, boolean move) {
        List<File> directories = new ArrayList<>();
        collectDirectories(mScriptRoot, directories, 300);
        List<File> allowed = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (File directory : directories) {
            File target = canonicalFile(new File(directory, selected.getName()));
            if (target.equals(selected) || (selected.isDirectory()
                    && target.getPath().startsWith(selected.getPath() + File.separator))) continue;
            allowed.add(directory);
            labels.add(directory.equals(mScriptRoot) ? "脚本根目录"
                    : directory.getPath().substring(mScriptRoot.getPath().length() + 1));
        }
        new AlertDialog.Builder(this)
                .setTitle(move ? "移动到" : "复制到")
                .setItems(labels.toArray(new String[0]), (dialog, which) ->
                        copyOrMoveEntry(selected, allowed.get(which), move))
                .setNegativeButton("取消", null)
                .show();
    }

    private void collectDirectories(File directory, List<File> result, int limit) {
        if (directory == null || !directory.isDirectory() || result.size() >= limit) return;
        result.add(directory);
        File[] children = directory.listFiles(File::isDirectory);
        if (children == null) return;
        Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File child : children) {
            collectDirectories(child, result, limit);
            if (result.size() >= limit) break;
        }
    }

    private void copyOrMoveEntry(File selected, File destination, boolean move) {
        File target = canonicalFile(new File(destination, selected.getName()));
        if (!isWithinScriptRoot(target) || target.exists()) {
            showFileError("目标位置已有同名文件，或目标路径无效");
            return;
        }
        new Thread(() -> {
            try {
                if (selected.isDirectory()) {
                    if (move) FileUtils.moveDirectory(selected, target);
                    else FileUtils.copyDirectory(selected, target);
                } else {
                    if (move) FileUtils.moveFile(selected, target);
                    else FileUtils.copyFile(selected, target);
                }
                runOnUiThread(() -> {
                    refreshScriptEntries();
                    Toast.makeText(this, (move ? "已移动到：" : "已复制到：")
                            + target.getPath(), Toast.LENGTH_LONG).show();
                });
            } catch (IOException error) {
                runOnUiThread(() -> showFileError((move ? "移动失败：" : "复制失败：")
                        + error.getMessage()));
            }
        }, "imgui-file-operation").start();
    }

    private void shareEntry(File selected) {
        if (selected.isFile()) {
            Scripts.INSTANCE.send(new ScriptFile(selected));
            return;
        }
        new Thread(() -> {
            File archive = new File(getCacheDir(), "share-" + selected.getName() + ".zip");
            try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(archive))) {
                zipDirectory(selected, selected.getName(), output);
                runOnUiThread(() -> {
                    Uri uri = AppFileProvider.getUriForFile(this, archive);
                    Intent share = new Intent(Intent.ACTION_SEND)
                            .setType("application/zip")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(share, "分享项目"));
                });
            } catch (IOException error) {
                runOnUiThread(() -> showFileError("打包分享失败：" + error.getMessage()));
            }
        }, "imgui-share-project").start();
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

    private void runSelectedScript() {
        File selected = selectedScriptEntry();
        if (selected == null || !isCodeFile(selected)) {
            if (selected != null && !selected.isDirectory()) {
                showFileError("此文件类型不能直接运行");
            }
            return;
        }
        Scripts.INSTANCE.run(new ScriptFile(selected));
        refreshWorkspaceState();
    }

    private void createEntry(boolean directory) {
        showNameDialog(directory ? "新建目录" : "新建脚本", "", name -> {
            String entryName = validatedEntryName(name);
            if (entryName == null) {
                return;
            }
            if (!directory && !entryName.endsWith(".js") && !entryName.endsWith(".auto")) {
                entryName += ".js";
            }
            File target = canonicalFile(new File(mCurrentScriptDirectory, entryName));
            if (!isDirectChildOfCurrentDirectory(target) || target.exists()) {
                showFileError("名称无效或文件已经存在");
                return;
            }
            try {
                boolean created = directory ? target.mkdir() : target.createNewFile();
                if (!created) {
                    showFileError("创建失败");
                    return;
                }
                refreshScriptEntries();
                if (!directory) {
                    editScriptFile(target);
                }
            } catch (IOException error) {
                showFileError("创建失败：" + error.getMessage());
            }
        });
    }

    private void renameSelectedEntry() {
        File selected = selectedScriptEntry();
        if (selected == null) {
            return;
        }
        showNameDialog("重命名", selected.getName(), name -> {
            String entryName = validatedEntryName(name);
            if (entryName == null) {
                return;
            }
            File destination = canonicalFile(new File(selected.getParentFile(), entryName));
            File selectedParent = canonicalFile(selected.getParentFile());
            File destinationParent = canonicalFile(destination.getParentFile());
            if (!isWithinScriptRoot(destination) || !selectedParent.equals(destinationParent)
                    || destination.exists()) {
                showFileError("名称无效或文件已经存在");
                return;
            }
            if (!selected.renameTo(destination)) {
                showFileError("重命名失败");
                return;
            }
            refreshScriptEntries();
        });
    }

    private void confirmDeleteSelectedEntry() {
        File selected = selectedScriptEntry();
        if (selected == null) {
            return;
        }
        String type = selected.isDirectory() ? "目录" : "脚本";
        new AlertDialog.Builder(this)
                .setTitle("删除" + type)
                .setMessage("确定删除“" + selected.getName() + "”？"
                        + (selected.isDirectory() ? "\n目录中的全部内容也会被删除。" : ""))
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    if (!deleteRecursivelyInsideScriptRoot(selected)) {
                        showFileError("删除失败");
                        return;
                    }
                    refreshScriptEntries();
                })
                .show();
    }

    private void showNameDialog(String title, String initialValue, NameCallback callback) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(initialValue);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (dialog, which) ->
                        callback.onName(input.getText().toString()))
                .show();
        input.requestFocus();
    }

    private void showSearchDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("输入文件或目录名称");
        input.setText(mSearchQuery);
        input.setSelectAllOnFocus(true);
        CheckBox subdirectories = new CheckBox(this);
        subdirectories.setText("搜索子文件夹");
        subdirectories.setChecked(mSearchSubdirectories);
        CheckBox regex = new CheckBox(this);
        regex.setText("正则表达式");
        regex.setChecked(mSearchRegex);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, 0, padding, 0);
        content.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(subdirectories);
        content.addView(regex);
        new AlertDialog.Builder(this)
                .setTitle("搜索脚本")
                .setView(content)
                .setNeutralButton("清除", (dialog, which) -> {
                    mSearchQuery = "";
                    mSearchSubdirectories = false;
                    mSearchRegex = false;
                    refreshScriptEntries();
                })
                .setNegativeButton("取消", null)
                .setPositiveButton("搜索", (dialog, which) -> {
                    mSearchQuery = input.getText().toString().trim();
                    mSearchSubdirectories = subdirectories.isChecked();
                    mSearchRegex = regex.isChecked();
                    refreshScriptEntries();
                })
                .show();
        input.requestFocus();
    }

    private String validatedEntryName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            showFileError("名称不能为空，也不能包含路径分隔符");
            return null;
        }
        return name;
    }

    private boolean isDirectChildOfCurrentDirectory(File file) {
        if (file == null || mCurrentScriptDirectory == null || !isWithinScriptRoot(file)) {
            return false;
        }
        File parent = canonicalFile(file.getParentFile());
        return mCurrentScriptDirectory.equals(parent);
    }

    private boolean deleteRecursivelyInsideScriptRoot(File file) {
        File canonical = canonicalFile(file);
        if (canonical == null || canonical.equals(mScriptRoot) || !isWithinScriptRoot(canonical)) {
            return false;
        }
        if (canonical.isDirectory()) {
            File[] children = canonical.listFiles();
            if (children == null) {
                return false;
            }
            for (File child : children) {
                if (!deleteRecursivelyInsideScriptRoot(child)) {
                    return false;
                }
            }
        }
        return canonical.delete();
    }

    private void showFileError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private interface NameCallback {
        void onName(String name);
    }

    private File selectedScriptEntry() {
        String path = ImGuiNativeBridge.getSelectedScriptPath();
        if (path == null || path.isEmpty()) {
            return null;
        }
        File selected = canonicalFile(new File(path));
        return isWithinScriptRoot(selected) && selected.exists() ? selected : null;
    }

    private String scriptDirectoryLabel() {
        String label;
        if (mCurrentScriptDirectory == null || mScriptRoot == null
                || mCurrentScriptDirectory.equals(mScriptRoot)) {
            label = "内部存储  >  脚本";
        } else {
            String rootPath = mScriptRoot.getPath();
            String currentPath = mCurrentScriptDirectory.getPath();
            String relative = currentPath.substring(Math.min(rootPath.length(), currentPath.length()));
            label = "内部存储  >  脚本" + relative.replace(File.separatorChar, '/');
        }
        String[] filters = {"", "文件夹", "文件", "代码", "项目"};
        if (mScriptFilter > 0 && mScriptFilter < filters.length) {
            label += "  ·  " + filters[mScriptFilter];
        }
        if (mShowHiddenFiles) label += "  ·  显示隐藏文件";
        if (mSearchQuery.isEmpty()) return label;
        return label + "  ·  搜索：" + mSearchQuery
                + (mSearchSubdirectories ? "（含子目录）" : "")
                + (mSearchRegex ? "（正则）" : "");
    }

    private boolean isWithinScriptRoot(File file) {
        if (file == null || mScriptRoot == null) {
            return false;
        }
        String rootPath = mScriptRoot.getPath();
        String filePath = canonicalFile(file).getPath();
        return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
    }

    private static File canonicalFile(File file) {
        if (file == null) {
            return null;
        }
        try {
            return file.getCanonicalFile();
        } catch (IOException ignored) {
            return file.getAbsoluteFile();
        }
    }

    private String prepareBundledFont() {
        File fontDir = new File(getFilesDir(), "fonts");
        File fontFile = new File(fontDir, "NotoSansCJKsc-Regular.otf");
        try {
            if (!fontDir.isDirectory() && !fontDir.mkdirs()) {
                throw new IOException("cannot create font directory");
            }
            int assetSize;
            try (InputStream input = getAssets().open(FONT_ASSET)) {
                assetSize = input.available();
            }
            if (!fontFile.isFile() || fontFile.length() != assetSize) {
                File temporary = new File(fontDir, "NotoSansCJKsc-Regular.otf.tmp");
                try (InputStream input = getAssets().open(FONT_ASSET);
                     FileOutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }
                    output.getFD().sync();
                }
                if (fontFile.exists() && !fontFile.delete()) {
                    throw new IOException("cannot replace font");
                }
                if (!temporary.renameTo(fontFile)) {
                    throw new IOException("cannot install font");
                }
            }
            return fontFile.getAbsolutePath();
        } catch (IOException error) {
            Toast.makeText(this, "中文字体加载失败，将使用默认字体", Toast.LENGTH_LONG).show();
            return null;
        }
    }

    private void restoreFloatingWindowPreference() {
        if (!Pref.isFloatingMenuShown() || FloatyWindowManger.isCircularMenuShowing()) {
            return;
        }
        try {
            FloatyWindowManger.showCircularMenu();
        } catch (RuntimeException ignored) {
        }
    }

    private void migrateBundledQuickJsSamplesOnUpgrade() {
        // No-op for now; samples are served from assets directly.
    }
}
