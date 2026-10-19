package com.jdkshen.aijspro.pluginclient;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 「电脑连接」的纯 Java 地址/状态 helper（不依赖 Android，可用 JVM 单测）。
 *
 * <p>两组规则固定在这里，避免 Dart、bridge、ws-rpc、legacy 各自解释：
 * <ul>
 *   <li>默认地址走 Pro 的 ws-rpc：端口 {@link #WS_RPC_CLIENT_PORT}（21029）；</li>
 *   <li>显式 {@code IP:8327} 保留 legacy 自研通道；其他自定义端口仍走 ws-rpc；</li>
 *   <li>显式 {@code protocol=legacy/ws-rpc} 优先于端口推断，但 legacy 只支持 8327。</li>
 * </ul>
 *
 * <p>另外提供两个小型状态机：
 * {@link Outbound} 统一管理「开始/重试/已授权/失败/取消」与 generation，
 * {@link MdnsTracker} 统一管理 mDNS 注册状态与 generation，晚到的旧 NSD 回调会被丢弃。
 */
public final class ProComputerLink {

    public static final String PROTOCOL_WS_RPC = "ws-rpc";
    public static final String PROTOCOL_LEGACY = "legacy";

    /** Pro 9.x「手机回连电脑」的默认端口（0x5225）。 */
    public static final int WS_RPC_CLIENT_PORT = 21029;
    /** 本项目 legacy 调试服务器的固定监听端口。 */
    public static final int LEGACY_SERVER_PORT = 9317;
    /** legacy 自研协议里电脑端接受回连的固定端口。 */
    public static final int LEGACY_CLIENT_PORT = 8327;

    public static final String MDNS_STOPPED = "STOPPED";
    public static final String MDNS_REGISTERING = "REGISTERING";
    public static final String MDNS_REGISTERED = "REGISTERED";
    public static final String MDNS_FAILED = "FAILED";
    public static final String MDNS_STOP_FAILED = "STOP_FAILED";
    public static final String MDNS_UNAVAILABLE = "UNAVAILABLE";

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;

    private static final Pattern HOSTNAME = Pattern.compile(
            "^(?=.{1,253}$)([A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$");

    private ProComputerLink() {
    }

    /** 地址/端口/协议不合法；message 是可直接展示给用户的中文原因。 */
    public static final class InvalidTargetException extends Exception {

        private static final long serialVersionUID = 1L;

        public InvalidTargetException(String message) {
            super(message);
        }
    }

    /** 解析后的连接目标（不可变）。 */
    public static final class Target {

        /** 不含 IPv6 方括号的地址。 */
        public final String host;
        public final int port;
        public final String protocol;
        public final boolean explicitProtocol;
        public final boolean explicitPort;

        private Target(String host, int port, String protocol,
                       boolean explicitProtocol, boolean explicitPort) {
            this.host = host;
            this.port = port;
            this.protocol = protocol;
            this.explicitProtocol = explicitProtocol;
            this.explicitPort = explicitPort;
        }

        public boolean isLegacy() {
            return PROTOCOL_LEGACY.equals(protocol);
        }

        public boolean isIpv6() {
            return host.indexOf(':') >= 0;
        }

        /** 拼 URI/日志时使用的 host（IPv6 带方括号）。 */
        public String hostForUri() {
            return isIpv6() ? "[" + host + "]" : host;
        }

        /** 实际拨号目标，例如 {@code ws://192.168.1.2:21029}。 */
        public String endpointUri() {
            return (isLegacy() ? "tcp://" : "ws://") + hostForUri() + ":" + port;
        }

        /**
         * 展示/回填到输入框的地址。
         *
         * <p>默认 ws-rpc 21029 的 IPv4/主机名只显示 host（Dart 会自己补 21029）；
         * legacy 8327、自定义端口、IPv6 都显式带端口，避免 Dart 再拼一次默认端口。
         */
        public String displayAddress() {
            boolean defaultWsRpc = !isLegacy() && port == WS_RPC_CLIENT_PORT;
            if (defaultWsRpc && !isIpv6()) {
                return host;
            }
            return hostForUri() + ":" + port;
        }

        public boolean sameAs(Target other) {
            return other != null
                    && host.equalsIgnoreCase(other.host)
                    && port == other.port
                    && protocol.equals(other.protocol);
        }

        @Override
        public String toString() {
            return protocol + " " + endpointUri();
        }
    }

    // ------------------------------------------------------------ 地址解析

    public static Target parseTarget(String rawHost) throws InvalidTargetException {
        return parseTarget(rawHost, null, null);
    }

    public static Target parseTarget(String rawHost, String rawProtocol)
            throws InvalidTargetException {
        return parseTarget(rawHost, rawProtocol, null);
    }

    /**
     * @param rawHost      用户输入：host / host:port / [ipv6] / [ipv6]:port
     * @param rawProtocol  可选，{@code ws-rpc} 或 {@code legacy}
     * @param explicitPort 可选的独立 port 参数（Dart 目前不传）
     */
    public static Target parseTarget(String rawHost, String rawProtocol, Integer explicitPort)
            throws InvalidTargetException {
        String text = rawHost == null ? "" : rawHost.trim();
        if (text.isEmpty()) {
            throw new InvalidTargetException("请输入电脑 IP 地址（例如 192.168.1.100）");
        }
        if (hasWhitespace(text)) {
            throw new InvalidTargetException("电脑地址不能包含空格，请只填写 IP 或 IP:端口");
        }
        if (text.contains("://")) {
            throw new InvalidTargetException(
                    "只填写电脑 IP（或 IP:端口），不要带 ws://、http:// 等前缀");
        }

        String protocol = normalizeProtocol(rawProtocol);
        String host;
        Integer hostPort = null;

        if (text.charAt(0) == '[') {
            int close = text.indexOf(']');
            if (close < 0) {
                throw new InvalidTargetException("IPv6 地址缺少右方括号，请写成 [地址]:端口");
            }
            host = text.substring(1, close);
            String rest = text.substring(close + 1);
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":")) {
                    throw new InvalidTargetException(
                            "IPv6 地址带端口请写成 [地址]:端口，例如 [fe80::1]:21029");
                }
                hostPort = parsePort(rest.substring(1));
            }
            validateIpv6(host);
        } else {
            int first = text.indexOf(':');
            int last = text.lastIndexOf(':');
            if (first < 0) {
                host = text;
            } else if (first == last) {
                host = text.substring(0, first);
                hostPort = parsePort(text.substring(first + 1));
                if (host.isEmpty()) {
                    throw new InvalidTargetException("IP 地址不能为空，请填写电脑 IP 或 IP:端口");
                }
            } else {
                // 多个冒号且没有方括号：按裸 IPv6 处理（不带端口）。
                host = text;
                validateIpv6(host);
            }
        }

        if (explicitPort != null) {
            validatePort(explicitPort);
            if (hostPort != null) {
                throw new InvalidTargetException(
                        "端口已在地址中填写，请不要再单独传 port 参数");
            }
        }
        validateHost(host);

        int port;
        if (hostPort != null) {
            port = hostPort;
        } else if (explicitPort != null) {
            port = explicitPort;
        } else if (PROTOCOL_LEGACY.equals(protocol)) {
            port = LEGACY_CLIENT_PORT;
        } else {
            port = WS_RPC_CLIENT_PORT;
        }

        String effectiveProtocol = protocol;
        if (effectiveProtocol == null) {
            effectiveProtocol = port == LEGACY_CLIENT_PORT
                    ? PROTOCOL_LEGACY : PROTOCOL_WS_RPC;
        } else if (PROTOCOL_LEGACY.equals(effectiveProtocol)
                && port != LEGACY_CLIENT_PORT) {
            throw new InvalidTargetException(
                    "legacy 调试通道只支持电脑端 " + LEGACY_CLIENT_PORT
                            + " 端口；连接其他端口请去掉 protocol=legacy，改用 ws-rpc");
        }

        return new Target(host, port, effectiveProtocol,
                protocol != null, hostPort != null || explicitPort != null);
    }

    private static String normalizeProtocol(String rawProtocol) throws InvalidTargetException {
        if (rawProtocol == null) {
            return null;
        }
        String value = rawProtocol.trim().toLowerCase(Locale.US).replace('_', '-');
        if (value.isEmpty()) {
            return null;
        }
        switch (value) {
            case "ws-rpc":
            case "wsrpc":
            case "ws":
            case "rpc":
            case "pro":
                return PROTOCOL_WS_RPC;
            case "legacy":
            case "old":
            case "tcp":
                return PROTOCOL_LEGACY;
            default:
                throw new InvalidTargetException(
                        "未知的连接协议：" + rawProtocol + "（支持 ws-rpc 或 legacy）");
        }
    }

    private static Integer parsePort(String rawPort) throws InvalidTargetException {
        String value = rawPort == null ? "" : rawPort.trim();
        if (value.isEmpty()) {
            throw new InvalidTargetException("端口不能为空，请填写 1 到 65535 之间的数字");
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                throw new InvalidTargetException(
                        "端口格式不正确：请填写 1 到 65535 之间的数字");
            }
        }
        int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new InvalidTargetException("端口格式不正确：请填写 1 到 65535 之间的数字");
        }
        validatePort(port);
        return port;
    }

    private static void validatePort(int port) throws InvalidTargetException {
        if (port < 1 || port > 65535) {
            throw new InvalidTargetException("端口应在 1 到 65535 之间");
        }
    }

    private static void validateHost(String host) throws InvalidTargetException {
        if (host == null || host.isEmpty()) {
            throw new InvalidTargetException("电脑地址不能为空，请填写 IP 或主机名");
        }
        if (host.indexOf(':') >= 0) {
            // 已在括号/裸 IPv6 分支校验。
            return;
        }
        if (host.indexOf('/') >= 0 || host.indexOf('\\') >= 0 || host.indexOf('@') >= 0) {
            throw new InvalidTargetException(
                    "电脑地址格式不正确：请填写 IP 或主机名，可带端口（如 192.168.1.100:21029）");
        }
        if (isNumericAddress(host)) {
            validateIpv4(host);
            return;
        }
        for (int i = 0; i < host.length(); i++) {
            if (host.charAt(i) > 127) {
                throw new InvalidTargetException(
                        "电脑地址仅支持字母、数字、点和连字符；中文/特殊字符请改用 IP");
            }
        }
        if (!HOSTNAME.matcher(host).matches()) {
            throw new InvalidTargetException(
                    "电脑地址格式不正确：请填写 IP 或主机名，可带端口（如 192.168.1.100:21029）");
        }
    }

    private static boolean isNumericAddress(String host) {
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (!Character.isDigit(c) && c != '.') {
                return false;
            }
        }
        return true;
    }

    private static void validateIpv4(String host) throws InvalidTargetException {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            throw new InvalidTargetException(
                    "IP 地址格式不正确：应写成 a.b.c.d（例如 192.168.1.100）");
        }
        for (String part : parts) {
            if (part.isEmpty() || !isAllDigits(part)) {
                throw new InvalidTargetException(
                        "IP 地址格式不正确：应写成 a.b.c.d（例如 192.168.1.100）");
            }
            int value;
            try {
                value = Integer.parseInt(part);
            } catch (NumberFormatException error) {
                throw new InvalidTargetException("IP 地址格式不正确：每段应在 0 到 255 之间");
            }
            if (value < 0 || value > 255) {
                throw new InvalidTargetException("IP 地址格式不正确：每段应在 0 到 255 之间");
            }
        }
    }

    private static void validateIpv6(String host) throws InvalidTargetException {
        if (host == null || host.isEmpty() || host.indexOf(':') < 0) {
            throw new InvalidTargetException(
                    "IPv6 地址格式不正确；带端口请写成 [地址]:端口");
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            if (!(address instanceof Inet6Address)) {
                throw new InvalidTargetException(
                        "IPv6 地址格式不正确；带端口请写成 [地址]:端口");
            }
        } catch (UnknownHostException error) {
            throw new InvalidTargetException(
                    "IPv6 地址格式不正确；带端口请写成 [地址]:端口");
        }
    }

    private static boolean isAllDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 状态选择

    /**
     * 状态页的首选协议：真实活动出站连接 → ws-rpc 监听 → legacy 监听 → legacy 回退。
     *
     * <p>出站优先于监听，是因为用户刚点了「连接电脑」时，界面必须先显示这次尝试
     * 的协议；监听只是被动可用能力。Pro 出站同时在跑时优先 Pro。
     */
    public static String preferredProtocol(boolean rpcOutboundActive,
                                           boolean legacyOutboundActive,
                                           boolean rpcListening,
                                           boolean legacyListening) {
        if (rpcOutboundActive) {
            return PROTOCOL_WS_RPC;
        }
        if (legacyOutboundActive) {
            return PROTOCOL_LEGACY;
        }
        if (rpcListening) {
            return PROTOCOL_WS_RPC;
        }
        if (legacyListening) {
            return PROTOCOL_LEGACY;
        }
        return PROTOCOL_LEGACY;
    }

    /** 沿用 Dart 侧 stateCode：3=已连接，2=正在连接，1=仅服务器监听，0=空闲。 */
    public static int stateCode(boolean connected, boolean connecting, boolean listening) {
        if (connected) {
            return 3;
        }
        if (connecting) {
            return 2;
        }
        if (listening) {
            return 1;
        }
        return 0;
    }

    public static String clientStateName(boolean connected, boolean connecting) {
        if (connected) {
            return "CONNECTED";
        }
        if (connecting) {
            return "CONNECTING";
        }
        return "DISCONNECTED";
    }

    public static String serverStateName(int ordinal) {
        switch (ordinal) {
            case 1:
                return "CONNECTING";
            case 2:
                return "LISTENING";
            default:
                return "DISCONNECTED";
        }
    }

    // ------------------------------------------------------------ 出站状态机

    /**
     * 手机 → 电脑出站连接的状态机。generation 让旧线程的回调全部失效：
     * 切目标/断开后，旧连接再授权或再失败都不会污染新状态。
     */
    public static final class Outbound {

        private final Object mLock = new Object();
        private int mGeneration;
        private Target mTarget;
        private int mState = STATE_DISCONNECTED;
        private String mError = "";
        private boolean mActive;

        /** 开始一轮新连接（会立即作废旧 generation）。 */
        public int begin(Target target) {
            if (target == null) {
                throw new IllegalArgumentException("target == null");
            }
            synchronized (mLock) {
                mGeneration++;
                mTarget = target;
                mState = STATE_CONNECTING;
                mError = "";
                mActive = true;
                return mGeneration;
            }
        }

        public int currentGeneration() {
            synchronized (mLock) {
                return mGeneration;
            }
        }

        public boolean isCurrent(int generation) {
            synchronized (mLock) {
                return generation == mGeneration;
            }
        }

        public boolean isActive() {
            synchronized (mLock) {
                return mActive;
            }
        }

        public int state() {
            synchronized (mLock) {
                return mState;
            }
        }

        public String error() {
            synchronized (mLock) {
                return mError;
            }
        }

        public Target target() {
            synchronized (mLock) {
                return mTarget;
            }
        }

        /** WebSocket 已连上、但还没等到 debug.authorize：仍然是 CONNECTING。 */
        public boolean markConnecting(int generation) {
            synchronized (mLock) {
                if (generation != mGeneration) {
                    return false;
                }
                mState = STATE_CONNECTING;
                mActive = true;
                return true;
            }
        }

        /** 只有 debug.authorize 成功后才允许进入 CONNECTED。 */
        public boolean markConnected(int generation) {
            synchronized (mLock) {
                if (generation != mGeneration) {
                    return false;
                }
                mState = STATE_CONNECTED;
                mError = "";
                mActive = true;
                return true;
            }
        }

        /** 一次尝试失败/掉线，1 秒后重试：保持 CONNECTING 并带上可操作原因。 */
        public boolean markRetry(int generation, String error) {
            synchronized (mLock) {
                if (generation != mGeneration) {
                    return false;
                }
                mState = STATE_CONNECTING;
                mError = error == null ? "" : error;
                mActive = true;
                return true;
            }
        }

        /** 重试耗尽：DISCONNECTED，但保留 target 供「上次地址」展示。 */
        public boolean markFailed(int generation, String error) {
            synchronized (mLock) {
                if (generation != mGeneration) {
                    return false;
                }
                mState = STATE_DISCONNECTED;
                mError = error == null ? "" : error;
                mActive = false;
                return true;
            }
        }

        /** 线程退出（正常/失败/被替换）；不覆盖已有错误。 */
        public boolean end(int generation) {
            synchronized (mLock) {
                if (generation != mGeneration) {
                    return false;
                }
                mState = STATE_DISCONNECTED;
                mActive = false;
                return true;
            }
        }

        /** 在没有 active 连接时记录参数错误（例如 service 传入非法 host）。 */
        public boolean reportError(String error) {
            synchronized (mLock) {
                if (mActive) {
                    return false;
                }
                mState = STATE_DISCONNECTED;
                mError = error == null ? "" : error;
                return true;
            }
        }

        /** 用户主动断开/取消：停旧连接，清空 target 与错误。 */
        public void cancel() {
            synchronized (mLock) {
                mGeneration++;
                mTarget = null;
                mState = STATE_DISCONNECTED;
                mError = "";
                mActive = false;
            }
        }

        public Snapshot snapshot() {
            synchronized (mLock) {
                return new Snapshot(mGeneration, mTarget, mState, mError, mActive);
            }
        }
    }

    /** {@link Outbound} 的原子快照。 */
    public static final class Snapshot {

        public final int generation;
        public final Target target;
        public final int state;
        public final String error;
        public final boolean active;

        Snapshot(int generation, Target target, int state, String error, boolean active) {
            this.generation = generation;
            this.target = target;
            this.state = state;
            this.error = error;
            this.active = active;
        }
    }

    // ------------------------------------------------------------ mDNS 状态机

    /**
     * NsdManager 回调状态机。每次注册/停止都递增 generation；晚到的旧回调
     * （注册成功/失败、注销成功/失败）返回 false，不能改写新状态。
     */
    public static final class MdnsTracker {

        private final Object mLock = new Object();
        private int mGeneration;
        private boolean mRegistered;
        private String mState = MDNS_STOPPED;
        private String mError = "";
        private String mServiceName = "";

        /**
         * @return 本次注册的 generation；已有注册/正在注册时返回 -1（无需重复注册）
         */
        public int beginRegistration(String requestedServiceName) {
            synchronized (mLock) {
                if (mRegistered || MDNS_REGISTERING.equals(mState)) {
                    return -1;
                }
                mGeneration++;
                mRegistered = false;
                mState = MDNS_REGISTERING;
                mError = "";
                mServiceName = requestedServiceName == null ? "" : requestedServiceName;
                return mGeneration;
            }
        }

        public boolean isCurrent(int generation) {
            synchronized (mLock) {
                return generation == mGeneration;
            }
        }

        public boolean isRegistered() {
            synchronized (mLock) {
                return mRegistered;
            }
        }

        public String state() {
            synchronized (mLock) {
                return mState;
            }
        }

        public String error() {
            synchronized (mLock) {
                return mError;
            }
        }

        public String serviceName() {
            synchronized (mLock) {
                return mServiceName;
            }
        }

        /** @return false 表示这是旧 generation 的迟到回调，调用方应忽略 */
        public boolean onRegistered(int generation, String actualServiceName) {
            synchronized (mLock) {
                if (generation != mGeneration) {
                    return false;
                }
                mRegistered = true;
                mState = MDNS_REGISTERED;
                mError = "";
                if (actualServiceName != null && !actualServiceName.isEmpty()) {
                    mServiceName = actualServiceName;
                }
                return true;
            }
        }

        public boolean onRegistrationFailed(int generation, int errorCode) {
            return registrationFailed(generation, "mDNS 注册失败（NSD 错误码 " + errorCode
                    + "）：局域网自动发现不可用，可手动输入电脑 IP 连接");
        }

        public boolean registrationFailed(int generation, String reason) {
            synchronized (mLock) {
                if (generation != mGeneration) {
                    return false;
                }
                mRegistered = false;
                mState = MDNS_FAILED;
                mError = reason == null ? "" : reason;
                return true;
            }
        }

        public boolean onUnregistered(int generation) {
            synchronized (mLock) {
                if (generation != mGeneration) {
                    return false;
                }
                mRegistered = false;
                mState = MDNS_STOPPED;
                mError = "";
                return true;
            }
        }

        public boolean onUnregistrationFailed(int generation, int errorCode) {
            synchronized (mLock) {
                if (generation != mGeneration) {
                    return false;
                }
                mRegistered = false;
                mState = MDNS_STOP_FAILED;
                mError = "mDNS 注销失败（NSD 错误码 " + errorCode
                        + "）：服务已停止，残留注册会在系统侧过期";
                return true;
            }
        }

        /** 主动停止：立即置 false，并作废旧回调。 */
        public void stop() {
            synchronized (mLock) {
                mGeneration++;
                mRegistered = false;
                mState = MDNS_STOPPED;
                mError = "";
                mServiceName = "";
            }
        }

        /** 系统没有 NSD/Context：报告不可用，同时作废旧回调。 */
        public void unavailable(String reason) {
            synchronized (mLock) {
                mGeneration++;
                mRegistered = false;
                mState = MDNS_UNAVAILABLE;
                mError = reason == null ? "" : reason;
                mServiceName = "";
            }
        }
    }
}
