package com.stardust.autojs.engine.node24;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * &lt;canvas&gt; 的真实绘制控件。
 *
 * <p>绘制回调在单线程后台执行器里跑，画在 ARGB_8888 的软件 Canvas 上，完成后回主线程
 * invalidate。这样 JS 的 canvas.drawText 不会占用/阻塞主线程，也不会与 Node 回调重入；
 * 每帧最多一个任务，帧间隔 32ms（约 30fps）。
 */
public class NodeUiCanvasView extends View {

    private static final String TAG = "NodeUiCanvasView";
    private static final long FRAME_INTERVAL_MS = 32L;

    /** draw 回调（由 ui.js 的真实 Java 接口桥注册）。 */
    public interface DrawListener {
        void onDraw(Canvas canvas);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean frameScheduled = new AtomicBoolean(false);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    private DrawListener drawListener;
    private Bitmap bitmap;
    private Bitmap spare;
    private Canvas frontCanvas;
    private Canvas spareCanvas;
    private boolean drawing;
    private int generation;
    private ExecutorService painter;
    private boolean attached;

    public NodeUiCanvasView(Context context) {
        super(context);
    }

    public NodeUiCanvasView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public NodeUiCanvasView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setDrawListener(DrawListener listener) {
        runOnMain(() -> {
            drawListener = listener;
            if (listener != null) {
                ensurePainter();
                scheduleFrame();
            }
        });
    }

    public void invalidateFrame() {
        runOnMain(this::invalidate);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Bitmap current = bitmap;
        if (current != null && !current.isRecycled()) {
            canvas.drawBitmap(current, 0, 0, null);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recreateBitmap(w, h);
        scheduleFrame();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        destroyed.set(false);
        recreateBitmap(getWidth(), getHeight());
        ensurePainter();
        scheduleFrame();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        generation++;
        destroyed.set(true);
        drawListener = null;
        if (painter != null) {
            painter.shutdownNow();
            painter = null;
        }
        recycleBitmap();
        super.onDetachedFromWindow();
    }

    private void ensurePainter() {
        if (painter == null || painter.isShutdown()) {
            painter = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "NodeUiCanvas");
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    private void recreateBitmap(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (bitmap != null && bitmap.getWidth() == width && bitmap.getHeight() == height
                && !bitmap.isRecycled()) {
            return;
        }
        recycleBitmap();
        generation++;
        try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            frontCanvas = new Canvas(bitmap);
        } catch (Throwable error) {
            Log.w(TAG, "创建画布位图失败：" + width + "x" + height, error);
            bitmap = null;
        }
    }

    private void recycleBitmap() {
        Bitmap current = bitmap;
        bitmap = null;
        frontCanvas = null;
        if (current != null && !current.isRecycled()) {
            current.recycle();
        }
        if (spare != null && !spare.isRecycled()) spare.recycle();
        spare = null;
        spareCanvas = null;
    }

    private void scheduleFrame() {
        if (drawListener == null || !attached || destroyed.get()) return;
        if (!frameScheduled.compareAndSet(false, true)) return;
        main.postDelayed(this::renderFrame, FRAME_INTERVAL_MS);
    }

    private void renderFrame() {
        frameScheduled.set(false);
        if (!attached || destroyed.get()) return;
        if (drawing) return;
        final DrawListener listener = drawListener;
        if (listener == null) return;
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            scheduleFrame();
            return;
        }
        recreateBitmap(width, height);
        if (bitmap == null) {
            scheduleFrame();
            return;
        }
        // A worker owns its back buffer until it posts completion. The UI only
        // reads the front buffer, and resize/detach never recycle the worker's bitmap.
        final Bitmap target = spare != null ? spare : Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Canvas canvas = spareCanvas != null ? spareCanvas : new Canvas(target);
        spare = null;
        spareCanvas = null;
        final int stamp = generation;
        drawing = true;
        ensurePainter();
        try {
            painter.execute(() -> {
                try {
                    if (target != null && !target.isRecycled()) {
                        target.eraseColor(Color.TRANSPARENT);
                    }
                    listener.onDraw(canvas);
                } catch (Throwable error) {
                    Log.w(TAG, "draw 回调失败", error);
                } finally {
                    main.post(() -> {
                        drawing = false;
                        if (attached && !destroyed.get() && generation == stamp) {
                            spare = bitmap;
                            spareCanvas = frontCanvas;
                            bitmap = target;
                            frontCanvas = canvas;
                            invalidate();
                        } else if (!target.isRecycled()) target.recycle();
                        scheduleFrame();
                    });
                }
            });
        } catch (Throwable ignored) {
            drawing = false;
            if (!target.isRecycled()) target.recycle();
        }
    }

    private void runOnMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            main.post(runnable);
        }
    }
}
