package com.jdkshen.aijspro.ui.floating;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.Toast;

import com.afollestad.materialdialogs.MaterialDialog;
import com.makeramen.roundedimageview.RoundedImageView;
import com.stardust.app.DialogUtils;
import com.stardust.app.OperationDialogBuilder;
import com.stardust.app.OperationItemClickListener;
import com.stardust.autojs.core.record.Recorder;
import com.stardust.enhancedfloaty.FloatyService;
import com.stardust.enhancedfloaty.FloatyWindow;

import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.autojs.record.GlobalActionRecorder;
import com.jdkshen.aijspro.model.explorer.ExplorerDirPage;
import com.jdkshen.aijspro.model.explorer.Explorers;
import com.jdkshen.aijspro.model.script.Scripts;
import com.jdkshen.aijspro.tool.AccessibilityServiceTool;
import com.jdkshen.aijspro.tool.RootTool;
import com.jdkshen.aijspro.ui.common.NotAskAgainDialog;
import com.jdkshen.aijspro.ui.floating.layoutinspector.LayoutBoundsFloatyWindow;
import com.jdkshen.aijspro.ui.floating.layoutinspector.LayoutHierarchyFloatyWindow;
import com.jdkshen.aijspro.ui.floating.layoutinspector.LayoutInspectorDiagnostics;
import com.jdkshen.aijspro.ui.main.MainActivity;
import com.jdkshen.aijspro.ui.explorer.ExplorerView;
import com.jdkshen.aijspro.theme.dialog.ThemeColorMaterialDialogBuilder;

import com.stardust.util.ClipboardUtil;
import com.stardust.util.Func1;
import com.stardust.view.accessibility.AccessibilityService;
import com.stardust.view.accessibility.LayoutInspector;
import com.stardust.view.accessibility.NodeInfo;

import org.greenrobot.eventbus.EventBus;
import org.jdeferred.Deferred;
import org.jdeferred.impl.DeferredObject;

/**
 * Created by Stardust on 2017/10/18.
 */

public class CircularMenu implements Recorder.OnStateChangedListener, LayoutInspector.CaptureAvailableListener, OperationItemClickListener, FloatingMenuVisibility.Target {


    public static class StateChangeEvent {
        private int currentState;
        private int previousState;

        public StateChangeEvent(int currentState, int previousState) {
            this.currentState = currentState;
            this.previousState = previousState;
        }

        public int getCurrentState() {
            return currentState;
        }

        public int getPreviousState() {
            return previousState;
        }
    }

    public static final int STATE_CLOSED = -1;
    public static final int STATE_NORMAL = 0;
    public static final int STATE_RECORDING = 1;

    private static final int IC_ACTION_VIEW = R.drawable.ic_android_eat_js;

    CircularMenuWindow mWindow;
    private int mState;
    private RoundedImageView mActionViewIcon;
    private Context mContext;
    private GlobalActionRecorder mRecorder;
    private MaterialDialog mSettingsDialog;
    private MaterialDialog mLayoutInspectDialog;
    private String mRunningPackage, mRunningActivity;
    private Deferred<NodeInfo, Void, Void> mCaptureDeferred;
    private final boolean mInitiallyHidden;
    private boolean mClosed;

    public CircularMenu(Context context) {
        this(context, false);
    }

    CircularMenu(Context context, boolean initiallyHidden) {
        mInitiallyHidden = initiallyHidden;
        mContext = createThemedContext(context);
        initFloaty();
        setupListeners();
        mRecorder = GlobalActionRecorder.getSingleton(context);
        mRecorder.addOnStateChangedListener(this);
        AutoJs.getInstance().getLayoutInspector().addCaptureAvailableListener(this);
    }

