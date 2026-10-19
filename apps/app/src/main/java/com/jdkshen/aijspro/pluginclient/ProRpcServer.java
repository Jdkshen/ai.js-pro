package com.jdkshen.aijspro.pluginclient;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.stardust.app.GlobalAppContext;
import com.stardust.autojs.ScriptEngineService;
import com.stardust.autojs.engine.ScriptEngine;
import com.stardust.autojs.execution.ScriptExecution;
import com.stardust.autojs.project.ProjectLauncher;
import com.stardust.view.accessibility.LayoutInspector;
import com.stardust.view.accessibility.NodeInfo;

import com.jdkshen.aijspro.BuildConfig;
import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.autojs.AutoJs;
import com.jdkshen.aijspro.model.script.ScriptFile;
import com.jdkshen.aijspro.model.script.Scripts;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Pro 9 的「电脑连接」（ws-rpc）服务端，手机侧实现。
 *
 * <p>对应反编译出的 {@code org.autojs.autojs.debugserver.DebugService} +
 * {@code rpc.RpcService}：监听 {@code new InetSocketAddress(0)} 的动态端口，
 * 通过两条通道公布真实端口：
 * <ul>
 *   <li>{@link DebugServerProvider}（adb / USB，authority
 *       {@code com.boyai.auma.debug.provider} + {@code org.autojs.autojspro.debug.provider}）；</li>
 *   <li>mDNS/NSD：{@code _autojsprodebug._tcp.} + 服务名
 *       {@code AutoJsProDevice-<uuid>}，{@code uuid} 与 {@code debug.authorize} 返回值一致。</li>
 * </ul>
 *
 * <p>传输/应用层：WebSocket（{@link ProRpcWebSocket}）+ {@link ProRpcFrames}
 * （4 字节大端类型 + 36 字节通道 id）+ JSON-RPC 2.0（{@link ProRpcDispatcher}）。
 *
 * <p>状态机沿用 Pro 的命名：{@link ServerState}（0/1/2 直接就是
 * {@link DebugServerProvider} 的 {@code state} 列）与 {@link ClientState}。
 *
 * <p>与 Pro 的一个差异：Pro 的 {@code startServer()} 连异常都吞掉后仍置
 * {@code LISTENING}（反编译可见），这里绑定失败会回到 {@code DISCONNECTED}
 * 并把异常抛给调用方，避免 UI/Provider 报「已监听」但实际没端口。
 */
public final class ProRpcServer implements DebugServerProvider.WsRpcEndpoint {

    /** Pro 的 {@code ServerState}：ordinal 即 provider 里 state 列的取值。 */
    public enum ServerState {
        DISCONNECTED, CONNECTING, LISTENING
    }

    /** Pro 的 {@code ClientState}（手机回连电脑）。 */
    public enum ClientState {
        DISCONNECTED, CONNECTING, CONNECTED
    }

    /** 「手机回连电脑」的默认端口（Pro 的 {@code 0x5225} = 21029）。 */
    public static final int PRO_COMPUTER_DEFAULT_PORT = 21029;

    private static final String LOG_TAG = "ProRpcServer";
    private static final String NSD_SERVICE_TYPE = "_autojsprodebug._tcp.";
    private static final String NSD_SERVICE_PREFIX = "AutoJsProDevice-";
    /** 回连断线后的重试：Pro 是 delay(1000) 且最多 3 次。 */
    private static final int CLIENT_RETRY_LIMIT = 3;
    private static final long CLIENT_RETRY_DELAY_MILLIS = 1000;
    /** 布局捕获等待无障碍回包的上限（与自研扩展的 inspect_layout 一致）。 */
    private static final long LAYOUT_TIMEOUT_SECONDS = 6;
    /** 握手阶段 socket 读超时：不让半开连接卡死接受线程。 */
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 10000;

    private static final ProRpcServer INSTANCE = new ProRpcServer();

    public static ProRpcServer getInstance() {
        return INSTANCE;
    }

    private final Object mLock = new Object();
    /** 串行化 start/stop，避免刚 stop 的旧 mDNS 注销把紧接着的新注册停掉。 */
    private final Object mLifecycleLock = new Object();
    private final CopyOnWriteArrayList<ProRpcSession> mSessions = new CopyOnWriteArrayList<>();
    private final Map<String, ScriptExecution> mExecutions = new ConcurrentHashMap<>();
    private final ProRpcDispatcher.Host mHost = new AndroidHost();
    /** 同一进程内稳定：mDNS 服务名与 AuthInfo.uuid 都用它。 */
    private final String mUuid = UUID.randomUUID().toString();

