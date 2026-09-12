package com.jdkshen.aijspro.ui.main.drawer;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.afollestad.materialdialogs.MaterialDialog;
import com.stardust.app.AppOpsKt;
import com.stardust.app.GlobalAppContext;
import com.stardust.notification.NotificationListenerService;
import com.stardust.util.IntentUtil;
import com.stardust.view.accessibility.AccessibilityService;

import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.external.foreground.ForegroundService;
import com.jdkshen.aijspro.network.NodeBB;
import com.jdkshen.aijspro.network.UserService;
import com.jdkshen.aijspro.network.entity.user.User;
import com.jdkshen.aijspro.pluginclient.DevPluginService;
import com.jdkshen.aijspro.tool.AccessibilityServiceTool;
import com.jdkshen.aijspro.tool.Observers;
import com.jdkshen.aijspro.tool.WifiTool;
import com.jdkshen.aijspro.ui.BaseActivity;
import com.jdkshen.aijspro.ui.common.NotAskAgainDialog;
import com.jdkshen.aijspro.ui.floating.CircularMenu;
import com.jdkshen.aijspro.ui.floating.FloatyWindowManger;
import com.jdkshen.aijspro.ui.main.MainActivity;
import com.jdkshen.aijspro.ui.settings.SettingsActivity;
import com.jdkshen.aijspro.ui.terminal.EmbeddedTerminalActivity;
import com.jdkshen.aijspro.ui.update.UpdateCheckDialog;
import com.jdkshen.aijspro.ui.user.LoginActivity;
import com.jdkshen.aijspro.ui.user.WebActivity;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/**
 * 抽屉宿主（主界面的侧栏）。
 *
 * <p>界面已统一到 Miuix：{@link MiuixDrawerHost#createView(DrawerFragment)} 返回 Compose 视图，
 * 这里的 public 方法就是 Miuix 抽屉回调的整套接口（读取状态 / 切换开关 / 打开页面）。
 * 旧的 XML 抽屉（{@code fragment_drawer}、DrawerMenuAdapter、菜单项与主题色入口）已随 UI 统一删除。
 *
 * <p>仍留在本类里的是"动作与状态"部分：无障碍开启（含 Shizuku/Root 快速通道）、悬浮窗、
 * 前台服务、通知/使用情况权限、夜间模式、远程连接、检查更新与退出——这些与视图无关，
 * 换界面不必重写。
 */
public class DrawerFragment extends androidx.fragment.app.Fragment {

    private static final String URL_PROJECT_HOME = "https://github.com/Jdkshen/ai.js-pro";

    private Disposable mConnectionStateDisposable;
    private View mMiuixDrawerView;

