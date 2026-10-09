package com.stardust.autojs.core.graphics;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import java.util.concurrent.atomic.AtomicInteger;

/** Bitmap-backed ScriptCanvas keeps all Android drawing methods on the owning JS thread. */
public final class QuickJsCanvasBuffer implements AutoCloseable {
    private static final AtomicInteger LIVE_BITMAPS = new AtomicInteger();
    private final ScriptCanvas canvas = new ScriptCanvas();
    private final CanvasFrameBuffer<Bitmap> frames = new CanvasFrameBuffer<>(new CanvasFrameBuffer.Factory<Bitmap>() {
        @Override public Bitmap create(int width, int height) {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            LIVE_BITMAPS.incrementAndGet();
            return bitmap;
        }
        @Override public void dispose(Bitmap bitmap) {
            bitmap.recycle();
            LIVE_BITMAPS.decrementAndGet();
        }
    });
    private Thread writer;

    public ScriptCanvas getCanvas() { return canvas; }

    public synchronized boolean begin(int width, int height) {
        Bitmap back = frames.begin(width, height);
        if (back == null) return false;
        try {
            back.eraseColor(Color.TRANSPARENT);
            canvas.setCanvas(new Canvas(back));
            writer = Thread.currentThread();
            return true;
        } catch (Throwable error) {
            frames.finish(false);
            throw error;
        }
    }

    public synchronized void finish(boolean publish) {
        if (writer == null) return;
        if (writer != Thread.currentThread()) throw new IllegalStateException("Canvas 帧必须在绘制回调线程结束");
        canvas.setCanvas(null);
        writer = null;
        frames.finish(publish);
    }

    public void render(Canvas target) {
        target.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        frames.render(bitmap -> target.drawBitmap(bitmap, 0, 0, null));
    }

    public boolean isClosed() { return frames.isClosed(); }
    public synchronized boolean isWriting() { return writer != null; }
    public static int liveBitmapCount() { return LIVE_BITMAPS.get(); }
    @Override public synchronized void close() {
        // Runtime cleanup runs after JS has stopped on its writer thread; finally may be interrupted.
        if (writer == Thread.currentThread()) {
            canvas.setCanvas(null);
            writer = null;
            frames.finish(false);
        }
        frames.close();
    }
}
