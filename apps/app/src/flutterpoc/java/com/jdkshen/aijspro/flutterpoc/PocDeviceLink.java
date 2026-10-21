package com.jdkshen.aijspro.flutterpoc;

import android.content.Context;
import com.jdkshen.aijspro.pluginclient.AiJsComputerAddress;
import com.jdkshen.aijspro.pluginclient.DevPluginPrefs;
import com.jdkshen.aijspro.pluginclient.DevPluginServer;
import com.jdkshen.aijspro.pluginclient.DevPluginServerService;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** AI.js Pro VS Code link: device listener 9317, computer listener 8327. */
final class PocDeviceLink {
    private PocDeviceLink() { }

    static Object state(Context context) throws Exception {
        DevPluginServer server = DevPluginServer.getInstance();
        boolean listening = server.isRunning();
        int clients = server.getClientCount();
        String host = server.getReconnectHost();
        String last = host.isEmpty() ? context.getSharedPreferences("computer_plugin_link", 0)
                .getString("aijs_last_address", "") : server.getComputerAddress();
        String clientState = clients > 0 ? "CONNECTED" : server.getClientState().name();
        JSONArray ips = addresses();
        return new JSONObject()
                .put("stateCode", clients > 0 ? 3 : listening ? 2 : 0)
                .put("state", server.getServerState().name())
                .put("serverState", server.getServerStateOrdinal())
                .put("listening", listening)
                .put("port", DevPluginServer.PORT)
                .put("clientPort", AiJsComputerAddress.DEFAULT_PORT)
                .put("addresses", ips)
                .put("endpoint", listening && ips.length() > 0 ? ips.optString(0) + ":" + DevPluginServer.PORT : "")
                .put("lastHost", last)
                .put("clientStateKnown", true)
                .put("clientState", clientState)
                .put("clients", clients)
                .put("outboundEnabled", !host.isEmpty())
                .put("protocol", "aijs")
                .put("discoveryKnown", true)
                .put("discovery", new JSONArray())
                .put("pluginVersion", server.getClientVersionName())
                .put("clientVersion", server.getClientVersion())
                .put("clientError", server.getClientError())
                .put("serverError", server.getServerError())
                .put("lastError", server.getLastError());
    }

    static Object set(Context context, JSONObject args) throws Exception {
        DevPluginServer server = DevPluginServer.getInstance();
        int mode = args.optInt("mode", 2);
        boolean enabled = args.optBoolean("enabled", false);
        if (mode == 2) {
            DevPluginPrefs.setServerEnabled(context, enabled);
            if (enabled) {
                start(context);
            } else {
                server.disconnectClient();
                DevPluginServerService.stop(context);
                server.stop();
            }
        } else if (mode == 1) {
            if (enabled) {
                Integer port = null;
                if (args.has("port") && !args.isNull("port")) {
                    try { port = Integer.valueOf(args.getString("port")); }
                    catch (Exception error) { throw new IOException("端口应在 1 到 65535 之间"); }
                }
                AiJsComputerAddress target = AiJsComputerAddress.parse(args.optString("host", ""), port);
                // Validate first; an invalid form must not disconnect a working session.
                server.disconnectClient();
                start(context);
                server.connectClient(target.host, target.port);
                context.getSharedPreferences("computer_plugin_link", 0).edit()
                        .putString("aijs_last_address", target.displayAddress()).apply();
            } else {
                server.disconnectClient();
                if (!DevPluginPrefs.isServerEnabled(context) && server.getClientCount() == 0) {
                    DevPluginServerService.stop(context);
                    server.stop();
                }
            }
        } else {
            throw new IOException("未知的连接模式：" + mode);
        }
        return state(context);
    }

    private static void start(Context context) throws Exception {
        DevPluginServer server = DevPluginServer.getInstance();
        server.clearServerError();
        DevPluginServerService.start(context);
        long deadline = android.os.SystemClock.elapsedRealtime() + 8000;
        while (!server.isRunning() && server.getServerError().isEmpty()
                && android.os.SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(60);
        }
        if (!server.isRunning()) {
            throw new IOException(server.getServerError().isEmpty()
                    ? "AI.js Pro 电脑连接服务未能启动，请重试" : server.getServerError());
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

}
