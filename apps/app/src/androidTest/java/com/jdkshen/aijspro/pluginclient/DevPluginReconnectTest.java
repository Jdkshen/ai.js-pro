package com.jdkshen.aijspro.pluginclient;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.DataInputStream;
import java.io.EOFException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import static org.junit.Assert.*;

/** Real TCP lifecycle tests; restore the user's server state and remembered computer afterwards. */
public class DevPluginReconnectTest {
    private final DevPluginServer server = DevPluginServer.getInstance();
    private SharedPreferences prefs;
    private String previousHost;
    private boolean wasRunning;

    @Before public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = context.getSharedPreferences("dev_plugin_server", Context.MODE_PRIVATE);
        previousHost = prefs.getString("computer_host", null);
        wasRunning = server.isRunning();
        server.stop();
        prefs.edit().remove("computer_host").commit();
    }

    @After public void restore() throws Exception {
        server.stop();
        if (previousHost == null) prefs.edit().remove("computer_host").commit();
        else prefs.edit().putString("computer_host", previousHost).commit();
        if (wasRunning) server.start();
    }

    private static void hello(Socket socket) throws Exception {
        socket.setSoTimeout(5000);
        String greeting = DevPluginFrames.parseJson(DevPluginFrames.read(new DataInputStream(socket.getInputStream())));
        assertTrue(greeting.contains("\"type\":\"hello\""));
        socket.getOutputStream().write(DevPluginFrames.encodeJson("{\"id\":1,\"type\":\"hello\",\"data\":{\"client_version\":4}}"));
        socket.getOutputStream().flush();
    }

    private void awaitClients(int count) throws Exception {
        long until = SystemClock.elapsedRealtime() + 5000;
        while (server.getClientCount() != count && SystemClock.elapsedRealtime() < until) Thread.sleep(20);
        assertEquals(count, server.getClientCount());
    }

    private static void noConnection(ServerSocket socket) throws Exception {
        socket.setSoTimeout(4000);
        try (Socket unexpected = socket.accept()) { fail("Unexpected duplicate/reconnection"); }
        catch (SocketTimeoutException expected) { }
    }

    @Test public void remembersPeerReconnectsAfterDropAndRestartAndRespectsStop() throws Exception {
        String host = DevPluginServer.localIpv4Address();
        assertNotNull("This device test requires a LAN interface", host);
        try (ServerSocket computer = new ServerSocket()) {
            computer.setReuseAddress(true);
            computer.bind(new InetSocketAddress(host, 8327));
            server.start();
            try (Socket manual = new Socket(host, DevPluginServer.PORT)) {
                hello(manual);
                awaitClients(1);
                long until = SystemClock.elapsedRealtime() + 2000;
                while (server.getReconnectHost().isEmpty() && SystemClock.elapsedRealtime() < until) Thread.sleep(20);
                assertEquals(host, server.getReconnectHost());
                noConnection(computer);
            }
            computer.setSoTimeout(12000);
            try (Socket reconnected = computer.accept()) {
                hello(reconnected);
                awaitClients(1);
                noConnection(computer);
                server.stop();
                assertEquals(-1, reconnected.getInputStream().read());
                noConnection(computer);
            }
            server.start();
            computer.setSoTimeout(12000);
            try (Socket restored = computer.accept()) {
                hello(restored);
                awaitClients(1);
            }
        }
    }

    @Test public void usbLoopbackIsNotRememberedAsComputer() throws Exception {
        server.start();
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), DevPluginServer.PORT)) {
            hello(socket);
            awaitClients(1);
            assertEquals("", server.getReconnectHost());
        }
    }

    @Test public void missingHelloTimesOutWithoutRememberingPeer() throws Exception {
        server.start();
        try (Socket socket = new Socket(DevPluginServer.localIpv4Address(), DevPluginServer.PORT)) {
            socket.setSoTimeout(14000);
            DevPluginFrames.read(new DataInputStream(socket.getInputStream()));
            assertEquals(-1, socket.getInputStream().read());
            assertEquals(0, server.getClientCount());
            assertEquals("", server.getReconnectHost());
        }
    }
}