    private volatile ServerState mServerState = ServerState.DISCONNECTED;
    private volatile String mServerError = "";
    private ServerSocket mServerSocket;

    /** mDNS 注册/回调状态；generation 保证晚到的旧回调不会污染新状态。 */
    private final Object mMdnsLock = new Object();
    private final ProComputerLink.MdnsTracker mMdns = new ProComputerLink.MdnsTracker();
    private NsdManager.RegistrationListener mNsdListener;

    /** 出站连接的目标/状态/错误；只有 authorize 成功才会进入 CONNECTED。 */
    private final ProComputerLink.Outbound mClient = new ProComputerLink.Outbound();
    private Thread mClientThread;
    private ProRpcSession mClientSession;

    private ProRpcServer() {
        // 把 ws-rpc 的真实状态/端口交给 DebugServerProvider（com.boyai.auma.debug.provider 那一行）。
        // 进程被 adb 查询冷启动、服务还没起来时这里报 0/DISCONNECTED，正是期望值。
        DebugServerProvider.setWsRpcEndpoint(this);
    }

    // ------------------------------------------------------------ 状态

    public ServerState getServerState() {
        return mServerState;
    }

    /** 出站连接状态：WebSocket 连上但未 authorize 时仍是 CONNECTING。 */
    public ClientState getClientState() {
        switch (mClient.state()) {
            case ProComputerLink.STATE_CONNECTED:
                return ClientState.CONNECTED;
            case ProComputerLink.STATE_CONNECTING:
                return ClientState.CONNECTING;
            default:
                return ClientState.DISCONNECTED;
        }
    }

    public String getClientError() {
        return mClient.error();
    }

    /** 最近一次/当前出站目标；未连接过或用户已断开时返回 null。 */
    public ProComputerLink.Target getClientTarget() {
        return mClient.target();
    }

    public String getClientDisplayAddress() {
        ProComputerLink.Target target = mClient.target();
        return target == null ? "" : target.displayAddress();
    }

    public String getClientUri() {
        ProComputerLink.Target target = mClient.target();
        return target == null ? "" : target.endpointUri();
    }

    public String getServerError() {
        return mServerError;
    }

    public boolean isMdnsRegistered() {
        return mMdns.isRegistered();
    }

    public String getMdnsState() {
        return mMdns.state();
    }

    public String getMdnsError() {
        return mMdns.error();
    }

    public String getMdnsServiceName() {
        return mMdns.serviceName();
    }

    public boolean isRunning() {
        return mServerState == ServerState.LISTENING;
    }

    /** 真实端口；未监听时返回 0（Pro 的 provider 也是 0）。 */
    public int getPort() {
        ServerSocket serverSocket;
        synchronized (mLock) {
            serverSocket = mServerSocket;
        }
        if (serverSocket == null || serverSocket.isClosed()) {
            return 0;
        }
        try {
            SocketAddress address = serverSocket.getLocalSocketAddress();
            if (address instanceof InetSocketAddress) {
                return ((InetSocketAddress) address).getPort();
            }
        } catch (RuntimeException error) {
            Log.w(LOG_TAG, "cannot read the local port", error);
        }
        return 0;
    }

    public String getUuid() {
        return mUuid;
    }

    /**
     * 已授权连接数（入站 + 回连目标）。未完成 debug.authorize 的 socket 不计入；
     * 原始 WebSocket 数量看 {@link #getOpenSessionCount()}。
     */
    public int getClientCount() {
        int count = 0;
        for (ProRpcSession session : mSessions) {
            if (session.isAuthorized()) {
                count++;
            }
        }
        return count;
    }

    /** 已建立 WebSocket 的会话数（含尚未 authorize 的）。 */
    public int getOpenSessionCount() {
        return mSessions.size();
    }

    /** {@link DebugServerProvider.WsRpcEndpoint}：0=DISCONNECTED / 1=CONNECTING / 2=LISTENING。 */
    @Override
    public int stateOrdinal() {
        return mServerState.ordinal();
    }

    /** {@link DebugServerProvider.WsRpcEndpoint}：真实监听端口，未监听为 0。 */
    @Override
    public int port() {
        return getPort();
    }

    ProRpcDispatcher.Host newHost() {
        return mHost;
    }

    // ------------------------------------------------------------ 服务端

