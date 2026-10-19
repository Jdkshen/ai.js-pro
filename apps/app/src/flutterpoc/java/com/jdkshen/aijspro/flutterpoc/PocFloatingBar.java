package com.jdkshen.aijspro.flutterpoc;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.jdkshen.aijspro.flutterpoc.scriptproc.PocScriptHost;
import com.stardust.autojs.ScriptEngineService;
import com.stardust.autojs.execution.ExecutionConfig;
import com.stardust.autojs.execution.ScriptExecution;
import com.stardust.autojs.script.JavaScriptFileSource;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 「悬浮运行」显示的原生悬浮控制条：
 * 可拖动（位置持久化），只保留 ▶ 运行当前脚本、⏹ 停止全部两个运行/停止入口，
 * 另有 📄 打开 Flutter 运行日志、✎ 在编辑器打开当前脚本、✕ 关闭。
 *
 * <p>「停止当前」入口已按用户要求移除（task-42）：停止只保留 ⏹ 停止全部，
 * 与音量上键/通知栏停止同一语义；切换脚本/关闭悬浮条时的内部清理不算 UI 入口。
 *
 * <p>Phase1 起运行/停止都交给主进程统一入口 {@link PocRuntime#runScript} /
 * {@link PocRuntime#stopRunningScript}：默认跑在 :script 宿主进程里（崩了不影响主界面），
 * 宿主不可用时回退 :native/主进程；开关关闭时保持 Phase0 的主进程引擎路径。
 */
final class PocFloatingBar {

    private static final String TAG = "GlassFlutterPoc";
    private static final String PREFS = "flutter-glass";
    private static final String KEY_X = "floaty_bar.x";
    private static final String KEY_Y = "floaty_bar.y";
    private static final String KEY_COLLAPSED = "floaty_bar.collapsed";
    /** Flutter 玻璃主界面：📄 日志入口走 pending_action 后拉起它，由 Flutter 打开 LogPage。 */
    private static final String GLASS_ACTIVITY =
            "com.jdkshen.aijspro.flutterpoc.GlassFlutterActivity";
    /** pendingAction 的存储键（与 PocIntegration.pendingFromPrefs 读取的 key 一致）。 */
    private static final String KEY_PENDING_ACTION = "pending_action";
    private static final String EDITOR_ACTIVITY =
            "com.jdkshen.aijspro.ui.editor.ProCodeEditorActivity";

    private static WindowManager windowManager;
    private static View barView;
    private static TextView dragView;
    private static TextView labelView;
    private static ChevronView collapseView;
    private static final List<View> collapsible = new ArrayList<>();
    private static boolean collapsed;
    private static WindowManager.LayoutParams params;
    private static Context appContext;
    private static File scriptFile;
    private static ScriptExecution execution;

    private static float touchDownX;
    private static float touchDownY;
    private static int startX;
    private static int startY;

    private PocFloatingBar() {
    }

    static boolean isShowing() {
        return barView != null;
    }

    static Object show(android.app.Activity activity, String path) throws Exception {
        if (BuildVersion.overlayForbidden(activity)) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (Throwable error) {
                Log.w(TAG, "无法打开悬浮窗授权页", error);
            }
            return new JSONObject().put("needsPermission", true);
        }
        File file = new File(path).getCanonicalFile();
        if (!file.isFile()) {
            throw new IOException("脚本不存在：" + file.getName());
        }
        if (!file.getName().toLowerCase(java.util.Locale.ROOT).matches(".+\\.(js|mjs)")) {
            throw new IOException("请选择 JavaScript 脚本");
        }
        // 换一个脚本时先停掉控制条上正在跑的那个，避免「标题是 A、跑的是 B」。
        // 这是内部清理，不是用户可见的「停止当前」入口（该按钮已移除）。
        if (scriptFile != null && !scriptFile.getPath().equals(file.getPath())) {
            stopCurrentScript();
        }
        scriptFile = file;
        appContext = activity.getApplicationContext();
        install(appContext);
        return new JSONObject().put("needsPermission", false);
    }

    private static void install(Context context) {
        if (barView != null) {
            updateLabel();
            return;
        }
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        collapsible.clear();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 8), dp(context, 4), dp(context, 4), dp(context, 4));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF01A1F27);
        background.setCornerRadius(dp(context, 22));
        background.setStroke(dp(context, 1), 0x66FFFFFF);
        row.setBackground(background);

        dragView = action(context, "≡", 0xFFE8F3F1, null);
        dragView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        dragView.setContentDescription("拖动悬浮控制条");
        GradientDrawable dragChip = new GradientDrawable();
        dragChip.setShape(GradientDrawable.OVAL);
        dragChip.setColor(0x1FFFFFFF);
        dragView.setBackground(dragChip);
        dragView.setOnTouchListener((view, event) -> dragBar(view, event));
        row.addView(dragView);

        labelView = new TextView(context);
        labelView.setTextColor(0xFFE8F3F1);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        labelView.setSingleLine(true);
        labelView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        labelView.setMaxWidth(dp(context, 84));
        labelView.setPadding(dp(context, 2), 0, dp(context, 2), 0);
        labelView.setOnTouchListener((view, event) -> dragBar(view, event));
        row.addView(labelView);
        collapsible.add(labelView);

        collapseView = new ChevronView(context);
        collapseView.setColor(0xFFC9A7FF);
        collapseView.setContentDescription("收起悬浮窗");
        collapseView.setTooltipText("收起悬浮窗");
        GradientDrawable collapseChip = new GradientDrawable();
        collapseChip.setShape(GradientDrawable.OVAL);
        collapseChip.setColor(0x26C9A7FF);
        collapseView.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x40FFFFFF), collapseChip, null));
        collapseView.setOnClickListener(view -> toggleCollapsed());
        LinearLayout.LayoutParams collapseParams = new LinearLayout.LayoutParams(dp(context, 30), dp(context, 30));
        collapseParams.setMargins(dp(context, 2), 0, dp(context, 2), 0);
        row.addView(collapseView, collapseParams);
        collapsible.add(collapseView);
        // 只保留一个运行和一个停止：▶ 运行 / ⏹ 停止全部（不再提供 ■ 停止当前）。
        addCollapsible(row, action(context, "▶", 0xFF6BE88A, view -> start()));
        addCollapsible(row, action(context, "⏹", 0xFFFFB74D, view -> stopAll()));
        TextView edit = action(context, "✎", 0xFF7EC8FF, view -> openEditor());
        edit.setContentDescription("在编辑器中打开当前脚本");
        addCollapsible(row, edit);
        addCollapsible(row, action(context, "📄", 0xFF4DD0E1, view -> openLog()));
        addCollapsible(row, action(context, "✕", 0xFFFF7B8A, view -> close()));

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                BuildVersion.overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = prefs.getInt(KEY_X, dp(context, 24));
        params.y = prefs.getInt(KEY_Y, dp(context, 140));
        barView = row;
        collapsed = prefs.getBoolean(KEY_COLLAPSED, false);
        applyCollapsed();
        updateLabel();
        // 先量一遍内容再固定窗口尺寸：否则覆盖窗口按「不超出屏幕」的旧宽度测量，
        // 最右边的 ✕ 会被窗口裁掉（dev-920 现象）。
        row.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int contentWidth = Math.min(row.getMeasuredWidth(),
                context.getResources().getDisplayMetrics().widthPixels - dp(context, 8));
        params.width = contentWidth;
        params.height = row.getMeasuredHeight();
        try {
            windowManager.addView(row, params);
            row.addOnLayoutChangeListener((view, left, top, right, bottom,
                                           oldLeft, oldTop, oldRight, oldBottom) -> {
                if (right - left != oldRight - oldLeft) {
                    clampPosition();
                }
            });
            row.post(PocFloatingBar::clampPosition);
        } catch (Throwable error) {
            Log.w(TAG, "悬浮控制条添加失败", error);
            barView = null;
        }
    }

    private static boolean dragBar(View view, MotionEvent event) {
        if (params == null || windowManager == null || barView == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = event.getRawX();
                touchDownY = event.getRawY();
                startX = params.x;
                startY = params.y;
                return true;
            case MotionEvent.ACTION_MOVE: {
                params.x = Math.max(0, startX + Math.round(event.getRawX() - touchDownX));
                params.y = Math.max(0, startY + Math.round(event.getRawY() - touchDownY));
                clampPosition();
                try {
                    windowManager.updateViewLayout(barView, params);
                } catch (Throwable ignored) {
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                savePosition();
                view.performClick();
                return true;
            default:
                return false;
        }
    }

    /** 保证控制条不超出屏幕（加按钮后变宽，右侧可能被裁掉）。 */
    private static void clampPosition() {
        if (params == null || barView == null || appContext == null) {
            return;
        }
        android.util.DisplayMetrics metrics = appContext.getResources().getDisplayMetrics();
        int barWidth = params.width > 0 ? params.width : barView.getWidth();
        int maxX = Math.max(0, metrics.widthPixels - barWidth - dp(appContext, 8));
        int maxY = Math.max(0, metrics.heightPixels - barView.getHeight() - dp(appContext, 8));
        params.x = Math.max(0, Math.min(params.x, maxX));
        params.y = Math.max(0, Math.min(params.y, maxY));
        if (windowManager != null) {
            try {
                windowManager.updateViewLayout(barView, params);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void savePosition() {
        if (appContext == null || params == null) {
            return;
        }
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply();
    }

    /** 自绘的收起/展开 chevron：几何居中，不受字体基线影响。 */
    private static final class ChevronView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean mCollapsed;

        ChevronView(Context context) {
            super(context);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
            mPaint.setStrokeWidth(2.1f * context.getResources().getDisplayMetrics().density);
        }

        void setColor(int color) {
            mPaint.setColor(color);
            invalidate();
        }

        void setCollapsed(boolean collapsed) {
            mCollapsed = collapsed;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float half = getWidth() * 0.19f;
            float top = getHeight() * 0.10f;
            float bottom = getHeight() * 0.10f;
            canvas.save();
            if (mCollapsed) {
                canvas.rotate(180f, cx, cy);
            }
            Path path = new Path();
            path.moveTo(cx - half, cy - top);
            path.lineTo(cx, cy + bottom);
            path.lineTo(cx + half, cy - top);
            canvas.drawPath(path, mPaint);
            canvas.restore();
        }
    }

    private static void addCollapsible(LinearLayout row, TextView view) {
        row.addView(view);
        collapsible.add(view);
    }

    private static TextView action(Context context, String text, int color,
                                  View.OnClickListener listener) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        view.setPadding(0, 0, 0, 0);
        view.setLineSpacing(0, 1f);
        int size = dp(context, 30);
        view.setMinWidth(size);
        view.setMinHeight(size);
        GradientDrawable chip = new GradientDrawable();
        chip.setShape(GradientDrawable.OVAL);
        // 每个按钮一圈同色半透明底，远看能分清颜色又不会太花。
        chip.setColor((color & 0x00FFFFFF) | 0x26000000);
        // 按下时有轻微水波反馈，点起来不「木」。
        view.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x40FFFFFF), chip, null));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(context, 2), 0, dp(context, 2), 0);
        view.setLayoutParams(params);
        if (listener != null) {
            view.setOnClickListener(listener);
        }
        return view;
    }

    /**
     * ▶ 运行当前脚本：默认交给 {@link PocRuntime#runScript} 统一入口（:script / :native / 回退
     * 主进程都在这条路由里决定）。
     *
     * <p>以下两种保留 Phase0 的主进程引擎路径（由悬浮条自己持有 execution，用于 ● 运行状态显示）：
     * <ul>
     *   <li>{@code "auto";} 执行模式脚本且 AUTO 门控关闭
     *       （{@link PocRuntime#isAutoInScriptEnabled()} 默认 false；P1 验收后打开开关，
     *       auto 脚本也会和普通脚本一样交给 :script，远程自动化代理见 task-20）；</li>
     *   <li>{@link PocScriptHost#isEnabled()} 关闭（回退开关）。</li>
     * </ul>
     */
    private static void start() {
        if (scriptFile == null || isRunning()) {
            // 已经在跑（宿主进程或旧的主进程执行对象）：重复点 ▶ 不重复启动。
            updateLabel();
            return;
        }
        // auto 脚本在 AUTO 门控关闭时仍留主进程；门控打开（P1 验收后）即和普通脚本一样走 :script。
        boolean autoStaysInMainProcess = PocRuntime.isAutoModeScript(scriptFile)
                && !PocRuntime.isAutoInScriptEnabled();
        if (PocScriptHost.isEnabled() && !autoStaysInMainProcess) {
            try {
                PocRuntime.runScript(appContext, scriptFile.getPath());
                updateLabel();
            } catch (Throwable error) {
                Log.w(TAG, "悬浮运行启动失败", error);
                updateLabel("启动失败：" + error.getMessage());
            }
            return;
        }
        // 旧路径：auto 脚本需要主进程 Accessibility；或 PocScriptHost 回退开关关闭。
        try {
            JavaScriptFileSource source = new JavaScriptFileSource(scriptFile);
            ExecutionConfig config = ExecutionConfig.getDefault();
            config.setWorkingDirectory(scriptFile.getParent() == null ? "" : scriptFile.getParent());
            execution = ScriptEngineService.getInstance().execute(source, config);
            updateLabel();
        } catch (Throwable error) {
            Log.w(TAG, "悬浮运行启动失败", error);
            execution = null;
            updateLabel("启动失败：" + error.getMessage());
        }
    }

    /** 当前脚本是否在跑：先看宿主进程（:script/:native）里有没有同路径任务，再兼容旧 execution。 */
    private static boolean isRunning() {
        if (execution != null) {
            return true;
        }
        if (scriptFile == null) {
            return false;
        }
        try {
            return PocRuntime.hasRunningScript(scriptFile.getPath());
        } catch (Throwable error) {
            Log.w(TAG, "查询脚本运行状态失败", error);
            return false;
        }
    }

    /**
     * 内部清理「控制条当前脚本」：换脚本/关闭悬浮条时用，不是用户可见的停止入口
     * （UI 上已移除 ■ 停止当前，只保留 ⏹ 停止全部）。
     *
     * <p>宿主进程里按路径停（:script 优先，:native 兜底），旧主进程执行对象兼容。
     */
    private static void stopCurrentScript() {
        if (scriptFile != null) {
            try {
                PocRuntime.stopRunningScript(scriptFile.getPath());
            } catch (Throwable error) {
                Log.w(TAG, "停止宿主进程脚本失败", error);
            }
        }
        ScriptExecution current = execution;
        execution = null;
        if (current != null) {
            try {
                if (current.getEngine() != null) {
                    current.getEngine().forceStop();
                }
            } catch (Throwable error) {
                Log.w(TAG, "悬浮运行停止失败", error);
            }
        }
        updateLabel();
    }

    /** ⏹ 停止全部：宿主进程（:script/:native）里的任务 + 旧主进程引擎任务一起停。 */
    /** task-165：悬浮条 ⏹ 1.2s 防抖，避免连点重复触发停止/通知栏反复刷新。 */
    private static long sLastStopAllAt;

    private static void stopAll() {
        long now = System.currentTimeMillis();
        if (now - sLastStopAllAt < 1200L) {
            return;
        }
        sLastStopAllAt = now;
        // task-165：统一停止入口（宿主进程 + 主进程 + Node UI overlay + 前台通知）。
        PocRuntime.stopAllScripts(appContext);
        execution = null;
        try {
            PocRuntime.stopAllHostScripts();
        } catch (Throwable error) {
            Log.w(TAG, "停止全部宿主进程脚本失败", error);
        }
        try {
            ScriptEngineService.getInstance().stopAll();
        } catch (Throwable error) {
            Log.w(TAG, "停止全部脚本失败", error);
        }
        updateLabel();
    }

    /** task-166：调试广播复验悬浮菜单停止入口（仅 flutterPoc debug）。 */
    static void debugStopAll() {
        stopAll();
    }

    /** 收起/展开：对齐原厂控制条——收起时隐藏标题与整排按钮，只留箭头。 */
    private static void toggleCollapsed() {
        collapsed = !collapsed;
        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_COLLAPSED, collapsed).apply();
            if (collapsed && !prefs.getBoolean("floaty_bar.hint_shown", false)) {
                prefs.edit().putBoolean("floaty_bar.hint_shown", true).apply();
                Toast.makeText(appContext, "已收起悬浮窗，点 ⌃ 展开", Toast.LENGTH_SHORT).show();
            }
        }
        // 收起/展开会大幅改变宽度：重建一次窗口，避免透传窗口沿用旧宽度把最后的按钮裁掉。
        if (barView != null && windowManager != null) {
            try {
                windowManager.removeView(barView);
            } catch (Throwable ignored) {
            }
            barView = null;
            collapsible.clear();
            install(appContext);
        } else {
            applyCollapsed();
        }
    }

    private static void applyCollapsed() {
        // 对齐原厂 FloatingControllerView.collapse()：收起时隐藏标题与整排按钮，只留折叠箭头；
        // 拖动把手是本实现额外加的，收起时保留（否则没法再拖动）。
        for (View view : collapsible) {
            if (view == collapseView) {
                continue;
            }
            view.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        if (collapseView != null) {
            // 自绘 chevron：展开态「⌄」提示可收起，收起态「⌃」提示可展开（旋转 180°）。
            collapseView.setCollapsed(collapsed);
            collapseView.setVisibility(View.VISIBLE);
            collapseView.setContentDescription(collapsed ? "展开悬浮窗" : "收起悬浮窗");
            collapseView.setTooltipText(collapsed ? "展开悬浮窗" : "收起悬浮窗");
        }
    }

    /** 原厂控制条的 ✎：在原生 Ace 工作区打开当前脚本。 */
    private static void openEditor() {
        if (appContext == null || scriptFile == null) {
            return;
        }
        try {
            appContext.startActivity(new Intent().setClassName(appContext, EDITOR_ACTIVITY)
                    .putExtra("path", scriptFile.getPath())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable error) {
            Log.w(TAG, "打开编辑器失败", error);
        }
    }

    /**
     * 📄 打开 Flutter 新版运行日志页（与工具栏日志入口一致）。
     *
     * <p>悬浮条跑在覆盖窗口里、拿不到 Flutter 的导航栈，所以沿用 ✎/▤ 那套 pendingAction：
     * 先把 {@code {"action":"log"}} 写进 flutter-glass 偏好，再拉起/恢复 GlassFlutterActivity；
     * Flutter resume 时 consumeFloatingAction 会 openUtility(LogPage())，读完即清 pending。
     * 不再直接启动原生日志 Activity。
     */
    private static void openLog() {
        if (appContext != null) com.jdkshen.aijspro.log.UnifiedLogNavigation.open(appContext);
    }

    private static void close() {
        stopCurrentScript();
        if (barView != null && windowManager != null) {
            try {
                windowManager.removeView(barView);
            } catch (Throwable ignored) {
            }
            barView = null;
        }
    }

    private static void updateLabel() {
        updateLabel(null);
    }

    private static void updateLabel(String status) {
        if (labelView == null || scriptFile == null) {
            return;
        }
        if (status != null) {
            labelView.setText(status);
            return;
        }
        String name = scriptFile.getName();
        boolean running = isRunning();
        if (dragView != null) {
            dragView.setTextColor(running ? 0xFF6BE88A : 0xFFE8F3F1);
        }
        labelView.setText(running ? "● " + name : "▶ " + name);
        labelView.setTextColor(running ? 0xFF6BE88A : 0xFFE8F3F1);
        labelView.setTypeface(null, running ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    /** 系统版本兼容的小工具，避免在布局代码里散落 Build 判断。 */
    static final class BuildVersion {
        static boolean overlayForbidden(Context context) {
            return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                    && !Settings.canDrawOverlays(context);
        }

        static int overlayType() {
            return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
        }
    }
}