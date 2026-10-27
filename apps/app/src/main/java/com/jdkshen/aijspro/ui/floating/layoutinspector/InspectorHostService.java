package com.jdkshen.aijspro.ui.floating.layoutinspector;

import android.content.Intent;
import android.os.*;
import android.util.Log;
import com.jdkshen.aijspro.theme.AppThemePalette;
import com.jdkshen.aijspro.theme.AppThemeRepository;
import com.stardust.enhancedfloaty.FloatyService;
import com.stardust.view.accessibility.LayoutInspector;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Dedicated :inspector renderer. It never reaches the main process's AutoJs singleton. */
public final class InspectorHostService extends FloatyService {
    private static final String TAG = "InspectorRenderer";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static InspectorHostService instance;
    private final ThreadPoolExecutor io = InspectorProcessBridge.executor("InspectorRendererIO");
    private final Messenger inbox = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private final Map<Integer, WindowRequest> pending = new HashMap<>();
    private final IBinder.DeathRecipient clientDeath = () -> MAIN.post(() -> finishRenderer(false));
    private Messenger client;
    private IBinder clientBinder;
    private InspectorFloatyWindow window;
    private String owner;
    private int generation, replacing;
    private boolean destroying;
    private Future<?> loading;

    @Override public void onCreate() {
        super.onCreate();
        if (!InspectorProcessBridge.isRendererProcess(this)) throw new IllegalStateException("分析服务必须运行在独立进程");
        instance = this;
    }
    @Override public IBinder onBind(Intent intent) { return inbox.getBinder(); }
    @Override public boolean onUnbind(Intent intent) { finishRenderer(false); return false; }
    @Override public void onDestroy() {
        destroying = true; finishRenderer(false);
        if (instance == this) instance = null;
        io.shutdownNow();
        super.onDestroy();
    }

    private boolean receive(Message message) {
        if (destroying || message.sendingUid != android.os.Process.myUid()) return true;
        String receivedOwner = message.getData().getString(InspectorProcessBridge.OWNER);
        if (message.what == InspectorProcessBridge.OPEN) {
            if (receivedOwner == null || !receivedOwner.matches("[0-9a-f]{32}") || message.replyTo == null) return true;
            open(receivedOwner, message.replyTo, message.getData()); return true;
        }
        if (owner == null || !owner.equals(receivedOwner)) return true;
        switch (message.what) {
            case InspectorProcessBridge.CLOSE: finishRenderer(true); break;
            case InspectorProcessBridge.WINDOWS: receiveWindows(message.arg1, message.getData().getString(InspectorProcessBridge.TOKEN)); break;
            case InspectorProcessBridge.THEME: updateTheme(message.getData()); break;
            default: break;
        }
        return true;
    }

    private void open(String sessionOwner, Messenger reply, Bundle extras) {
        ++generation; int requestGeneration = generation;
        cancelPending();
        if (loading != null) loading.cancel(true);
        io.purge(); unlinkClient();
        owner = sessionOwner; client = reply; clientBinder = reply.getBinder();
        try { clientBinder.linkToDeath(clientDeath, 0); }
        catch (RemoteException dead) { finishRenderer(false); return; }
        String token = extras.getString(InspectorProcessBridge.TOKEN);
        if (token == null) { fail("缺少布局快照"); return; }
        applyTheme(extras);
        try {
            loading = io.submit(() -> {
                try {
                    InspectorSnapshotTransport.Payload snapshot = InspectorSnapshotTransport.read(this, token);
                    MAIN.post(() -> {
                        if (destroying || requestGeneration != generation || !sessionOwner.equals(owner)) return;
                        try {
                            InspectorSession session = new InspectorSession(snapshot.root, snapshot.windows);
                            InspectorFloatyWindow next = snapshot.hierarchy
                                    ? new LayoutHierarchyFloatyWindow(session) : new LayoutBoundsFloatyWindow(session);
                            replaceWindow(next);
                            Message ready = InspectorProcessBridge.message(InspectorProcessBridge.READY, owner, 0);
                            ready.arg2 = android.os.Process.myPid(); send(ready);
                        } catch (RuntimeException error) { fail(error.getClass().getSimpleName()); }
                    });
                } catch (IOException | RuntimeException error) {
                    MAIN.post(() -> { if (requestGeneration == generation && sessionOwner.equals(owner)) fail(error.getMessage()); });
                } finally { InspectorSnapshotTransport.discard(this, token); }
            });
        } catch (RejectedExecutionException busy) { fail("上次布局仍在清理，请重新打开"); }
    }

    private void applyTheme(Bundle extras) {
        AppThemeRepository.get(this).applyRemotePalette(extras.getBoolean("themeDark"), extras.getInt("themeAccent", 0xff009688));
    }
    private void updateTheme(Bundle extras) {
        AppThemePalette current = AppThemeRepository.get(this).getPalette();
        if (current.isDark == extras.getBoolean("themeDark") && current.accent == extras.getInt("themeAccent")) return;
        applyTheme(extras);
        if (window != null) {
            InspectorSession session = window.session;
            InspectorFloatyWindow next = window.hierarchy
                    ? new LayoutHierarchyFloatyWindow(session) : new LayoutBoundsFloatyWindow(session);
            try { replaceWindow(next); } catch (RuntimeException error) { fail("主题更新失败，请重新打开分析界面"); }
        }
    }

