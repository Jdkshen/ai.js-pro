package com.jdkshen.aijspro.pluginclient;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.net.ConnectivityManager;
import android.net.Network;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stardust.app.GlobalAppContext;
import com.stardust.view.accessibility.LayoutInspector;
import com.stardust.view.accessibility.NodeInfo;

import com.jdkshen.aijspro.BuildConfig;
import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.autojs.AutoJs;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

/**
 * Device-side server for the VS Code extension "Auto.js Pro"
 * ({@code hyb1996.auto-js-pro-ext}, protocol family of Pro 9.2 and earlier).
 *
 * <p>The extension either dials the device on {@link #PORT} directly ("输入设备IP地址")
 * or, over USB, runs
 * {@code adb shell content query --uri content://org.autojs.autojspro.debug.provider/debug-server}
 * (served by {@link DebugServerProvider}), forwards a local port to this one and
 * connects to {@code 127.0.0.1:<forwarded>}. Once connected the device speaks
 * first: it sends the {@code hello} frame, the extension answers with its own
 * {@code hello}, then pushes {@code command} / {@code bytes_command} frames
 * (run/save/stop/stopAll plus run_project/save_project with a zipped project).
 *
 * <p>The wire format and message vocabulary are decoded from the extension's
 * bundled {@code dist/extension.js} (webpack module 8012, class {@code LegacyDevice});
 * the command semantics are shared with the old Auto.js 4.x protocol that
 * {@link DevPluginResponseHandler} already implements, so that handler is reused
 * as-is.
 *
 * <p>Only the run/save/stop subset is implemented. The extension's
 * {@code browse_files} / {@code inspect_layout} / {@code debug} commands are not
 * answered with real data; the extension gates those on the advertised
 * {@code app_version_code} (>= 80100 / 80800) which this app intentionally never
 * reaches, and a command that has no handler still receives an empty response.
 */
public final class DevPluginServer {

    /** Auto.js Pro 9 legacy debug server port (also what the extension's USB forward targets). */
    public static final int PORT = 9317;

    /**
     * Reported to the extension as the device's protocol version. The extension
     * gzips frames only when this is >= 4, so 3 keeps the wire format plain while
     * matching what Pro 9.2-era devices report. The debug-adapter feature also
     * requires {@code app_version_code >= 80100}, which this app never reports.
     */
    public static final int PROTOCOL_VERSION = 3;

    private static final String LOG_TAG = "DevPluginServer";
    private static final String PREFS_NAME = "dev_plugin_server";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_COMPUTER_HOST = "computer_host";
    // The installed Pro extension's LegacyDeviceClient accepts device-initiated TCP here.
    private static final int COMPUTER_PORT = 8327;
    /** How many archive frames may wait for their announcing json (the extension sends bytes first). */
    private static final int MAX_BUFFERED_ARCHIVES = 4;

