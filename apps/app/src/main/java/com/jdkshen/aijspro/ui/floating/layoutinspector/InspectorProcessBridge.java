package com.jdkshen.aijspro.ui.floating.layoutinspector;

import android.content.*;
import android.os.*;
import android.util.Log;
import android.widget.Toast;
import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.autojs.key.GlobalKeyObserver;
import com.jdkshen.aijspro.external.HostProcess;
import com.jdkshen.aijspro.ui.floating.FloatyWindowManger;
import com.jdkshen.aijspro.theme.AppThemePalette;
import com.jdkshen.aijspro.theme.AppThemeRepository;
import com.stardust.view.accessibility.LayoutInspector;
import com.stardust.view.accessibility.NodeInfo;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Main-process collector and a bound, same-UID renderer. Every message is session scoped. */
public final class InspectorProcessBridge {
    public static final String PROCESS_SUFFIX = ":inspector";
    static final int OPEN = 1, READY = 2, CLOSE = 3, CLOSED = 4, REFRESH = 5,
            WINDOWS = 6, CANCEL_REFRESH = 7, ERROR = 8, THEME = 9;
    static final String OWNER = "owner", TOKEN = "token", ERROR_TEXT = "error";
    private static final String TAG = "InspectorProcess";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor IO = executor("InspectorBrokerIO");
    private static volatile Session active;

    private InspectorProcessBridge() { }

    public static boolean isRendererProcess(Context context) {
        return (context.getPackageName() + PROCESS_SUFFIX).equals(HostProcess.name());
    }

    public static boolean open(Context context, NodeInfo root,
                               List<LayoutInspector.CapturedWindow> windows, boolean hierarchy,
                               InspectorFloatyWindow fallback) {
        if (root == null || !HostProcess.isMain(context)) return false;
        Context app = context.getApplicationContext();
        List<LayoutInspector.CapturedWindow> copy = windows == null
                ? Collections.emptyList() : new ArrayList<>(windows);
        MAIN.post(() -> {
            Session previous = active;
            if (previous != null) previous.dispose(true, null);
            Session session = new Session(app, fallback); active = session;
            session.prepare(root, copy, hierarchy);
        });
        return true;
    }

    public static void close() {
        if (HostProcess.name().endsWith(PROCESS_SUFFIX)) {
            InspectorHostService.closeRenderer(); return;
        }
        MAIN.post(() -> { Session session = active; if (session != null) session.dispose(true, null); });
    }

    /** Child UI receives only detached snapshots; the accessibility query remains in main. */
    public static LayoutInspector.CaptureRequest refreshWindows(LayoutInspector.WindowsAvailableListener listener) {
        if (HostProcess.name().endsWith(PROCESS_SUFFIX)) return InspectorHostService.requestWindows(listener);
        return AutoJs.getInstance().getLayoutInspector().captureVisibleWindowsRequest(listener);
    }

    public static boolean isOpen() { Session session = active; return session != null && !session.closed; }
    public static int getRendererPid() { Session session = active; return session == null ? 0 : session.rendererPid; }

