package com.jdkshen.aijspro.ui.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.tabs.TabLayout;

/** Native tabs with a moving glass lens; the pager remains the source of selection. */
public class GlassTabLayout extends TabLayout {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lensPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF track = new RectF();
    private final RectF lens = new RectF();
    private final Matrix lensMatrix = new Matrix();
    private final int touchSlop;
    private final float density;
    private Bitmap tabSnapshot;
    private Canvas snapshotCanvas;
    private BitmapShader tabShader;
    private Shader refractionShader;
    private Shader trackHighlight;
    private Shader lensHighlight;
    private boolean snapshotDirty = true;
    private boolean dark;
    private int trackColor = Color.rgb(253, 253, 253);
    private int lensColor = Color.rgb(225, 236, 232);
    private float pagePosition;
    private int pagerState = ViewPager.SCROLL_STATE_IDLE;
    private ViewPager pager;
    private boolean draggingTabs;
    private float downX;
    private float downY;
    private float lastX;
    private int pointerId = -1;

    private final ViewPager.OnPageChangeListener pageListener = new ViewPager.SimpleOnPageChangeListener() {
        @Override public void onPageScrolled(int position, float offset, int pixels) {
            pagePosition = position + offset;
            postInvalidateOnAnimation();
        }

        @Override public void onPageSelected(int position) {
            snapshotDirty = true;
            if (pagerState == ViewPager.SCROLL_STATE_IDLE) pagePosition = position;
            postInvalidateOnAnimation();
        }

        @Override public void onPageScrollStateChanged(int state) {
            pagerState = state;
            if (state == ViewPager.SCROLL_STATE_IDLE && pager != null) {
                pagePosition = pager.getCurrentItem();
                snapshotDirty = true;
            }
            postInvalidateOnAnimation();
        }
    };

    public GlassTabLayout(Context context) { this(context, null); }

