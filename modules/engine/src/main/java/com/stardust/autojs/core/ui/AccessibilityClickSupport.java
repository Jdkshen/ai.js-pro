package com.stardust.autojs.core.ui;

import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.View;

import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;

import com.stardust.autojs.R;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Script-owned accessibility click actions. UI callbacks are delivered on the script thread. */
public final class AccessibilityClickSupport implements AutoCloseable {
    private static final int CLICK = 16;
    private static final int LONG_CLICK = 32;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayBlockingQueue<Long> events = new ArrayBlockingQueue<>(256);
    private final Map<View, SparseArray<Binding>> owned = new IdentityHashMap<>();
    private volatile boolean closed;

    private static final class Binding {
        final long key;
        Binding(long key) { this.key = key; }
    }

    @SuppressWarnings("unchecked")
    private static SparseArray<Binding> bindings(View view, boolean create) {
        SparseArray<Binding> value = (SparseArray<Binding>) view.getTag(R.id.view_tag_accessibility_click_actions);
        if (value == null && create) {
            value = new SparseArray<>();
            view.setTag(R.id.view_tag_accessibility_click_actions, value);
        }
        return value;
    }

    public void replaceAccessibilityAction(View view, int action, String label, long key) {
        if (view == null) throw new IllegalArgumentException("view 不能为空");
        if (action != CLICK && action != LONG_CLICK) throw new IllegalArgumentException("仅支持 ACTION_CLICK(16) 和 ACTION_LONG_CLICK(32)");
        if (key < 0) throw new IllegalArgumentException("无效的回调编号");
        onMain(() -> {
            if (closed) throw new IllegalStateException("无障碍点击会话已关闭");
            SparseArray<Binding> current = bindings(view, key != 0);
            if (key == 0) {
                if (current != null) current.remove(action);
                ViewCompat.removeAccessibilityAction(view, action);
                SparseArray<Binding> mine = owned.get(view);
                if (mine != null) { mine.remove(action); if (mine.size() == 0) owned.remove(view); }
                if (current != null && current.size() == 0) view.setTag(R.id.view_tag_accessibility_click_actions, null);
                return null;
            }
            Binding binding = new Binding(key);
            current.put(action, binding);
            SparseArray<Binding> mine = owned.get(view);
            if (mine == null) { mine = new SparseArray<>(); owned.put(view, mine); }
            mine.put(action, binding);
            if (action == CLICK) view.setClickable(true); else view.setLongClickable(true);
            ViewCompat.replaceAccessibilityAction(view, new AccessibilityActionCompat(action, label), label,
                    (target, arguments) -> {
                        SparseArray<Binding> active = bindings(target, false);
                        if (closed || active == null || active.get(action) != binding) return false;
                        if (!events.offer(binding.key)) { events.poll(); events.offer(binding.key); }
                        return true;
                    });
            return null;
        });
    }

    public long poll() {
        Long event = events.poll();
        return event == null ? 0 : event;
    }

    /** Native performClick preserves the existing OnClickListener and accessibility notification. */
    public boolean performClick(View view) {
        if (view == null) throw new IllegalArgumentException("view 不能为空");
        return onMain(view::performClick);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        events.clear();
        Runnable cleanup = () -> {
            for (Map.Entry<View, SparseArray<Binding>> entry : owned.entrySet()) {
                View view = entry.getKey();
                SparseArray<Binding> active = bindings(view, false);
                if (active == null) continue;
                SparseArray<Binding> mine = entry.getValue();
                for (int i = 0; i < mine.size(); i++) {
                    int action = mine.keyAt(i);
                    // Another script can replace an action on a shared view; its binding must survive our exit.
                    if (active.get(action) == mine.valueAt(i)) {
                        active.remove(action);
                        ViewCompat.removeAccessibilityAction(view, action);
                    }
                }
                if (active.size() == 0) view.setTag(R.id.view_tag_accessibility_click_actions, null);
            }
            owned.clear();
        };
        // Forced script stop interrupts the engine thread. Cleanup must still be posted,
        // without an interruptible wait that could cancel it before the UI thread runs it.
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run();
        else main.post(cleanup);
    }

    private <T> T onMain(java.util.concurrent.Callable<T> action) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) return action.call();
            FutureTask<T> task = new FutureTask<>(action);
            if (!main.post(task)) throw new IllegalStateException("主线程不可用");
            try { return task.get(5, TimeUnit.SECONDS); }
            catch (Exception error) { task.cancel(false); throw error; }
        } catch (RuntimeException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("无障碍点击操作失败", error); }
    }
}