    /** 抽屉里显示的用户名（未登录为 null）。 */
    private String mDrawerUserName;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mConnectionStateDisposable = DevPluginService.getInstance().connectionState()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(state -> {
                    if (state.getException() != null) {
                        showMessage(state.getException().getMessage());
                    }
                });
        EventBus.getDefault().register(this);
        refreshDrawerUserName();
    }

    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mMiuixDrawerView = MiuixDrawerHost.createView(this);
        return mMiuixDrawerView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // 悬浮窗要等窗口/服务就绪后再恢复：在 onCreateView 里做可能静默失败。
        if (Pref.isFloatingMenuShown() && !FloatyWindowManger.isCircularMenuShowing()) {
            FloatyWindowManger.showCircularMenuIfNeeded();
        }
        refreshMiuixDrawer();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mConnectionStateDisposable != null) {
            mConnectionStateDisposable.dispose();
        }
        EventBus.getDefault().unregister(this);
    }

    // ---- Miuix 抽屉回调（MiuixDrawerHost 调用的就是这些）----

    public String getDrawerUserName() {
        return mDrawerUserName;
    }

    public void openUserArea() {
        loginOrShowUserInfo();
    }

    public boolean isAccessibilityEnabled() {
        return AccessibilityServiceTool.isAccessibilityServiceEnabled(getActivity());
    }

    public void setAccessibilityEnabled(boolean checked) {
        boolean enabled = isAccessibilityEnabled();
        if (checked && !enabled) {
            enableAccessibilityService();
        } else if (!checked && enabled) {
            if (!AccessibilityService.Companion.disable()) {
                AccessibilityServiceTool.goToAccessibilitySetting();
            }
        }
    }

    public boolean isStableModeEnabled() {
        return Pref.isStableModeEnabled();
    }

    public void setStableModeEnabled(boolean enabled) {
        PreferenceManager.getDefaultSharedPreferences(getContext())
                .edit().putBoolean(getString(R.string.key_stable_mode), enabled).apply();
        if (enabled) {
            showStableModePromptIfNeeded();
        }
    }

    public boolean isNotificationEnabled() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2
                && NotificationListenerService.Companion.getInstance() != null;
    }

    public void openNotificationSettings(boolean checked) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            return;
        }
        boolean enabled = NotificationListenerService.Companion.getInstance() != null;
        if ((checked && !enabled) || (!checked && enabled)) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }

    public boolean isUsageStatsEnabled() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                && AppOpsKt.isOpPermissionGranted(getContext(), android.app.AppOpsManager.OPSTR_GET_USAGE_STATS);
    }

    public void openUsageStats(boolean checked) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        boolean enabled = isUsageStatsEnabled();
        if (checked && !enabled) {
            if (new NotAskAgainDialog.Builder(getContext(), "DrawerFragment.usage_stats")
                    .title(R.string.text_usage_stats_permission)
                    .content(R.string.description_usage_stats_permission)
                    .positiveText(R.string.ok)
                    .dismissListener(dialog -> IntentUtil.requestAppUsagePermission(getContext()))
                    .show() == null) {
                IntentUtil.requestAppUsagePermission(getContext());
            }
        }
        if (!checked && enabled) {
            IntentUtil.requestAppUsagePermission(getContext());
        }
    }

    public boolean isForegroundServicePrefEnabled() {
        return Pref.isForegroundServiceEnabled();
    }

    public void setForegroundServiceEnabled(boolean checked) {
        if (checked) {
            ForegroundService.start(GlobalAppContext.get());
        } else {
            ForegroundService.stop(GlobalAppContext.get());
        }
    }

    public boolean isFloatingWindowShowing() {
        return FloatyWindowManger.isCircularMenuShowing() || Pref.isFloatingMenuShown();
    }

    public void setFloatingWindowEnabled(boolean checked) {
        boolean showing = FloatyWindowManger.isCircularMenuShowing();
        if (getActivity() != null && !getActivity().isFinishing()) {
            Pref.setFloatingMenuShown(checked);
        }
        if (checked && !showing) {
            FloatyWindowManger.showCircularMenu();
            enableAccessibilityServiceByRootIfNeeded();
        } else if (!checked && showing) {
            FloatyWindowManger.hideCircularMenu();
        }
    }

    public boolean isVolumeDownControlEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(getContext())
                .getBoolean(getString(R.string.key_use_volume_control_record), false);
    }

    public void setVolumeDownControlEnabled(boolean enabled) {
        PreferenceManager.getDefaultSharedPreferences(getContext())
                .edit().putBoolean(getString(R.string.key_use_volume_control_record), enabled).apply();
    }

    public boolean isNightModePrefEnabled() {
        return Pref.isNightModeEnabled();
    }

    public void setNightModePrefEnabled(boolean enabled) {
        // Persist so the choice survives restarts (setLocalNightMode alone is not sticky).
        if (getContext() != null) {
            PreferenceManager.getDefaultSharedPreferences(getContext())
                    .edit().putBoolean(getString(R.string.key_night_mode), enabled).apply();
        }
        if (getActivity() instanceof BaseActivity) {
            ((BaseActivity) getActivity()).setNightModeEnabled(enabled);
        }
    }

    public boolean isFollowSystemEnabled() {
        return Pref.isFollowSystemThemeEnabled();
    }

    public void setFollowSystemEnabled(boolean enabled) {
        Pref.setFollowSystemThemeEnabled(enabled);
    }

    public boolean isRemoteConnected() {
        return DevPluginService.getInstance().isConnected();
    }

    public void openRemoteConnection() {
        inputRemoteHost();
    }

    public void disconnectRemote() {
        DevPluginService.getInstance().disconnectIfNeeded();
    }

    public void openTerminal() {
        if (getContext() == null) {
            return;
        }
        Intent intent = new Intent(getContext(), EmbeddedTerminalActivity.class)
                .putExtra(EmbeddedTerminalActivity.EXTRA_WORKING_DIRECTORY, Pref.getScriptDirPath());
        startActivity(intent);
    }

    public void openSettingsFromDrawer() {
        if (getContext() != null) {
            startActivity(new Intent(getContext(), SettingsActivity.class));
        }
    }

    public void checkForUpdatesFromDrawer() {
        // 统一走设置页同一条链路（UpdateCheckDialog）：它会读取「更新源」设置（自建源），
        // 出错时还会带上具体原因。
        if (getActivity() == null) {
            return;
        }
        new UpdateCheckDialog(getActivity()).show();
    }

    public void exitAppFromDrawer() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).exitCompletely();
        }
    }

    public void openServiceStatus() {
        if (getContext() == null) {
            return;
        }
        startActivity(new Intent(getContext(), com.jdkshen.aijspro.ui.service.ServiceStatusActivity.class));
    }

    // ---- 内部动作 ----

    private void loginOrShowUserInfo() {
        UserService.getInstance()
                .me()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(user -> {
                            if (getActivity() == null)
                                return;
                            Intent intent = new Intent(getContext(), WebActivity.class);
                            intent.putExtra(WebActivity.EXTRA_URL, NodeBB.url("user/" + user.getUserslug()));
                            intent.putExtra(Intent.EXTRA_TITLE, user.getUsername());
                            startActivity(intent);
                        },
                        error -> {
                            if (getActivity() == null)
                                return;
                            startActivity(new Intent(getActivity(), LoginActivity.class));
                        }
                );
    }

    /** 抽屉里显示的用户名：异步取一次，失败/未登录按 null 处理（界面显示「未登录」）。 */
    private void refreshDrawerUserName() {
        UserService.getInstance()
                .me()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(user -> {
                            mDrawerUserName = user == null ? null : user.getUsername();
                            refreshMiuixDrawer();
                        },
                        error -> {
                            mDrawerUserName = null;
                            refreshMiuixDrawer();
                        });
    }

    private void enableAccessibilityService() {
        if (AccessibilityServiceTool.isFastEnableAvailable()) {
            enableAccessibilityServiceFast();
            return;
        }
        if (!Pref.shouldEnableAccessibilityServiceByRoot()) {
            AccessibilityServiceTool.goToAccessibilitySetting();
            return;
        }
        enableAccessibilityServiceByRoot();
    }

    private void enableAccessibilityServiceFast() {
        Observable.fromCallable(() ->
                        AccessibilityServiceTool.enableAccessibilityServiceByShizukuAndWaitFor(4000, true))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(succeed -> {
                    if (getContext() == null) return;
                    if (succeed) {
                        Toast.makeText(getContext(), R.string.text_enable_accessibility_service_fast_success,
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(getContext(), R.string.text_enable_accessibility_service_fast_failed,
                                Toast.LENGTH_SHORT).show();
                        AccessibilityServiceTool.goToAccessibilitySetting();
                    }
                    refreshMiuixDrawer();
                }, error -> {
                    if (getContext() == null) return;
                    Toast.makeText(getContext(), R.string.text_enable_accessibility_service_fast_failed,
                            Toast.LENGTH_SHORT).show();
                    AccessibilityServiceTool.goToAccessibilitySetting();
                });
    }

    private void enableAccessibilityServiceByRoot() {
        Observable.fromCallable(() -> AccessibilityServiceTool.enableAccessibilityServiceByRootAndWaitFor(4000))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(succeed -> {
                    if (!succeed) {
                        Toast.makeText(getContext(), R.string.text_enable_accessibitliy_service_by_root_failed, Toast.LENGTH_SHORT).show();
                        AccessibilityServiceTool.goToAccessibilitySetting();
                    }
                    refreshMiuixDrawer();
                });
    }

    @SuppressLint("CheckResult")
    private void enableAccessibilityServiceByRootIfNeeded() {
        Observable.fromCallable(() -> Pref.shouldEnableAccessibilityServiceByRoot() && !isAccessibilityServiceEnabled())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(needed -> {
                    if (needed) {
                        enableAccessibilityServiceByRoot();
                    }
                });
    }

    private void inputRemoteHost() {
        String host = Pref.getServerAddressOrDefault(WifiTool.getRouterIp(getActivity()));
        new MaterialDialog.Builder(getActivity())
                .title(R.string.text_server_address)
                .input("", host, (dialog, input) -> {
                    Pref.saveServerAddress(input.toString());
                    DevPluginService.getInstance().connectToServer(input.toString())
                            .subscribe(Observers.emptyConsumer(), this::onConnectException);
                })
                .neutralText(R.string.text_help)
                .onNeutral((dialog, which) -> IntentUtil.browse(getActivity(), URL_PROJECT_HOME))
                .show();
    }

    private void onConnectException(Throwable e) {
        Toast.makeText(GlobalAppContext.get(), getString(R.string.error_connect_to_remote, e.getMessage()),
                Toast.LENGTH_LONG).show();
    }

    private void showStableModePromptIfNeeded() {
        new NotAskAgainDialog.Builder(getContext(), "DrawerFragment.stable_mode")
                .title(R.string.text_stable_mode)
                .content(R.string.description_stable_mode)
                .positiveText(R.string.ok)
                .show();
    }

    private boolean isAccessibilityServiceEnabled() {
        return AccessibilityServiceTool.isAccessibilityServiceEnabled(getActivity());
    }

    private void refreshMiuixDrawer() {
        if (mMiuixDrawerView == null) return;
        Object refresh = mMiuixDrawerView.getTag();
        if (refresh instanceof Runnable) {
            ((Runnable) refresh).run();
        }
    }

    private void showMessage(CharSequence text) {
        if (getContext() == null)
            return;
        Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show();
    }

    // ---- 事件 ----

    @Subscribe
    public void onCircularMenuStateChange(CircularMenu.StateChangeEvent event) {
        refreshMiuixDrawer();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onLoginStateChange(UserService.LoginStateChange change) {
        refreshDrawerUserName();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDrawerOpen(MainActivity.DrawerOpenEvent event) {
        refreshMiuixDrawer();
    }
}
