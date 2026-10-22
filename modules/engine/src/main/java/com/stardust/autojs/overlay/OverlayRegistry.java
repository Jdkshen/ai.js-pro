package com.stardust.autojs.overlay;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import com.stardust.app.GlobalAppContext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 进程内浮窗注册表：任何引擎创建的浮窗都登记到这里，脚本被停止/引擎销毁/进程退出时
 * 统一反注册并 removeView，避免脚本直接 WindowManager.addView 留下的 View 拦截触摸。
 *
 * <p>该注册表只覆盖当前进程；MCP 主进程通过 filesDir/overlay-state/overlay-&lt;pid&gt;.json
 * 快照聚合其它进程的浮窗（见 McpTools.list_overlays）。
 */
public final class OverlayRegistry {

    private static final String TAG = "OverlayRegistry";
    private static final String STATE_DIR = "overlay-state";

    public enum Kind {
        RHINO_FLOATY,
        QUICKJS_FLOATY,
        QUICKJS_DRAW,
        NODE_CANVAS,
        NODE_TEXT,
        NODE_UI,
        OTHER
    }

    public static final class Entry {
        public final int ownerEngineId;
        public final String ownerTag;
        public final String engine;
        public final Kind kind;
        public final View view;
        public final WindowManager windowManager;
        public final boolean touchable;
        public final long createdAt;

        Entry(int ownerEngineId, String ownerTag, String engine, Kind kind, View view,
              WindowManager windowManager, boolean touchable) {
            this.ownerEngineId = ownerEngineId;
            this.ownerTag = ownerTag == null ? "" : ownerTag;
            this.engine = engine == null ? "" : engine;
            this.kind = kind == null ? Kind.OTHER : kind;
            this.view = view;
            this.windowManager = windowManager;
            this.touchable = touchable;
            this.createdAt = System.currentTimeMillis();
        }
    }

    private static final Object LOCK = new Object();
    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile File sStateFile;
    private static volatile boolean sShutdownHookInstalled;

    private OverlayRegistry() {
    }

    public static void register(int ownerEngineId, String ownerTag, String engine, Kind kind,
                                View view, WindowManager wm, boolean touchable) {
        if (view == null) return;
        Entry entry = new Entry(ownerEngineId, ownerTag, engine, kind, view, wm, touchable);
        synchronized (LOCK) {
            for (int i = ENTRIES.size() - 1; i >= 0; i--) {
                if (ENTRIES.get(i).view == view) ENTRIES.remove(i);
            }
            ENTRIES.add(entry);
        }
        installShutdownHook();
        persistSnapshot();
        Log.d(TAG, "register " + engine + " kind=" + kind + " owner=" + ownerEngineId + " total=" + size());
    }

    public static void unregister(View view) {
        if (view == null) return;
        boolean changed = false;
        synchronized (LOCK) {
            for (int i = ENTRIES.size() - 1; i >= 0; i--) {
                if (ENTRIES.get(i).view == view) {
                    ENTRIES.remove(i);
                    changed = true;
                }
            }
        }
        if (changed) persistSnapshot();
    }

    public static int closeByOwner(int ownerEngineId, String reason) {
        List<Entry> targets = new ArrayList<>();
        synchronized (LOCK) {
            for (int i = ENTRIES.size() - 1; i >= 0; i--) {
                if (ENTRIES.get(i).ownerEngineId == ownerEngineId) {
                    targets.add(ENTRIES.remove(i));
                }
            }
        }
        return closeEntries(targets, reason);
    }

    public static int closeByTag(String ownerTag, String reason) {
        if (ownerTag == null) return 0;
        List<Entry> targets = new ArrayList<>();
        synchronized (LOCK) {
            for (int i = ENTRIES.size() - 1; i >= 0; i--) {
                if (ownerTag.equals(ENTRIES.get(i).ownerTag)) {
                    targets.add(ENTRIES.remove(i));
                }
            }
        }
        return closeEntries(targets, reason);
    }

    public static int closeAll(String reason) {
        List<Entry> targets;
        synchronized (LOCK) {
            targets = new ArrayList<>(ENTRIES);
            ENTRIES.clear();
        }
        return closeEntries(targets, reason);
    }