    /** Root calls this in switchView: preserve session state while closing the old view tree. */
    public static boolean replaceInspectorWindow(InspectorFloatyWindow previous, InspectorFloatyWindow next) {
        InspectorHostService service = instance;
        if (service == null || service.window != previous || service.destroying) return false;
        try { service.replaceWindow(next); return true; }
        catch (RuntimeException error) { service.fail("无法切换分析视图"); return true; }
    }
    private void replaceWindow(InspectorFloatyWindow next) {
        boolean searchOpen = next.session.searchOpen;
        ++replacing;
        try {
            if (window != null) window.close();
            next.session.searchOpen = searchOpen;
            window = next; FloatyService.addWindow(next);
        } finally { --replacing; }
    }

    // Kept as small lifecycle hooks for other view transitions; prefer the atomic helper above.
    public static void beginViewSwitch() { if (instance != null) ++instance.replacing; }
    public static void setInspectorWindow(InspectorFloatyWindow next) {
        if (instance != null) { instance.window = next; if (instance.replacing > 0) --instance.replacing; }
    }
    public static void onInspectorWindowClosed(InspectorFloatyWindow closed) {
        InspectorHostService service = instance;
        if (service != null && service.window == closed && service.replacing == 0 && !service.destroying) {
            service.window = null; service.finishRenderer(true);
        }
    }
    public static void closeRenderer() { MAIN.post(() -> { if (instance != null) instance.finishRenderer(true); }); }

    public static LayoutInspector.CaptureRequest requestWindows(LayoutInspector.WindowsAvailableListener listener) {
        InspectorHostService service = instance;
        int id = REQUESTS.updateAndGet(value -> value == Integer.MAX_VALUE ? 1 : value + 1);
        WindowRequest request = new WindowRequest(service, id, listener);
        MAIN.post(() -> {
            if (request.cancelled) return;
            if (service == null || service.destroying || service.owner == null) { request.complete(Collections.emptyList()); return; }
            service.cancelPending(); service.pending.put(id, request);
            MAIN.postDelayed(request.timeout, 20000);
            service.send(InspectorProcessBridge.message(InspectorProcessBridge.REFRESH, service.owner, id));
        });
        return request;
    }

    private void receiveWindows(int id, String token) {
        WindowRequest request = pending.get(id);
        if (request == null || request.cancelled) { discard(token); return; }
        if (token == null) { request.complete(Collections.emptyList()); return; }
        try {
            request.reading = io.submit(() -> {
                try {
                    InspectorSnapshotTransport.Payload snapshot = InspectorSnapshotTransport.read(this, token);
                    MAIN.post(() -> request.complete(snapshot.windows));
                } catch (IOException | RuntimeException error) { MAIN.post(() -> request.complete(Collections.emptyList())); }
                finally { InspectorSnapshotTransport.discard(this, token); }
            });
        } catch (RejectedExecutionException busy) { discard(token); request.complete(Collections.emptyList()); }
    }
    private void cancelPending() { for (WindowRequest request : new ArrayList<>(pending.values())) request.cancelNow(); }
    private void discard(String token) {
        if (token == null) return;
        try { io.execute(() -> InspectorSnapshotTransport.discard(this, token)); }
        catch (RejectedExecutionException ignored) { InspectorSnapshotTransport.discard(this, token); }
    }

    private static final class WindowRequest implements LayoutInspector.CaptureRequest {
        final InspectorHostService service;
        final int id;
        final LayoutInspector.WindowsAvailableListener listener;
        final Runnable timeout = this::onTimeout;
        volatile boolean cancelled;
        Future<?> reading;
        WindowRequest(InspectorHostService service, int id, LayoutInspector.WindowsAvailableListener listener) {
            this.service = service; this.id = id; this.listener = listener;
        }
        @Override public long getId() { return id; }
        @Override public void cancel() { cancelled = true; MAIN.post(this::cancelNow); }
        void cancelNow() {
            cancelled = true; MAIN.removeCallbacks(timeout);
            if (service != null) service.pending.remove(id);
            if (reading != null) reading.cancel(true);
            notifyCancel();
        }
        void notifyCancel() {
            if (service != null && service.owner != null) service.send(
                    InspectorProcessBridge.message(InspectorProcessBridge.CANCEL_REFRESH, service.owner, id));
        }
        private void onTimeout() {
            if (!cancelled) { notifyCancel(); complete(Collections.emptyList()); }
        }
        void complete(List<LayoutInspector.CapturedWindow> windows) {
            if (cancelled || (service != null && service.pending.get(id) != this)) return;
            cancelled = true; MAIN.removeCallbacks(timeout);
            if (service != null) service.pending.remove(id);
            listener.onWindowsAvailable(windows);
        }
    }

    private void fail(String error) {
        Message failure = InspectorProcessBridge.message(InspectorProcessBridge.ERROR, owner, 0);
        failure.getData().putString(InspectorProcessBridge.ERROR_TEXT, error == null ? "未知错误" : error);
        send(failure); finishRenderer(false);
    }
    private void send(Message message) {
        if (client == null) return;
        try { client.send(message); }
        catch (RemoteException dead) { MAIN.post(() -> finishRenderer(false)); }
    }
    private void finishRenderer(boolean notify) {
        ++generation;
        cancelPending();
        if (loading != null) { loading.cancel(true); loading = null; }
        ++replacing;
        try { if (window != null) { window.close(); window = null; } }
        catch (RuntimeException error) { Log.w(TAG, "window cleanup failed", error); }
        finally { --replacing; }
        if (notify && owner != null) send(InspectorProcessBridge.message(InspectorProcessBridge.CLOSED, owner, 0));
        owner = null; unlinkClient(); client = null; stopSelf();
    }
    private void unlinkClient() {
        if (clientBinder != null) {
            try { clientBinder.unlinkToDeath(clientDeath, 0); } catch (NoSuchElementException ignored) { }
            clientBinder = null;
        }
    }
}
