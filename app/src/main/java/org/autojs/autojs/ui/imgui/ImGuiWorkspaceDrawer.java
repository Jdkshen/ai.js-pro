package org.autojs.autojs.ui.imgui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import org.autojs.autojs.BuildConfig;
import org.autojs.autojs.R;

import java.util.Collections;

/**
 * Android-native drawer placed above the ImGui workspace. Keeping the drawer native gives every
 * menu row a real accessibility node while the file workspace remains the same SurfaceView.
 */
final class ImGuiWorkspaceDrawer extends DrawerLayout {

    interface ActionListener {
        void onDrawerAction(int action);
    }

    private static final String TAG = "AIJsWorkspaceDrawer";
    private static final String PREFERENCES = "imgui_workspace_drawer";
    private static final String KEY_IS_OPEN = "drawer_is_open";
    private static final String KEY_LAST_OPENED_AT = "drawer_last_opened_at";
    private static final String KEY_LAST_CLOSED_AT = "drawer_last_closed_at";
    private static final String KEY_OPEN_COUNT = "drawer_open_count";
    private static final String KEY_CLOSE_COUNT = "drawer_close_count";
    private static final String KEY_MORE_EXPANDED = "more_services_expanded";
    private static final String KEY_DEVELOPER_EXPANDED = "developer_expanded";
    private static final int ACTION_ACCESSIBILITY = 60;
    private static final int ACTION_FLOATING = 61;
    private static final int ACTION_DEVELOPER = 63;
    private static final int ACTION_TERMINAL = 64;
    private static final int ACTION_THEME = 65;
    private static final int ACTION_BLOG = 66;
    private static final int ACTION_CHANNEL = 67;
    private static final int ACTION_SETTINGS = 68;
    private static final int ACTION_UPDATE = 69;
    private static final int ACTION_EXIT = 70;
    private static final int ACTION_NOTIFICATION_ACCESS = 72;
    private static final int ACTION_USAGE_ACCESS = 73;
    private static final int ACTION_OVERLAY_PERMISSION = 74;
    private static final int ACTION_BATTERY_OPTIMIZATION = 75;
    private static final int ACTION_DEVELOPER_HELP = 76;

    private static final int PANEL_COLOR = Color.rgb(31, 33, 35);
    private static final int HEADER_COLOR = Color.rgb(18, 20, 22);
    private static final int ROW_COLOR = Color.rgb(38, 40, 42);
    private static final int CHILD_COLOR = Color.rgb(43, 46, 49);
    private static final int TEXT_PRIMARY = Color.rgb(241, 243, 244);
    private static final int TEXT_SECONDARY = Color.rgb(166, 171, 176);
    private static final int ACCENT = Color.rgb(55, 201, 176);
    private static final int DANGER = Color.rgb(239, 118, 118);
    private static final int ROLE_TEXT_PRIMARY = 1;
    private static final int ROLE_TEXT_SECONDARY = 2;
    private static final int ROLE_TEXT_ACCENT = 3;
    private static final int ROLE_TEXT_DANGER = 4;
    private static final int ROLE_ICON_PRIMARY = 5;
    private static final int ROLE_ICON_DANGER = 6;
    private static final int ROLE_BG_TOOLBAR = 7;
    private static final int ROLE_BG_ROW = 8;
    private static final int ROLE_BG_CHILD = 9;

    private final ActionListener mActionListener;
    private final SharedPreferences mPreferences;
    private final FrameLayout mContent = new FrameLayout(getContext());
    private final LinearLayout mDrawerPane = new LinearLayout(getContext());
    private ImageView mHamburgerButton;
    private ImageView mAppIcon;
    private SwitchCompat mAccessibilitySwitch;
    private SwitchCompat mFloatingSwitch;
    private TextView mHeaderStatus;
    private TextView mAccessibilitySubtitle;
    private TextView mFloatingSubtitle;
    private TextView mDeveloperActionLabel;
    private boolean mAccessibilityEnabled;
    private boolean mFloatingShown;
    private boolean mDeveloperConnected;
    private boolean mUpdatingSwitches;
    private int mPendingAction;
    private org.autojs.autojs.theme.AppThemePalette mThemePalette;

