package com.jdkshen.aijspro.inrt.debuglog;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Log-only VSCode bridge for the packaged/inrt runtime.
 *
 * <p>Safety contract: the bridge only speaks the legacy hello+log subset. It never
 * implements or answers read_file / screenshot / inspect_layout / command frames.
 * A client that sends anything other than hello is ignored after handshake.
 *
 * <p>Wire contract (matches apps/app DevPluginServer):
 * device sends hello first, then reads client hello and requires data.client_version >= 1.
 * Every log frame is {@code {"id":N,"type":"log","data":{"log":"one line"}}}.
 */
public final class InrtLogBridge implements Closeable {

    public static final int DEFAULT_PORT = 9317;
    /** Legacy extension only requires client_version >= 1; device hello explicitly says 3. */
    public static final int SERVER_VERSION = 3;
    private static final int BIND_RETRY_MILLIS = 30_000;
    private static final int HELLO_TIMEOUT_MILLIS = 10_000;

    /** One line callback; network/session management stays inside the bridge. */
    public interface Listener {
        void onLog(String line);
    }

    /** Android API 21-safe diagnostic sink (java.util.function.Consumer is API 24+). */
    public interface Diagnostic {
        void log(String message);
    }

    /** Adapter for the current process console (ConsoleImpl.addLogListener in LogActivity). */
    public interface LogSource {
        void addListener(Listener listener);

        void removeListener(Listener listener);
    }

    public static final class Hello {
        public final String product;
        public final String deviceName;
        public final String appVersion;
        public final int appVersionCode;
        public final String deviceId;

        public Hello(String product, String deviceName, String appVersion,
                     int appVersionCode, String deviceId) {
            this.product = product == null ? "AI.js Pro" : product;
            this.deviceName = deviceName == null ? "inrt device" : deviceName;
            this.appVersion = appVersion == null ? "" : appVersion;
            this.appVersionCode = appVersionCode;
            this.deviceId = deviceId == null ? "" : deviceId;
        }
    }