    /** 绑定动态端口并开始接受连接；绑定失败抛 {@link IOException}（状态回到 DISCONNECTED）。 */
    public void start() throws IOException {
        synchronized (mLifecycleLock) {
            ServerSocket serverSocket;
            boolean alreadyListening;
            synchronized (mLock) {
                if (mServerSocket != null && !mServerSocket.isClosed()) {
                    alreadyListening = true;
                    serverSocket = mServerSocket;
                } else {
                    alreadyListening = false;
                    mServerState = ServerState.CONNECTING;
                    mServerError = "";
                    serverSocket = new ServerSocket();
                    try {
                        serverSocket.setReuseAddress(true);
                        // Pro 用的就是 InetSocketAddress(0)：端口由内核分配，通配 0.0.0.0。
                        serverSocket.bind(new InetSocketAddress(0), 8);
                    } catch (IOException error) {
                        try {
                            serverSocket.close();
                        } catch (IOException ignored) {
                        }
                        mServerState = ServerState.DISCONNECTED;
                        mServerError = "ws-rpc 服务启动失败：" + messageOf(error)
                                + "。请重启应用或检查网络权限后重试";
                        throw new IOException(mServerError, error);
                    }
                    mServerSocket = serverSocket;
                    mServerState = ServerState.LISTENING;
                }
            }
            if (alreadyListening) {
                // 服务重启时若上次 mDNS 注册失败，这里补一次；已注册/注册中则幂等返回。
                if (!mMdns.isRegistered()
                        && !ProComputerLink.MDNS_REGISTERING.equals(mMdns.state())) {
                    registerMdnsSafely();
                }
                return;
            }
            Thread acceptThread = new Thread(() -> acceptLoop(serverSocket), "pro-rpc-server");
            acceptThread.setDaemon(true);
            acceptThread.start();
            registerMdnsSafely();
            Log.i(LOG_TAG, "ws-rpc listening on port " + getPort() + " (uuid " + mUuid + ")");
        }
    }

