package com.stardust.autojs.core.inputevent;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pointer state and cleanup independent of the Android/root transport. */
public final class TouchController implements AutoCloseable {
    public interface Backend extends AutoCloseable {
        void down(int id, int x, int y) throws IOException;
        void move(int id, int x, int y) throws IOException;
        void up(int id, int x, int y) throws IOException;
        void close() throws IOException;
    }
    private final Backend backend;
    private final int maxPointers;
    private final Map<Integer, int[]> points = new LinkedHashMap<>();
    private boolean closed;

    public TouchController(Backend backend, int maxPointers) {
        this.backend = backend;
        this.maxPointers = maxPointers;
    }
    public synchronized void down(int id, int x, int y) throws IOException {
        checkOpen(); checkId(id); checkCoordinates(x, y);
        if (points.containsKey(id)) throw new IllegalStateException("手指 " + id + " 已按下");
        if (points.size() >= maxPointers) throw new IllegalStateException("当前触控通道最多支持 " + maxPointers + " 个手指");
        // Track even an uncertain transport failure, so close() still attempts an UP.
        points.put(id, new int[]{x, y});
        backend.down(id, x, y);
    }
    public synchronized void move(int id, int x, int y) throws IOException {
        checkOpen(); checkCoordinates(x, y); requirePoint(id);
        points.put(id, new int[]{x, y});
        backend.move(id, x, y);
    }
    public synchronized void up(int id) throws IOException {
        checkOpen(); int[] point = requirePoint(id);
        backend.up(id, point[0], point[1]);
        points.remove(id);
    }
    public synchronized int pointerCount() { return points.size(); }
    public synchronized boolean isClosed() { return closed; }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (Map.Entry<Integer, int[]> entry : points.entrySet()) {
            int[] point = entry.getValue();
            try { backend.up(entry.getKey(), point[0], point[1]); }
            catch (Exception error) {
                IOException wrapped = new IOException("抬起手指失败", error);
                if (failure == null) failure = wrapped; else failure.addSuppressed(wrapped);
            }
        }
        points.clear();
        try { backend.close(); }
        catch (Exception error) {
            IOException wrapped = new IOException("关闭触控通道失败", error);
            if (failure == null) failure = wrapped; else failure.addSuppressed(wrapped);
        }
        if (failure != null) throw failure;
    }
    private void checkOpen() { if (closed) throw new IllegalStateException("touch 已经 exit()"); }
    private static void checkId(int id) {
        if (id < 0 || id > 31) throw new IllegalArgumentException("手指 id 必须在 0～31 之间");
    }
    private static void checkCoordinates(int x, int y) {
        if (x < 0 || y < 0) throw new IllegalArgumentException("触控坐标不能为负数");
    }
    private int[] requirePoint(int id) {
        checkId(id);
        int[] point = points.get(id);
        if (point == null) throw new IllegalStateException("请先 down 手指 " + id);
        return point;
    }
}