    ImGuiWorkspaceDrawer(Context context, ActionListener actionListener) {
        super(context);
        mActionListener = actionListener;
        mPreferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        setScrimColor(Color.argb(132, 0, 0, 0));
        setDrawerElevation(dp(12));
        setFocusableInTouchMode(true);

        addView(mContent, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildDrawerPane();
        addView(mDrawerPane, createDrawerLayoutParams(getResources().getDisplayMetrics().widthPixels));
        addDrawerListener(new SimpleDrawerListener() {
            @Override
            public void onDrawerSlide(View drawerView, float slideOffset) {
                boolean drawerActive = slideOffset > 0.001f;
                setHamburgerVisible(!drawerActive);
            }

            @Override
            public void onDrawerOpened(View drawerView) {
                recordDrawerState(true);
                setHamburgerVisible(false);
                int menuChildren = -1;
                android.widget.ScrollView sv = null;
                if (mDrawerPane.getChildCount() > 1 && mDrawerPane.getChildAt(1) instanceof ScrollView) {
                    sv = (ScrollView) mDrawerPane.getChildAt(1);
                    if (sv.getChildCount() > 0 && sv.getChildAt(0) instanceof ViewGroup) {
                        menuChildren = ((ViewGroup) sv.getChildAt(0)).getChildCount();
                    }
                }
                android.util.Log.i(TAG, "drawer OPENED paneChildren=" + mDrawerPane.getChildCount()
                        + " scrollH=" + (sv != null ? sv.getHeight() : -1)
                        + " menuChildren=" + menuChildren);
                drawerView.sendAccessibilityEvent(
                        android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            }

            @Override
            public void onDrawerClosed(View drawerView) {
                recordDrawerState(false);
                setHamburgerVisible(true);
                android.util.Log.i(TAG, "drawer CLOSED");
                if (mPendingAction != 0) {
                    int action = mPendingAction;
                    mPendingAction = 0;
                    mActionListener.onDrawerAction(action);
                }
            }

            @Override
            public void onDrawerStateChanged(int newState) {
                // An interrupted/cancelled open can return to offset 0 without onDrawerClosed.
                if (newState == STATE_IDLE && !isDrawerVisible(GravityCompat.START)) {
                    setHamburgerVisible(true);
                }
            }
        });

        // Native Android hamburger button — positioned on top of the SurfaceView.
        // Directly calls open(); does NOT depend on ImGui render frames.
        buildHamburgerButton();
    }

    void setWorkspaceContent(View view) {
        mContent.removeAllViews();
        mContent.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Re-add the hamburger button on top of the SurfaceView.
        // removeAllViews() above removed it; we must restore it as the topmost child.
        if (mHamburgerButton == null) {
            buildHamburgerButton();
        } else {
            ViewParent oldParent = mHamburgerButton.getParent();
            if (oldParent instanceof ViewGroup) {
                ((ViewGroup) oldParent).removeView(mHamburgerButton);
            }
            mContent.addView(mHamburgerButton);
        }
        mHamburgerButton.bringToFront();
        androidx.core.view.ViewCompat.requestApplyInsets(mHamburgerButton);
    }

    void open() {
        setHamburgerVisible(false);
        openDrawer(GravityCompat.START, true);
    }

    boolean closeIfOpen() {
        if (!isDrawerOpen(GravityCompat.START) && !isDrawerVisible(GravityCompat.START)) {
            return false;
        }
        mPendingAction = 0;
        closeDrawer(GravityCompat.START, true);
        return true;
    }

    /**
     * Apply a complete theme palette to all drawer views.
     * Updates existing views — does NOT recreate the drawer (preserves scroll, state).
     */
    void applyTheme(org.autojs.autojs.theme.AppThemePalette palette) {
        if (palette == null) return;
        mThemePalette = palette;
        // Panel background
        mDrawerPane.setBackgroundColor(palette.surfacePrimary);
        // Scrim color
        setScrimColor(palette.scrim);
        // Hamburger button icon tint
        if (mHamburgerButton != null) {
            mHamburgerButton.setColorFilter(palette.iconPrimary, android.graphics.PorterDuff.Mode.SRC_IN);
            // Update ripple
            int rippleColor = palette.isDark ? 0x28FFFFFF : 0x1A000000;
            android.graphics.drawable.ShapeDrawable shape = new android.graphics.drawable.ShapeDrawable();
            shape.getPaint().setColor(android.graphics.Color.TRANSPARENT);
            shape.getPaint().setStyle(android.graphics.Paint.Style.FILL);
            shape.setShape(new android.graphics.drawable.shapes.OvalShape());
            mHamburgerButton.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(rippleColor), null, shape));
        }
        // Recursively update all child views in the drawer pane
        applyThemeToViewGroup(mDrawerPane, palette);
    }