    public void stop() {
        synchronized (mLifecycleLock) {
            ServerSocket serverSocket;
            List<ProRpcSession> sessions;
            synchronized (mLock) {
                serverSocket = mServerSocket;
                mServerSocket = null;
                mServerState = ServerState.DISCONNECTED;
                sessions = new ArrayList<>(mSessions);
            }
            unregisterMdns();
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }
            }
            // 先作废出站 generation，再关会话：避免会话 close 后、cancel 前的窗口里
            // clientLoop 又安排一次重连。
            disconnectFromComputer();
            for (ProRpcSession session : sessions) {
                session.close();
            }
            mSessions.clear();
            Log.i(LOG_TAG, "ws-rpc stopped");
        }
    }

    private void acceptLoop(ServerSocket serverSocket) {
        while (true) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException error) {
                if (!serverSocket.isClosed()) {
                    Log.w(LOG_TAG, "accept failed", error);
                }
                return;
            }
            ProRpcWebSocket webSocket;
            try {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
                webSocket = ProRpcWebSocket.accept(socket);
                socket.setSoTimeout(0);
            } catch (IOException error) {
                Log.w(LOG_TAG, "websocket handshake failed: " + error.getMessage());
                closeQuietly(socket);
                continue;
            }
            ProRpcSession session = new ProRpcSession(this, webSocket);
            synchronized (mLock) {
                // stop() 可能刚好在握手期间发生：不要把会话加进已停止的服务。
                if (mServerSocket != serverSocket || mServerState != ServerState.LISTENING) {
                    session.close();
                    continue;
                }
                mSessions.add(session);
            }
            session.start();
            Log.i(LOG_TAG, "client connected: " + socket.getRemoteSocketAddress());
        }
    }

    void onSessionClosed(ProRpcSession session) {
        mSessions.remove(session);
        synchronized (mLock) {
            if (mClientSession == session) {
                mClientSession = null;
            }
        }
        Log.i(LOG_TAG, "session closed (" + mSessions.size() + " left)");
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------ mDNS

    private void registerMdnsSafely() {
        try {
            registerMdns();
        } catch (Throwable error) {
            mMdns.unavailable("mDNS 注册异常：" + messageOf(error)
                    + "。局域网自动发现不可用，可手动输入电脑 IP 连接");
            Log.w(LOG_TAG, "mDNS registration crashed", error);
        }
    }

    private void registerMdns() {
        Context context = GlobalAppContext.get();
        if (context == null) {
            mMdns.unavailable("无法获取应用上下文，mDNS 未注册；可手动输入电脑 IP 连接");
            return;
        }
        NsdManager manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (manager == null) {
            mMdns.unavailable("系统没有可用的 mDNS/NSD 服务，局域网自动发现不可用；可手动输入电脑 IP 连接");
            Log.w(LOG_TAG, "NsdManager unavailable, skipping mDNS registration");
            return;
        }
        final int generation = mMdns.beginRegistration(NSD_SERVICE_PREFIX + mUuid);
        if (generation < 0) {
            return;
        }
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(NSD_SERVICE_PREFIX + mUuid);
        info.setServiceType(NSD_SERVICE_TYPE);
        info.setPort(getPort());
        NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                String actualName = serviceInfo == null ? null : serviceInfo.getServiceName();
                if (mMdns.onRegistered(generation, actualName)) {
                    Log.i(LOG_TAG, "mDNS registered: " + mMdns.serviceName());
                } else {
                    Log.d(LOG_TAG, "ignoring stale mDNS registration callback");
                }
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                if (!mMdns.onRegistrationFailed(generation, errorCode)) {
                    Log.d(LOG_TAG, "ignoring stale mDNS registration failure");
                    return;
                }
                synchronized (mMdnsLock) {
                    // 注册失败后 stop() 不应再对失败的 listener 调 unregister。
                    if (mNsdListener == this) {
                        mNsdListener = null;
                    }
                }
                Log.w(LOG_TAG, mMdns.error());
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                if (mMdns.onUnregistered(generation)) {
                    Log.i(LOG_TAG, "mDNS unregistered");
                } else {
                    Log.d(LOG_TAG, "ignoring stale mDNS unregistration callback");
                }
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                if (mMdns.onUnregistrationFailed(generation, errorCode)) {
                    Log.w(LOG_TAG, mMdns.error());
                } else {
                    Log.d(LOG_TAG, "ignoring stale mDNS unregistration failure");
                }
            }
        };
        synchronized (mMdnsLock) {
            if (!mMdns.isCurrent(generation)) {
                return;
            }
            mNsdListener = listener;
            try {
                manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
            } catch (RuntimeException error) {
                if (mNsdListener == listener) {
                    mNsdListener = null;
                }
                mMdns.registrationFailed(generation,
                        "mDNS 注册失败：" + messageOf(error)
                                + "。局域网自动发现不可用，可手动输入电脑 IP 连接");
                Log.w(LOG_TAG, "cannot register the mDNS service", error);
            }
        }
    }

    private void unregisterMdns() {
        NsdManager.RegistrationListener listener;
        synchronized (mMdnsLock) {
            listener = mNsdListener;
            mNsdListener = null;
            // 先作废旧 generation：注销回调即使晚到也不能把新注册状态改掉。
            mMdns.stop();
        }
        if (listener == null) {
            return;
        }
        Context context = GlobalAppContext.get();
        NsdManager manager = context == null
                ? null : (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            manager.unregisterService(listener);
        } catch (RuntimeException error) {
            Log.w(LOG_TAG, "cannot unregister the mDNS service", error);
        }
    }

    // ------------------------------------------------------------ 客户端（手机回连电脑）

    /**
     * 回连电脑的 ws-rpc 服务（{@code ws://host:port}，默认端口 21029）。
     *
     * <p>只供已知地址的 service 入口使用；Dart 桥走 {@link #connectToComputer(ProComputerLink.Target)}，
     * 那里已经按 IP/端口选好协议。这里的 host 允许是 host 或 host:port。
     */
    public void connectToComputer(String host, int port) {
        try {
            ProComputerLink.Target target = ProComputerLink.parseTarget(
                    host, ProComputerLink.PROTOCOL_WS_RPC,
                    port > 0 ? Integer.valueOf(port) : null);
            connectToComputer(target);
        } catch (ProComputerLink.InvalidTargetException error) {
            String message = "电脑地址无效：" + error.getMessage();
            mClient.reportError(message);
            Log.w(LOG_TAG, message);
        }
    }

    /**
     * 连接一个已校验的 ws-rpc 目标。
     *
     * <p>切目标会先停旧 socket/线程并递增 generation；旧线程之后的重连、授权、
     * 失败回调都会因 generation 不匹配被忽略。只有电脑端发来并成功处理的
     * {@code debug.authorize} 才把状态从 CONNECTING 推到 CONNECTED。
     */
    public void connectToComputer(ProComputerLink.Target target) {
        if (target == null) {
            throw new IllegalArgumentException("target == null");
        }
        if (target.isLegacy()) {
            throw new IllegalArgumentException("ProRpcServer 只接受 ws-rpc 目标，legacy 请走 DevPluginServer");
        }
        int generation;
        Thread thread;
        Thread previousThread;
        ProRpcSession previousSession;
        synchronized (mLock) {
            ProComputerLink.Snapshot snapshot = mClient.snapshot();
            if (mClientThread != null && snapshot.target != null
                    && snapshot.target.sameAs(target)) {
                return;
            }
            previousThread = mClientThread;
            previousSession = mClientSession;
            mClientSession = null;
            generation = mClient.begin(target);
            thread = new Thread(() -> clientLoop(generation, target), "pro-rpc-client");
            thread.setDaemon(true);
            mClientThread = thread;
        }
        if (previousSession != null) {
            previousSession.close();
        }
        if (previousThread != null && previousThread != Thread.currentThread()) {
            previousThread.interrupt();
        }
        synchronized (mLock) {
            if (mClient.isCurrent(generation) && mClientThread == thread) {
                thread.start();
            }
        }
    }

    /** 关闭回连并取消重试/旧线程；保留入站服务器与其它通道。 */
    public void disconnectFromComputer() {
        Thread thread;
        ProRpcSession session;
        synchronized (mLock) {
            mClient.cancel();
            thread = mClientThread;
            mClientThread = null;
            session = mClientSession;
            mClientSession = null;
        }
        if (session != null) {
            session.close();
        }
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
    }

    /** 回连会话通过 debug.authorize 后由 ProRpcSession 回调。 */
    void onSessionAuthorized(ProRpcSession session) {
        boolean currentOutbound;
        synchronized (mLock) {
            currentOutbound = session == mClientSession
                    && mClient.markConnected(mClient.currentGeneration());
        }
        if (currentOutbound) {
            Log.i(LOG_TAG, "computer session authorized; client state CONNECTED");
        } else {
            Log.d(LOG_TAG, "session authorized (inbound or stale)");
        }
    }

    private void clientLoop(int generation, ProComputerLink.Target target) {
        int retries = 0;
        String lastFailure = "";
        try {
            while (mClient.isCurrent(generation)) {
                ProRpcSession session = null;
                boolean authorized = false;
                try {
                    ProRpcWebSocket webSocket = ProRpcWebSocket.connect(
                            target.host, target.port, "/");
                    session = new ProRpcSession(this, webSocket);
                    synchronized (mLock) {
                        if (!mClient.isCurrent(generation)) {
                            session.close();
                            return;
                        }
                        mClientSession = session;
                        mSessions.add(session);
                    }
                    Log.i(LOG_TAG, "websocket connected to computer " + target.endpointUri()
                            + "; waiting for debug.authorize");
                    session.start();
                    session.awaitClosed();
                    authorized = session.isAuthorized();
                    if (!mClient.isCurrent(generation)) {
                        return;
                    }
                    if (authorized) {
                        // 已经成功握手后掉线：重新开始一轮 1 s × 3。
                        retries = 0;
                        lastFailure = "与电脑的 ws-rpc 连接已断开：请确认电脑端 VSCode 调试插件仍在运行";
                        Log.i(LOG_TAG, "connection to computer lost: " + target.endpointUri());
                    } else {
                        lastFailure = "电脑端已建立 WebSocket，但未完成 debug.authorize 授权："
                                + "请在电脑端 VSCode 确认授权后重试";
                        Log.w(LOG_TAG, "computer websocket closed before debug.authorize: "
                                + target.endpointUri());
                    }
                } catch (IOException error) {
                    lastFailure = describeConnectError(target, error);
                    Log.d(LOG_TAG, "connect to " + target.endpointUri() + " failed: "
                            + error.getMessage());
                } catch (RuntimeException error) {
                    lastFailure = "连接电脑 " + target.displayAddress() + " 失败："
                            + messageOf(error) + "。请检查电脑插件和网络后重试";
                    Log.w(LOG_TAG, "connect to computer failed", error);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                } finally {
                    if (session != null) {
                        session.close();
                        synchronized (mLock) {
                            if (mClientSession == session) {
                                mClientSession = null;
                            }
                        }
                        mSessions.remove(session);
                    }
                }
                if (!mClient.isCurrent(generation)) {
                    return;
                }
                if (retries >= CLIENT_RETRY_LIMIT) {
                    String finalMessage = lastFailure + "（已重试 " + CLIENT_RETRY_LIMIT
                            + " 次；请确认电脑插件已启动、手机与电脑在同一局域网，且防火墙放行 "
                            + target.port + " 端口）";
                    mClient.markFailed(generation, finalMessage);
                    try {
                        GlobalAppContext.toast("连接电脑失败：" + target.displayAddress());
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                retries++;
                mClient.markRetry(generation, lastFailure + "（1 秒后重试 "
                        + retries + "/" + CLIENT_RETRY_LIMIT + "）");
                Log.i(LOG_TAG, "retry connecting " + retries + "/" + CLIENT_RETRY_LIMIT
                        + ": " + target.endpointUri());
                try {
                    Thread.sleep(CLIENT_RETRY_DELAY_MILLIS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            synchronized (mLock) {
                if (mClient.isCurrent(generation)) {
                    mClientThread = null;
                    mClientSession = null;
                    mClient.end(generation);
                }
            }
        }
    }

    private static String describeConnectError(ProComputerLink.Target target, IOException error) {
        String address = target.displayAddress();
        if (error instanceof UnknownHostException) {
            return "找不到电脑 " + target.host + "：请检查 IP/主机名是否填写正确，并确认手机已连接同一局域网";
        }
        if (error instanceof SocketTimeoutException) {
            return "连接电脑 " + address + " 超时：请确认电脑已启动 VSCode 的 Auto.js Pro 调试插件、"
                    + "手机与电脑在同一局域网，且防火墙放行 " + target.port + " 端口";
        }
        if (error instanceof NoRouteToHostException) {
            return "无法到达电脑 " + address + "：请确认手机与电脑在同一 WiFi，且电脑防火墙放行 "
                    + target.port + " 端口";
        }
        if (error instanceof ConnectException) {
            String raw = messageOf(error).toLowerCase(Locale.US);
            if (raw.contains("refused") || raw.contains("拒绝")) {
                return "电脑 " + address + " 拒绝连接：请先在 VSCode 启动 9.x 调试插件，"
                        + "确认它监听该端口后重试";
            }
            return "无法连接电脑 " + address + "：请确认电脑插件已启动、手机与电脑在同一局域网";
        }
        if (error instanceof SocketException) {
            return "连接电脑 " + address + " 失败：" + messageOf(error)
                    + "。请检查手机网络后重试";
        }
        String raw = messageOf(error);
        String lower = raw.toLowerCase(Locale.US);
        if (lower.contains("handshake") || lower.contains("websocket")
                || lower.contains("http/1.1") || lower.contains("switching protocols")) {
            return "电脑 " + address + " 接受了 TCP 连接，但 WebSocket/ws-rpc 握手失败："
                    + "请确认电脑端是 9.x 调试插件（不是普通 HTTP 服务）后再试";
        }
        return "连接电脑 " + address + " 失败：" + raw
                + "。请确认电脑插件已启动、手机与电脑在同一局域网，且防火墙放行 "
                + target.port + " 端口";
    }

    // ------------------------------------------------------------ 设备能力（debug.* 的方法体）

    private final class AndroidHost implements ProRpcDispatcher.Host {

        @Override
        public String deviceName() {
            String brand = Build.BRAND == null ? "" : Build.BRAND;
            String model = Build.MODEL == null ? "" : Build.MODEL;
            String name = (brand + " " + model).trim();
            return name.isEmpty() ? "Android" : name;
        }

        @Override
        public String deviceId() {
            return stableDeviceId();
        }

        @Override
        public String uuid() {
            return mUuid;
        }

        @Override
        public String appVersion() {
            return BuildConfig.VERSION_NAME;
        }

        @Override
        public long appVersionCode() {
            return BuildConfig.VERSION_CODE;
        }

        @Override
        public boolean authorize(String token) {
            return ProRpcAuthorizer.authorize(GlobalAppContext.get(), token,
                    ProRpcAuthorizer.TIMEOUT_MILLIS);
        }

        @Override
        public JsonObject inspectLayout(JsonObject params) throws Exception {
            JsonObject result = new JsonObject();
            // 与手机端「布局层次分析」同一份无障碍数据；导出格式与自研扩展的 inspect_layout 相同。
            result.addProperty("data", captureLayoutXml());
            addScreenshot(result);
            return result;
        }

        @Override
        public JsonObject listFiles(JsonObject params) throws Exception {
            File baseDir = new File(Pref.getScriptDirPath());
            String dir = ProRpcDispatcher.stringOf(params, "dir");
            if (dir == null) {
                dir = ProRpcDispatcher.stringOf(params, "path");
            }
            if (dir == null) {
                dir = "";
            }
            // 电脑端可能传相对路径（脚本目录内）或绝对路径（脚本目录本身/其子目录）。
            // 绝对路径先校验不越界，再换算回相对路径交给 DevPluginFileService 的目录遍历。
            File resolved = resolveInsideScriptDir(dir);
            JsonObject listing = DevPluginFileService.listFiles(baseDir,
                    relativeToBase(baseDir, resolved), false);
            JsonObject result = new JsonObject();
            result.addProperty("dir", resolved.getPath());
            JsonArray files = new JsonArray();
            JsonArray directories = listing.getAsJsonArray("dirs");
            if (directories != null) {
                for (JsonElement element : directories) {
                    files.add(fileEntry(element.getAsString(), true, 0, 0));
                }
            }
            JsonArray entries = listing.getAsJsonArray("files");
            if (entries != null) {
                for (JsonElement element : entries) {
                    JsonObject item = element.getAsJsonObject();
                    files.add(fileEntry(item.get("path").getAsString(), false,
                            item.get("size").getAsLong(), item.get("mtime").getAsLong()));
                }
            }
            result.add("files", files);
            JsonElement truncated = listing.get("truncated");
            result.addProperty("truncated", truncated != null && truncated.getAsBoolean());
            return result;
        }

        @Override
        public JsonObject runFile(JsonObject params) throws Exception {
            String path = ProRpcDispatcher.stringOf(params, "file");
            if (path == null) {
                path = ProRpcDispatcher.stringOf(params, "path");
            }
            if (path == null || path.trim().isEmpty()) {
                throw new ProRpcDispatcher.RpcException(ProRpcDispatcher.CODE_PARAMS,
                        "file should be a string");
            }
            File file = resolveInsideScriptDir(path);
            if (!file.isFile()) {
                throw new ProRpcDispatcher.RpcException(ProRpcDispatcher.CODE_PARAMS,
                        "no such file: " + path);
            }
            ScriptExecution execution = Scripts.INSTANCE.run(new ScriptFile(file));
            if (execution == null) {
                throw new IllegalStateException("cannot run " + file.getPath());
            }
            return executionInfo(execution, "file");
        }

        @Override
        public JsonObject runProject(JsonObject params) throws Exception {
            String dir = ProRpcDispatcher.stringOf(params, "dir");
            if (dir == null) {
                dir = ProRpcDispatcher.stringOf(params, "projectDir");
            }
            if (dir == null) {
                dir = ProRpcDispatcher.stringOf(params, "path");
            }
            if (dir == null || dir.trim().isEmpty()) {
                throw new ProRpcDispatcher.RpcException(ProRpcDispatcher.CODE_PARAMS,
                        "dir should be a string");
            }
            File projectDir = resolveInsideScriptDir(dir);
            if (!projectDir.isDirectory()) {
                throw new ProRpcDispatcher.RpcException(ProRpcDispatcher.CODE_PARAMS,
                        "no such project: " + dir);
            }
            ScriptExecution execution;
            try {
                execution = new ProjectLauncher(projectDir.getPath())
                        .launch(AutoJs.getInstance().getScriptEngineService());
            } catch (Exception error) {
                throw new ProRpcDispatcher.RpcException(ProRpcDispatcher.CODE_PARAMS,
                        "不是有效的工程目录：" + messageOf(error));
            }
            if (execution == null) {
                throw new IllegalStateException("cannot launch project " + projectDir.getPath());
            }
            return executionInfo(execution, "project");
        }

        @Override
        public void stop(JsonObject params) {
            String id = ProRpcDispatcher.stringOf(params, "executionId");
            if (id == null) {
                id = ProRpcDispatcher.stringOf(params, "id");
            }
            if (id == null) {
                return;
            }
            ScriptExecution execution = mExecutions.remove(id);
            if (execution == null && !id.startsWith("exec-")) {
                // 兼容把 executionId 当数字回传的客户端（我们发出去的是 "exec-<n>"）。
                execution = mExecutions.remove("exec-" + id);
            }
            stopExecution(execution);
        }

        @Override
        public void stopAll() {
            for (ScriptExecution execution : mExecutions.values()) {
                stopExecution(execution);
            }
            mExecutions.clear();
            AutoJs autoJs = AutoJs.getInstance();
            ScriptEngineService service = autoJs == null ? null : autoJs.getScriptEngineService();
            if (service != null) {
                service.stopAll();
            }
        }
    }

    private JsonObject executionInfo(ScriptExecution execution, String mode) {
        String id = "exec-" + execution.getId();
        mExecutions.put(id, execution);
        JsonObject info = new JsonObject();
        info.addProperty("executionId", id);
        info.addProperty("executionMode", mode);
        return info;
    }

    private static void stopExecution(ScriptExecution execution) {
        if (execution == null) {
            return;
        }
        ScriptEngine engine = execution.getEngine();
        if (engine != null && !engine.isDestroyed()) {
            engine.forceStop();
        }
    }

    /**
     * 与 legacy 通道共用同一个设备号（{@code dev_plugin_server/device_id}）。
     *
     * <p>直接读同一个 prefs 而不是调 {@link DevPluginServer}，是为了让两条通道
     * 互不依赖：ws-rpc 起来而 legacy 服务没开时也能给出稳定 deviceId。
     */
    private static String stableDeviceId() {
        Context context = GlobalAppContext.get();
        if (context == null) {
            return "";
        }
        SharedPreferences preferences = context.getSharedPreferences(
                "dev_plugin_server", Context.MODE_PRIVATE);
        String id = preferences.getString("device_id", null);
        if (id == null || id.isEmpty()) {
            id = UUID.randomUUID().toString();
            preferences.edit().putString("device_id", id).apply();
        }
        return id;
    }

    /** 把脚本目录内的绝对路径换算成相对路径（脚本目录本身返回空串）。 */
    private static String relativeToBase(File baseDir, File target) throws IOException {
        String base = baseDir.getCanonicalPath();
        String path = target.getCanonicalPath();
        if (path.equals(base)) {
            return "";
        }
        if (!path.startsWith(base + File.separator)) {
            throw new IOException("路径越界：" + target);
        }
        return path.substring(base.length() + 1).replace(File.separatorChar, '/');
    }

    private static File resolveInsideScriptDir(String path) throws IOException {
        File baseDir = new File(Pref.getScriptDirPath());
        File candidate = new File(path);
        if (!candidate.isAbsolute()) {
            return DevPluginFileService.resolve(baseDir, path);
        }
        String base = baseDir.getCanonicalPath();
        String canonical = candidate.getCanonicalPath();
        if (!canonical.equals(base) && !canonical.startsWith(base + File.separator)) {
            throw new IOException("路径越界：" + path);
        }
        return candidate;
    }

    private static JsonObject fileEntry(String path, boolean directory, long size, long lastModified) {
        JsonObject entry = new JsonObject();
        entry.addProperty("name", nameOf(path));
        entry.addProperty("path", path);
        entry.addProperty("isDirectory", directory);
        entry.addProperty("isFile", !directory);
        entry.addProperty("size", size);
        entry.addProperty("lastModified", lastModified);
        return entry;
    }

    private static String nameOf(String path) {
        if (path == null) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    // ------------------------------------------------------------ 布局 / 截图（debug.inspectLayout）

    private static String captureLayoutXml() throws Exception {
        AutoJs autoJs = AutoJs.getInstance();
        LayoutInspector inspector = autoJs == null ? null : autoJs.getLayoutInspector();
        if (inspector == null) {
            throw new IllegalStateException("布局分析需要开启 AI.js Pro 的无障碍服务");
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final NodeInfo[] holder = new NodeInfo[1];
        LayoutInspector.CaptureAvailableListener listener = capture -> {
            holder[0] = capture;
            latch.countDown();
        };
        inspector.addCaptureAvailableListener(listener);
        try {
            if (!inspector.captureCurrentWindow()) {
                throw new IllegalStateException("无法捕获当前窗口：请确认无障碍服务已开启且有活动窗口");
            }
            if (!latch.await(LAYOUT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("布局捕获超时（无障碍服务可能未开启）");
            }
            if (holder[0] == null) {
                throw new IllegalStateException("无法读取当前界面节点：部分页面不向系统提供节点");
            }
            return DevPluginLayoutService.dump(holder[0]);
        } finally {
            try {
                inspector.removeCaptureAvailableListener(listener);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void addScreenshot(JsonObject result) {
        try {
            DevPluginScreenshotService.Result shot = DevPluginScreenshotService.capture();
            result.addProperty("width", shot.width);
            result.addProperty("height", shot.height);
            result.addProperty("screenshot",
                    Base64.encodeToString(toJpeg(shot.png), Base64.NO_WRAP));
        } catch (Throwable error) {
            // 无障碍截图需要 Android 11+ 且无障碍已开启；没有就只回布局数据。
            Log.d(LOG_TAG, "inspectLayout without screenshot: " + messageOf(error));
            result.addProperty("width", 0);
            result.addProperty("height", 0);
            result.addProperty("screenshot", "");
        }
    }

    /** Pro 的 LayoutInfo.screenshot 是 JPEG→Base64，这里把无障碍 PNG 转成 JPEG。 */
    private static byte[] toJpeg(byte[] png) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (bitmap == null) {
            throw new IllegalStateException("截图解码失败");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, png.length / 4));
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)) {
                throw new IllegalStateException("JPEG 编码失败");
            }
            return out.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private static String messageOf(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isEmpty() ? String.valueOf(error) : message;
    }
}