    static ThreadPoolExecutor executor(String name) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(4), task -> { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; });
        executor.allowCoreThreadTimeOut(true); return executor;
    }

    static Message message(int what, String owner, int requestId) {
        Message message = Message.obtain(null, what); message.arg1 = requestId;
        Bundle bundle = new Bundle(); bundle.putString(OWNER, owner); message.setData(bundle); return message;
    }
    static void putTheme(Context context, Message message) {
        AppThemePalette palette = AppThemeRepository.get(context).getPalette();
        message.getData().putBoolean("themeDark", palette.isDark);
        message.getData().putInt("themeAccent", palette.accent);
    }
    private static boolean sameUid(Message message) { return message.sendingUid == android.os.Process.myUid(); }

    private static final class Session implements ServiceConnection {
        final Context context;
        final String owner = UUID.randomUUID().toString().replace("-", "");
        final Set<String> files = new HashSet<>();
        final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
        final GlobalKeyObserver.OnVolumeDownListener volumeDown = InspectorProcessBridge::close;
        final AppThemeRepository.ThemeListener themeListener = palette -> MAIN.post(this::sendTheme);
        final Runnable openingTimeout = () -> dispose(true, "分析界面启动超时，请重新打开");
        final IBinder.DeathRecipient death = () -> MAIN.post(() -> dispose(false, "分析界面进程已结束，可重新打开"));
        final InspectorFloatyWindow fallback;
        volatile boolean closed;
        volatile int rendererPid;
        Messenger renderer;
        IBinder binder;
        boolean bound;
        String openingToken;
        Future<?> preparing, refreshing;
        LayoutInspector.CaptureRequest captureRequest;
        int refreshId;

        Session(Context context, InspectorFloatyWindow fallback) { this.context = context; this.fallback = fallback; }

        void prepare(NodeInfo root, List<LayoutInspector.CapturedWindow> windows, boolean hierarchy) {
            InspectorSnapshotTransport.cleanupOrphans(context);
            GlobalKeyObserver.getSingleton().addVolumeDownListener(volumeDown);
            AppThemeRepository.get(context).addListener(themeListener);
            IO.purge();
            try {
                preparing = IO.submit(() -> {
                    String token = null;
                    try {
                        token = InspectorSnapshotTransport.write(context, root, windows, hierarchy);
                        String result = token;
                        MAIN.post(() -> {
                            if (closed || active != this) { discard(result); return; }
                            files.add(result); openingToken = result; bind();
                        });
                    } catch (IOException | RuntimeException error) {
                        if (token != null) InspectorSnapshotTransport.discard(context, token);
                        MAIN.post(() -> dispose(false, "无法准备分析界面：" + reason(error)));
                    }
                });
            } catch (RejectedExecutionException busy) { dispose(false, "上次布局仍在清理，请稍后重试"); }
        }

        void bind() {
            if (closed) return;
            try {
                bound = context.bindService(new Intent(context, InspectorHostService.class), this, Context.BIND_AUTO_CREATE);
                if (!bound) dispose(false, "无法连接独立分析界面");
            } catch (RuntimeException error) { dispose(false, "无法连接独立分析界面：" + reason(error)); }
        }

        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            if (closed || active != this) { unbind(); return; }
            binder = service; renderer = new Messenger(service);
            try {
                service.linkToDeath(death, 0);
                Message open = message(OPEN, owner, 0); open.replyTo = replies;
                MAIN.postDelayed(openingTimeout, 12000);
                open.getData().putString(TOKEN, openingToken); putTheme(context, open); renderer.send(open);
            } catch (RemoteException | RuntimeException error) { dispose(false, "分析界面连接已失效"); }
        }
        @Override public void onServiceDisconnected(ComponentName name) { dispose(false, "分析界面进程已结束，可重新打开"); }
        @Override public void onBindingDied(ComponentName name) { dispose(false, "分析界面连接已失效，可重新打开"); }
        @Override public void onNullBinding(ComponentName name) { dispose(false, "分析界面没有返回连接"); }

        boolean receive(Message message) {
            if (!sameUid(message) || closed || active != this || !owner.equals(message.getData().getString(OWNER))) return true;
            switch (message.what) {
                case READY:
                    rendererPid = message.arg2; MAIN.removeCallbacks(openingTimeout);
                    discard(openingToken); openingToken = null;
                    Log.i(TAG, "renderer ready pid=" + rendererPid + " collector=" + android.os.Process.myPid());
                    break;
                case CLOSED: dispose(false, null); break;
                case ERROR: dispose(true, "分析界面打开失败：" + message.getData().getString(ERROR_TEXT, "未知错误")); break;
                case REFRESH: refresh(message.arg1); break;
                case CANCEL_REFRESH: if (refreshId == message.arg1) cancelRefresh(); break;
                default: break;
            }
            return true;
        }

        void refresh(int requestId) {
            cancelRefresh(); refreshId = requestId;
            captureRequest = AutoJs.getInstance().getLayoutInspector().captureVisibleWindowsRequest(windows -> MAIN.post(() -> {
                if (closed || active != this || refreshId != requestId) return;
                captureRequest = null;
                if (windows.isEmpty()) { sendWindows(requestId, null); return; }
                try {
                    refreshing = IO.submit(() -> {
                        String token = null;
                        try {
                            NodeInfo root = null;
                            for (LayoutInspector.CapturedWindow window : windows) if (window.getRoot() != null) { root = window.getRoot(); break; }
                            token = InspectorSnapshotTransport.write(context, root, windows, false);
                            String result = token;
                            MAIN.post(() -> {
                                if (closed || active != this || refreshId != requestId) { discard(result); return; }
                                files.add(result); sendWindows(requestId, result);
                            });
                        } catch (IOException | RuntimeException error) {
                            if (token != null) InspectorSnapshotTransport.discard(context, token);
                            MAIN.post(() -> { if (!closed && refreshId == requestId) sendWindows(requestId, null); });
                        }
                    });
                } catch (RejectedExecutionException busy) { sendWindows(requestId, null); }
            }));
        }

        void cancelRefresh() {
            refreshId = 0;
            if (captureRequest != null) { captureRequest.cancel(); captureRequest = null; }
            if (refreshing != null) { refreshing.cancel(true); refreshing = null; }
            IO.purge();
        }
        void sendWindows(int requestId, String token) {
            if (closed || renderer == null) { discard(token); return; }
            Message response = message(WINDOWS, owner, requestId); response.getData().putString(TOKEN, token);
            try { renderer.send(response); }
            catch (RemoteException error) { dispose(false, "分析界面连接已失效"); }
            if (token != null) {
                // Child deletes after reading; retain a cleanup fallback until the next request or close.
                MAIN.postDelayed(() -> discard(token), 30000);
            }
        }
        void sendTheme() {
            if (closed || renderer == null) return;
            Message theme = message(THEME, owner, 0); putTheme(context, theme);
            try { renderer.send(theme); } catch (RemoteException error) { dispose(false, "分析界面连接已失效"); }
        }

        void dispose(boolean tellRenderer, String error) {
            if (closed) return; closed = true;
            final InspectorFloatyWindow fallbackWindow = error == null ? null : fallback;
            if (fallbackWindow != null) {
                MAIN.post(() -> {
                    try {
                        if (!fallbackWindow.isClosedForFallback()) {
                            FloatyWindowManger.addWindowInProcess(context, fallbackWindow);
                        }
                    } catch (RuntimeException fallbackError) {
                        Log.w(TAG, "in-process inspector fallback failed", fallbackError);
                    }
                });
            }
            if (active == this) active = null;
            MAIN.removeCallbacks(openingTimeout);
            GlobalKeyObserver.getSingleton().removeVolumeDownListener(volumeDown);
            AppThemeRepository.get(context).removeListener(themeListener);
            if (preparing != null) preparing.cancel(true);
            cancelRefresh();
            if (tellRenderer && renderer != null) try { renderer.send(message(CLOSE, owner, 0)); } catch (RemoteException ignored) { }
            if (binder != null) binder.unlinkToDeath(death, 0);
            renderer = null; binder = null; unbind();
            for (String token : new ArrayList<>(files)) discard(token);
            if (error != null) { Log.w(TAG, error); Toast.makeText(context, error, Toast.LENGTH_LONG).show(); }
        }
        void unbind() {
            bound = false;
            try { context.unbindService(this); } catch (IllegalArgumentException ignored) { }
        }
        void discard(String token) {
            if (token == null) return; files.remove(token);
            try { IO.execute(() -> InspectorSnapshotTransport.discard(context, token)); }
            catch (RejectedExecutionException busy) { InspectorSnapshotTransport.discard(context, token); }
        }
        String reason(Exception error) { String value = error.getMessage(); return value == null ? error.getClass().getSimpleName() : value; }
    }
}