    public GlassTabLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setSelectedTabIndicator(new ColorDrawable(Color.TRANSPARENT));
        setSelectedTabIndicatorHeight(0);
        setTabRippleColor(ColorStateList.valueOf(Color.TRANSPARENT));
        addOnTabSelectedListener(new OnTabSelectedListener() {
            @Override public void onTabSelected(Tab tab) { dirtySnapshot(); }
            @Override public void onTabUnselected(Tab tab) { dirtySnapshot(); }
            @Override public void onTabReselected(Tab tab) { dirtySnapshot(); }
        });
    }

    /** Colors use the same resolved light/dark mode as the Compose toolbar. */
    public void setGlassTheme(boolean dark, int surface) {
        this.dark = dark;
        trackColor = dark ? Color.rgb(22, 25, 27) : Color.rgb(253, 253, 253);
        lensColor = dark ? Color.rgb(44, 58, 56) : Color.rgb(225, 236, 232);
        setBackgroundColor(surface);
        updateHighlights();
        dirtySnapshot();
    }

    @Override public void setupWithViewPager(@Nullable ViewPager viewPager) {
        finishTabDrag();
        if (pager != null) pager.removeOnPageChangeListener(pageListener);
        super.setupWithViewPager(viewPager);
        pager = viewPager;
        if (pager != null) {
            pagePosition = pager.getCurrentItem();
            pager.addOnPageChangeListener(pageListener);
        }
        dirtySnapshot();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        releaseSnapshot();
        track.set(dp(10), dp(3), w - dp(10), h - dp(3));
        updateHighlights();
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        snapshotDirty = true;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (paint == null || track.isEmpty()) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(trackColor);
        canvas.drawRoundRect(track, track.height() / 2, track.height() / 2, paint);
        paint.setShader(trackHighlight);
        canvas.drawRoundRect(track, track.height() / 2, track.height() / 2, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.6f));
        paint.setColor(dark ? 0x24FFFFFF : 0xFFFFFFFF);
        canvas.drawRoundRect(track, track.height() / 2, track.height() / 2, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (paint == null || getTabCount() == 0 || getWidth() == 0 || getHeight() == 0) return;
        ensureSnapshot();
        if (tabShader == null) return;

        float position = Math.max(0, Math.min(getTabCount() - 1, pagePosition));
        int first = (int) position;
        float fraction = position - first;
        float center = tabCenter(first) + (tabCenter(Math.min(first + 1, getTabCount() - 1))
                - tabCenter(first)) * fraction;
        float stretch = (float) Math.sin(fraction * Math.PI);
        float cell = tabSpacing();
        float width = Math.min(track.width(), Math.abs(cell) * (0.66f + 0.30f * stretch));
        float height = Math.min(track.height() - dp(4), dp(34 + 4 * stretch));
        float cy = getHeight() / 2f;
        lens.set(center - width / 2, cy - height / 2, center + width / 2, cy + height / 2);

        // A cached, small tab-strip texture is sampled through the lens. Only its
        // matrix changes per frame: no screenshot, bitmap allocation or blur pass.
        float zoomX = 1.06f + 0.32f * stretch;
        float zoomY = 1.06f + 0.16f * stretch;
        if (refractionShader != null && Build.VERSION.SDK_INT >= 33) {
            RefractionApi33.update(refractionShader, center, cy, width / 2, height / 2,
                    0.08f + 0.40f * stretch);
            lensPaint.setShader(refractionShader);
        } else {
            lensMatrix.setScale(zoomX, zoomY, center, cy);
            tabShader.setLocalMatrix(lensMatrix);
            lensPaint.setShader(tabShader);
        }
        canvas.drawRoundRect(lens, height / 2, height / 2, lensPaint);

        paint.setShader(lensHighlight);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(lens, height / 2, height / 2, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.8f));
        paint.setColor(dark ? 0x58FFFFFF : 0xDBFFFFFF);
        canvas.drawRoundRect(lens, height / 2, height / 2, paint);
        // A soft lower rim and brighter upper rim suggest curved glass edges.
        paint.setStrokeWidth(dp(0.5f));
        paint.setColor(dark ? 0x35000000 : 0x18005447);
        canvas.drawArc(lens, 20, 140, false, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void ensureSnapshot() {
        if (tabSnapshot == null) {
            tabSnapshot = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
            snapshotCanvas = new Canvas(tabSnapshot);
            tabShader = new BitmapShader(tabSnapshot, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            if (Build.VERSION.SDK_INT >= 33) refractionShader = RefractionApi33.create(tabShader);
            snapshotDirty = true;
        }
        if (snapshotDirty) {
            tabSnapshot.eraseColor(lensColor);
            super.dispatchDraw(snapshotCanvas);
            snapshotDirty = false;
        }
    }

    private void updateHighlights() {
        if (getHeight() == 0) return;
        trackHighlight = new LinearGradient(0, 0, 0, getHeight(),
                dark ? new int[] {0x15FFFFFF, 0x00FFFFFF, 0x08FFFFFF}
                        : new int[] {0x90FFFFFF, 0x00FFFFFF, 0x38D4E2DF},
                new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
        lensHighlight = new LinearGradient(0, dp(6), 0, getHeight() - dp(6),
                dark ? new int[] {0x26FFFFFF, 0x00FFFFFF, 0x0CFFFFFF}
                        : new int[] {0x72FFFFFF, 0x00FFFFFF, 0x24FFFFFF},
                new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP);
    }

    private float tabCenter(int index) {
        ViewGroup strip = getChildCount() > 0 ? (ViewGroup) getChildAt(0) : null;
        View tab = strip != null && index < strip.getChildCount() ? strip.getChildAt(index) : null;
        return tab == null ? getWidth() / 2f
                : strip.getLeft() + tab.getLeft() + tab.getWidth() / 2f - getScrollX();
    }

    private float tabSpacing() {
        return getTabCount() > 1 ? tabCenter(1) - tabCenter(0) : track.width();
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = lastX = event.getX();
            downY = event.getY();
            pointerId = event.getPointerId(0);
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !draggingTabs) {
            int index = event.findPointerIndex(pointerId);
            if (index >= 0 && pager != null && Math.abs(tabSpacing()) > 1) {
                float dx = event.getX(index) - downX;
                float dy = event.getY(index) - downY;
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)
                        && pager.beginFakeDrag()) {
                    draggingTabs = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
            }
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL
                || event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (!draggingTabs) pointerId = -1;
        }
        return draggingTabs;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!draggingTabs) return super.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE) {
            int index = event.findPointerIndex(pointerId);
            if (index < 0 || pager == null || !pager.isFakeDragging()) {
                finishTabDrag();
                return true;
            }
            float x = event.getX(index);
            pager.fakeDragBy(-(x - lastX) * pager.getWidth() / tabSpacing());
            lastX = x;
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            int lifted = event.getActionIndex();
            if (event.getPointerId(lifted) == pointerId) {
                int next = lifted == 0 ? 1 : 0;
                pointerId = event.getPointerId(next);
                lastX = event.getX(next);
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            finishTabDrag();
        }
        return true;
    }

    private void finishTabDrag() {
        if (draggingTabs && pager != null && pager.isFakeDragging()) {
            int nearest = Math.round(pagePosition);
            pager.endFakeDrag();
            // The navigation lens snaps to the closest icon. Do not apply the
            // amplified full-page fling velocity to this short tab-bar gesture.
            pager.setCurrentItem(nearest, true);
        }
        draggingTabs = false;
        pointerId = -1;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    @Override protected void onDetachedFromWindow() {
        finishTabDrag();
        releaseSnapshot();
        super.onDetachedFromWindow();
    }

    private void releaseSnapshot() {
        // Let the render thread release any reference to the old bitmap safely.
        tabSnapshot = null;
        snapshotCanvas = null;
        tabShader = null;
        refractionShader = null;
        if (lensPaint != null) lensPaint.setShader(null);
        snapshotDirty = true;
    }

    private void dirtySnapshot() { snapshotDirty = true; postInvalidateOnAnimation(); }
    private float dp(float value) { return value * density; }

    /** Isolate newer graphics APIs so Android 8–12 can use the matrix lens. */
    @androidx.annotation.RequiresApi(33)
    private static class RefractionApi33 {
        static Shader create(BitmapShader tabs) {
            android.graphics.RuntimeShader shader = new android.graphics.RuntimeShader(
                    "uniform shader tabs;\n"
                    + "uniform float2 center;\n"
                    + "uniform float2 radius;\n"
                    + "uniform float strength;\n"
                    + "half4 main(float2 xy) {\n"
                    + "  float2 delta = xy - center;\n"
                    + "  float2 rim = float2(max(abs(delta.x) - (radius.x - radius.y), 0.0), delta.y);\n"
                    + "  float d = clamp(length(rim) / max(radius.y, 1.0), 0.0, 1.0);\n"
                    + "  float magnification = 1.02 + strength * (1.0 - d * d);\n"
                    + "  return tabs.eval(center + delta / magnification);\n"
                    + "}\n");
            shader.setInputShader("tabs", tabs);
            return shader;
        }

        static void update(Shader shader, float cx, float cy, float rx, float ry, float strength) {
            android.graphics.RuntimeShader runtime = (android.graphics.RuntimeShader) shader;
            runtime.setFloatUniform("center", cx, cy);
            runtime.setFloatUniform("radius", rx, ry);
            runtime.setFloatUniform("strength", strength);
        }
    }
}
