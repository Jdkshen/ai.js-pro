package com.stardust.autojs.runtime.api;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;

import com.stardust.autojs.core.image.capture.AccessibilityScreenCapturer;

/**
 * 脚本可用的 **液态玻璃透镜**：按住容器的某处，会浮起一个圆形透镜，
 * 里面是「背后真实内容」的放大 + 色散折射 —— 与酷安 16.x 底栏按住拖动的效果同构。
 *
 * <p>怎么拿到「背后的内容」：走**无障碍截图通道**（{@code AccessibilityScreenCapturer}，
 * Android 11+，无需 MediaProjection 授权、不弹窗）。因为脚本运行在独立 Activity 里，
 * {@code RenderEffect} 的 {@code content} 只能采样本 Activity 的渲染树、拿不到宿主 App，
 * 所以必须先「截一张整屏」再把它当作 {@code uniform shader} 的输入。
 * 系统对无障碍截图有 ~340ms 限频 ⇒ 只在**手指按下时抓一次**（拖动过程中背景静止，够用）。
 *
 * <p>用法（脚本）：
 * <pre>
 * var Lens = Packages.com.stardust.autojs.runtime.api.AgsLiquidLens;
 * var lens = new Lens(ctx, 90f);            // 90 = 透镜半径 px
 * bar.addView(lens, new FrameLayout.LayoutParams(w, h));   // 覆盖整个 bar
 * lens.attachTo(bar);                       // 让它接管 bar 的触摸
 * </pre>
 */
public class AgsLiquidLens extends View {

    /** 透镜本体：放大 + R/G/B 分别按略微不同的偏移采样 ⇒ 边缘出现真实色散（彩虹边）。 */
    private static final String SHADER_SRC =
            "uniform shader content;\n"
            + "uniform float2 lensCenter;\n"      // 透镜中心（本 View 坐标系）
            + "uniform float lensRadius;\n"
            + "uniform float magnification;\n"
            + "uniform float aberration;\n"
            + "uniform float2 screenOffset;\n"    // 本 View 在屏幕上的左上角（截图坐标系）
            + "uniform float2 screenScale;\n"     // 截图/屏幕 与 View 的缩放比（通常 1,1）
            + "uniform float coordDebug;\n"       // 1 = 把采样坐标画成颜色（调试坐标系用）
            + "uniform float2 screenSize;\n"
            + "half4 main(float2 xy) {\n"
            + "  float2 d = xy - lensCenter;\n"
            // 归一化：先把偏移除半径再算长度 ⇒ 无论 View 多扁，透镜都是**正圆**（否则 970x154 的 View 会把它压成椭圆）
            + "  float t = length(d) / lensRadius;\n"
            + "  if (t > 1.0) { return half4(0.0); }\n"
            + "  float m = mix(magnification, 1.0, smoothstep(0.0, 1.0, t));\n"
            + "  float2 c = lensCenter + d / m + screenOffset;\n"
            + "  if (coordDebug > 0.5) { return half4(c.x / max(screenSize.x,1.0), c.y / max(screenSize.y,1.0), 0.5, 1.0); }\n"
            // 色散：三个通道在同一坐标系里做微小偏移（不要用 screenScale —— 截图与屏幕本就 1:1，
            // 而 screenScale.y = 2400/158 = 15.19 会把 R/B 采样点推出屏幕外，表现为"偏红"）
            + "  float2 dc = d / m;\n"
            + "  half r = content.eval(c + dc * aberration).r;\n"
            + "  half g = content.eval(c).g;\n"
            + "  half b = content.eval(c - dc * aberration).b;\n"
            + "  float alpha = 1.0 - smoothstep(0.88, 1.0, t);\n"
            + "  float edge = smoothstep(0.72, 1.0, t) * (1.0 - smoothstep(0.90, 1.0, t));\n"
            + "  return half4(half3(r, g, b) * 1.45 + edge * 0.55, alpha);\n"
            + "}\n";

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radiusPx;
    private float magnification = 1.55f;
    private float aberration = 0.014f;
    private float cx = -1f, cy = -1f;
    private Bitmap screenBitmap;
    private boolean failed;
    private boolean loggedOnce;
    /** 调试：1 = 透镜里把「采样坐标」画成颜色（红=X/宽, 绿=Y/高），用来定位坐标系错位。 */
    private float coordDebug = 0f;
    /** 脚本可显式指定本 View 在屏幕上的左上角（比 getLocationOnScreen 可靠：收口窗口下它可能返回窗口内坐标）。 */
    private float explicitOffX = Float.NaN, explicitOffY = Float.NaN;