    private final int requestedPort;
    private final Hello hello;
    private final Diagnostic diagnostic;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "inrt-log-bridge-writer");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Session> sessions = Collections.synchronizedSet(new LinkedHashSet<>());
    private final AtomicBoolean forwarding = new AtomicBoolean(false);
    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private volatile int boundPort = -1;
    private volatile Thread acceptThread;
    private volatile LogSource logSource;
    private final Listener sourceListener = this::publishLog;

    public InrtLogBridge() {
        this(DEFAULT_PORT, new Hello("AI.js Pro", "inrt device", "", 0, ""));
    }

    public InrtLogBridge(int port) {
        this(port, new Hello("AI.js Pro", "inrt device", "", 0, ""));
    }

    public InrtLogBridge(int port, Hello hello) {
        this(port, hello, message -> System.err.println("[InrtLogBridge] " + message));
    }

    public InrtLogBridge(int port, Hello hello, Diagnostic diagnostic) {
        this.requestedPort = port;
        this.hello = hello == null ? new Hello("AI.js Pro", "inrt device", "", 0, "") : hello;
        this.diagnostic = diagnostic == null ? message -> { } : diagnostic;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        Thread thread = new Thread(this::acceptLoop, "inrt-log-bridge-accept");
        thread.setDaemon(true);
        acceptThread = thread;
        thread.start();
    }

    /** Attach the packaged process console source. Must be called after start/before logs. */
    public synchronized void attach(LogSource source) {
        if (logSource != null) {
            detachLocked();
        }
        if (source == null) {
            return;
        }
        logSource = source;
        source.addListener(sourceListener);
    }

    public synchronized void detach() {
        detachLocked();
    }

    private void detachLocked() {
        LogSource source = logSource;
        logSource = null;
        if (source != null) {
            try {
                source.removeListener(sourceListener);
            } catch (RuntimeException ignored) {
                // A dying console must not crash the bridge.
            }
        }
    }

    /**
     * Called by the console adapter. Public for the JVM socket test; production callers
     * normally go through attach(ConsoleImpl adapter).
     */
    public void publishLog(String content) {
        if (!running || content == null) {
            return;
        }
        if (!forwarding.compareAndSet(false, true)) {
            // Defensive re-entry guard: a bridge diagnostic must never feed itself.
            return;
        }
        try {
            String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
            String[] lines = normalized.split("\n", -1);
            int count = lines.length;
            while (count > 0 && lines[count - 1].isEmpty()) {
                count--;
            }
            if (count == 0) {
                lines = new String[]{""};
                count = 1;
            }
            for (int i = 0; i < count; i++) {
                for (Session session : snapshotSessions()) {
                    session.sendLog(lines[i]);
                }
            }
        } finally {
            forwarding.set(false);
        }
    }

    public int getBoundPort() {
        return boundPort;
    }

    public boolean isRunning() {
        return running;
    }

    /** Number of clients that completed hello; used by diagnostics and the JVM socket test. */
    public int getAttachedClientCount() {
        int count = 0;
        for (Session session : snapshotSessions()) {
            if (session.attached) {
                count++;
            }
        }
        return count;
    }

    private void acceptLoop() {
        while (running) {
            ServerSocket server = null;
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(requestedPort));
                serverSocket = server;
                boundPort = server.getLocalPort();
                diagnostic.log("VSCode 日志桥已监听 0.0.0.0:" + boundPort
                        + "（仅日志；命令帧一律忽略）");
                while (running) {
                    Socket socket;
                    try {
                        socket = server.accept();
                    } catch (IOException error) {
                        if (!running) {
                            break;
                        }
                        diagnostic.log("VSCode 日志桥 accept 失败：" + shortError(error) + "；继续监听");
                        continue;
                    }
                    try {
                        startSession(socket);
                    } catch (Throwable error) {
                        closeQuietly(socket);
                        diagnostic.log("VSCode 日志桥客户端初始化失败：" + shortError(error));
                    }
                }
            } catch (Exception error) {
                if (running) {
                    diagnostic.log("VSCode 日志桥端口 " + requestedPort + " 绑定失败："
                            + shortError(error) + "；30 秒后重试（常见原因：宿主 AI.js Pro 已占用 9317）");
                    sleepBeforeRetry();
                }
            } finally {
                if (serverSocket == server) {
                    serverSocket = null;
                }
                closeQuietly(server);
            }
        }
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(BIND_RETRY_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void startSession(Socket socket) throws IOException {
        socket.setTcpNoDelay(true);
        Session session = new Session(socket);
        sessions.add(session);
        try {
            session.sendHello();
            session.startReader();
        } catch (RuntimeException error) {
            closeSession(session);
            throw error;
        }
    }

    private java.util.List<Session> snapshotSessions() {
        synchronized (sessions) {
            return new java.util.ArrayList<>(sessions);
        }
    }

    private void closeSession(Session session) {
        sessions.remove(session);
        session.close();
    }

    @Override
    public synchronized void close() {
        if (!running && serverSocket == null) {
            return;
        }
        running = false;
        detachLocked();
        ServerSocket server = serverSocket;
        serverSocket = null;
        closeQuietly(server);
        for (Session session : snapshotSessions()) {
            session.close();
        }
        sessions.clear();
        writer.shutdownNow();
        Thread thread = acceptThread;
        if (thread != null) {
            thread.interrupt();
        }
        acceptThread = null;
    }

    private final class Session implements Closeable {
        private final Socket socket;
        private final DataInputStream input;
        private final OutputStream output;
        private final AtomicLong sessionId = new AtomicLong(1);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile boolean attached;

        Session(Socket socket) throws IOException {
            this.socket = socket;
            this.input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.output = socket.getOutputStream();
        }

        void sendHello() {
            long id = sessionId.getAndIncrement();
            String json = "{\"id\":" + id + ",\"type\":\"hello\",\"data\":{"
                    + "\"product\":" + jsonString(hello.product) + ","
                    + "\"device_name\":" + jsonString(hello.deviceName) + ","
                    + "\"app_version\":" + jsonString(hello.appVersion) + ","
                    + "\"app_version_code\":" + hello.appVersionCode + ","
                    + "\"server_version\":" + SERVER_VERSION + ","
                    + "\"device_id\":" + jsonString(hello.deviceId)
                    + "}}";
            // Single writer executor: this hello is queued before any log frame.
            try {
                writer.execute(() -> writeFrame(InrtLogFrames.encodeJson(json)));
            } catch (RejectedExecutionException ignored) {
                // Bridge is stopping.
            }
        }

        void startReader() {
            Thread thread = new Thread(this::readLoop, "inrt-log-bridge-client");
            thread.setDaemon(true);
            thread.start();
        }

        void sendLog(String line) {
            if (!attached || closed.get()) {
                return;
            }
            long id = sessionId.getAndIncrement();
            String json = "{\"id\":" + id + ",\"type\":\"log\",\"data\":{\"log\":"
                    + jsonString(line) + "}}";
            try {
                writer.execute(() -> writeFrame(InrtLogFrames.encodeJson(json)));
            } catch (RejectedExecutionException ignored) {
                // Bridge is stopping.
            }
        }

        private void readLoop() {
            try {
                socket.setSoTimeout(HELLO_TIMEOUT_MILLIS);
                InrtLogFrames.Frame first = InrtLogFrames.read(input);
                String json = InrtLogFrames.parseJson(first);
                if (!"hello".equals(stringValue(json, "type"))) {
                    close();
                    return;
                }
                int clientVersion = intValue(json, "client_version");
                if (clientVersion < 1) {
                    diagnostic.log("VSCode 日志桥客户端 hello client_version=" + clientVersion
                            + " < 1，关闭连接");
                    close();
                    return;
                }
                socket.setSoTimeout(0);
                attached = true;
                while (running && !closed.get()) {
                    // Deliberately ignore every command/control frame: this bridge is log-only.
                    InrtLogFrames.read(input);
                }
            } catch (SocketTimeoutException timeout) {
                close();
            } catch (EOFException eof) {
                close();
            } catch (Throwable error) {
                close();
            }
        }

        private void writeFrame(byte[] frame) {
            if (closed.get()) {
                return;
            }
            synchronized (this) {
                if (closed.get()) {
                    return;
                }
                try {
                    output.write(frame);
                    output.flush();
                } catch (IOException error) {
                    close();
                }
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            attached = false;
            sessions.remove(this);
            closeQuietly(socket);
        }
    }

    private static String stringValue(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static int intValue(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)")
                .matcher(json);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }

    private static String shortError(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
