package com.jdkshen.aijspro.ui.main;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import androidx.core.view.GravityCompat;
import androidx.fragment.app.Fragment;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.widget.Toolbar;
import androidx.viewpager.widget.ViewPager;

import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.appbar.AppBarLayout;
import com.stardust.app.FragmentPagerAdapterBuilder;
import com.stardust.app.OnActivityResultDelegate;
import com.stardust.autojs.core.permission.OnRequestPermissionsResultCallback;
import com.stardust.autojs.core.permission.PermissionRequestProxyActivity;
import com.stardust.autojs.core.permission.RequestPermissionCallbacks;
import com.stardust.enhancedfloaty.FloatyService;
import com.stardust.pio.PFiles;
import com.stardust.util.BackPressedHandler;
import com.stardust.util.DeveloperUtils;
import com.stardust.util.DrawerAutoClose;

import com.jdkshen.aijspro.BuildConfig;
import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.external.foreground.ForegroundService;
import com.jdkshen.aijspro.model.explorer.Explorers;
import com.jdkshen.aijspro.tool.AccessibilityServiceTool;
import com.jdkshen.aijspro.ui.BaseActivity;
import com.jdkshen.aijspro.ui.common.NotAskAgainDialog;
import com.jdkshen.aijspro.ui.doc.DocumentationActivity;
import com.jdkshen.aijspro.ui.doc.DocsFragment;
import com.jdkshen.aijspro.ui.floating.FloatyWindowManger;
import com.jdkshen.aijspro.ui.imgui.ImGuiWorkspaceActivity;
import com.jdkshen.aijspro.ui.log.LogActivity;
import com.jdkshen.aijspro.ui.main.community.CommunityFragment;
import com.jdkshen.aijspro.ui.main.market.MarketFragment;
import com.jdkshen.aijspro.ui.main.scripts.MyScriptListFragment;
import com.jdkshen.aijspro.ui.main.task.TaskManagerFragment;
import com.jdkshen.aijspro.ui.settings.SettingsActivity;
import com.jdkshen.aijspro.ui.update.VersionGuard;
import com.jdkshen.aijspro.ui.widget.CommonMarkdownView;
import com.jdkshen.aijspro.ui.widget.SearchViewItem;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.util.Arrays;

public class MainActivity extends BaseActivity implements OnActivityResultDelegate.DelegateHost, BackPressedHandler.HostActivity, PermissionRequestProxyActivity {

    public static class DrawerOpenEvent {
        static DrawerOpenEvent SINGLETON = new DrawerOpenEvent();
    }

    private static final String LOG_TAG = "MainActivity";

    DrawerLayout mDrawerLayout;

    ViewPager mViewPager;

    FloatingActionButton mFab;
    View mMiuixFab;

    TabLayout mTabLayout;

    private FragmentPagerAdapterBuilder.StoredFragmentPagerAdapter mPagerAdapter;
    private OnActivityResultDelegate.Mediator mActivityResultMediator = new OnActivityResultDelegate.Mediator();
    private RequestPermissionCallbacks mRequestPermissionCallbacks = new RequestPermissionCallbacks();
    private VersionGuard mVersionGuard;
    private BackPressedHandler.Observer mBackPressObserver = new BackPressedHandler.Observer();
    private SearchViewItem mSearchViewItem;
    private MenuItem mSearchMenuItem;
    private MenuItem mLogMenuItem;
    private boolean mDocsSearchItemExpanded;
    private boolean mShowingHome = true;

    private static final int[] PAGE_TITLES = {
            R.string.text_file,
            R.string.text_tutorial,
            R.string.text_community,
            R.string.text_market,
            R.string.text_manage
    };

    private static final int[] PAGE_ICONS = {
            R.drawable.ic_nav_scripts,
            R.drawable.ic_nav_tutorial,
            R.drawable.ic_nav_community,
            R.drawable.ic_nav_market,
            R.drawable.ic_nav_manage
    };