    /** 文件类命令（list_files / read_file / write_file）的读写线程：别堵主线程与协议读线程。 */
    private static final ExecutorService FILE_WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dev-plugin-files");
        thread.setDaemon(true);
        return thread;
    });

    private static final DevPluginServer INSTANCE = new DevPluginServer();

    public static DevPluginServer getInstance() {
        return INSTANCE;
    }

    private final Object mLock = new Object();
    private final CopyOnWriteArrayList<Session> mSessions = new CopyOnWriteArrayList<>();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private ServerSocket mServerSocket;
    private DevPluginResponseHandler mResponseHandler;
    private ScheduledExecutorService mReconnectWorker;
    private Socket mConnectingSocket;
    private volatile long mNextReconnect;
    private int mReconnectFailures;
    private ConnectivityManager.NetworkCallback mNetworkCallback;

    public String getReconnectHost() {
        return GlobalAppContext.get().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_COMPUTER_HOST, "");
    }

    private DevPluginServer() {
    }

    public boolean isRunning() {
        synchronized (mLock) {
            return mServerSocket != null && !mServerSocket.isClosed();
        }
    }

    /** Number of VS Code-side clients that completed the hello handshake. */
    public int getClientCount() {
        int count = 0;
        for (Session session : mSessions) {
            if (session.isAttached()) {
                count++;
            }
        }
        return count;
    }

    /** Binds the port synchronously so callers can report "port in use" right away. */
    public void start() throws IOException {
        synchronized (mLock) {
            if (isRunning()) {
                return;
            }
            ServerSocket serverSocket = new ServerSocket();
            try {
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(PORT), 8);
            } catch (IOException e) {
                serverSocket.close();
                throw e;
            }
            mServerSocket = serverSocket;
            if (mResponseHandler == null) {
                mResponseHandler = new DevPluginResponseHandler(
                        new File(GlobalAppContext.get().getCacheDir(), "remote_project"));
            }
            Thread acceptThread = new Thread(() -> acceptLoop(serverSocket), "dev-plugin-server");
            acceptThread.setDaemon(true);
            acceptThread.start();
            mReconnectFailures = 0;
            mNextReconnect = 0;
            mReconnectWorker = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "dev-plugin-reconnect");
                thread.setDaemon(true);
                return thread;
            });
            mReconnectWorker.scheduleWithFixedDelay(() -> reconnect(serverSocket), 2, 3, TimeUnit.SECONDS);
            watchNetwork(serverSocket);
            Log.i(LOG_TAG, "dev plugin server listening on port " + PORT);
        }
    }

    public void stop() {
        synchronized (mLock) {
            ServerSocket serverSocket = mServerSocket;
            mServerSocket = null;
            if (mReconnectWorker != null) {
                mReconnectWorker.shutdownNow();
                mReconnectWorker = null;
            }
            closeSocket(mConnectingSocket);
            mConnectingSocket = null;
            if (mNetworkCallback != null) {
                try { connectivity().unregisterNetworkCallback(mNetworkCallback); }
                catch (RuntimeException ignored) { }
                mNetworkCallback = null;
            }
            if (serverSocket != null) {
                try { serverSocket.close(); } catch (IOException ignored) { }
            }
            for (Session session : mSessions) session.close();
            mSessions.clear();
            if (serverSocket != null) Log.i(LOG_TAG, "dev plugin server stopped");
        }
    }

    private static void closeSocket(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) { }
    }

    private boolean hasSessionFor(String host) {
        for (Session session : mSessions) {
            if (!session.mClosed && host.equals(session.mSocket.getInetAddress().getHostAddress())) return true;
        }
        return false;
    }

    private void reconnect(ServerSocket owner) {
        Socket socket = null;
        try {
            String host = getReconnectHost();
            synchronized (mLock) {
                if (mServerSocket != owner || owner.isClosed() || host.isEmpty()
                        || hasSessionFor(host) || mConnectingSocket != null
                        || SystemClock.elapsedRealtime() < mNextReconnect) return;
                // Quiet bounded retries while the computer/extension is offline.
                mNextReconnect = SystemClock.elapsedRealtime() + Math.min(30000L, 2000L << Math.min(mReconnectFailures++, 4));
                socket = new Socket();
                mConnectingSocket = socket;
            }
            socket.connect(new InetSocketAddress(host, COMPUTER_PORT), 3000);
            synchronized (mLock) {
                if (mServerSocket != owner || hasSessionFor(host)) {
                    closeSocket(socket);
                    return;
                }
                Session session = new Session(socket);
                mSessions.add(session);
                session.start();
                Log.i(LOG_TAG, "reconnecting to computer " + host + ":" + COMPUTER_PORT);
            }
        } catch (IOException | RuntimeException error) {
            closeSocket(socket);
            Log.d(LOG_TAG, "computer not available: " + error.getMessage());
        } finally {
            synchronized (mLock) {
                if (mConnectingSocket == socket) mConnectingSocket = null;
            }
        }
    }

    private ConnectivityManager connectivity() {
        return (ConnectivityManager) GlobalAppContext.get().getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    private void watchNetwork(ServerSocket owner) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        mNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { mNextReconnect = 0; }
            @Override public void onLost(Network network) {
                synchronized (mLock) {
                    if (mServerSocket != owner) return;
                    // A dead Wi-Fi socket may otherwise stay "connected" for minutes.
                    for (Session session : mSessions) {
                        if (!session.mSocket.getInetAddress().isLoopbackAddress()) session.close();
                    }
                    closeSocket(mConnectingSocket);
                    mNextReconnect = 0;
                }
            }
        };
        try { connectivity().registerDefaultNetworkCallback(mNetworkCallback); }
        catch (RuntimeException error) {
            mNetworkCallback = null;
            Log.w(LOG_TAG, "network change monitoring unavailable", error);
        }
    }

    private void rememberComputer(Socket socket) {
        InetAddress address = socket.getInetAddress();
        // USB forwards report loopback: never remember it as a LAN computer.
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isMulticastAddress()) return;
        String host = address.getHostAddress();
        if (!host.equals(getReconnectHost())) {
            GlobalAppContext.get().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(KEY_COMPUTER_HOST, host).apply();
            Log.i(LOG_TAG, "remembered computer " + host);
        }
        synchronized (mLock) { mReconnectFailures = 0; }
    }

    /** Mirrors this app's script console to every attached VS Code client ("log" frames). */
    public void broadcastLog(String log) {
        for (Session session : mSessions) {
            session.sendLog(log);
        }
    }

    /** First non-loopback IPv4 address, for the drawer summary ("<ip>:9317"). */
    public static String localIpv4Address() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    String host = address.getHostAddress();
                    if (host != null && !host.contains(":")) {
                        return host;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "cannot resolve local address", e);
        }
        return null;
    }

    private void acceptLoop(ServerSocket serverSocket) {
        while (true) {
            synchronized (mLock) {
                if (mServerSocket != serverSocket) return;
            }
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    Log.w(LOG_TAG, "accept failed", e);
                }
                return;
            }
            try {
                synchronized (mLock) {
                    if (mServerSocket != serverSocket) { closeSocket(socket); return; }
                    Session session = new Session(socket);
                    mSessions.add(session);
                    session.start();
                }
                Log.i(LOG_TAG, "client connected: " + socket.getRemoteSocketAddress());
            } catch (IOException e) {
                Log.w(LOG_TAG, "cannot serve connection", e);
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private String deviceId() {
        Context context = GlobalAppContext.get();
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String id = preferences.getString(KEY_DEVICE_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            preferences.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    private static String asString(JsonObject object, String name) {
        if (object.has(name) && object.get(name).isJsonPrimitive()) {
            return object.get(name).getAsString();
        }
        return null;
    }

    private static long asLong(JsonObject object, String name) {
        if (object.has(name) && object.get(name).isJsonPrimitive()) {
            return object.get(name).getAsLong();
        }
        return -1;
    }

    private final class Session {

        private final Socket mSocket;
        private final DataInputStream mInput;
        private final OutputStream mOutput;
        private final Object mWriteLock = new Object();
        private final AtomicLong mMessageId = new AtomicLong();
        private final Map<String, JsonObject> mPendingBytesCommands = new LinkedHashMap<>();
        /** Archive frames that arrived before their bytes_command json. */
        private final Map<String, byte[]> mReceivedArchives = new LinkedHashMap<>();
        /** Frames are produced on the main thread but written off it (StrictMode forbids writes there). */
        private final ExecutorService mWriter;

        private volatile boolean mClosed;
        private volatile boolean mAttached;
        private long mClientVersion;

        Session(Socket socket) throws IOException {
            mSocket = socket;
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(10000); // An unavailable or non-Pro peer must not block retries forever.
            mInput = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            mOutput = new BufferedOutputStream(socket.getOutputStream());
            mWriter = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "dev-plugin-writer");
                thread.setDaemon(true);
                return thread;
            });
        }

        boolean isAttached() {
            return mAttached;
        }

        void start() {
            sendHello();
            Thread thread = new Thread(this::readLoop, "dev-plugin-session");
            thread.setDaemon(true);
            thread.start();
        }

        void close() {
            if (mClosed) {
                return;
            }
            mClosed = true;
            mWriter.shutdownNow();
            try {
                mSocket.close();
            } catch (IOException ignored) {
            }
            mSessions.remove(this);
            Log.i(LOG_TAG, "client disconnected: " + mSocket.getRemoteSocketAddress());
        }

        void respond(long id, JsonObject data) {
            if (id < 0 || mClosed) {
                return;
            }
            JsonObject message = new JsonObject();
            message.addProperty("id", mMessageId.incrementAndGet());
            message.addProperty("type", "response_" + id);
            message.add("data", data);
            sendJson(message);
        }

        void sendLog(String log) {
            if (mClosed || !mAttached) {
                return;
            }
            JsonObject message = new JsonObject();
            message.addProperty("id", mMessageId.incrementAndGet());
            message.addProperty("type", "log");
            JsonObject data = new JsonObject();
            data.addProperty("log", log);
            message.add("data", data);
            sendJson(message);
        }

        /** 发原始字节帧（read_file 的内容；对端按紧随的 response 里的 md5 配对）。 */
        void sendBytes(byte[] payload) {
            if (mClosed) {
                return;
            }
            final byte[] frame = DevPluginFrames.encode(DevPluginFrames.TYPE_BYTES, payload);
            try {
                mWriter.execute(() -> writeFrame(frame));
            } catch (RejectedExecutionException e) {
                // Session is closing.
            }
        }

        private void readLoop() {
            try {
                while (!mClosed) {
                    DevPluginFrames.Frame frame = DevPluginFrames.read(mInput);
                    dispatch(frame);
                }
            } catch (EOFException e) {
                // Peer closed the connection.
            } catch (IOException e) {
                if (!mClosed) {
                    Log.d(LOG_TAG, "session closed: " + e.getMessage());
                }
            } catch (RuntimeException e) {
                Log.w(LOG_TAG, "protocol error", e);
            } finally {
                close();
            }
        }

        private void dispatch(DevPluginFrames.Frame frame) throws IOException {
            switch (frame.type) {
                case DevPluginFrames.TYPE_JSON:
                case DevPluginFrames.TYPE_GZIP_JSON:
                    JsonObject message = new JsonParser()
                            .parse(DevPluginFrames.parseJson(frame)).getAsJsonObject();
                    onJson(message);
                    break;
                case DevPluginFrames.TYPE_BYTES:
                case DevPluginFrames.TYPE_GZIP_BYTES:
                    onBytes(DevPluginFrames.parseBytes(frame));
                    break;
                default:
                    Log.w(LOG_TAG, "unknown frame type " + frame.type);
            }
        }

        private void onJson(JsonObject message) {
            String type = asString(message, "type");
            if (type == null) {
                return;
            }
            switch (type) {
                case "hello":
                    mClientVersion = asLong(message.getAsJsonObject("data"), "client_version");
                    if (mClientVersion < 1) { close(); break; }
                    try { mSocket.setSoTimeout(0); }
                    catch (IOException error) { close(); break; }
                    mAttached = true;
                    rememberComputer(mSocket);
                    Log.i(LOG_TAG, "client hello: version=" + mClientVersion);
                    break;
                case "command": {
                    final long id = asLong(message, "id");
                    JsonObject commandData = message.getAsJsonObject("data");
                    String command = commandData == null ? null : asString(commandData, "command");
                    if (DevPluginFileService.isFileCommand(command)) {
                        // 自研扩展的扩展命令：文件列表 / 读取 / 写入（都相对脚本目录）。
                        handleFileCommand(id, command, commandData);
                        break;
                    }
                    if (DevPluginLayoutService.isLayoutCommand(command)) {
                        // 自研扩展的扩展命令：布局分析（无障碍捕获当前窗口 → XML）。
                        handleInspectLayout(id);
                        break;
                    }
                    if (DevPluginScreenshotService.isScreenshotCommand(command)) {
                        // 自研扩展的扩展命令：图色助手（无障碍全屏截图 → PNG）。
                        handleScreenshot(id);
                        break;
                    }
                    if (DevPluginDebugService.isDebugCommand(command)) {
                        // 自研扩展的扩展命令：断点调试（Rhino）。
                        handleDebugCommand(id, command, commandData);
                        break;
                    }
                    mMainHandler.post(() -> {
                        JsonObject result = new JsonObject();
                        try {
                            if ("stop".equals(command)) {
                                String target = asString(commandData, "id");
                                boolean stopped = target != null && mResponseHandler.stopScript(target);
                                result.addProperty("stopped", stopped);
                                if (!stopped) result.addProperty("error", "没有找到正在运行的脚本：" + target);
                            } else if ("stopAll".equals(command)) {
                                result.addProperty("stoppedCount", mResponseHandler.stopAllScripts());
                            } else {
                                mResponseHandler.handle(message);
                            }
                        } catch (Throwable t) {
                            Log.w(LOG_TAG, "command failed", t);
                            result.addProperty("error", t.getMessage() == null ? t.toString() : t.getMessage());
                        }
                        respond(id, result);
                    });
                    break;
                }
                case "bytes_command": {
                    String md5 = asString(message, "md5");
                    if (md5 == null) {
                        Log.w(LOG_TAG, "bytes_command without md5");
                        break;
                    }
                    // The extension writes the archive frame *before* the annoucing json
                    // (`sendBytes(...)` runs before `sendBytesCommand(...)` in extension.js),
                    // so the archive is usually already buffered when this arrives.
                    byte[] archive;
                    synchronized (mReceivedArchives) {
                        archive = mReceivedArchives.remove(md5);
                    }
                    if (archive != null) {
                        Log.d(LOG_TAG, "matched buffered archive for " + md5);
                        handleBytesCommand(message, archive);
                    } else {
                        synchronized (mPendingBytesCommands) {
                            mPendingBytesCommands.put(md5, message);
                        }
                    }
                    break;
                }
                default:
                    Log.w(LOG_TAG, "unhandled message: " + type);
            }
        }

        private void onBytes(byte[] payload) {
            String md5 = DevPluginFrames.md5Hex(payload);
            JsonObject command;
            synchronized (mPendingBytesCommands) {
                command = mPendingBytesCommands.remove(md5);
                if (command == null && mPendingBytesCommands.size() == 1) {
                    // Fallback for builds that announce nothing or hash differently.
                    Iterator<Map.Entry<String, JsonObject>> iterator =
                            mPendingBytesCommands.entrySet().iterator();
                    command = iterator.next().getValue();
                    iterator.remove();
                }
            }
            if (command == null) {
                synchronized (mReceivedArchives) {
                    mReceivedArchives.put(md5, payload);
                    while (mReceivedArchives.size() > MAX_BUFFERED_ARCHIVES) {
                        Iterator<String> oldest = mReceivedArchives.keySet().iterator();
                        oldest.next();
                        oldest.remove();
                    }
                }
                Log.d(LOG_TAG, "buffered archive frame: " + payload.length + " bytes, md5 " + md5);
                return;
            }
            handleBytesCommand(command, payload);
        }

        private void handleBytesCommand(JsonObject command, byte[] payload) {
            final long id = asLong(command, "id");
            Log.i(LOG_TAG, "handling project archive (" + payload.length + " bytes)");
            mResponseHandler.handleBytes(command, payload)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(dir -> {
                        JsonObject data = command.getAsJsonObject("data");
                        data.addProperty("dir", dir.getPath());
                        try {
                            mResponseHandler.handle(command);
                        } catch (Throwable t) {
                            Log.w(LOG_TAG, "bytes command failed", t);
                        }
                        respond(id, new JsonObject());
                    }, error -> {
                        Log.w(LOG_TAG, "cannot unzip project", error);
                        respond(id, new JsonObject());
                    });
        }

        /**
         * 自研扩展扩展命令：文件列表 / 读取 / 写入。
         *
         * <p>读写在后台线程完成；失败时 response.data 里带 {@code error}，扩展据此 reject。
         * {@code read_file} 先发 bytes 帧再发 response（response.data.md5 用于配对）。
         */
        private void handleFileCommand(long id, String command, JsonObject data) {
            final String path = data == null ? null : asString(data, "path");
            FILE_WORKER.execute(() -> {
                try {
                    File baseDir = new File(Pref.getScriptDirPath());
                    if (DevPluginFileService.LIST_FILES.equals(command)) {
                        boolean recursive = data.get("recursive") == null
                                || data.get("recursive").getAsBoolean();
                        respond(id, DevPluginFileService.listFiles(baseDir, path, recursive));
                        return;
                    }
                    if (DevPluginFileService.READ_FILE.equals(command)) {
                        byte[] bytes = DevPluginFileService.readFile(baseDir, path);
                        JsonObject result = new JsonObject();
                        result.addProperty("path", path);
                        result.addProperty("size", bytes.length);
                        result.addProperty("md5", DevPluginFrames.md5Hex(bytes));
                        sendBytes(bytes);
                        respond(id, result);
                        return;
                    }
                    // write_file
                    String script = data.get("script") == null ? "" : data.get("script").getAsString();
                    respond(id, DevPluginFileService.writeFile(baseDir, path, script));
                } catch (Throwable error) {
                    Log.w(LOG_TAG, command + " failed", error);
                    JsonObject result = new JsonObject();
                    result.addProperty("error",
                            error.getMessage() == null ? error.toString() : error.getMessage());
                    respond(id, result);
                }
            });
        }

        /**
         * 布局分析：无障碍捕获当前窗口（与手机悬浮窗「布局层次分析」同一份数据），
         * 导出成 XML 后以 bytes 帧回发（response 里带 md5/size）。
         */
        private void handleInspectLayout(long id) {
            FILE_WORKER.execute(() -> {
                LayoutInspector.CaptureAvailableListener listener = null;
                try {
                    AutoJs autoJs = AutoJs.getInstance();
                    LayoutInspector inspector = autoJs == null ? null : autoJs.getLayoutInspector();
                    if (inspector == null) {
                        respondError(id, "布局分析需要开启 AI.js Pro 的无障碍服务");
                        return;
                    }
                    final CountDownLatch latch = new CountDownLatch(1);
                    final NodeInfo[] holder = new NodeInfo[1];
                    listener = capture -> {
                        holder[0] = capture;
                        latch.countDown();
                    };
                    inspector.addCaptureAvailableListener(listener);
                    if (!inspector.captureCurrentWindow()) {
                        respondError(id, "无法捕获当前窗口：请确认无障碍服务已开启且有活动窗口");
                        return;
                    }
                    if (!latch.await(6, TimeUnit.SECONDS)) {
                        respondError(id, "布局捕获超时（无障碍服务可能未开启）");
                        return;
                    }
                    if (holder[0] == null) {
                        respondError(id, "无法读取当前界面节点：请关闭分析窗口后重试，部分页面不向系统提供节点");
                        return;
                    }
                    byte[] bytes = DevPluginLayoutService.dump(holder[0])
                            .getBytes(StandardCharsets.UTF_8);
                    JsonObject result = new JsonObject();
                    result.addProperty("size", bytes.length);
                    result.addProperty("md5", DevPluginFrames.md5Hex(bytes));
                    sendBytes(bytes);
                    respond(id, result);
                } catch (Throwable error) {
                    Log.w(LOG_TAG, "inspect_layout failed", error);
                    respondError(id, error.getMessage() == null
                            ? error.toString() : error.getMessage());
                } finally {
                    if (listener != null) {
                        try {
                            AutoJs autoJs = AutoJs.getInstance();
                            LayoutInspector inspector = autoJs == null ? null : autoJs.getLayoutInspector();
                            if (inspector != null) {
                                inspector.removeCaptureAvailableListener(listener);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
        }

        /**
         * 图色助手：一次性全屏截图（无障碍通道）→ PNG bytes 帧 + response{width,height,size,md5}。
         */
        private void handleScreenshot(long id) {
            FILE_WORKER.execute(() -> {
                try {
                    DevPluginScreenshotService.Result shot = DevPluginScreenshotService.capture();
                    JsonObject result = new JsonObject();
                    result.addProperty("width", shot.width);
                    result.addProperty("height", shot.height);
                    result.addProperty("size", shot.png.length);
                    result.addProperty("md5", DevPluginFrames.md5Hex(shot.png));
                    sendBytes(shot.png);
                    respond(id, result);
                } catch (Throwable error) {
                    Log.w(LOG_TAG, "screenshot failed", error);
                    respondError(id, error.getMessage() == null
                            ? error.toString() : error.getMessage());
                }
            });
        }

        /**
         * 断点调试命令（自研扩展）：debug_run / resume / set_breakpoints / evaluate /
         * variables / terminate。事件用 {@code debug_event} 回发（stopped/terminated/…）。
         */
        private void handleDebugCommand(long id, String command, JsonObject data) {
            DevPluginDebugService service = DevPluginDebugService.getInstance();
            try {
                switch (command) {
                    case DevPluginDebugService.DEBUG_RUN: {
                        JsonElement scriptElement = data.get("script");
                        if (scriptElement == null || !scriptElement.isJsonPrimitive()) {
                            respondError(id, "缺少脚本内容");
                            break;
                        }
                        service.start((event, payload) -> sendDebugEvent(event, payload),
                                asString(data, "name"), scriptElement.getAsString(),
                                parseBreakpoints(data));
                        respond(id, new JsonObject());
                        break;
                    }
                    case DevPluginDebugService.DEBUG_RESUME:
                        service.resume(asString(data, "action"));
                        respond(id, new JsonObject());
                        break;
                    case DevPluginDebugService.DEBUG_SET_BREAKPOINTS:
                        service.setBreakpoints(parseBreakpoints(data));
                        respond(id, new JsonObject());
                        break;
                    case DevPluginDebugService.DEBUG_EVALUATE: {
                        String result = service.evaluate(asString(data, "expr"), asInt(data, "frame"));
                        JsonObject response = new JsonObject();
                        if (result == null) {
                            response.addProperty("error", "当前没有暂停的栈帧（或表达式无法求值）");
                        } else {
                            response.addProperty("result", result);
                        }
                        respond(id, response);
                        break;
                    }
                    case DevPluginDebugService.DEBUG_VARIABLES: {
                        JsonArray variables = service.variables(asInt(data, "ref"));
                        JsonObject response = new JsonObject();
                        if (variables == null) {
                            response.addProperty("error", "当前没有暂停的栈帧");
                        } else {
                            response.add("variables", variables);
                        }
                        respond(id, response);
                        break;
                    }
                    case DevPluginDebugService.DEBUG_TERMINATE:
                        service.terminate();
                        respond(id, new JsonObject());
                        break;
                    default:
                        respond(id, new JsonObject());
                }
            } catch (Throwable error) {
                Log.w(LOG_TAG, command + " failed", error);
                respondError(id, error.getMessage() == null ? error.toString() : error.getMessage());
            }
        }

        private List<Integer> parseBreakpoints(JsonObject data) {
            List<Integer> lines = new ArrayList<>();
            if (data == null) {
                return lines;
            }
            JsonElement element = data.get("breakpoints");
            if (element == null || !element.isJsonArray()) {
                return lines;
            }
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonPrimitive()) {
                    try {
                        lines.add(item.getAsInt());
                    } catch (Throwable ignored) {
                    }
                }
            }
            return lines;
        }

        /** 调试事件（stopped / terminated / started / error），带上 event 字段。 */
        private void sendDebugEvent(String event, JsonObject payload) {
            if (mClosed) {
                return;
            }
            JsonObject message = new JsonObject();
            message.addProperty("id", mMessageId.incrementAndGet());
            message.addProperty("type", "debug_event");
            JsonObject data = payload == null ? new JsonObject() : payload;
            data.addProperty("event", event);
            message.add("data", data);
            sendJson(message);
        }

        private int asInt(JsonObject object, String name) {
            if (object != null && object.has(name) && object.get(name).isJsonPrimitive()) {
                try {
                    return object.get(name).getAsInt();
                } catch (Throwable ignored) {
                }
            }
            return 0;
        }

        private void respondError(long id, String message) {
            JsonObject result = new JsonObject();
            result.addProperty("error", message == null ? "未知错误" : message);
            respond(id, result);
        }

        private void sendHello() {
            JsonObject message = new JsonObject();
            message.addProperty("id", mMessageId.incrementAndGet());
            message.addProperty("type", "hello");
            JsonObject data = new JsonObject();
            data.addProperty("device_name", Build.BRAND + " " + Build.MODEL);
            data.addProperty("app_version", BuildConfig.VERSION_NAME);
            data.addProperty("app_version_code", BuildConfig.VERSION_CODE);
            data.addProperty("server_version", PROTOCOL_VERSION);
            data.addProperty("device_id", deviceId());
            message.add("data", data);
            sendJson(message);
        }

        private void sendJson(JsonObject message) {
            if (mClosed) {
                return;
            }
            final byte[] frame = DevPluginFrames.encodeJson(message.toString());
            try {
                mWriter.execute(() -> writeFrame(frame));
            } catch (RejectedExecutionException e) {
                // Session is closing.
            }
        }

        private void writeFrame(byte[] frame) {
            if (mClosed) {
                return;
            }
            try {
                synchronized (mWriteLock) {
                    mOutput.write(frame);
                    mOutput.flush();
                }
            } catch (IOException e) {
                Log.d(LOG_TAG, "send failed: " + e.getMessage());
                close();
            }
        }
    }
}