    /**
     * 悬浮窗用的是 {@code FloatyService} 的 context，它不归 AppCompat 管，
     * 所以直接 inflate 会拿到**系统**主题（系统深色、应用浅色时，菜单会渲染成深色底 + 粉色 error 圆，
     * 2026-09-17 真机踩到）。这里按应用自己的规则（跟随系统 / 手动）把配置写进一个子 context，
     * 再套 AppTheme —— 与 {@code ThemeColorManagerCompat.applyThemeMode()} 同一条规则。
     */
    private static Context createThemedContext(Context base) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (isAppInDarkTheme(base) ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO);
        return new ContextThemeWrapper(base.createConfigurationContext(config), R.style.AppTheme);
    }

    /** 应用自己的深色判定：跟随系统优先，否则看应用内开关。 */
    private static boolean isAppInDarkTheme(Context base) {
        if (Pref.isFollowSystemThemeEnabled()) {
            int nightMode = base.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return nightMode == Configuration.UI_MODE_NIGHT_YES;
        }
        return Pref.isNightModeEnabled();
    }

    private void setupListeners() {
        mWindow.setOnActionViewClickListener(v -> {
            if (mState == STATE_RECORDING) {
                stopRecord();
            } else if (mWindow.isExpanded()) {
                mWindow.collapse();
            } else {
                mCaptureDeferred = new DeferredObject<>();
                if (!AutoJs.getInstance().getLayoutInspector().captureCurrentWindow()) {
                    mCaptureDeferred.reject(null);
                }
                mWindow.expand();
            }
        });
    }

    private void initFloaty() {
        mWindow = new CircularMenuWindow(mContext, new CircularMenuFloaty() {

            @Override
            public View inflateActionView(Context context, CircularMenuWindow window) {
                // 用 mContext（已按应用主题包好）而不是传进来的裸 context，否则主题属性会落到系统主题上
                View actionView = View.inflate(mContext, R.layout.circular_action_view, null);
                mActionViewIcon = actionView.findViewById(R.id.icon);
                return actionView;
            }

            @Override
            public CircularActionMenu inflateMenuItems(Context context, CircularMenuWindow window) {
                CircularActionMenu menu = (CircularActionMenu) View.inflate(mContext, R.layout.circular_action_menu, null);
                bindMenuItem(menu, R.id.script_list, CircularMenu.this::showScriptList);
                bindMenuItem(menu, R.id.record, CircularMenu.this::startRecord);
                bindMenuItem(menu, R.id.layout_inspect, CircularMenu.this::inspectLayout);
                bindMenuItem(menu, R.id.stop_all_scripts, CircularMenu.this::stopAllScripts);
                bindMenuItem(menu, R.id.settings, CircularMenu.this::settings);
                return menu;
            }
        });
        mWindow.setKeepToSideHiddenWidthRadio(0.25f);
        mWindow.setOnCloseListener(this::onWindowClosed);
        mWindow.setTemporarilyHidden(mInitiallyHidden);
        FloatyService.addWindow(mWindow);
    }

    @Override
    public void setTemporarilyHidden(boolean hidden) {
        mWindow.setTemporarilyHidden(hidden);
    }

    private void bindMenuItem(View menu, int id, Runnable action) {
        View view = menu.findViewById(id);
        if (view != null) {
            view.setOnClickListener(v -> action.run());
        }
    }


    void showScriptList() {
        mWindow.collapse();
        ExplorerView explorerView = new ExplorerView(mContext);
        explorerView.setExplorer(Explorers.workspace(), ExplorerDirPage.createRoot(Pref.getScriptDirPath()));
        explorerView.setDirectorySpanSize(2);
        final MaterialDialog dialog = new ThemeColorMaterialDialogBuilder(mContext)
                .title(R.string.text_run_script)
                .customView(explorerView, false)
                .positiveText(R.string.cancel)
                .build();
        explorerView.setOnItemOperatedListener(file -> dialog.dismiss());
        explorerView.setOnItemClickListener((view, item) -> Scripts.INSTANCE.run(item.toScriptFile()));
        DialogUtils.showDialog(dialog);

    }

    void startRecord() {
        mWindow.collapse();
        if (!RootTool.isRootAvailable()) {
            DialogUtils.showDialog(new NotAskAgainDialog.Builder(mContext, "CircularMenu.root")
                    .title(R.string.text_device_not_rooted)
                    .content(R.string.prompt_device_not_rooted)
                    .neutralText(R.string.text_device_rooted)
                    .positiveText(R.string.ok)
                    .onNeutral(((dialog, which) -> mRecorder.start()))
                    .build());
        } else {
            mRecorder.start();

        }
    }

    private void setState(int state) {
        int previousState = mState;
        mState = state;
        mActionViewIcon.setImageResource(mState == STATE_RECORDING ? R.drawable.ic_ali_record :
                IC_ACTION_VIEW);
        // 悬浮球底色：平时是品牌浅色底盘（跟布局里的 background 一致），录制中换成主题的 error 色。
        // 原来用的是写死的 circle_white / circle_red（见 2026-09-17 的 Miuix 化）。
        mActionViewIcon.setBackgroundResource(mState == STATE_RECORDING ? R.drawable.circular_menu_item_danger :
                R.drawable.ai_js_pro_float_icon_background);
        int padding = (int) mContext.getResources().getDimension(mState == STATE_RECORDING ?
                R.dimen.padding_circular_menu_recording : R.dimen.padding_circular_menu_normal);
        mActionViewIcon.setPadding(padding, padding, padding, padding);
        EventBus.getDefault().post(new StateChangeEvent(mState, previousState));

    }

    private void stopRecord() {
        mRecorder.stop();
    }

    void inspectLayout() {
        mWindow.collapse();
        if (showMiuixActionMenu(mContext.getString(R.string.text_inspect_layout),
                new int[]{R.id.layout_bounds, R.id.layout_hierarchy, R.id.layout_diagnostics},
                new int[]{R.drawable.ic_circular_menu_bounds, R.drawable.ic_circular_menu_hierarchy, R.drawable.ic_circular_menu_hierarchy},
                new String[]{mContext.getString(R.string.text_inspect_layout_bounds),
                        mContext.getString(R.string.text_inspect_layout_hierarchy),
                        mContext.getString(R.string.text_layout_inspector_diagnostics)})) {
            return;
        }
        mLayoutInspectDialog = new OperationDialogBuilder(mContext)
                .item(R.id.layout_bounds, R.drawable.ic_circular_menu_bounds, R.string.text_inspect_layout_bounds)
                .item(R.id.layout_hierarchy, R.drawable.ic_circular_menu_hierarchy,
                        R.string.text_inspect_layout_hierarchy)
                .item(R.id.layout_diagnostics, R.drawable.ic_circular_menu_hierarchy,
                        R.string.text_layout_inspector_diagnostics)
                .bindItemClick(this)
                .title(R.string.text_inspect_layout)
                .build();
        DialogUtils.showDialog(mLayoutInspectDialog);
    }

    /**
     * 用 miuix 源集的列表弹框（居中卡片、26dp 圆角）弹一个菜单；动作仍由 {@link #onOperationItemClick(int)} 分发。
     *
     * @return 是否已用 Miuix 展示；false 时调用方回落旧的 `OperationDialogBuilder`
     */
    private boolean showMiuixActionMenu(String title, int[] ids, int[] iconRes, String[] labels) {
        try {
            Class<?> host = Class.forName("com.jdkshen.aijspro.ui.floating.MiuixActionMenuHost");
            Object handled = host.getMethod("show", Context.class, String.class, int[].class,
                            int[].class, String[].class, java.util.function.IntConsumer.class)
                    .invoke(null, mContext, title, ids, iconRes, labels,
                            (java.util.function.IntConsumer) this::onOperationItemClick);
            return Boolean.TRUE.equals(handled);
        } catch (Throwable e) {
            // 回落要有迹可循，不静默
            android.util.Log.w("CircularMenu", "Miuix 列表弹框不可用，回落旧实现", e);
            return false;
        }
    }

    void showLayoutBounds() {
        inspectLayout(LayoutBoundsFloatyWindow::new);
    }


    void showLayoutHierarchy() {
        inspectLayout(LayoutHierarchyFloatyWindow::new);
    }

    private void inspectLayout(Func1<NodeInfo, FloatyWindow> windowCreator) {
        if (mLayoutInspectDialog != null) {
            mLayoutInspectDialog.dismiss();
            mLayoutInspectDialog = null;
        }
        if (AccessibilityService.Companion.getInstance() == null) {
            AutoJs.getInstance().getLayoutInspector().captureCurrentWindow();
            LayoutInspectorDiagnostics.show(mContext, AutoJs.getInstance().getLayoutInspector());
            return;
        }
        if (mCaptureDeferred == null) {
            mCaptureDeferred = new DeferredObject<>();
            if (!AutoJs.getInstance().getLayoutInspector().captureCurrentWindow()) {
                mCaptureDeferred.reject(null);
            }
        }
        MaterialDialog progress = DialogUtils.showDialog(new ThemeColorMaterialDialogBuilder(mContext)
                .content(R.string.text_layout_inspector_is_dumping)
                .canceledOnTouchOutside(false)
                .progress(true, 0)
                .build());
        mCaptureDeferred.promise()
                .then(capture -> {
                    mActionViewIcon.post(() -> {
                                if (!progress.isCancelled()) {
                                    progress.dismiss();
                                    FloatyService.addWindow(windowCreator.call(capture));
                                }
                            }
                    );
                }, err -> mActionViewIcon.post(() -> {
                    progress.dismiss();
                    LayoutInspectorDiagnostics.show(mContext, AutoJs.getInstance().getLayoutInspector());
                }));
    }


    void stopAllScripts() {
        mWindow.collapse();
        AutoJs.getInstance().getScriptEngineService().stopAllAndToast();
    }


    @Override
    public void onCaptureAvailable(NodeInfo capture) {
        if (mCaptureDeferred != null && mCaptureDeferred.isPending()) {
            if (capture == null) mCaptureDeferred.reject(null);
            else mCaptureDeferred.resolve(capture);
        }
    }


    void settings() {
        mWindow.collapse();
        mRunningPackage = AutoJs.getInstance().getInfoProvider().getLatestPackageByUsageStatsIfGranted();
        mRunningActivity = AutoJs.getInstance().getInfoProvider().getLatestActivity();
        // 后两项要带上「当前包名 / 当前 Activity」的实时值，所以文案在这里拼好，两条路径共用
        int[] ids = {R.id.accessibility_service, R.id.package_name, R.id.class_name,
                R.id.open_launcher, R.id.pointer_location, R.id.floating_menu_exit};
        int[] icons = {R.drawable.ic_service_green, R.drawable.ic_ali_app, R.drawable.ic_ali_android,
                R.drawable.ic_android_eat_js, R.drawable.ic_zoom_out_map_white_24dp,
                R.drawable.ic_close_white_48dp};
        String[] labels = {mContext.getString(R.string.text_accessibility_settings),
                mContext.getString(R.string.text_current_package) + mRunningPackage,
                mContext.getString(R.string.text_current_activity) + mRunningActivity,
                mContext.getString(R.string.text_open_main_activity),
                mContext.getString(R.string.text_pointer_location),
                mContext.getString(R.string.text_exit_floating_window)};
        if (showMiuixActionMenu(mContext.getString(R.string.text_more), ids, icons, labels)) {
            return;
        }
        mSettingsDialog = new OperationDialogBuilder(mContext)
                .item(ids[0], icons[0], labels[0])
                .item(ids[1], icons[1], labels[1])
                .item(ids[2], icons[2], labels[2])
                .item(ids[3], icons[3], labels[3])
                .item(ids[4], icons[4], labels[4])
                .item(ids[5], icons[5], labels[5])
                .bindItemClick(this)
                .title(R.string.text_more)
                .build();
        DialogUtils.showDialog(mSettingsDialog);
    }


    void enableAccessibilityService() {
        dismissSettingsDialog();
        AccessibilityServiceTool.enableAccessibilityService();
    }


    private void dismissSettingsDialog() {
        if (mSettingsDialog == null)
            return;
        mSettingsDialog.dismiss();
        mSettingsDialog = null;
    }

    void copyPackageName() {
        dismissSettingsDialog();
        if (TextUtils.isEmpty(mRunningPackage))
            return;
        ClipboardUtil.setClip(mContext, mRunningPackage);
        Toast.makeText(mContext, R.string.text_already_copy_to_clip, Toast.LENGTH_SHORT).show();
    }

    void copyActivityName() {
        dismissSettingsDialog();
        if (TextUtils.isEmpty(mRunningActivity))
            return;
        ClipboardUtil.setClip(mContext, mRunningActivity);
        Toast.makeText(mContext, R.string.text_already_copy_to_clip, Toast.LENGTH_SHORT).show();
    }

    void openLauncher() {
        dismissSettingsDialog();
        mContext.startActivity(new Intent(mContext, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    void togglePointerLocation() {
        dismissSettingsDialog();
        RootTool.togglePointerLocation();
    }

    public void close() {
        dismissSettingsDialog();
        try {
            mWindow.close();
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } finally {
            onWindowClosed();
        }
    }

    private void onWindowClosed() {
        if (mClosed) return;
        mClosed = true;
        FloatyWindowManger.onCircularMenuClosed(this);
        EventBus.getDefault().post(new StateChangeEvent(STATE_CLOSED, mState));
        mState = STATE_CLOSED;
        if (mRecorder != null) mRecorder.removeOnStateChangedListener(this);
        AutoJs.getInstance().getLayoutInspector().removeCaptureAvailableListener(this);
    }


    @Override
    public void onOperationItemClick(int id) {
        switch (id) {
            case R.id.layout_bounds:
                showLayoutBounds();
                break;
            case R.id.layout_hierarchy:
                showLayoutHierarchy();
                break;
            case R.id.layout_diagnostics:
                if (mLayoutInspectDialog != null) {
                    mLayoutInspectDialog.dismiss();
                    mLayoutInspectDialog = null;
                }
                LayoutInspectorDiagnostics.show(mContext, AutoJs.getInstance().getLayoutInspector());
                break;
            case R.id.accessibility_service:
                enableAccessibilityService();
                break;
            case R.id.package_name:
                copyPackageName();
                break;
            case R.id.class_name:
                copyActivityName();
                break;
            case R.id.open_launcher:
                openLauncher();
                break;
            case R.id.pointer_location:
                togglePointerLocation();
                break;
            case R.id.floating_menu_exit:
                close();
                break;
        }
    }


    @Override
    public void onStart() {
        setState(STATE_RECORDING);
    }

    @Override
    public void onStop() {
        setState(STATE_NORMAL);
    }

    @Override
    public void onPause() {
    }

    @Override
    public void onResume() {

    }
}