    @Override
    protected boolean shouldApplyThemeColorToStatusBar() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        checkPermissions();
        showAccessibilitySettingPromptIfDisabled();
        mVersionGuard = new VersionGuard(this);
        showAnnunciationIfNeeded();
        EventBus.getDefault().register(this);
        applyDayNightMode();
        setContentView(R.layout.activity_main);
        bindViews();
        setUpViews();
        installMiuixNavigationIfNeeded();
        installMiuixFabIfNeeded();
        // Match the familiar Auto.js Pro startup flow: scripts are the primary
        // workspace. The service dashboard remains available from the drawer.
        showPage(0);
        syncStatusBarWithAppBar();
    }

    private void syncStatusBarWithAppBar() {
        if (BuildConfig.MIUIX_PILOT) {
            // Miuix navigation/status colors are installed by installMiuixNavigationIfNeeded.
            return;
        }
        int surface = com.google.android.material.color.MaterialColors.getColor(
                findViewById(R.id.app_bar), com.google.android.material.R.attr.colorSurface);
        getWindow().setStatusBarColor(surface);
        getWindow().setNavigationBarColor(surface);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decorView = getWindow().getDecorView();
            int flags = decorView.getSystemUiVisibility();
            if (Pref.isNightModeEnabled()) {
                decorView.setSystemUiVisibility(flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            } else {
                decorView.setSystemUiVisibility(flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        }
    }

    private void bindViews() {
        mDrawerLayout = findViewById(R.id.drawer_layout);
        mViewPager = findViewById(R.id.viewpager);
        mFab = findViewById(R.id.fab);
        mTabLayout = findViewById(R.id.tab);
        View setting = findViewById(R.id.setting);
        if (setting != null) {
            setting.setOnClickListener(v -> startSettingActivity());
        }
        View exit = findViewById(R.id.exit);
        if (exit != null) {
            exit.setOnClickListener(v -> exitCompletely());
        }
        setupQuickCards();
    }

    private void setupQuickCards() {
        bindBigCard(R.id.home_core_service, R.string.text_quick_service,
                R.string.text_quick_service_subtitle, this::openServiceStatus);
        bindSmallCard(R.id.home_accessibility, R.drawable.ic_service_green,
                R.string.text_accessibility_service, this::openAccessibilitySetting);
        bindSmallCard(R.id.home_floating, R.drawable.ic_robot_64,
                R.string.text_floating_window, this::toggleFloatingWindow);
        bindRow(R.id.home_developer, R.drawable.ic_connect_to_pc,
                R.string.text_quick_developer, this::openDeveloperTools);
        if (BuildConfig.MIUIX_PILOT) {
            findViewById(R.id.home_developer).setVisibility(View.GONE);
        }
        bindRow(R.id.home_exit, R.drawable.ic_close_white_48dp,
                R.string.text_quick_exit_app, this::exitCompletely);
        TextView version = findViewById(R.id.home_version);
        if (version != null) {
            version.setText(getString(R.string.text_version_footer, BuildConfig.VERSION_NAME));
        }
        refreshServiceStatus();
    }

    private void bindBigCard(int cardId, int titleRes, int subtitleRes, Runnable action) {
        View card = findViewById(cardId);
        if (card == null) return;
        ((TextView) card.findViewById(R.id.big_card_title)).setText(titleRes);
        ((TextView) card.findViewById(R.id.big_card_subtitle)).setText(subtitleRes);
        card.setOnClickListener(v -> action.run());
    }

    private void bindSmallCard(int cardId, int iconRes, int labelRes, Runnable action) {
        View card = findViewById(cardId);
        if (card == null) return;
        ((ImageView) card.findViewById(R.id.small_card_icon)).setImageResource(iconRes);
        ((TextView) card.findViewById(R.id.small_card_label)).setText(labelRes);
        card.setOnClickListener(v -> action.run());
    }

    private void bindRow(int rowId, int iconRes, int labelRes, Runnable action) {
        View row = findViewById(rowId);
        if (row == null) return;
        ((ImageView) row.findViewById(R.id.row_icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.row_label)).setText(labelRes);
        row.setOnClickListener(v -> action.run());
    }

    private void refreshServiceStatus() {
        View card = findViewById(R.id.home_core_service);
        if (card == null) return;
        String status = getString(R.string.text_quick_service_status,
                AccessibilityServiceTool.isAccessibilityServiceEnabled(this)
                        ? getString(R.string.text_on) : getString(R.string.text_off),
                FloatyWindowManger.isCircularMenuShowing()
                        ? getString(R.string.text_on) : getString(R.string.text_off),
                Pref.isForegroundServiceEnabled()
                        ? getString(R.string.text_on) : getString(R.string.text_off));
        ((TextView) card.findViewById(R.id.big_card_status)).setText(status);
    }

    private void openServiceStatus() {
        startActivity(new Intent(this,
                com.jdkshen.aijspro.ui.service.ServiceStatusActivity.class));
    }

    private void openAccessibilitySetting() {
        AccessibilityServiceTool.enableAccessibilityService();
    }

    private void toggleFloatingWindow() {
        if (FloatyWindowManger.isCircularMenuShowing()) {
            FloatyWindowManger.hideCircularMenu();
            Pref.setFloatingMenuShown(false);
        } else {
            FloatyWindowManger.showCircularMenuIfNeeded();
            Pref.setFloatingMenuShown(true);
        }
        refreshServiceStatus();
    }

    private void openDeveloperTools() {
        startActivity(new Intent(this, ImGuiWorkspaceActivity.class));
    }

    void setUpViews() {
        setUpToolbar();
        setUpTabViewPager();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        registerBackPressHandlers();
        mDrawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                EventBus.getDefault().post(DrawerOpenEvent.SINGLETON);
            }
        });
    }

    /** Installs the flavor-isolated Compose/Miuix bar while keeping the native pager and lists. */
    private void installMiuixNavigationIfNeeded() {
        if (!BuildConfig.MIUIX_PILOT) return;
        try {
            Class<?> host = Class.forName("com.jdkshen.aijspro.ui.main.MiuixMainNavigationHost");
            View bar = (View) host.getMethod("createView", MainActivity.class).invoke(null, this);
            AppBarLayout appBar = findViewById(R.id.app_bar);
            Toolbar toolbar = findViewById(R.id.toolbar);
            toolbar.setVisibility(View.GONE);
            // Match AijsMiuixTheme backgrounds (light #F7F7F7 / dark black)
            // so the status bar and tab strip blend with the Miuix navigation bar.
            int surface = Pref.isNightModeEnabled()
                    ? android.graphics.Color.BLACK
                    : android.graphics.Color.rgb(0xF7, 0xF7, 0xF7);
            int selected = android.graphics.Color.rgb(0, 150, 136);
            int normal = android.graphics.Color.argb(130, 0, 0, 0);
            mTabLayout.setBackgroundColor(surface);
            getWindow().setStatusBarColor(surface);
            mTabLayout.setTabIconTint(new android.content.res.ColorStateList(
                    new int[][] { new int[] { android.R.attr.state_selected }, new int[] {} },
                    new int[] { selected, normal }));
            appBar.addView(bar, 0, new AppBarLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // The Miuix shell stays fixed; only the file RecyclerView should scroll.
            for (int i = 0; i < appBar.getChildCount(); i++) {
                View child = appBar.getChildAt(i);
                if (child.getLayoutParams() instanceof AppBarLayout.LayoutParams) {
                    AppBarLayout.LayoutParams params = (AppBarLayout.LayoutParams) child.getLayoutParams();
                    params.setScrollFlags(0);
                    child.setLayoutParams(params);
                }
            }
            appBar.setExpanded(true, false);
        } catch (Throwable error) {
            // A broken pilot must fall back to the proven toolbar, not break startup.
            android.util.Log.e(LOG_TAG, "Unable to install Miuix main navigation", error);
        }
    }

    private void installMiuixFabIfNeeded() {
        if (!BuildConfig.MIUIX_PILOT) return;
        try {
            Class<?> host = Class.forName("com.jdkshen.aijspro.ui.main.MiuixMainFabHost");
            mMiuixFab = (View) host.getMethod("createView", MainActivity.class).invoke(null, this);
            ViewGroup parent = (ViewGroup) mFab.getParent();
            androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams params =
                    new androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.END | Gravity.BOTTOM;
            int margin = (int) (16 * getResources().getDisplayMetrics().density);
            params.setMargins(margin, margin, margin, margin);
            parent.addView(mMiuixFab, params);
            // Remove the legacy FAB entirely in pilot mode: ViewPagerFragment.onPageShow
            // calls mFab.show() on every page switch, which would bring the old blue FAB
            // back on top of the Miuix one.
            ViewGroup fabParent = (ViewGroup) mFab.getParent();
            if (fabParent != null) {
                fabParent.removeView(mFab);
            }
            mFab.setVisibility(View.GONE);
            mFab.setAlpha(0f);
        } catch (Throwable error) {
            android.util.Log.e(LOG_TAG, "Unable to install Miuix FAB", error);
        }
    }

    public void performMainFabClickFromMiuix() {
        mFab.performClick();
    }

    /** True only on the file page: there the Miuix FAB opens the create menu. */
    public boolean isCurrentPageCreateMenu() {
        Fragment fragment = mPagerAdapter == null ? null
                : mPagerAdapter.getStoredFragment(mViewPager.getCurrentItem());
        return fragment instanceof MyScriptListFragment;
    }

    public void updateMiuixFabVisibility(ViewPagerFragment fragment) {
        if (mMiuixFab == null) {
            return;
        }
        boolean visible = fragment != null && fragment.isShown() && !fragment.isFabRotationGone();
        mMiuixFab.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public void performMainCreateActionFromMiuix(int position) {
        Fragment fragment = mPagerAdapter == null ? null
                : mPagerAdapter.getStoredFragment(mViewPager.getCurrentItem());
        if (fragment instanceof MyScriptListFragment) {
            ((MyScriptListFragment) fragment).onClick(null, position);
        }
    }

    public void openMainDrawerFromMiuix() {
        mDrawerLayout.openDrawer(GravityCompat.START);
    }

    public void openLogFromMiuix() {
        startActivity(new Intent(this, LogActivity.class));
    }

    public void openImguiFromMiuix() {
        startActivity(new Intent(this, ImGuiWorkspaceActivity.class));
    }

    public void openDocumentationFromMiuix() {
        startActivity(new Intent(this, DocumentationActivity.class));
    }

    public void submitSearchFromMiuix(String query) {
        submitQuery(query == null || query.trim().isEmpty() ? null : query.trim());
    }

    private void showAnnunciationIfNeeded() {
        if (!Pref.shouldShowAnnunciation()) {
            return;
        }
        new CommonMarkdownView.DialogBuilder(this)
                .padding(36, 0, 36, 0)
                .markdown(PFiles.read(getResources().openRawResource(R.raw.annunciation)))
                .title(R.string.text_annunciation)
                .positiveText(R.string.ok)
                .canceledOnTouchOutside(false)
                .show();
    }


    private void registerBackPressHandlers() {
        mBackPressObserver.registerHandler(new DrawerAutoClose(mDrawerLayout, Gravity.START));
        mBackPressObserver.registerHandler(new BackPressedHandler.DoublePressExit(this, R.string.text_press_again_to_exit));
    }

    private void checkPermissions() {
        checkPermission(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    private void showAccessibilitySettingPromptIfDisabled() {
        if (AccessibilityServiceTool.isAccessibilityServiceEnabled(this)) {
            return;
        }
        new NotAskAgainDialog.Builder(this, "MainActivity.accessibility")
                .title(R.string.text_need_to_enable_accessibility_service)
                .content(R.string.explain_accessibility_permission)
                .positiveText(R.string.text_go_to_setting)
                .negativeText(R.string.text_cancel)
                .onPositive((dialog, which) ->
                        AccessibilityServiceTool.enableAccessibilityService()
                ).show();
    }

    private void setUpToolbar() {
        Toolbar toolbar = $(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setTitle(R.string.app_name);
        ActionBarDrawerToggle drawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout, toolbar, R.string.text_drawer_open,
                R.string.text_drawer_close);
        drawerToggle.syncState();
        mDrawerLayout.addDrawerListener(drawerToggle);
    }

    private void setUpTabViewPager() {
        mPagerAdapter = new FragmentPagerAdapterBuilder(this)
                .add(new MyScriptListFragment(), R.string.text_file)
                .add(createTutorialFragment(), R.string.text_tutorial)
                .add(createCommunityFragment(), R.string.text_community)
                .add(createMarketFragment(), R.string.text_market)
                .add(new TaskManagerFragment(), R.string.text_manage)
                .build();
        mViewPager.setAdapter(mPagerAdapter);
        mTabLayout.setupWithViewPager(mViewPager);
        for (int i = 0; i < PAGE_ICONS.length; i++) {
            TabLayout.Tab tab = mTabLayout.getTabAt(i);
            if (tab != null) {
                tab.setIcon(PAGE_ICONS[i]);
                tab.setText(null);
                tab.setContentDescription(PAGE_TITLES[i]);
            }
        }
        setUpViewPagerFragmentBehaviors();
    }

    private ViewPagerFragment createTutorialFragment() {
        if (BuildConfig.MIUIX_PILOT) {
            try {
                return (ViewPagerFragment) Class.forName(
                        "com.jdkshen.aijspro.ui.sample.MiuixSampleFragment")
                        .getDeclaredConstructor().newInstance();
            } catch (Throwable error) {
                android.util.Log.e(LOG_TAG, "Unable to create Miuix sample page", error);
            }
        }
        return new DocsFragment();
    }

    private ViewPagerFragment createMarketFragment() {
        if (BuildConfig.MIUIX_PILOT) {
            try {
                return (ViewPagerFragment) Class.forName(
                        "com.jdkshen.aijspro.ui.market.MiuixMarketFragment")
                        .getDeclaredConstructor().newInstance();
            } catch (Throwable error) {
                android.util.Log.e(LOG_TAG, "Unable to create Miuix market page", error);
            }
        }
        return new MarketFragment();
    }

    private ViewPagerFragment createCommunityFragment() {
        if (BuildConfig.MIUIX_PILOT) {
            try {
                return (ViewPagerFragment) Class.forName(
                        "com.jdkshen.aijspro.ui.community.MiuixCommunityFragment")
                        .getDeclaredConstructor().newInstance();
            } catch (Throwable error) {
                android.util.Log.e(LOG_TAG, "Unable to create Miuix community page", error);
            }
        }
        return new CommunityFragment();
    }

    public void showPage(int position) {
        if (mViewPager == null || position < 0 || position >= PAGE_TITLES.length) return;
        mShowingHome = false;
        findViewById(R.id.home_content).setVisibility(View.GONE);
        mViewPager.setVisibility(View.VISIBLE);
        mTabLayout.setVisibility(View.VISIBLE);
        mFab.setVisibility(View.VISIBLE);
        if (mMiuixFab != null) mMiuixFab.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
        mViewPager.setCurrentItem(position);
        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.app_name);
        updateHomeMenuState();
        mDrawerLayout.closeDrawer(GravityCompat.START);
    }

    public void showHome() {
        mShowingHome = true;
        View home = findViewById(R.id.home_content);
        if (home != null) home.setVisibility(View.VISIBLE);
        if (mViewPager != null) mViewPager.setVisibility(View.GONE);
        if (mTabLayout != null) mTabLayout.setVisibility(View.GONE);
        if (mFab != null) mFab.setVisibility(View.GONE);
        if (mMiuixFab != null) mMiuixFab.setVisibility(View.GONE);
        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) toolbar.setTitle(R.string.app_name);
        updateHomeMenuState();
        if (mDrawerLayout != null) mDrawerLayout.closeDrawer(GravityCompat.START);
    }

    private void setUpViewPagerFragmentBehaviors() {
        mPagerAdapter.setOnFragmentInstantiateListener((pos, fragment) -> {
            ((ViewPagerFragment) fragment).setFab(mFab);
            if (pos == mViewPager.getCurrentItem()) {
                ((ViewPagerFragment) fragment).onPageShow();
            }
        });
        mViewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            private ViewPagerFragment mPreviousFragment;

            @Override
            public void onPageSelected(int position) {
                if (mMiuixFab != null) {
                    mMiuixFab.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
                }
                Fragment fragment = mPagerAdapter.getStoredFragment(position);
                if (fragment == null)
                    return;
                if (mPreviousFragment != null) {
                    mPreviousFragment.onPageHide();
                }
                mPreviousFragment = (ViewPagerFragment) fragment;
                mPreviousFragment.onPageShow();
            }
        });
    }


    void startSettingActivity() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    public void exitCompletely() {
        finish();
        FloatyWindowManger.hideCircularMenu();
        ForegroundService.stop(this);
        stopService(new Intent(this, FloatyService.class));
        AutoJs.getInstance().getScriptEngineService().stopAll();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mVersionGuard.checkForDeprecatesAndUpdates();
        refreshServiceStatus();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        mActivityResultMediator.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (mRequestPermissionCallbacks.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
            return;
        }
        if (getGrantResult(Manifest.permission.READ_EXTERNAL_STORAGE, permissions, grantResults) == PackageManager.PERMISSION_GRANTED) {
            Explorers.workspace().refreshAll();
        }
    }

    private int getGrantResult(String permission, String[] permissions, int[] grantResults) {
        int i = Arrays.asList(permissions).indexOf(permission);
        if (i < 0) {
            return 2;
        }
        return grantResults[i];
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!BuildConfig.DEBUG) {
            DeveloperUtils.verifyApk(this, R.string.dex_crcs);
        }
    }


    @NonNull
    @Override
    public OnActivityResultDelegate.Mediator getOnActivityResultDelegateMediator() {
        return mActivityResultMediator;
    }

    @Override
    public void onBackPressed() {
        if (mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
            mDrawerLayout.closeDrawer(GravityCompat.START);
            return;
        }
        if (!mShowingHome) {
            Fragment fragment = mPagerAdapter.getStoredFragment(mViewPager.getCurrentItem());
            if (fragment instanceof BackPressedHandler
                    && ((BackPressedHandler) fragment).onBackPressed(this)) {
                return;
            }
            if (mViewPager.getCurrentItem() != 0) {
                showPage(0);
                return;
            }
        }
        if (!mBackPressObserver.onBackPressed(this)) {
            super.onBackPressed();
        }
    }

    @Override
    public void addRequestPermissionsCallback(OnRequestPermissionsResultCallback callback) {
        mRequestPermissionCallbacks.addCallback(callback);
    }

    @Override
    public boolean removeRequestPermissionsCallback(OnRequestPermissionsResultCallback callback) {
        return mRequestPermissionCallbacks.removeCallback(callback);
    }


    @Override
    public BackPressedHandler.Observer getBackPressedObserver() {
        return mBackPressObserver;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        MenuItem workspace = menu.findItem(R.id.action_imgui_workspace);
        if (workspace != null && BuildConfig.MIUIX_PILOT) workspace.setVisible(false);
        MenuItem searchMenuItem = menu.findItem(R.id.action_search);
        mSearchMenuItem = searchMenuItem;
        mLogMenuItem = menu.findItem(R.id.action_log);
        setUpSearchMenuItem(searchMenuItem);
        updateHomeMenuState();
        return true;
    }

    private void updateHomeMenuState() {
        if (mSearchMenuItem != null) {
            mSearchMenuItem.setVisible(!mShowingHome);
        }
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_imgui_workspace) {
            startActivity(new Intent(this, ImGuiWorkspaceActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.action_log) {
            if (mDocsSearchItemExpanded) {
                submitForwardQuery();
            } else {
                startActivity(new Intent(this, LogActivity.class));
            }
            return true;
        }
        if (item.getItemId() == R.id.action_documentation) {
            startActivity(new Intent(this, DocumentationActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Subscribe
    public void onLoadUrl(CommunityFragment.LoadUrl loadUrl) {
        mDrawerLayout.closeDrawer(GravityCompat.START);
    }


    private void setUpSearchMenuItem(MenuItem searchMenuItem) {
        mSearchViewItem = new SearchViewItem(this, searchMenuItem) {
            @Override
            public boolean onMenuItemActionExpand(MenuItem item) {
                if (mViewPager.getCurrentItem() == 1) {
                    mDocsSearchItemExpanded = true;
                    mLogMenuItem.setIcon(R.drawable.ic_ali_up);
                }
                return super.onMenuItemActionExpand(item);
            }

            @Override
            public boolean onMenuItemActionCollapse(MenuItem item) {
                if (mDocsSearchItemExpanded) {
                    mDocsSearchItemExpanded = false;
                    mLogMenuItem.setIcon(R.drawable.ic_ali_log);
                }
                return super.onMenuItemActionCollapse(item);
            }
        };
        mSearchViewItem.setQueryCallback(this::submitQuery);
    }

    private void submitQuery(String query) {
        if (query == null) {
            EventBus.getDefault().post(QueryEvent.CLEAR);
            return;
        }
        QueryEvent event = new QueryEvent(query);
        EventBus.getDefault().post(event);
        if (event.shouldCollapseSearchView()) {
            mSearchViewItem.collapse();
        }
    }

    private void submitForwardQuery() {
        QueryEvent event = QueryEvent.FIND_FORWARD;
        EventBus.getDefault().post(event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
    }
}
