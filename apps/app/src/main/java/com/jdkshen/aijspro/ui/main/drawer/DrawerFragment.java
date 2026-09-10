package com.jdkshen.aijspro.ui.main.drawer;

import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.afollestad.materialdialogs.MaterialDialog;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomViewTarget;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;
import com.stardust.app.AppOpsKt;
import com.stardust.app.GlobalAppContext;
import com.stardust.notification.NotificationListenerService;

import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.BuildConfig;
import com.jdkshen.aijspro.external.foreground.ForegroundService;
import com.jdkshen.aijspro.network.UserService;
import com.jdkshen.aijspro.tool.Observers;
import com.jdkshen.aijspro.ui.BaseActivity;
import com.jdkshen.aijspro.ui.common.NotAskAgainDialog;
import com.jdkshen.aijspro.ui.floating.CircularMenu;
import com.jdkshen.aijspro.ui.floating.FloatyWindowManger;
import com.jdkshen.aijspro.ui.terminal.EmbeddedTerminalActivity;
import com.jdkshen.aijspro.network.NodeBB;
import com.jdkshen.aijspro.network.VersionService;
import com.jdkshen.aijspro.network.api.UserApi;
import com.jdkshen.aijspro.network.entity.user.User;
import com.jdkshen.aijspro.network.entity.VersionInfo;
import com.jdkshen.aijspro.tool.SimpleObserver;
import com.jdkshen.aijspro.ui.main.MainActivity;
import com.jdkshen.aijspro.ui.main.community.CommunityFragment;
import com.jdkshen.aijspro.ui.user.LoginActivity;
import com.jdkshen.aijspro.ui.settings.SettingsActivity;
import com.jdkshen.aijspro.ui.update.UpdateInfoDialogBuilder;
import com.jdkshen.aijspro.ui.user.WebActivity;
import com.jdkshen.aijspro.ui.widget.AvatarView;

import com.stardust.theme.ThemeColorManager;

import com.jdkshen.aijspro.theme.ThemeColorManagerCompat;

import com.stardust.view.accessibility.AccessibilityService;

import com.jdkshen.aijspro.pluginclient.DevPluginService;
import com.jdkshen.aijspro.tool.AccessibilityServiceTool;
import com.jdkshen.aijspro.tool.WifiTool;

import com.stardust.util.IntentUtil;

import com.jdkshen.aijspro.ui.widget.BackgroundTarget;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Arrays;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;


/**
 * Created by Stardust on 2017/1/30.
 * TODO these codes are so ugly!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
 */
public class DrawerFragment extends androidx.fragment.app.Fragment {

    private static final String URL_PROJECT_HOME = "https://github.com/Jdkshen/ai.js-pro";

    View mHeaderView;
    TextView mUserName;
    AvatarView mAvatar;
    View mShadow;
    View mDefaultCover;
    RecyclerView mDrawerMenu;


    private DrawerMenuItem mConnectionItem = new DrawerMenuItem(R.drawable.ic_connect_to_pc, R.string.debug, 0, this::connectOrDisconnectToRemote);
    private DrawerMenuItem mAccessibilityServiceItem = new DrawerMenuItem(R.drawable.ic_service_green, R.string.text_accessibility_service, 0, this::enableOrDisableAccessibilityService);
    private DrawerMenuItem mStableModeItem = new DrawerMenuItem(R.drawable.ic_stable, R.string.text_stable_mode, R.string.key_stable_mode, null) {
        @Override
        public void setChecked(boolean checked) {
            super.setChecked(checked);
            if (checked)
                showStableModePromptIfNeeded();
        }
    };

    private DrawerMenuItem mNotificationPermissionItem = new DrawerMenuItem(R.drawable.ic_ali_notification, R.string.text_notification_permission, 0, this::goToNotificationServiceSettings);
    private DrawerMenuItem mUsageStatsPermissionItem = new DrawerMenuItem(R.drawable.ic_ali_notification, R.string.text_usage_stats_permission, 0, this::goToUsageStatsSettings);
    private DrawerMenuItem mForegroundServiceItem = new DrawerMenuItem(R.drawable.ic_service_green, R.string.text_foreground_service, R.string.key_foreground_servie, this::toggleForegroundService);

    private DrawerMenuItem mFloatingWindowItem = new DrawerMenuItem(R.drawable.ic_robot_64, R.string.text_floating_window, 0, this::showOrDismissFloatingWindow);
    private DrawerMenuItem mCheckForUpdatesItem = new DrawerMenuItem(R.drawable.ic_check_for_updates, R.string.text_check_for_updates, this::checkForUpdates);

