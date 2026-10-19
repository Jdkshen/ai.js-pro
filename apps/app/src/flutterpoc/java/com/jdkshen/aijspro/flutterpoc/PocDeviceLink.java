package com.jdkshen.aijspro.flutterpoc;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ProviderInfo;
import android.util.Log;

import com.jdkshen.aijspro.pluginclient.DebugServerProvider;
import com.jdkshen.aijspro.pluginclient.DevPluginPrefs;
import com.jdkshen.aijspro.pluginclient.DevPluginServer;
import com.jdkshen.aijspro.pluginclient.DevPluginServerService;
import com.jdkshen.aijspro.pluginclient.ProComputerLink;
import com.jdkshen.aijspro.pluginclient.ProRpcServer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「电脑连接」（VS Code 调试插件）bridge。
 *
 * <p>mode 2 是用户开关：同时维护 legacy 9317 与 Pro ws-rpc 动态监听。
 * mode 1 是主动出站：默认 {@code host → ws://host:21029}；显式 {@code host:8327}
 * 保留 legacy 自研通道；其它显式端口按 ws-rpc 自定义端口处理；显式
 * {@code protocol=legacy/ws-rpc} 也可用。切换协议/断开时只停旧的出站连接，
 * 不会因为 mode 1 去关用户已经打开的 mode 2 入站服务。
 *
 * <p>状态字段保持 Dart 侧 computerPluginState 契约，并补充可诊断的协议、
 * 动态端口、授权连接数、mDNS/provider 真实状态；未完成 debug.authorize 的
 * ws-rpc socket 不计入 clients，也不会显示为已连接。
 */
final class PocDeviceLink {

    private static final String TAG = "GlassFlutterPoc";

    private static final String PREFS_NAME = "computer_plugin_link";
    private static final String KEY_LAST_HOST = "last_host";
    private static final String KEY_LAST_PORT = "last_port";
    private static final String KEY_LAST_PROTOCOL = "last_protocol";

    private static final String PROTOCOL_WS_RPC = ProComputerLink.PROTOCOL_WS_RPC;
    private static final String PROTOCOL_LEGACY = ProComputerLink.PROTOCOL_LEGACY;

    private PocDeviceLink() {
    }

    static Object state(Context context) {
        DevPluginServer legacy = DevPluginServer.getInstance();
        ProRpcServer pro = ProRpcServer.getInstance();

        DevPluginServer.ServerState legacyServerState = legacy.getServerState();
        ProRpcServer.ServerState rpcServerState = pro.getServerState();
        boolean legacyListening = legacyServerState == DevPluginServer.ServerState.LISTENING;
        boolean rpcListening = rpcServerState == ProRpcServer.ServerState.LISTENING;
        boolean listening = rpcListening || legacyListening;
        int rpcPort = rpcListening ? pro.getPort() : 0;
        int serverPort = rpcListening
                ? rpcPort : (legacyListening ? ProComputerLink.LEGACY_SERVER_PORT : 0);

        ProRpcServer.ClientState rpcClient = pro.getClientState();
        DevPluginServer.ClientState legacyClient = legacy.getClientState();
        String legacyHost = legacy.getReconnectHost();
        boolean rpcOutboundActive = rpcClient != ProRpcServer.ClientState.DISCONNECTED;
        boolean legacyOutboundActive = !legacyHost.isEmpty()
                && legacyClient != DevPluginServer.ClientState.DISCONNECTED;
        String protocol = ProComputerLink.preferredProtocol(
                rpcOutboundActive, legacyOutboundActive, rpcListening, legacyListening);

        int rpcClients = pro.getClientCount();
        int legacyClients = legacy.getClientCount();
        int clients = rpcClients + legacyClients;
        boolean rpcConnected = rpcClient == ProRpcServer.ClientState.CONNECTED;
        boolean legacyConnected = legacyClient == DevPluginServer.ClientState.CONNECTED;
        boolean connected = clients > 0 || rpcConnected || legacyConnected;
        boolean connecting = !connected
                && (rpcClient == ProRpcServer.ClientState.CONNECTING
                || (legacyOutboundActive
                && legacyClient == DevPluginServer.ClientState.CONNECTING));

        SharedPreferences prefs = prefs(context);
        String proLastHost = pro.getClientDisplayAddress();
        ProComputerLink.Target rpcTarget = pro.getClientTarget();
        String legacyLastHost = legacyDisplayAddress(legacyHost);
        String lastHost = !proLastHost.isEmpty()
                ? proLastHost
                : (!legacyLastHost.isEmpty()
                ? legacyLastHost : prefs.getString(KEY_LAST_HOST, ""));
        String lastProtocol;
        int lastPort;
        if (!proLastHost.isEmpty() && rpcTarget != null) {
            lastProtocol = PROTOCOL_WS_RPC;
            lastPort = rpcTarget.port;
        } else if (!legacyHost.isEmpty()) {
            lastProtocol = PROTOCOL_LEGACY;
            lastPort = ProComputerLink.LEGACY_CLIENT_PORT;
        } else {
            lastProtocol = prefs.getString(KEY_LAST_PROTOCOL, "");
            lastPort = prefs.getInt(KEY_LAST_PORT, 0);
        }

        String rpcClientError = pro.getClientError();
        String legacyClientError = legacy.getClientError();
        String clientError = PROTOCOL_WS_RPC.equals(protocol)
                ? rpcClientError : legacyClientError;
        if (clientError == null || clientError.isEmpty()) {
            clientError = PROTOCOL_WS_RPC.equals(protocol)
                    ? legacyClientError : rpcClientError;
        }

        String rpcServerError = pro.getServerError();
        String legacyServerError = legacy.getServerError();
        String serverError = rpcServerError.isEmpty() ? legacyServerError : rpcServerError;
        String mdnsError = pro.getMdnsError();
        // mDNS 失败单独放在 mdnsError，不能塞进 lastError：Dart 会把它当成
        // “连接电脑失败”的原始原因，误导为插件/网络问题。
        String lastError = clientError;
        if (lastError == null || lastError.isEmpty()) {
            lastError = serverError;
        }

        boolean providerWsRpc = providerRegistered(context, DebugServerProvider.AUTHORITY_WS_RPC);
        boolean providerLegacy = providerRegistered(context, DebugServerProvider.AUTHORITY);
        boolean provider = providerWsRpc || providerLegacy;
        boolean mdnsRegistered = pro.isMdnsRegistered();
        JSONArray discovery = new JSONArray();
        if (provider) {
            discovery.put("provider");
        }
        if (mdnsRegistered) {
            discovery.put("mDNS");
        }

        JSONArray addresses = addresses();
        String endpoint = "";
        if (listening && addresses.length() > 0) {
            String address = addresses.optString(0);
            endpoint = rpcListening
                    ? "ws://" + address + ":" + rpcPort
                    : address + ":" + ProComputerLink.LEGACY_SERVER_PORT;
        }

        int serverOrdinal;
        if (rpcListening || legacyListening) {
            serverOrdinal = 2;
        } else if (rpcServerState == ProRpcServer.ServerState.CONNECTING
                || legacyServerState == DevPluginServer.ServerState.CONNECTING) {
            serverOrdinal = 1;
        } else {
            serverOrdinal = 0;
        }
        String serverStateName = ProComputerLink.serverStateName(serverOrdinal);
        String clientStateName = ProComputerLink.clientStateName(connected, connecting);
        int clientVersion = legacy.getClientVersion();

        try {
            JSONObject result = new JSONObject()
                    .put("stateCode", ProComputerLink.stateCode(connected, connecting, listening))
                    .put("state", serverStateName)
                    .put("serverState", serverOrdinal)
                    .put("serverStateName", serverStateName)
                    .put("listening", listening)
                    .put("debugServerListening", listening)
                    .put("port", serverPort)
                    .put("rpcPort", rpcPort)
                    .put("clientPort", ProComputerLink.WS_RPC_CLIENT_PORT)
                    .put("legacyPort", ProComputerLink.LEGACY_SERVER_PORT)
                    .put("legacyClientPort", ProComputerLink.LEGACY_CLIENT_PORT)
                    .put("addresses", addresses)
                    .put("endpoint", endpoint)
                    .put("lastHost", lastHost)
                    .put("lastPort", lastPort)
                    .put("lastProtocol", lastProtocol)
                    .put("clientStateKnown", true)
                    .put("clientState", clientStateName)
                    .put("clients", clients)
                    .put("authorizedClients", clients)
                    .put("clientAuthorized", clients > 0)
                    .put("protocol", protocol)
                    .put("protocolType", protocol)
                    .put("type", protocol)
                    .put("discovery", discovery)
                    .put("discoveryKnown", true)
                    .put("provider", provider)
                    .put("providerLegacy", providerLegacy)
                    .put("providerWsRpc", providerWsRpc)
                    .put("mdns", mdnsRegistered)
                    .put("nsd", mdnsRegistered)
                    .put("mdnsRegistered", mdnsRegistered)
                    .put("mdnsState", pro.getMdnsState())
                    .put("mdnsServiceName", pro.getMdnsServiceName())
                    .put("legacyListening", legacyListening)
                    .put("legacyServerState", legacyServerState.name())
                    .put("legacyClients", legacyClients)
                    .put("legacyClientState", legacyClient.name())
                    .put("rpcListening", rpcListening)
                    .put("rpcServerState", rpcServerState.name())
                    .put("rpcClients", rpcClients)
                    .put("rpcClientState", rpcClient.name())
                    .put("rpcOpenSessions", pro.getOpenSessionCount())
                    .put("wsRpc", rpcListening)
                    .put("rpc", rpcListening);
            // client_version 只来自 legacy hello，别把旧值挂到 ws-rpc 会话上。
            if (clientVersion > 0
                    && (legacyClients > 0 || PROTOCOL_LEGACY.equals(protocol))) {
                result.put("clientVersion", clientVersion);
            }
            String rpcUri = pro.getClientUri();
            if (!rpcUri.isEmpty()) {
                result.put("rpcClientUri", rpcUri);
            }
            if (rpcTarget != null) {
                result.put("rpcClientPort", rpcTarget.port);
            }
            if (!legacyHost.isEmpty()) {
                result.put("legacyHost", legacyHost);
            }
            if (clientError != null && !clientError.isEmpty()) {
                result.put("clientError", clientError);
            }
            if (rpcClientError != null && !rpcClientError.isEmpty()) {
                result.put("rpcClientError", rpcClientError);
            }
            if (legacyClientError != null && !legacyClientError.isEmpty()) {
                result.put("legacyClientError", legacyClientError);
            }
            if (!serverError.isEmpty()) {
                result.put("serverError", serverError);
            }
            if (!rpcServerError.isEmpty()) {
                result.put("rpcServerError", rpcServerError);
            }
            if (!legacyServerError.isEmpty()) {
                result.put("legacyServerError", legacyServerError);
            }
            if (!mdnsError.isEmpty()) {
                result.put("mdnsError", mdnsError);
            }
            if (lastError != null && !lastError.isEmpty()) {
                result.put("lastError", lastError);
            }
            return result;
        } catch (Exception error) {
            Log.w(TAG, "电脑连接状态组装失败", error);
            return new JSONObject();
        }
    }

    static Object set(Context context, JSONObject args) throws Exception {
        int mode = args.optInt("mode", 2);
        boolean enabled = args.optBoolean("enabled", false);
        DevPluginServer legacy = DevPluginServer.getInstance();
        ProRpcServer pro = ProRpcServer.getInstance();

        if (mode == 2) {
            DevPluginPrefs.setServerEnabled(context, enabled);
            if (enabled) {
                legacy.clearServerError();
                DevPluginServerService.start(context);
                waitRunning(true, 8000);
                if (!legacy.isRunning()) {
                    throw new IOException(serverStartFailure(legacy));
                }
                // service 里已尝试启动 ws-rpc；这里再同步确保一次，失败要给用户明确原因。
                if (!pro.isRunning()) {
                    try {
                        pro.start();
                    } catch (IOException error) {
                        throw new IOException("ws-rpc 监听未能启动：" + messageOf(error), error);
                    }
                }
            } else {
                DevPluginServerService.stop(context);
                legacy.stop();
                // 只有确实有 ws-rpc 监听时才 stop；纯 mode 1 出站不应被“服务器开关”误杀。
                if (pro.getServerState() != ProRpcServer.ServerState.DISCONNECTED) {
                    pro.stop();
                }
                waitRunning(false, 8000);
                waitServersStopped(4000);
            }
            return state(context);
        }

        if (mode == 1) {
            if (enabled) {
                ProComputerLink.Target target = parseTarget(args);
                rememberTarget(context, target);
                if (target.isLegacy()) {
                    // 切到 legacy：先停 Pro 出站和旧 legacy 回连，再确保监听存在。
                    // 先清 host 也避免 start() 之后旧地址的 tick 抢在新目标前拨号。
                    pro.disconnectFromComputer();
                    legacy.disconnectClient();
                    if (!legacy.isRunning()) {
                        startLegacyServer();
                    }
                    legacy.connectClient(target.host);
                } else {
                    // 切到 ws-rpc：停 legacy 出站，必要时停掉仅因 mode 1 拉起的监听。
                    legacy.disconnectClient();
                    stopMode1LegacyServerIfUnused(context);
                    pro.connectToComputer(target);
                }
            } else {
                pro.disconnectFromComputer();
                legacy.disconnectClient();
                if (!DevPluginPrefs.isServerEnabled(context)) {
                    // 只停 mode 1 自己拉起的监听；已有授权入站连接/用户 mode 2 服务不能停。
                    if (legacy.getClientCount() == 0) {
                        legacy.stop();
                    }
                    if (pro.getClientCount() == 0
                            && pro.getServerState() != ProRpcServer.ServerState.DISCONNECTED) {
                        pro.stop();
                    }
                }
            }
            return state(context);
        }

        throw new IOException("未知的连接模式：" + mode);
    }

    // ------------------------------------------------------------ 参数/目标

    private static ProComputerLink.Target parseTarget(JSONObject args) throws IOException {
        String host = args.has("host") && !args.isNull("host")
                ? args.optString("host", "") : "";
        String protocol = args.has("protocol") && !args.isNull("protocol")
                ? args.optString("protocol", "") : null;
        Integer port = null;
        if (args.has("port") && !args.isNull("port")) {
            Object raw = args.opt("port");
            if (raw instanceof Number) {
                port = ((Number) raw).intValue();
            } else {
                String text = String.valueOf(raw).trim();
                try {
                    port = Integer.parseInt(text);
                } catch (NumberFormatException error) {
                    throw new IOException("端口格式不正确：请填写 1 到 65535 之间的数字");
                }
            }
        }
        try {
            return ProComputerLink.parseTarget(host, protocol, port);
        } catch (ProComputerLink.InvalidTargetException error) {
            throw new IOException(error.getMessage(), error);
        }
    }

    private static void rememberTarget(Context context, ProComputerLink.Target target) {
        prefs(context).edit()
                .putString(KEY_LAST_HOST, target.displayAddress())
                .putInt(KEY_LAST_PORT, target.port)
                .putString(KEY_LAST_PROTOCOL, target.protocol)
                .apply();
    }

    // ------------------------------------------------------------ 启停/等待

    private static void startLegacyServer() throws IOException {
        DevPluginServer legacy = DevPluginServer.getInstance();
        legacy.clearServerError();
        // mode 1 不改用户 mode 2 开关，也不拉起 service（那会顺带开 Pro 监听/通知）。
        // legacy 的回连线程依赖它自己的 9317 监听，所以这里直接启动；断开时再停。
        try {
            legacy.start();
        } catch (IOException error) {
            String reason = serverStartFailure(legacy);
            legacy.disconnectClient();
            throw new IOException(reason, error);
        }
        waitRunning(true, 8000);
        if (!legacy.isRunning()) {
            String reason = serverStartFailure(legacy);
            legacy.disconnectClient();
            throw new IOException(reason);
        }
    }

    /**
     * 切回 ws-rpc 前停掉 mode 1 为 legacy 拉起的本地监听/reconnect；
     * 但用户显式打开的 mode 2 服务绝不能因切换协议被关掉。
     */
    private static void stopMode1LegacyServerIfUnused(Context context) {
        if (DevPluginPrefs.isServerEnabled(context)) {
            return;
        }
        DevPluginServer legacy = DevPluginServer.getInstance();
        ProRpcServer pro = ProRpcServer.getInstance();
        if (!legacy.isRunning()
                && pro.getServerState() == ProRpcServer.ServerState.DISCONNECTED) {
            return;
        }
        // 已有电脑通过入站通道连进来（且已授权）时，切换/断开出站不能把它踢掉。
        if (legacy.getClientCount() > 0 || pro.getClientCount() > 0) {
            return;
        }
        legacy.stop();
        pro.stop();
        waitRunning(false, 8000);
        waitServersStopped(4000);
    }

    private static String serverStartFailure(DevPluginServer legacy) {
        String reason = legacy.getServerError();
        return reason == null || reason.isEmpty()
                ? "调试服务器未能启动：端口 " + DevPluginServer.PORT
                + " 可能被占用，请重试或重启应用"
                : reason;
    }

    private static void waitRunning(boolean expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (DevPluginServer.getInstance().isRunning() == expected) {
                return;
            }
            if (expected && !DevPluginServer.getInstance().getServerError().isEmpty()) {
                return;
            }
            sleepBriefly();
        }
    }

    private static void waitServersStopped(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!DevPluginServer.getInstance().isRunning()
                    && ProRpcServer.getInstance().getServerState()
                    == ProRpcServer.ServerState.DISCONNECTED) {
                return;
            }
            sleepBriefly();
        }
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(60);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------ 其它

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * legacy 回连目标是 8327，但 DevPluginServer.getReconnectHost() 只存 host；
     * 补上端口，避免 Dart 按 clientPort=21029 再拼一次而显示错地址。
     */
    private static String legacyDisplayAddress(String host) {
        if (host == null || host.isEmpty()) {
            return "";
        }
        String formatted = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return formatted + ":" + ProComputerLink.LEGACY_CLIENT_PORT;
    }

    /** 发布包 -PdevPluginUsbDiscovery=false 会移除 provider，必须按运行时注册结果上报。 */
    private static boolean providerRegistered(Context context, String authority) {
        try {
            ProviderInfo info = context.getPackageManager()
                    .resolveContentProvider(authority, 0);
            return info != null && context.getPackageName().equals(info.packageName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static JSONArray addresses() {
        List<String> values = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        String host = address.getHostAddress();
                        if (host != null && !values.contains(host)) {
                            values.add(host);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new JSONArray(values);
    }

    private static String messageOf(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isEmpty() ? String.valueOf(error) : message;
    }
}