    public static List<Entry> snapshot() {
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<>(ENTRIES));
        }
    }

    public static int size() {
        synchronized (LOCK) {
            return ENTRIES.size();
        }
    }

    /** 供 MCP 主进程聚合读取：文件内容失败时返回空数组。 */
    public static JSONArray localSnapshotJson() {
        JSONArray array = new JSONArray();
        for (Entry entry : snapshot()) {
            array.put(entryJson(entry));
        }
        return array;
    }

    private static JSONObject entryJson(Entry entry) {
        JSONObject json = new JSONObject();
        try {
            json.put("ownerEngineId", entry.ownerEngineId);
            json.put("ownerTag", entry.ownerTag);
            json.put("engine", entry.engine);
            json.put("kind", entry.kind.name());
            json.put("touchable", entry.touchable);
            json.put("createdAt", entry.createdAt);
            json.put("viewClass", entry.view.getClass().getName());
            json.put("pid", android.os.Process.myPid());
        } catch (Throwable ignored) {
        }
        return json;
    }

    private static int closeEntries(List<Entry> targets, String reason) {
        if (targets == null || targets.isEmpty()) {
            persistSnapshot();
            return 0;
        }
        int closed = 0;
        for (Entry entry : targets) {
            try {
                removeNow(entry);
                closed++;
            } catch (Throwable e) {
                Log.w(TAG, "close failed " + entry.view.getClass().getName() + ": " + e);
            }
        }
        persistSnapshot();
        Log.i(TAG, "closed " + closed + "/" + targets.size() + " reason=" + reason);
        return closed;
    }

    private static void removeNow(Entry entry) throws Exception {
        Runnable task = () -> {
            View view = entry.view;
            WindowManager wm = entry.windowManager;
            if (wm != null) {
                try {
                    wm.removeViewImmediate(view);
                    return;
                } catch (Throwable ignored) {
                }
            }
            removeFromWindowManagerGlobal(view);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        MAIN_HANDLER.post(() -> {
            try {
                task.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await(1500, TimeUnit.MILLISECONDS);
    }

    private static void removeFromWindowManagerGlobal(View view) {
        try {
            Class<?> cls = Class.forName("android.view.WindowManagerGlobal");
            Method getInstance = cls.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object instance = getInstance.invoke(null);
            Method removeView = cls.getDeclaredMethod("removeView", View.class, boolean.class);
            removeView.setAccessible(true);
            removeView.invoke(instance, view, false);
        } catch (Throwable e) {
            Log.w(TAG, "WindowManagerGlobal.removeView failed: " + e);
        }
    }

    private static void installShutdownHook() {
        if (sShutdownHookInstalled) return;
        synchronized (LOCK) {
            if (sShutdownHookInstalled) return;
            sShutdownHookInstalled = true;
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(OverlayRegistry::deleteStateFile, "overlay-registry-exit"));
            } catch (Throwable ignored) {
            }
        }
    }

    private static File stateFile() {
        File file = sStateFile;
        if (file != null) return file;
        synchronized (LOCK) {
            if (sStateFile != null) return sStateFile;
            try {
                File dir = new File(GlobalAppContext.get().getFilesDir(), STATE_DIR);
                if (!dir.exists() && !dir.mkdirs()) return null;
                sStateFile = new File(dir, "overlay-" + android.os.Process.myPid() + ".json");
            } catch (Throwable e) {
                Log.w(TAG, "state dir unavailable: " + e);
                return null;
            }
            return sStateFile;
        }
    }

    private static void persistSnapshot() {
        File file = stateFile();
        if (file == null) return;
        try {
            JSONObject root = new JSONObject();
            root.put("pid", android.os.Process.myPid());
            root.put("updatedAt", System.currentTimeMillis());
            root.put("entries", localSnapshotJson());
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes("UTF-8"));
                out.flush();
            }
            if (!tmp.renameTo(file)) {
                tmp.delete();
            }
        } catch (Throwable e) {
            Log.w(TAG, "persist snapshot failed: " + e);
        }
    }

    private static void deleteStateFile() {
        try {
            File file = stateFile();
            if (file != null) file.delete();
        } catch (Throwable ignored) {
        }
    }
}
