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
import java.net.BindException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
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
 *
 * <p>States mirror Pro's {@code DebugService}: {@link ServerState} for the 9317
 * listener and {@link ClientState} for the phone → computer link. A failed client
 * dial is retried after 1 s, at most 3 times (initial attempt + 3 retries); the
 * failure reason is then kept in {@link #getClientError()} for the UI. All client
 * lifecycle logs are prefixed with {@code C-CONN:}.
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
    /** 回连失败后按 Pro 的节奏重试：间隔 1 秒，最多 3 次（加首次共 4 次尝试）。 */
    private static final int CLIENT_CONNECT_TIMEOUT_MS = 3000;
    private static final int CLIENT_RETRY_DELAY_MS = 1000;
    private static final int CLIENT_MAX_RETRIES = 3;
    /** How many archive frames may wait for their announcing json (the extension sends bytes first). */
    private static final int MAX_BUFFERED_ARCHIVES = 4;

    /** 监听端状态，命名与 Pro 的 DebugService.ServerState 一致。 */
    public enum ServerState { DISCONNECTED, CONNECTING, LISTENING }

    /** 电脑连接（手机 → 电脑）状态，命名与 Pro 的 DebugService.ClientState 一致。 */
    public enum ClientState { DISCONNECTED, CONNECTING, CONNECTED }

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
    /** 下一次回连尝试的时间（SystemClock.elapsedRealtime），受 mLock 保护。 */
    private long mNextClientRetry;
    /** 当前一轮回连已经用掉的重试次数，受 mLock 保护。 */
    private int mClientRetries;
    private volatile ServerState mServerState = ServerState.DISCONNECTED;
    private volatile ClientState mClientState = ClientState.DISCONNECTED;
    private volatile String mServerError = "";
    private volatile String mClientError = "";
    /** 电脑端 VS Code 插件最近一次 hello 里报告的 client_version。 */
    private volatile int mClientVersion;
    private ConnectivityManager.NetworkCallback mNetworkCallback;

    public String getReconnectHost() {
        return GlobalAppContext.get().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_COMPUTER_HOST, "");
    }

    public ServerState getServerState() {
        return mServerState;
    }

    public ClientState getClientState() {
        return mClientState;
    }

    /** Provider 的 {@code state} 列用 ordinal（0/1/2），与 Pro 的 ContentProvider 一致。 */
    public int getServerStateOrdinal() {
        return mServerState.ordinal();
    }

    public int getClientStateOrdinal() {
        return mClientState.ordinal();
    }

    /** 监听端启动失败的原因（如端口被占用）；空串表示没有错误。 */
    public String getServerError() {
        return mServerError;
    }

    /** 最近一次回连电脑失败的原因；空串表示没有错误。 */
    public String getClientError() {
        return mClientError;
    }

    /** 启动前清掉上一次的监听错误，避免 UI 读到已过期的失败原因。 */
    public void clearServerError() {
        mServerError = "";
    }

    /** 最近一次失败原因（优先客户端），供 UI 一行提示使用。 */
    public String getLastError() {
        if (!mClientError.isEmpty()) return mClientError;
        return mServerError;
    }

    /** 电脑端 VS Code 插件报告的 client_version；0 表示还没握手。 */
    public int getClientVersion() {
        return mClientVersion;
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
            if (mServerState != ServerState.DISCONNECTED) {
                return;
            }
            mServerState = ServerState.CONNECTING;
            mServerError = "";
            mClientError = "";
            mClientVersion = 0;
            ServerSocket serverSocket = new ServerSocket();
            try {
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(PORT), 8);
            } catch (IOException error) {
                try { serverSocket.close(); } catch (IOException ignored) { }
                mServerState = ServerState.DISCONNECTED;
                mServerError = describeBindError(error);
                Log.w(LOG_TAG, mServerError, error);
                throw new IOException(mServerError, error);
            }
            mServerSocket = serverSocket;
            mServerState = ServerState.LISTENING;
            if (mResponseHandler == null) {
                mResponseHandler = new DevPluginResponseHandler(
                        new File(GlobalAppContext.get().getCacheDir(), "remote_project"));
            }
            Thread acceptThread = new Thread(() -> acceptLoop(serverSocket), "dev-plugin-server");
            acceptThread.setDaemon(true);
            acceptThread.start();
            mClientRetries = 0;
            mNextClientRetry = 0;
            if (!getReconnectHost().isEmpty()) {
                mClientState = ClientState.CONNECTING;
            }
            mReconnectWorker = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "dev-plugin-reconnect");
                thread.setDaemon(true);
                return thread;
            });
            // 每 500 ms 检查一次；真正的重试间隔由 mNextClientRetry 控制（1 s × 3）。
            mReconnectWorker.scheduleWithFixedDelay(() -> {
                try {
                    clientTick();
                } catch (Throwable error) {
                    // 调度线程一旦抛出异常就不会再执行，必须兜住。
                    Log.w(LOG_TAG, "C-CONN: 回连调度异常", error);
                }
            }, 0, 500, TimeUnit.MILLISECONDS);
            watchNetwork(serverSocket);
            Log.i(LOG_TAG, "dev plugin server listening on port " + PORT);
        }
    }

    public void stop() {
        synchronized (mLock) {
            boolean wasListening = mServerState == ServerState.LISTENING;
            ServerSocket serverSocket = mServerSocket;
            mServerSocket = null;
            mServerState = ServerState.DISCONNECTED;
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
            mClientState = ClientState.DISCONNECTED;
            mClientRetries = CLIENT_MAX_RETRIES + 1;
            mNextClientRetry = 0;
            // 启动失败时 stop() 会被服务 onDestroy 调用；保留错误供 UI 读取。
            mClientVersion = 0;
            if (wasListening) {
                mServerError = "";
                mClientError = "";
            }
            if (serverSocket != null) Log.i(LOG_TAG, "dev plugin server stopped");
        }
    }

    private static void closeSocket(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) { }
    }

    private boolean isRunningLocked() {
        return mServerSocket != null && !mServerSocket.isClosed();
    }

    private boolean hasSessionFor(String host) {
        for (Session session : mSessions) {
            if (!session.mClosed && host.equals(session.mSocket.getInetAddress().getHostAddress())) return true;
        }
        return false;
    }

    /**
     * 「手机 → 电脑」回连：失败后等待 1 秒重试，一轮最多 3 次；仍失败则停止自动回连
     * 并把原因留在 {@link #getClientError()} 里。日志统一带 {@code C-CONN:} 前缀。
     */
    private void clientTick() {
        final String host;
        final Socket socket;
        final int attempt;
        synchronized (mLock) {
            if (!isRunningLocked() || mConnectingSocket != null) return;
            if (getClientCount() > 0) {
                mClientState = ClientState.CONNECTED;
                return;
            }
            host = getReconnectHost();
            if (host.isEmpty()) {
                mClientState = ClientState.DISCONNECTED;
                return;
            }
            // 电脑已经主动连进来了（或正在等 hello），不要同时再拨一条。
            if (hasSessionFor(host)) return;
            if (mClientRetries > CLIENT_MAX_RETRIES) return;
            if (SystemClock.elapsedRealtime() < mNextClientRetry) return;
            mClientState = ClientState.CONNECTING;
            attempt = mClientRetries + 1;
            socket = new Socket();
            mConnectingSocket = socket;
        }
        try {
            Log.i(LOG_TAG, "C-CONN: 正在连接电脑 " + host + ":" + COMPUTER_PORT
                    + "（第 " + attempt + "/" + (CLIENT_MAX_RETRIES + 1) + " 次尝试）");
            socket.connect(new InetSocketAddress(host, COMPUTER_PORT), CLIENT_CONNECT_TIMEOUT_MS);
            synchronized (mLock) {
                if (!isRunningLocked() || mConnectingSocket != socket) {
                    closeSocket(socket);
                    return;
                }
                mConnectingSocket = null;
                if (hasSessionFor(host) || getReconnectHost().isEmpty()) {
                    closeSocket(socket);
                    return;
                }
                Session session = new Session(socket, true);
                mSessions.add(session);
                session.start();
                mClientState = ClientState.CONNECTING;
                Log.i(LOG_TAG, "C-CONN: 已建立到电脑 " + host + ":" + COMPUTER_PORT
                        + " 的 TCP 连接，等待电脑端调试插件 hello");
            }
        } catch (IOException | RuntimeException error) {
            synchronized (mLock) {
                if (mConnectingSocket == socket) mConnectingSocket = null;
                closeSocket(socket);
                if (!isRunningLocked()) return;
                if (getReconnectHost().isEmpty()) {
                    mClientState = ClientState.DISCONNECTED;
                    return;
                }
                onClientAttemptFailed(host, error);
            }
        }
    }

    /** 调用方需持有 mLock。 */
    private void onClientAttemptFailed(String host, Throwable error) {
        String message = describeConnectError(host, error);
        mClientError = message;
        if (mClientRetries < CLIENT_MAX_RETRIES) {
            mClientRetries++;
            mNextClientRetry = SystemClock.elapsedRealtime() + CLIENT_RETRY_DELAY_MS;
            mClientState = ClientState.CONNECTING;
            Log.w(LOG_TAG, "C-CONN: " + message + "（第 " + mClientRetries + "/"
                    + CLIENT_MAX_RETRIES + " 次重试，1 秒后重试）");
        } else {
            mClientState = ClientState.DISCONNECTED;
            mNextClientRetry = 0;
            Log.w(LOG_TAG, "C-CONN: " + message + "（已重试 " + CLIENT_MAX_RETRIES
                    + " 次，暂停自动回连；修改地址或网络变化后会重新开始）");
        }
    }

    /** 一条会话结束（任方向）后同步电脑连接状态，并按需安排回连。 */
    private void onSessionClosed(Session session) {
        synchronized (mLock) {
            if (mServerSocket == null) {
                if (getClientCount() == 0) mClientState = ClientState.DISCONNECTED;
                return;
            }
            if (getClientCount() > 0) {
                mClientState = ClientState.CONNECTED;
                return;
            }
            String host = getReconnectHost();
            if (host.isEmpty()) {
                mClientState = ClientState.DISCONNECTED;
                return;
            }
            if (session.mOutgoing) {
                if (session.isAttached()) {
                    // 已握手后掉线：重新开始一轮 1 s × 3 回连。
                    mClientRetries = 0;
                    mClientError = "";
                    mClientState = ClientState.CONNECTING;
                    mNextClientRetry = SystemClock.elapsedRealtime() + CLIENT_RETRY_DELAY_MS;
                    Log.i(LOG_TAG, "C-CONN: 与电脑的连接已断开，1 秒后重连");
                } else {
                    onClientAttemptFailed(host, new IOException("电脑端接受了连接但没有完成 hello 握手"));
                }
            } else {
                // 电脑主动连入后断开：保留上次错误，按相同节奏回连。
                mClientRetries = 0;
                mClientState = ClientState.CONNECTING;
                mNextClientRetry = SystemClock.elapsedRealtime() + CLIENT_RETRY_DELAY_MS;
                Log.i(LOG_TAG, "C-CONN: 电脑主动连接已断开，1 秒后回连");
            }
        }
    }

    /** 电脑端 hello 完成：记录版本、清掉错误、进入 CONNECTED。 */
    private void onSessionAttached(Session session) {
        synchronized (mLock) {
            mClientVersion = (int) session.mClientVersion;
            mClientState = ClientState.CONNECTED;
            mClientError = "";
            mClientRetries = 0;
            mNextClientRetry = 0;
        }
        Log.i(LOG_TAG, "C-CONN: 电脑端握手完成（client_version=" + session.mClientVersion
                + "，方向=" + (session.mOutgoing ? "手机→电脑" : "电脑→手机") + "）");
    }

    /** 记住/更新电脑地址，供之后自动回连；USB 回环地址不记。 */
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
    }

    /** 主动连接电脑：写地址并清零重试计数，下一次 tick 立即尝试。 */
    public void connectClient(String host) {
        String target = host == null ? "" : host.trim();
        synchronized (mLock) {
            GlobalAppContext.get().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(KEY_COMPUTER_HOST, target).apply();
            mClientRetries = 0;
            mNextClientRetry = 0;
            mClientError = "";
            mClientVersion = 0;
            mClientState = target.isEmpty() ? ClientState.DISCONNECTED : ClientState.CONNECTING;
        }
        if (!target.isEmpty()) {
            Log.i(LOG_TAG, "C-CONN: 准备主动连接电脑 " + target + ":" + COMPUTER_PORT);
        }
    }

    /** 关闭「手机 → 电脑」模式：清掉记住的地址、取消重试并真正断开已建立的出站会话。 */
    public void disconnectClient() {
        synchronized (mLock) {
            GlobalAppContext.get().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .remove(KEY_COMPUTER_HOST).apply();
            mClientRetries = CLIENT_MAX_RETRIES + 1;
            mNextClientRetry = 0;
            mClientError = "";
            mClientVersion = 0;
            closeSocket(mConnectingSocket);
            mConnectingSocket = null;
            for (Session session : mSessions) {
                if (session.mOutgoing) session.close();
            }
            if (getClientCount() == 0) mClientState = ClientState.DISCONNECTED;
        }
        Log.i(LOG_TAG, "C-CONN: 客户端模式已关闭，断开与电脑的连接");
    }

    private static String describeBindError(IOException error) {
        String raw = error.getMessage() == null ? error.toString() : error.getMessage();
        if (error instanceof BindException
                || raw.contains("Address already in use")
                || raw.contains("EADDRINUSE")) {
            return "端口 " + PORT + " 已被占用：请关闭占用该端口的应用（或停止另一个调试服务器）后重试开启";
        }
        return "无法在端口 " + PORT + " 启动调试服务：" + raw + "。请检查网络权限后重试";
    }

    private static String describeConnectError(String host, Throwable error) {
        String target = host + ":" + COMPUTER_PORT;
        if (error instanceof SocketTimeoutException) {
            return "连接电脑 " + target + " 超时：请确认电脑已启动 VS Code 调试插件、与手机在同一局域网，且电脑防火墙放行 "
                    + COMPUTER_PORT + " 端口";
        }
        if (error instanceof UnknownHostException) {
            return "找不到电脑地址 " + host + "：请检查 IP 是否填写正确";
        }
        if (error instanceof NoRouteToHostException) {
            return "无法到达电脑 " + target + "：请确认手机与电脑在同一 WiFi，且电脑防火墙放行 "
                    + COMPUTER_PORT + " 端口";
        }
        if (error instanceof ConnectException) {
            String raw = error.getMessage() == null ? "" : error.getMessage();
            if (raw.contains("refused") || raw.contains("拒绝")) {
                return "电脑 " + target + " 拒绝连接：请确认电脑已启动 VS Code 调试插件并监听 "
                        + COMPUTER_PORT + " 端口";
            }
            return "无法连接电脑 " + target + "：请确认电脑已启动调试插件、与手机在同一局域网";
        }
        if (error instanceof SocketException) {
            String raw = error.getMessage() == null ? error.toString() : error.getMessage();
            return "连接电脑 " + target + " 失败：" + raw + "。请确认手机网络已连接后重试";
        }
        String raw = error.getMessage() == null ? error.toString() : error.getMessage();
        return "连接电脑 " + target + " 失败：" + raw + "。请确认电脑已启动 VS Code 调试插件、与手机同一局域网，且防火墙放行 "
                + COMPUTER_PORT + " 端口";
    }

    private ConnectivityManager connectivity() {
        return (ConnectivityManager) GlobalAppContext.get().getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    private void watchNetwork(ServerSocket owner) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        mNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                synchronized (mLock) {
                    if (mServerSocket != owner || getReconnectHost().isEmpty()) return;
                    // 网络恢复：立刻重新开始一轮 1 s × 3 回连。
                    mClientRetries = 0;
                    mNextClientRetry = 0;
                    if (getClientCount() == 0) {
                        mClientState = ClientState.CONNECTING;
                    }
                }
                Log.i(LOG_TAG, "C-CONN: 网络可用，准备回连电脑");
            }
            @Override public void onLost(Network network) {
                synchronized (mLock) {
                    if (mServerSocket != owner) return;
                    // A dead Wi-Fi socket may otherwise stay "connected" for minutes.
                    for (Session session : mSessions) {
                        if (!session.mSocket.getInetAddress().isLoopbackAddress()) session.close();
                    }
                    closeSocket(mConnectingSocket);
                    mConnectingSocket = null;
                    mClientRetries = 0;
                    mNextClientRetry = 0;
                    if (!getReconnectHost().isEmpty()) {
                        mClientState = ClientState.CONNECTING;
                        mClientError = "网络已断开：请检查手机 WiFi/热点，恢复后会自动回连电脑";
                    }
                }
            }
        };
        try { connectivity().registerDefaultNetworkCallback(mNetworkCallback); }
        catch (RuntimeException error) {
            mNetworkCallback = null;
            Log.w(LOG_TAG, "network change monitoring unavailable", error);
        }
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
                    Session session = new Session(socket, false);
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

        /** true = 这条连接是手机主动拨电脑（客户端模式），false = 电脑拨手机。 */
        private final boolean mOutgoing;
        private volatile boolean mClosed;
        private volatile boolean mAttached;
        private long mClientVersion;

        Session(Socket socket, boolean outgoing) throws IOException {
            mSocket = socket;
            mOutgoing = outgoing;
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
            onSessionClosed(this);
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
                    onSessionAttached(this);
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