    /**
     * 预览模式：不依赖触摸，直接在指定位置画一个透镜并抓屏。
     * 用途：自动化验证 / 开机自检 —— MCP 注入的触摸事件进不了脚本窗口，只有真实手指能触发 onTouchEvent。
     */
    public void previewAt(final float x, final float y) {
        post(new Runnable() {
            @Override public void run() {
                cx = x; cy = y;
                captureScreen();
                invalidate();
                android.util.Log.i("AgsLiquidLens", "previewAt " + x + "," + y);
            }
        });
    }

    /** 关闭预览（触摸会接管）。 */
    public void clearPreview() { cx = -1f; cy = -1f; invalidate(); }

    /** 显式指定屏幕偏移（截图坐标系）。传 NaN 恢复自动检测。 */
    public void setScreenOffset(float x, float y) { this.explicitOffX = x; this.explicitOffY = y; paint.setShader(null); invalidate(); }
    private float screenW = 1080f, screenH = 2400f;

    public void setCoordDebug(boolean on) { this.coordDebug = on ? 1f : 0f; paint.setShader(null); invalidate(); }

    public AgsLiquidLens(Context context, float radiusPx) {
        super(context);
        this.radiusPx = Math.max(24f, radiusPx);
        setWillNotDraw(false);
        // 自己收触摸：本类作为 bar 的最上层子 View，天然能收到触摸；
        // onTouchEvent 返回 false 表示「不消费」，事件继续派发给下层 tab（点击照旧有效）。
        setClickable(true);
        setFocusable(false);
    }

    /** 放大倍率（1.0 = 不放大，1.8 已经很明显）。 */
    public void setMagnification(float m) {
        this.magnification = Math.max(1.0f, Math.min(2.5f, m));
        invalidate();
    }

    /** 色散强度（0.005 轻微，0.03 很夸张）。 */
    public void setAberration(float a) {
        this.aberration = Math.max(0f, Math.min(0.05f, a));
        invalidate();
    }

    /** 把自己挂到容器的触摸上（这样不必当子 View 也能收事件；addView 后同样有效）。 */
    public void attachTo(View host) {
        if (host == null) return;
        host.setOnTouchListener((v, ev) -> onHostTouch(v, ev));
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        handleTouch(ev);
        return false;   // 不消费 ⇒ 事件继续派发给下层 tab
    }

    private boolean onHostTouch(View v, MotionEvent ev) {
        handleTouch(ev);
        return false;
    }