    private void applyThemeToViewGroup(ViewGroup group, org.autojs.autojs.theme.AppThemePalette palette) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            // Recurse into any container (rows, labels blocks, sections) FIRST so nested
            // TextViews inside labelBlock get recolored too.
            if (child instanceof ViewGroup) {
                applyThemeToViewGroup((ViewGroup) child, palette);
            }
            // SwitchCompat is also a TextView; handle it before the TextView branch.
            if (child instanceof SwitchCompat) {
                SwitchCompat sw = (SwitchCompat) child;
                int[][] states = new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{-android.R.attr.state_checked}
                };
                sw.setThumbTintList(new android.content.res.ColorStateList(states,
                        new int[]{palette.accent, palette.textDisabled}));
                sw.setTrackTintList(new android.content.res.ColorStateList(states,
                        new int[]{palette.accentMuted, palette.surfaceElevated}));
            } else if (child instanceof TextView) {
                TextView tv = (TextView) child;
                int role = themeRole(child);
                if (role == ROLE_TEXT_DANGER) tv.setTextColor(palette.danger);
                else if (role == ROLE_TEXT_ACCENT) tv.setTextColor(palette.accent);
                else if (role == ROLE_TEXT_SECONDARY) tv.setTextColor(palette.textSecondary);
                else tv.setTextColor(palette.textPrimary);
            } else if (child instanceof ImageView) {
                ImageView iv = (ImageView) child;
                int role = themeRole(child);
                if (role == ROLE_ICON_DANGER) {
                    iv.setColorFilter(palette.danger, android.graphics.PorterDuff.Mode.SRC_IN);
                } else if (role == ROLE_ICON_PRIMARY && iv != mAppIcon) {
                    iv.setColorFilter(palette.iconPrimary, android.graphics.PorterDuff.Mode.SRC_IN);
                }
            }
            if (child instanceof LinearLayout) {
                int role = themeRole(child);
                if (role == ROLE_BG_TOOLBAR) child.setBackgroundColor(palette.toolbarBackground);
                else if (role == ROLE_BG_CHILD) child.setBackgroundColor(palette.surfaceSecondary);
                else if (role == ROLE_BG_ROW) child.setBackgroundColor(palette.rowBackground);
            }
        }
    }

    private int themeRole(View view) {
        Object tag = view.getTag();
        return tag instanceof Integer ? (Integer) tag : 0;
    }

    void updateServiceState(boolean accessibilityEnabled, boolean floatingShown,
                            boolean developerConnected) {
        mAccessibilityEnabled = accessibilityEnabled;
        mFloatingShown = floatingShown;
        mDeveloperConnected = developerConnected;
        mUpdatingSwitches = true;
        if (mAccessibilitySwitch != null) {
            mAccessibilitySwitch.setChecked(accessibilityEnabled);
            mAccessibilitySwitch.setContentDescription(
                    "无障碍服务，" + (accessibilityEnabled ? "已开启" : "未开启"));
        }
        if (mFloatingSwitch != null) {
            mFloatingSwitch.setChecked(floatingShown);
            mFloatingSwitch.setContentDescription(
                    "悬浮窗，" + (floatingShown ? "已开启" : "已关闭"));
        }
        if (mAccessibilitySubtitle != null) {
            mAccessibilitySubtitle.setText((accessibilityEnabled ? "已开启" : "未开启")
                    + " · 控件识别与自动操作");
            mAccessibilitySubtitle.setTag(accessibilityEnabled
                    ? ROLE_TEXT_ACCENT : ROLE_TEXT_SECONDARY);
            mAccessibilitySubtitle.setTextColor(accessibilityEnabled
                    ? themeAccent() : themeSecondaryText());
        }
        if (mFloatingSubtitle != null) {
            mFloatingSubtitle.setText((floatingShown ? "已显示" : "已隐藏")
                    + " · 悬浮控制与快捷入口");
            mFloatingSubtitle.setTag(floatingShown ? ROLE_TEXT_ACCENT : ROLE_TEXT_SECONDARY);
            mFloatingSubtitle.setTextColor(floatingShown
                    ? themeAccent() : themeSecondaryText());
        }
        if (mDeveloperActionLabel != null) {
            mDeveloperActionLabel.setText(developerConnected ? "断开开发者调试" : "连接开发者调试");
            mDeveloperActionLabel.setContentDescription(
                    "开发者调试，" + (developerConnected ? "已连接" : "未连接"));
        }
        if (mHeaderStatus != null) {
            mHeaderStatus.setText("无障碍 " + (accessibilityEnabled ? "已开启" : "未开启")
                    + "   ·   悬浮窗 " + (floatingShown ? "已开启" : "未开启")
                    + "   ·   调试 " + (developerConnected ? "已连接" : "未连接"));
        }
        mUpdatingSwitches = false;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width > 0) {
            LayoutParams params = (LayoutParams) mDrawerPane.getLayoutParams();
            int targetWidth = drawerWidth(width);
            if (params.width != targetWidth) {
                params.width = targetWidth;
                mDrawerPane.setLayoutParams(params);
            }
        }
        updateEdgeGestureExclusion(width, height);
    }

    private void updateEdgeGestureExclusion(int width, int height) {
        if (Build.VERSION.SDK_INT < 29 || width <= 0 || height <= 0) return;
        int allowedHeight = Math.min(height, dp(200));
        int top = Math.max(0, (height - allowedHeight) / 2);
        Rect leftEdge = new Rect(0, top, Math.min(width, dp(48)), top + allowedHeight);
        try {
            View.class.getMethod("setSystemGestureExclusionRects", java.util.List.class)
                    .invoke(this, Collections.singletonList(leftEdge));
        } catch (ReflectiveOperationException error) {
            Log.w(TAG, "Unable to set drawer gesture exclusion", error);
        }
    }

    /**
     * Removed custom edge-swipe detection — DrawerLayout natively handles left-edge
     * drag-to-open. Keeping only super.onInterceptTouchEvent() avoids the two-owner
     * conflict where DrawerLayout and our custom detector both fight for the same events.
     * The hamburger button provides a reliable tap-to-open alternative.
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return super.onInterceptTouchEvent(event);
    }

    private void buildDrawerPane() {
        mDrawerPane.setOrientation(LinearLayout.VERTICAL);
        mDrawerPane.setBackgroundColor(PANEL_COLOR);
        mDrawerPane.setContentDescription("AI.js Pro 侧栏菜单");

        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(12), dp(16), dp(10));
        header.setBackgroundColor(HEADER_COLOR);
        header.setTag(ROLE_BG_TOOLBAR);

        LinearLayout identity = new LinearLayout(getContext());
        identity.setOrientation(LinearLayout.HORIZONTAL);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        ImageView appIcon = new ImageView(getContext());
        appIcon.setImageResource(getContext().getApplicationInfo().icon);
        appIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        appIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mAppIcon = appIcon;
        identity.addView(appIcon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout titleBlock = new LinearLayout(getContext());
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(dp(12), 0, 0, 0);
        TextView title = text("AI.js Pro", 21, TEXT_PRIMARY);
        TextView subtitle = text("版本 " + BuildConfig.VERSION_NAME + " · Android "
                + Build.VERSION.RELEASE, 11, TEXT_SECONDARY);
        titleBlock.addView(title);
        titleBlock.addView(subtitle);
        identity.addView(titleBlock, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(identity, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        mHeaderStatus = text("正在获取服务状态…", 11, ACCENT);
        mHeaderStatus.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(mHeaderStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
        mDrawerPane.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(96)));

        ScrollView scrollView = new ScrollView(getContext());
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        LinearLayout menu = new LinearLayout(getContext());
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(0, dp(2), 0, dp(12));
        scrollView.addView(menu, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mDrawerPane.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        addGroup(menu, "核心服务");
        SwitchRow accessibilityRow = addSwitchRow(menu, "无障碍服务",
                "未开启 · 控件识别与自动操作", R.drawable.ic_service_green,
                checked -> {
                    if (mUpdatingSwitches) return;
                    mUpdatingSwitches = true;
                    mAccessibilitySwitch.setChecked(mAccessibilityEnabled);
                    mUpdatingSwitches = false;
                    runAfterClose(ACTION_ACCESSIBILITY);
                });
        mAccessibilitySwitch = accessibilityRow.toggle;
        mAccessibilitySubtitle = accessibilityRow.subtitle;
        SwitchRow floatingRow = addSwitchRow(menu, "悬浮窗",
                "已隐藏 · 悬浮控制与快捷入口", R.drawable.ic_robot_64,
                checked -> {
                    if (!mUpdatingSwitches) mActionListener.onDrawerAction(ACTION_FLOATING);
                });
        mFloatingSwitch = floatingRow.toggle;
        mFloatingSubtitle = floatingRow.subtitle;

        LinearLayout moreChildren = childContainer();
        addChildRow(moreChildren, "通知使用权", android.R.drawable.ic_dialog_info,
                ACTION_NOTIFICATION_ACCESS);
        addChildRow(moreChildren, "使用情况访问权限", android.R.drawable.ic_menu_recent_history,
                ACTION_USAGE_ACCESS);
        addChildRow(moreChildren, "悬浮窗权限", android.R.drawable.ic_menu_view,
                ACTION_OVERLAY_PERMISSION);
        addChildRow(moreChildren, "电池优化设置", android.R.drawable.ic_lock_idle_low_battery,
                ACTION_BATTERY_OPTIMIZATION);
        addExpandable(menu, "权限与后台", "系统权限、电池与后台运行",
                android.R.drawable.ic_menu_manage,
                KEY_MORE_EXPANDED, moreChildren);

        addGroup(menu, "开发工具");
        LinearLayout developerChildren = childContainer();
        mDeveloperActionLabel = addChildRow(developerChildren, "连接开发者调试",
                R.drawable.ic_connect_to_pc, ACTION_DEVELOPER);
        addChildRow(developerChildren, "连接帮助", android.R.drawable.ic_menu_help,
                ACTION_DEVELOPER_HELP);
        addExpandable(menu, "开发者调试", "VS Code 连接与调试帮助",
                R.drawable.ic_connect_to_pc,
                KEY_DEVELOPER_EXPANDED, developerChildren);
        addActionRow(menu, "终端", "在当前脚本目录打开", R.drawable.ic_developer,
                ACTION_TERMINAL);

        addGroup(menu, "应用");
        addActionRow(menu, "外观与主题", "修改主题颜色", R.drawable.ic_personalize, ACTION_THEME);
        addActionRow(menu, "设置", "脚本、编辑器与运行设置", R.drawable.ic_ali_settings,
                ACTION_SETTINGS);
        addActionRow(menu, "检查更新", "查看可用的新版本", R.drawable.ic_check_for_updates,
                ACTION_UPDATE);

        addGroup(menu, "帮助与社区");
        addActionRow(menu, "官方文档", "Auto.js 文档与学习资料",
                android.R.drawable.ic_menu_info_details, ACTION_BLOG);
        addActionRow(menu, "官方论坛", "社区交流与问题反馈", android.R.drawable.ic_menu_share,
                ACTION_CHANNEL);

        addExitFooter();
        updateServiceState(mAccessibilityEnabled, mFloatingShown, mDeveloperConnected);
    }

    private void addGroup(LinearLayout parent, String label) {
        TextView group = text(label, 13, TEXT_SECONDARY);
        group.setGravity(Gravity.CENTER_VERTICAL);
        group.setPadding(dp(20), dp(8), dp(12), 0);
        parent.addView(group, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
    }

    private SwitchRow addSwitchRow(LinearLayout parent, String label, String summary,
                                   @DrawableRes int icon, SwitchChangedListener listener) {
        LinearLayout row = baseRow(ROW_COLOR);
        row.addView(icon(icon), iconParams());
        LinearLayout labels = labelBlock(label, summary);
        TextView subtitle = (TextView) labels.getChildAt(1);
        row.addView(labels, new LinearLayout.LayoutParams(0, dp(54), 1f));
        SwitchCompat toggle = new SwitchCompat(getContext());
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}
        };
        toggle.setThumbTintList(new ColorStateList(states,
                new int[]{ACCENT, Color.rgb(190, 193, 196)}));
        toggle.setTrackTintList(new ColorStateList(states,
                new int[]{Color.rgb(38, 119, 106), Color.rgb(82, 86, 90)}));
        toggle.setContentDescription(label);
        row.addView(toggle, new LinearLayout.LayoutParams(dp(58), dp(54)));
        toggle.setOnCheckedChangeListener((button, checked) -> listener.onChanged(checked));
        row.setOnClickListener(view -> toggle.setChecked(!toggle.isChecked()));
        row.setContentDescription(label + "开关");
        parent.addView(row, rowParams(dp(56)));
        return new SwitchRow(toggle, subtitle);
    }

    private void addActionRow(LinearLayout parent, String label, String summary,
                              @DrawableRes int icon, int action) {
        LinearLayout row = baseRow(ROW_COLOR);
        row.addView(icon(icon), iconParams());
        row.addView(labelBlock(label, summary), new LinearLayout.LayoutParams(0, dp(54), 1f));
        row.setContentDescription(label);
        row.setOnClickListener(view -> runAfterClose(action));
        parent.addView(row, rowParams(dp(56)));
    }

    private TextView addChildRow(LinearLayout parent, String label, @DrawableRes int icon, int action) {
        LinearLayout row = baseRow(CHILD_COLOR);
        row.setPadding(dp(48), 0, dp(12), 0);
        ImageView image = icon(icon);
        row.addView(image, new LinearLayout.LayoutParams(dp(30), dp(46)));
        TextView title = text(label, 14, TEXT_PRIMARY);
        title.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(title, new LinearLayout.LayoutParams(0, dp(46), 1f));
        row.setContentDescription(label);
        row.setOnClickListener(view -> runAfterClose(action));
        parent.addView(row, rowParams(dp(46)));
        return title;
    }

    private void addExpandable(LinearLayout parent, String label, String summary,
                               @DrawableRes int icon,
                               String preferenceKey, LinearLayout children) {
        LinearLayout section = new LinearLayout(getContext());
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = baseRow(ROW_COLOR);
        row.addView(icon(icon), iconParams());
        row.addView(labelBlock(label, summary), new LinearLayout.LayoutParams(0, dp(54), 1f));
        TextView arrow = text("›", 25, TEXT_SECONDARY);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(42), dp(54)));
        section.addView(row, rowParams(dp(56)));
        section.addView(children, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        boolean expanded = mPreferences.getBoolean(preferenceKey, false);
        applyExpandedState(row, arrow, children, label, expanded);
        row.setOnClickListener(view -> {
            boolean next = children.getVisibility() != View.VISIBLE;
            applyExpandedState(row, arrow, children, label, next);
            mPreferences.edit().putBoolean(preferenceKey, next).apply();
            Log.i(TAG, label + (next ? " expanded" : " collapsed"));
        });
        parent.addView(section, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void applyExpandedState(View row, TextView arrow, View children, String label,
                                    boolean expanded) {
        children.setVisibility(expanded ? View.VISIBLE : View.GONE);
        arrow.setRotation(expanded ? 90f : 0f);
        row.setContentDescription(label + "，" + (expanded ? "已展开" : "已收起"));
        row.setSelected(expanded);
    }

    private LinearLayout childContainer() {
        LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(CHILD_COLOR);
        container.setTag(ROLE_BG_CHILD);
        return container;
    }

    private LinearLayout baseRow(int color) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), 0, dp(12), 0);
        row.setBackgroundColor(color);
        row.setTag(color == HEADER_COLOR ? ROLE_BG_TOOLBAR
                : color == CHILD_COLOR ? ROLE_BG_CHILD : ROLE_BG_ROW);
        row.setClickable(true);
        row.setFocusable(true);
        return row;
    }

    private LinearLayout labelBlock(String titleValue, String summaryValue) {
        LinearLayout labels = new LinearLayout(getContext());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(titleValue, 16, TEXT_PRIMARY);
        title.setMaxLines(1);
        TextView summary = text(summaryValue, 11, TEXT_SECONDARY);
        summary.setMaxLines(1);
        labels.addView(title);
        labels.addView(summary);
        return labels;
    }

    private void addExitFooter() {
        LinearLayout footer = baseRow(HEADER_COLOR);
        ImageView exitIcon = icon(R.drawable.ic_ali_exit);
        exitIcon.setColorFilter(DANGER, PorterDuff.Mode.SRC_IN);
        exitIcon.setTag(ROLE_ICON_DANGER);
        footer.addView(exitIcon, iconParams());
        LinearLayout labels = labelBlock("退出 AI.js Pro", "结束应用及后台服务");
        ((TextView) labels.getChildAt(0)).setTextColor(DANGER);
        labels.getChildAt(0).setTag(ROLE_TEXT_DANGER);
        footer.addView(labels, new LinearLayout.LayoutParams(0, dp(54), 1f));
        footer.setContentDescription("退出 AI.js Pro");
        footer.setOnClickListener(view -> runAfterClose(ACTION_EXIT));
        mDrawerPane.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
    }

    private ImageView icon(@DrawableRes int resource) {
        ImageView image = new ImageView(getContext());
        image.setImageResource(resource);
        image.setColorFilter(Color.rgb(221, 225, 228), PorterDuff.Mode.SRC_IN);
        image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        image.setTag(ROLE_ICON_PRIMARY);
        return image;
    }

    private LinearLayout.LayoutParams iconParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(34), dp(54));
        params.rightMargin = dp(12);
        return params;
    }

    private LinearLayout.LayoutParams rowParams(int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        params.bottomMargin = dp(1);
        return params;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (color == DANGER) view.setTag(ROLE_TEXT_DANGER);
        else if (color == ACCENT) view.setTag(ROLE_TEXT_ACCENT);
        else if (color == TEXT_SECONDARY) view.setTag(ROLE_TEXT_SECONDARY);
        else view.setTag(ROLE_TEXT_PRIMARY);
        return view;
    }

    private int themeAccent() {
        return mThemePalette != null ? mThemePalette.accent : ACCENT;
    }

    private int themeSecondaryText() {
        return mThemePalette != null ? mThemePalette.textSecondary : TEXT_SECONDARY;
    }

    private LayoutParams createDrawerLayoutParams(int width) {
        LayoutParams params = new LayoutParams(drawerWidth(width),
                ViewGroup.LayoutParams.MATCH_PARENT);
        params.gravity = GravityCompat.START;
        return params;
    }

    private int drawerWidth(int fullWidth) {
        return Math.min(Math.round(fullWidth * 0.78f), dp(480));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Creates the native Android hamburger button and adds it as an overlay on mContent.
     * The button sits on top of the SurfaceView and directly opens the drawer.
     * It does NOT depend on ImGui render frames for click handling.
     */
    private void buildHamburgerButton() {
        mHamburgerButton = new ImageView(getContext());
        mHamburgerButton.setImageResource(R.drawable.ic_menu_hamburger);
        mHamburgerButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mHamburgerButton.setColorFilter(Color.rgb(221, 225, 228), PorterDuff.Mode.SRC_IN);

        // Ripple feedback
        int rippleColor = Color.argb(40, 255, 255, 255);
        android.graphics.drawable.ShapeDrawable shape = new android.graphics.drawable.ShapeDrawable();
        shape.getPaint().setColor(Color.TRANSPARENT);
        shape.getPaint().setStyle(android.graphics.Paint.Style.FILL);
        android.graphics.drawable.shapes.OvalShape oval = new android.graphics.drawable.shapes.OvalShape();
        shape.setShape(oval);
        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(rippleColor), null, shape);
        mHamburgerButton.setBackground(ripple);

        mHamburgerButton.setContentDescription("打开侧栏菜单");
        mHamburgerButton.setClickable(true);
        mHamburgerButton.setFocusable(true);
        mHamburgerButton.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        // Minimum touch target: 48dp × 48dp (Material spec)
        int touchSize = dp(56);
        int iconSize = dp(24);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(touchSize, touchSize);
        lp.gravity = Gravity.TOP | Gravity.START;
        mHamburgerButton.setLayoutParams(lp);
        mHamburgerButton.setPadding(
                (touchSize - iconSize) / 2, (touchSize - iconSize) / 2,
                (touchSize - iconSize) / 2, (touchSize - iconSize) / 2);

        mHamburgerButton.setOnClickListener(v -> {
            open();
        });

        mContent.addView(mHamburgerButton);

        // Apply initial insets using ViewCompat for cross-API compatibility.
        // The listener is set on the hamburger button so it receives insets directly.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(mHamburgerButton, (v, windowInsets) -> {
            updateHamburgerInsets();
            return windowInsets;
        });
    }

    /**
     * Updates hamburger button position based on WindowInsets / DisplayCutout.
     * Reads insets directly from the View (works across all API levels).
     */
    private void updateHamburgerInsets() {
        if (mHamburgerButton == null) return;
        int left = dp(4);
        int top = dp(4);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            WindowInsets insets = mHamburgerButton.getRootWindowInsets();
            if (insets != null) {
                left += insets.getSystemWindowInsetLeft();
                top += insets.getSystemWindowInsetTop();
            }
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mHamburgerButton.getLayoutParams();
        lp.leftMargin = left;
        lp.topMargin = top;
        mHamburgerButton.setLayoutParams(lp);
    }

    /**
     * Shows or hides the hamburger button (e.g. hide when drawer is fully open).
     */
    void setHamburgerVisible(boolean visible) {
        if (mHamburgerButton != null) {
            mHamburgerButton.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void runAfterClose(int action) {
        if (isDrawerVisible(GravityCompat.START)) {
            mPendingAction = action;
            closeDrawer(GravityCompat.START, true);
        } else {
            mActionListener.onDrawerAction(action);
        }
    }

    private void recordDrawerState(boolean open) {
        String countKey = open ? KEY_OPEN_COUNT : KEY_CLOSE_COUNT;
        String timeKey = open ? KEY_LAST_OPENED_AT : KEY_LAST_CLOSED_AT;
        int count = mPreferences.getInt(countKey, 0) + 1;
        mPreferences.edit()
                .putBoolean(KEY_IS_OPEN, open)
                .putLong(timeKey, System.currentTimeMillis())
                .putInt(countKey, count)
                .apply();
        Log.i(TAG, "drawer " + (open ? "opened" : "closed") + ", count=" + count);
    }

    private interface SwitchChangedListener {
        void onChanged(boolean checked);
    }

    private static final class SwitchRow {
        final SwitchCompat toggle;
        final TextView subtitle;

        SwitchRow(SwitchCompat toggle, TextView subtitle) {
            this.toggle = toggle;
            this.subtitle = subtitle;
        }
    }
}
