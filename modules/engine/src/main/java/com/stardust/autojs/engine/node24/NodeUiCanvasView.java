package com.stardust.autojs.engine.node24;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
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

    // ---- 原生场景绘制（Node overlay 高性能通道）----
    // 脚本只推一份「图元列表」，绘制全在主线程原生完成，不再每次 onDraw 回调 JS。
    private volatile Scene scene;
    private final Paint sceneStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sceneFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sceneText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF sceneRect = new RectF();

    /** 一条绘制指令（矩形/线段/圆圈/文字）。 */
    private static final class Op {
        int type;            // 0=rect 1=line 2=circle 3=text
        float x1, y1, x2, y2;
        float radius;
        int color;
        float stroke;
        boolean fill;
        String text;
        float textSize;
        int bgColor;
    }

    private static final class Scene {
        final List<Op> ops = new ArrayList<>();

        static Scene parse(String json) {
            Scene scene = new Scene();
            if (json == null || json.isEmpty()) { return scene; }
            try {
                JSONObject root = new JSONObject(json);
                JSONArray array = root.optJSONArray("ops");
                if (array == null) { return scene; }
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) { continue; }
                    String type = item.optString("t", "");
                    Op op = new Op();
                    if ("r".equals(type)) {
                        op.type = 0;
                        op.x1 = (float) item.optDouble("x", 0);
                        op.y1 = (float) item.optDouble("y", 0);
                        op.x2 = op.x1 + (float) item.optDouble("w", 0);
                        op.y2 = op.y1 + (float) item.optDouble("h", 0);
                        op.color = item.optInt("c", 0xff00ff00);
                        op.stroke = (float) item.optDouble("s", 3);
                        op.fill = item.optInt("f", 0) == 1;
                    } else if ("l".equals(type)) {
                        op.type = 1;
                        op.x1 = (float) item.optDouble("x1", 0);
                        op.y1 = (float) item.optDouble("y1", 0);
                        op.x2 = (float) item.optDouble("x2", 0);
                        op.y2 = (float) item.optDouble("y2", 0);
                        op.color = item.optInt("c", 0xff00ff00);
                        op.stroke = (float) item.optDouble("s", 3);
                    } else if ("c".equals(type)) {
                        op.type = 2;
                        op.x1 = (float) item.optDouble("x", 0);
                        op.y1 = (float) item.optDouble("y", 0);
                        op.radius = (float) item.optDouble("r", 4);
                        op.color = item.optInt("c", 0xff00ff00);
                        op.stroke = (float) item.optDouble("s", 3);
                        op.fill = item.optInt("f", 1) == 1;
                    } else if ("t".equals(type)) {
                        op.type = 3;
                        op.x1 = (float) item.optDouble("x", 0);
                        op.y1 = (float) item.optDouble("y", 0);
                        op.textSize = (float) item.optDouble("s", 24);
                        op.color = item.optInt("c", 0xffffffff);
                        op.text = item.optString("str", "");
                        op.bgColor = item.optInt("b", 0);   // 0 = 无背景
                    } else {
                        continue;
                    }
                    scene.ops.add(op);
                }
            } catch (Throwable error) {
                Log.w(TAG, "scene 解析失败", error);
            }
            return scene;
        }
    }

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

    /**
     * 原生场景绘制：脚本推一份图元 JSON，绘制不再回调 JS。
     * 传 null / 空串等同于关闭场景模式。
     */
    public void setScene(String json) {
        Scene parsed = (json == null || json.isEmpty()) ? null : Scene.parse(json);
        scene = parsed;
        runOnMain(this::invalidate);
    }

    public void clearScene() {
        scene = null;
        runOnMain(this::invalidate);
    }

    private boolean sceneMode() {
        return scene != null;
    }

    private void drawScene(Canvas canvas, Scene current) {
        for (int i = 0; i < current.ops.size(); i++) {
            Op op = current.ops.get(i);
            switch (op.type) {
                case 0:
                    if (op.fill) {
                        sceneFill.setStyle(Paint.Style.FILL);
                        sceneFill.setColor(op.color);
                        canvas.drawRect(op.x1, op.y1, op.x2, op.y2, sceneFill);
                    } else {
                        sceneStroke.setStyle(Paint.Style.STROKE);
                        sceneStroke.setStrokeWidth(op.stroke);
                        sceneStroke.setColor(op.color);
                        canvas.drawRect(op.x1, op.y1, op.x2, op.y2, sceneStroke);
                    }
                    break;
                case 1:
                    sceneStroke.setStyle(Paint.Style.STROKE);
                    sceneStroke.setStrokeWidth(op.stroke);
                    sceneStroke.setColor(op.color);
                    canvas.drawLine(op.x1, op.y1, op.x2, op.y2, sceneStroke);
                    break;
                case 2:
                    sceneStroke.setStyle(op.fill ? Paint.Style.FILL : Paint.Style.STROKE);
                    sceneStroke.setStrokeWidth(op.stroke);
                    sceneStroke.setColor(op.color);
                    canvas.drawCircle(op.x1, op.y1, op.radius, sceneStroke);
                    break;
                case 3:
                    sceneText.setTextSize(op.textSize);
                    sceneText.setColor(op.color);
                    if (op.bgColor != 0) {
                        float width = sceneText.measureText(op.text);
                        sceneRect.set(op.x1 - 8, op.y1 - op.textSize * 1.5f,
                                op.x1 - 8 + width + 16, op.y1 + op.textSize * 0.45f);
                        sceneFill.setStyle(Paint.Style.FILL);
                        sceneFill.setColor(op.bgColor);
                        canvas.drawRect(sceneRect, sceneFill);
                    }
                    canvas.drawText(op.text, op.x1, op.y1, sceneText);
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Scene current = scene;
        if (current != null) {
            drawScene(canvas, current);
            return;
        }
        Bitmap currentBitmap = bitmap;
        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            canvas.drawBitmap(currentBitmap, 0, 0, null);
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
        if (sceneMode()) { return; }   // 场景模式：只靠 setScene 主动 invalidate
        if (drawListener == null || !attached || destroyed.get()) return;
        if (!frameScheduled.compareAndSet(false, true)) return;
        main.postDelayed(this::renderFrame, FRAME_INTERVAL_MS);
    }

    private void renderFrame() {
        frameScheduled.set(false);
        if (!attached || destroyed.get()) return;
        if (sceneMode()) { return; }   // 场景模式不跑 JS 绘制回调
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