    private void handleTouch(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cx = ev.getX(); cy = ev.getY();
                captureScreen();
                invalidate();
                scheduleAutoHide();
                break;
            case MotionEvent.ACTION_MOVE:
                cx = ev.getX(); cy = ev.getY();
                invalidate();
                scheduleAutoHide();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(hideRunnable);
                cx = -1f; cy = -1f;
                invalidate();
                break;
            default:
                break;
        }
    }

    /**
     * 兜底自动隐藏：onTouchEvent 返回 false（为了不吞 tab 点击）⇒ 系统只把 DOWN 派给我们，
     * MOVE/UP 收不到 ⇒ 原来松开后透镜会一直停在按下位置。
     * 这里在 DOWN 时挂一个短定时器，到点无条件隐藏；每次 DOWN/MOVE 都重置。
     */
    private final Runnable hideRunnable = new Runnable() {
        @Override public void run() {
            cx = -1f; cy = -1f;
            invalidate();
            android.util.Log.i("AgsLiquidLens", "auto-hide (no further touch events)");
        }
    };

    private void scheduleAutoHide() {
        removeCallbacks(hideRunnable);
        postDelayed(hideRunnable, 5000L);   // 按住不动 5s 内都可见；松开后最长 5s 消失（收不到 UP 的妥协）
    }

    /** 抓一张整屏（无障碍通道，Android 11+、无需授权）。失败不崩，只降级为不画透镜。 */
    private void captureScreen() {
        if (failed) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { failed = true; return; }
        try {
            // 注意：不要用 isAvailable() 做前置拦截 —— 它只看**本进程**的 AccessibilityService 单例，
            // 而脚本跑在 :script 隔离进程、无障碍服务在主进程 ⇒ 这里恒 false，会把跨进程分支也拦死。
            // capture() 内部会自己选路：本进程无障碍 → 跨进程 RemoteAutomationBridge。
            Bitmap shot = new AccessibilityScreenCapturer().capture(2500L);
            if (screenBitmap != null && screenBitmap != shot) screenBitmap.recycle();
            screenBitmap = shot;
            screenW = shot.getWidth(); screenH = shot.getHeight();
            android.util.Log.i("AgsLiquidLens", "capture size " + screenW + "x" + screenH);
            if (!loggedOnce) {
                loggedOnce = true;
                int[] l0 = new int[2]; getLocationOnScreen(l0);
                android.util.Log.i("AgsLiquidLens", "lens geom: view=" + getWidth() + "x" + getHeight()
                        + " loc=" + l0[0] + "," + l0[1] + " radiusPx=" + radiusPx
                        + " root=" + getRootView().getWidth() + "x" + getRootView().getHeight()
                        + " screen=" + shot.getWidth() + "x" + shot.getHeight());
            }
            invalidate();
        } catch (Throwable e) {
            failed = true;
            android.util.Log.w("AgsLiquidLens", "screen capture failed (lens disabled): " + e, e);
        }
    }
    @Override
    protected void onDraw(Canvas canvas) {
        if (failed || screenBitmap == null || cx < 0f) return;
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            if (paint.getShader() == null) {
                BitmapShader bs = new BitmapShader(screenBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                paint.setShader(Api33.createLens(SHADER_SRC, bs));
            }
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            if (!Float.isNaN(explicitOffX)) { loc[0] = Math.round(explicitOffX); loc[1] = Math.round(explicitOffY); }
            Api33.updateLens(paint.getShader(), cx, cy, radiusPx, magnification, aberration,
                    loc[0], loc[1],
                    screenBitmap.getWidth() / (float) Math.max(1, getRootView().getWidth()),
                    screenBitmap.getHeight() / (float) Math.max(1, getRootView().getHeight()),
                    coordDebug, screenBitmap.getWidth(), screenBitmap.getHeight());
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), paint);
        } catch (Throwable e) {
            failed = true;
            paint.setShader(null);
            android.util.Log.w("AgsLiquidLens", "lens draw failed (disabled)", e);
        }
    }

    @androidx.annotation.RequiresApi(33)
    private static final class Api33 {
        static Shader createLens(String src, BitmapShader content) {
            android.graphics.RuntimeShader sh = new android.graphics.RuntimeShader(src);
            sh.setInputShader("content", content);
            return sh;
        }

        static void updateLens(Shader shader, float cx, float cy, float radius,
                               float magnification, float aberration,
                               float offX, float offY, float scaleX, float scaleY,
                               float coordDebug, float screenW, float screenH) {
            android.graphics.RuntimeShader sh = (android.graphics.RuntimeShader) shader;
            sh.setFloatUniform("lensCenter", cx, cy);
            sh.setFloatUniform("lensRadius", radius);
            sh.setFloatUniform("magnification", magnification);
            sh.setFloatUniform("aberration", aberration);
            sh.setFloatUniform("screenOffset", offX, offY);
            sh.setFloatUniform("screenScale", scaleX, scaleY);
            sh.setFloatUniform("coordDebug", coordDebug);
            sh.setFloatUniform("screenSize", screenW, screenH);
        }
    }
}