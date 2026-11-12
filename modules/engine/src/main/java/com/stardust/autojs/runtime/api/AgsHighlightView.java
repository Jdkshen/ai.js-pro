package com.stardust.autojs.runtime.api;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.view.View;

/**
 * 脚本可用的 **AGSL 高光层**：给一个容器（比如悬浮窗里的 bar）叠一层「法线高光」。
 *
 * <p>为什么要专门做一个类（而不是脚本里 {@code new JavaAdapter(...)}）：
 *  · {@code JavaAdapter} 是 <b>Rhino 引擎特有</b>的动态子类机制；Node 引擎下 {@code JavaAdapter} 不可用
 *    （实测三种取法全部 {@code undefined}）⇒ 脚本无法在 Node 下自造匿名 View；
 *  · 而 Node 引擎下 {@code ui.Activity} 也不存在、{@code class} 语法也不被支持 ⇒ 换 Rhino 也走不通。
 *  ⇒ 结论：**由宿主提供一个现成的 View 类**，脚本只负责 {@code new} 出来 + {@code addView}。
 *    这样两件事同时成立：① 不需要 JavaAdapter；② 打包产物（模板来自 engine）天然自带。
 *
 * <p>脚本用法：
 * <pre>
 * var V = new Packages.com.stardust.autojs.runtime.api.AgsHighlightView(ctx, w, h, radius, falloff);
 * bar.addView(V, new Packages.android.widget.FrameLayout.LayoutParams(w, h));
 * // 尺寸/圆角变化时：V.update(w, h, radius, falloff)
 * </pre>
 *
 * <p>API &lt; 33 没有 {@code RuntimeShader}：**静默不画**（高光只是装饰，降级为纯色玻璃，不影响功能）。
 */
public class AgsHighlightView extends View {

    /** 法线高光：圆角矩形边缘取法线，与光照方向点乘，得到一条「边缘亮、中间透」的光。 */
    private static final String SHADER_SRC =
            // 圆角矩形有符号距离场(SDF) + 边缘光带。
            //   上一版只有「法线方向」没有「距离」⇒ 矩形内部也算出 d=±0.707 ⇒ it=0.5 ⇒ 整块发白（真机看到右下角白三角）。
            //   现在 alpha = band(边缘带) × lit^falloff × 强度；内部 sd 很负 ⇒ band=0 ⇒ 直接返回全透明。
            "uniform float2 size;\n"
            + "uniform float cornerRadius;\n"
            + "uniform float2 lightDir;\n"
            + "uniform float falloff;\n"
            + "uniform float thickness;\n"
            + "float sdRoundBox(float2 p, float2 hs, float r) {\n"
            + "  float2 q = abs(p) - hs + float2(r);\n"
            + "  return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;\n"
            + "}\n"
            + "float2 grad(float2 p, float2 hs, float r) {\n"
            + "  float e = 0.7;\n"
            + "  float dx = sdRoundBox(p + float2(e, 0.0), hs, r) - sdRoundBox(p - float2(e, 0.0), hs, r);\n"
            + "  float dy = sdRoundBox(p + float2(0.0, e), hs, r) - sdRoundBox(p - float2(0.0, e), hs, r);\n"
            + "  float2 g = float2(dx, dy);\n"
            + "  float len = length(g);\n"
            + "  return len < 0.0001 ? float2(0.0) : g / len;\n"
            + "}\n"
            + "half4 main(float2 c) {\n"
            + "  float2 hs = size * 0.5;\n"
            + "  float2 p = c - hs;\n"
            + "  float sd = sdRoundBox(p, hs, cornerRadius);\n"
            + "  float dist = -sd;\n"
            + "  float band = 1.0 - clamp(dist / thickness, 0.0, 1.0);\n"
            + "  if (band <= 0.001) { return half4(0.0, 0.0, 0.0, 0.0); }\n"
            + "  float2 n = grad(p, hs, cornerRadius);\n"
            + "  float lit = abs(dot(n, normalize(lightDir)));\n"
            + "  return half4(1.0, 1.0, 1.0, band * pow(lit, falloff) * 0.45);\n"
            + "}\n";

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float widthPx;
    private float heightPx;
    private float cornerRadius;
    private float falloff;
    private boolean failed;

    public AgsHighlightView(Context context, float widthPx, float heightPx, float cornerRadius, float falloff) {
        super(context);
        this.widthPx = widthPx;
        this.heightPx = heightPx;
        this.cornerRadius = cornerRadius;
        this.falloff = falloff <= 0f ? 2.0f : falloff;
        setWillNotDraw(false);
    }

    /** 尺寸/圆角/衰减变化时更新（会重建 shader 的 uniform）。 */
    public void update(float widthPx, float heightPx, float cornerRadius, float falloff) {
        this.widthPx = widthPx;
        this.heightPx = heightPx;
        this.cornerRadius = cornerRadius;
        if (falloff > 0f) this.falloff = falloff;
        this.failed = false;
        paint.setShader(null);
        invalidate();
    }

    /** shader 是否可用（false ⇒ 当前系统/画布不支持，正在降级为不画）。 */
    public boolean isSupported() {
        return Build.VERSION.SDK_INT >= 33 && !failed;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (Build.VERSION.SDK_INT < 33) return;
        if (widthPx <= 0f || heightPx <= 0f) return;
        try {
            if (paint.getShader() == null) {
                paint.setShader(Api33.createShader(SHADER_SRC, widthPx, heightPx, cornerRadius, falloff));
            } else {
                Api33.update(paint.getShader(), widthPx, heightPx, cornerRadius, falloff);
            }
            canvas.drawRect(0f, 0f, widthPx, heightPx, paint);
        } catch (Throwable error) {
            // 只降级一次并留痕：高光是装饰，画不出来不该影响底栏本身。
            failed = true;
            paint.setShader(null);
            android.util.Log.w("AgsHighlightView", "AGSL highlight disabled (draw failed)", error);
        }
    }

    /** API 33+ 的 RuntimeShader 隔离在一个内部类里，避免低版本类校验失败。 */
    @androidx.annotation.RequiresApi(33)
    private static final class Api33 {
        static android.graphics.Shader createShader(String src, float w, float h, float radius, float falloff) {
            android.graphics.RuntimeShader shader = new android.graphics.RuntimeShader(src);
            update(shader, w, h, radius, falloff);
            return shader;
        }

        static void update(android.graphics.Shader shader, float w, float h, float radius, float falloff) {
            android.graphics.RuntimeShader runtime = (android.graphics.RuntimeShader) shader;
            runtime.setFloatUniform("size", w, h);
            // 光带宽度：取栏高的 1/24，夹在 2..6px（太宽糊成一片、太窄看不见）。
            runtime.setFloatUniform("cornerRadius", Math.max(1f, radius));
            runtime.setFloatUniform("thickness", Math.max(2f, Math.min(6f, h / 24f)));
            runtime.setFloatUniform("lightDir", 0.7071f, 0.7071f);
            runtime.setFloatUniform("falloff", falloff);
        }
    }
}