    private DrawerMenuAdapter mDrawerMenuAdapter;
    private Disposable mConnectionStateDisposable;
    private CommunityDrawerMenu mCommunityDrawerMenu = new CommunityDrawerMenu();
    private View mMiuixDrawerView;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mConnectionStateDisposable = DevPluginService.getInstance().connectionState()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(state -> {
                    if (mConnectionItem != null) {
                        setChecked(mConnectionItem, state.getState() == DevPluginService.State.CONNECTED);
                        setProgress(mConnectionItem, state.getState() == DevPluginService.State.CONNECTING);
                    }
                    if (state.getException() != null) {
                        showMessage(state.getException().getMessage());
                    }
                });
        EventBus.getDefault().register(this);

    }

    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        if (BuildConfig.MIUIX_PILOT) {
            View miuixView = tryCreateMiuixDrawerView();
            if (miuixView != null) {
                mMiuixDrawerView = miuixView;
                // setUpViews() is skipped in pilot mode, restore the floating
                // window from the persisted preference like the legacy drawer did.
                if (Pref.isFloatingMenuShown()) {
                    FloatyWindowManger.showCircularMenuIfNeeded();
                }
                return miuixView;
            }
        }
        View view = inflater.inflate(R.layout.fragment_drawer, container, false);
        mHeaderView = view.findViewById(R.id.header);
        mUserName = view.findViewById(R.id.username);
        mAvatar = view.findViewById(R.id.avatar);
        mShadow = view.findViewById(R.id.shadow);
        mDefaultCover = view.findViewById(R.id.default_cover);
        mDrawerMenu = view.findViewById(R.id.drawer_menu);
        view.findViewById(R.id.avatar).setOnClickListener(v -> loginOrShowUserInfo());
        setUpViews();
        return view;
    }

    private View tryCreateMiuixDrawerView() {
        try {
            Class<?> clazz = Class.forName("com.jdkshen.aijspro.ui.main.drawer.MiuixDrawerHost");
            java.lang.reflect.Method method = clazz.getMethod("createView", DrawerFragment.class);
            return (View) method.invoke(null, this);
        } catch (Throwable ignored) {
            return null;
        }
    }

    void setUpViews() {
        ThemeColorManager.addViewBackground(mHeaderView);
        initMenuItems();
        if (Pref.isFloatingMenuShown()) {
            FloatyWindowManger.showCircularMenuIfNeeded();
            setChecked(mFloatingWindowItem, true);
        }
        setChecked(mConnectionItem, DevPluginService.getInstance().isConnected());
        if (Pref.isForegroundServiceEnabled()) {
            ForegroundService.start(GlobalAppContext.get());
            setChecked(mForegroundServiceItem, true);
        }
    }

    private void initMenuItems() {
        mDrawerMenuAdapter = new DrawerMenuAdapter(new ArrayList<>(Arrays.asList(
                new DrawerMenuGroup(R.string.text_service),
                new DrawerMenuItem(R.drawable.ic_service_green, R.string.text_quick_service,
                        holder -> openServiceStatus()),
                mAccessibilityServiceItem,
                mStableModeItem,
                mNotificationPermissionItem,
                mForegroundServiceItem,
                mUsageStatsPermissionItem,

                new DrawerMenuGroup(R.string.text_script_record),
                mFloatingWindowItem,
                new DrawerMenuItem(R.drawable.ic_volume, R.string.text_volume_down_control, R.string.key_use_volume_control_record, null),

                new DrawerMenuGroup(R.string.text_others),
                mConnectionItem,
                new DrawerMenuItem(R.drawable.ic_personalize, R.string.text_theme_color, this::openThemeColorSettings),
                new DrawerMenuItem(R.drawable.ic_night_mode, R.string.text_night_mode, R.string.key_night_mode, this::toggleNightMode),
                mCheckForUpdatesItem
        )));
        mDrawerMenu.setAdapter(mDrawerMenuAdapter);
        mDrawerMenu.setLayoutManager(new LinearLayoutManager(getContext()));
    }

    public void openServiceStatus() {
        if (getContext() == null) {
            return;
        }
        startActivity(new Intent(getContext(), com.jdkshen.aijspro.ui.service.ServiceStatusActivity.class));
    }

    // ---- Miuix drawer bridge (only called when BuildConfig.MIUIX_PILOT) ----

    public String getDrawerUserName() {
        return mUserName != null ? mUserName.getText().toString() : null;
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
                && AppOpsKt.isOpPermissionGranted(getContext(), AppOpsManager.OPSTR_GET_USAGE_STATS);
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

    public boolean isFollowSystemEnabled() {
        return Pref.isFollowSystemThemeEnabled();
    }

    public void setFollowSystemEnabled(boolean enabled) {
        Pref.setFollowSystemThemeEnabled(enabled);
    }

    public void openTerminal() {
        if (getContext() == null) {
            return;
        }
        Intent intent = new Intent(getContext(), EmbeddedTerminalActivity.class)
                .putExtra(EmbeddedTerminalActivity.EXTRA_WORKING_DIRECTORY, Pref.getScriptDirPath());
        startActivity(intent);
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

    public boolean isRemoteConnected() {
        return DevPluginService.getInstance().isConnected();
    }

    public void openRemoteConnection() {
        inputRemoteHost();
    }

    public void disconnectRemote() {
        DevPluginService.getInstance().disconnectIfNeeded();
    }

    public void checkForUpdatesFromDrawer() {
        VersionService.getInstance().checkForUpdates()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<VersionInfo>() {

                    @Override
                    public void onNext(@io.reactivex.annotations.NonNull VersionInfo versionInfo) {
                        if (getActivity() == null) {
                            return;
                        }
                        if (versionInfo.isNewer()) {
                            new UpdateInfoDialogBuilder(getActivity(), versionInfo).show();
                        } else {
                            Toast.makeText(GlobalAppContext.get(), R.string.text_is_latest_version,
                                    Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onError(@io.reactivex.annotations.NonNull Throwable e) {
                        e.printStackTrace();
                        Toast.makeText(GlobalAppContext.get(), R.string.text_check_update_error,
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    public void openSettingsFromDrawer() {
        if (getContext() != null) {
            startActivity(new Intent(getContext(), SettingsActivity.class));
        }
    }

    public void exitAppFromDrawer() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).exitCompletely();
        }
    }

    private void openPage(int position) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).showPage(position);
        }
    }

    private void openHome() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).showHome();
        }
    }


    void loginOrShowUserInfo() {
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


    void enableOrDisableAccessibilityService(DrawerMenuItemViewHolder holder) {
        boolean isAccessibilityServiceEnabled = isAccessibilityServiceEnabled();
        boolean checked = holder.getSwitchCompat().isChecked();
        if (checked && !isAccessibilityServiceEnabled) {
            enableAccessibilityService();
        } else if (!checked && isAccessibilityServiceEnabled) {
            if (!AccessibilityService.Companion.disable()) {
                AccessibilityServiceTool.goToAccessibilitySetting();
            }
        }
    }

    void goToNotificationServiceSettings(DrawerMenuItemViewHolder holder) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            return;
        }
        boolean enabled = NotificationListenerService.Companion.getInstance() != null;
        boolean checked = holder.getSwitchCompat().isChecked();
        if ((checked && !enabled) || (!checked && enabled)) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }

    void goToUsageStatsSettings(DrawerMenuItemViewHolder holder) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        boolean enabled = AppOpsKt.isOpPermissionGranted(getContext(), AppOpsManager.OPSTR_GET_USAGE_STATS);
        boolean checked = holder.getSwitchCompat().isChecked();
        if(checked && !enabled){
            if(new NotAskAgainDialog.Builder(getContext(), "DrawerFragment.usage_stats")
                    .title(R.string.text_usage_stats_permission)
                    .content(R.string.description_usage_stats_permission)
                    .positiveText(R.string.ok)
                    .dismissListener(dialog -> IntentUtil.requestAppUsagePermission(getContext()))
                    .show() == null){
                IntentUtil.requestAppUsagePermission(getContext());
            }
        }
        if (!checked && enabled) {
            IntentUtil.requestAppUsagePermission(getContext());
        }
    }

    void showOrDismissFloatingWindow(DrawerMenuItemViewHolder holder) {
        boolean isFloatingWindowShowing = FloatyWindowManger.isCircularMenuShowing();
        boolean checked = holder.getSwitchCompat().isChecked();
        if (getActivity() != null && !getActivity().isFinishing()) {
            Pref.setFloatingMenuShown(checked);
        }
        if (checked && !isFloatingWindowShowing) {
            setChecked(mFloatingWindowItem, FloatyWindowManger.showCircularMenu());
            enableAccessibilityServiceByRootIfNeeded();
        } else if (!checked && isFloatingWindowShowing) {
            FloatyWindowManger.hideCircularMenu();
        }
    }

    void openThemeColorSettings(DrawerMenuItemViewHolder holder) {
        SettingsActivity.selectThemeColor(getActivity());
    }

    void toggleNightMode(DrawerMenuItemViewHolder holder) {
        ((BaseActivity) getActivity()).setNightModeEnabled(holder.getSwitchCompat().isChecked());
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

    void connectOrDisconnectToRemote(DrawerMenuItemViewHolder holder) {
        boolean checked = holder.getSwitchCompat().isChecked();
        boolean connected = DevPluginService.getInstance().isConnected();
        if (checked && !connected) {
            inputRemoteHost();
        } else if (!checked && connected) {
            DevPluginService.getInstance().disconnectIfNeeded();
        }
    }


    private void toggleForegroundService(DrawerMenuItemViewHolder holder) {
        boolean checked = holder.getSwitchCompat().isChecked();
        if (checked) {
            ForegroundService.start(GlobalAppContext.get());
        } else {
            ForegroundService.stop(GlobalAppContext.get());
        }
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
                .onNeutral((dialog, which) -> {
                    setChecked(mConnectionItem, false);
                    IntentUtil.browse(getActivity(), URL_PROJECT_HOME);
                })
                .cancelListener(dialog -> setChecked(mConnectionItem, false))
                .show();
    }

    private void onConnectException(Throwable e) {
        setChecked(mConnectionItem, false);
        Toast.makeText(GlobalAppContext.get(), getString(R.string.error_connect_to_remote, e.getMessage()),
                Toast.LENGTH_LONG).show();
    }

    void checkForUpdates(DrawerMenuItemViewHolder holder) {
        setProgress(mCheckForUpdatesItem, true);
        VersionService.getInstance().checkForUpdates()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SimpleObserver<VersionInfo>() {

                    @Override
                    public void onNext(@io.reactivex.annotations.NonNull VersionInfo versionInfo) {
                        if (getActivity() == null)
                            return;
                        if (versionInfo.isNewer()) {
                            new UpdateInfoDialogBuilder(getActivity(), versionInfo)
                                    .show();
                        } else {
                            Toast.makeText(GlobalAppContext.get(), R.string.text_is_latest_version, Toast.LENGTH_SHORT).show();
                        }
                        setProgress(mCheckForUpdatesItem, false);
                    }

                    @Override
                    public void onError(@io.reactivex.annotations.NonNull Throwable e) {
                        e.printStackTrace();
                        Toast.makeText(GlobalAppContext.get(), R.string.text_check_update_error, Toast.LENGTH_SHORT).show();
                        setProgress(mCheckForUpdatesItem, false);
                    }
                });
    }


    @Override
    public void onResume() {
        super.onResume();
        if (mMiuixDrawerView != null) {
            // Restore the floating window once the window/service are ready
            // (attempting during onCreateView can silently fail).
            if (Pref.isFloatingMenuShown() && !FloatyWindowManger.isCircularMenuShowing()) {
                FloatyWindowManger.showCircularMenuIfNeeded();
            }
            Object refresh = mMiuixDrawerView.getTag();
            if (refresh instanceof Runnable) {
                ((Runnable) refresh).run();
            }
            return;
        }
        syncSwitchState();
        syncUserInfo();
    }

    private void syncUserInfo() {
        NodeBB.getInstance().getRetrofit()
                .create(UserApi.class)
                .me()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::setUpUserInfo, error -> {
                    error.printStackTrace();
                    setUpUserInfo(null);
                });
    }

    private void setUpUserInfo(@Nullable User user) {
        if (mUserName == null || mAvatar == null)
            return;
        if (user == null) {
            mUserName.setText(R.string.not_login);
            mAvatar.setIcon(R.drawable.profile_avatar_placeholder);
        } else {
            mUserName.setText(user.getUsername());
            mAvatar.setUser(user);
        }
        setCoverImage(user);
    }

    private void setCoverImage(User user) {
        if (mDefaultCover == null || mShadow == null || mHeaderView == null)
            return;
        if (user == null || TextUtils.isEmpty(user.getCoverUrl()) || user.getCoverUrl().equals("/assets/images/cover-default.png")) {
            mDefaultCover.setVisibility(View.VISIBLE);
            mShadow.setVisibility(View.GONE);
            mHeaderView.setBackgroundColor(ThemeColorManagerCompat.getColorPrimary());
        } else {
            mDefaultCover.setVisibility(View.GONE);
            mShadow.setVisibility(View.VISIBLE);
            Glide.with(this)
                    .load(NodeBB.BASE_URL + user.getCoverUrl())
                    .apply(new RequestOptions()
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                    )
                    .into(new BackgroundTarget(mHeaderView));
        }
    }

    private void syncSwitchState() {
        setChecked(mAccessibilityServiceItem, AccessibilityServiceTool.isAccessibilityServiceEnabled(getActivity()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            setChecked(mNotificationPermissionItem, NotificationListenerService.Companion.getInstance() != null);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setChecked(mUsageStatsPermissionItem, AppOpsKt.isOpPermissionGranted(getContext(), AppOpsManager.OPSTR_GET_USAGE_STATS));
        }
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
        setProgress(mAccessibilityServiceItem, true);
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
                    setProgress(mAccessibilityServiceItem, false);
                    syncSwitchState();
                }, error -> {
                    if (getContext() == null) return;
                    Toast.makeText(getContext(), R.string.text_enable_accessibility_service_fast_failed,
                            Toast.LENGTH_SHORT).show();
                    setProgress(mAccessibilityServiceItem, false);
                    AccessibilityServiceTool.goToAccessibilitySetting();
                });
    }

    private void enableAccessibilityServiceByRoot() {
        setProgress(mAccessibilityServiceItem, true);
        Observable.fromCallable(() -> AccessibilityServiceTool.enableAccessibilityServiceByRootAndWaitFor(4000))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(succeed -> {
                    if (!succeed) {
                        Toast.makeText(getContext(), R.string.text_enable_accessibitliy_service_by_root_failed, Toast.LENGTH_SHORT).show();
                        AccessibilityServiceTool.goToAccessibilitySetting();
                    }
                    setProgress(mAccessibilityServiceItem, false);
                });
    }


    @Subscribe
    public void onCircularMenuStateChange(CircularMenu.StateChangeEvent event) {
        setChecked(mFloatingWindowItem, event.getCurrentState() != CircularMenu.STATE_CLOSED);
    }

    @Subscribe
    public void onCommunityPageVisibilityChange(CommunityFragment.VisibilityChange change) {
        if (change.visible) {
            mCommunityDrawerMenu.showCommunityMenu(mDrawerMenuAdapter);
        } else {
            mCommunityDrawerMenu.hideCommunityMenu(mDrawerMenuAdapter);
        }
        mDrawerMenu.scrollToPosition(0);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onLoginStateChange(UserService.LoginStateChange change) {
        syncUserInfo();
        if (mCommunityDrawerMenu.isShown()) {
            mCommunityDrawerMenu.setUserOnlineStatus(mDrawerMenuAdapter, change.isOnline());
        }
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDrawerOpen(MainActivity.DrawerOpenEvent event) {
        if (mMiuixDrawerView != null) {
            refreshMiuixDrawer();
            return;
        }
        if (mCommunityDrawerMenu.isShown()) {
            mCommunityDrawerMenu.refreshNotificationCount(mDrawerMenuAdapter);
        }
    }

    private void showStableModePromptIfNeeded() {
        new NotAskAgainDialog.Builder(getContext(), "DrawerFragment.stable_mode")
                .title(R.string.text_stable_mode)
                .content(R.string.description_stable_mode)
                .positiveText(R.string.ok)
                .show();
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        mConnectionStateDisposable.dispose();
        EventBus.getDefault().unregister(this);
    }


    private void showMessage(CharSequence text) {
        if (getContext() == null)
            return;
        Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show();
    }


    private void setProgress(DrawerMenuItem item, boolean progress) {
        item.setProgress(progress);
        if (mDrawerMenuAdapter != null) {
            mDrawerMenuAdapter.notifyItemChanged(item);
        } else {
            refreshMiuixDrawer();
        }
    }

    private void setChecked(DrawerMenuItem item, boolean checked) {
        item.setChecked(checked);
        if (mDrawerMenuAdapter != null) {
            mDrawerMenuAdapter.notifyItemChanged(item);
        } else {
            refreshMiuixDrawer();
        }
    }

    private void refreshMiuixDrawer() {
        if (mMiuixDrawerView == null) return;
        Object refresh = mMiuixDrawerView.getTag();
        if (refresh instanceof Runnable) {
            ((Runnable) refresh).run();
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        return AccessibilityServiceTool.isAccessibilityServiceEnabled(getActivity());
    }

}
