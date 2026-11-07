package com.jdkshen.aijspro.inrt.debuglog;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class InrtLogFramesTest {

    @Test
    public void jsonFrameRoundTrips() throws Exception {
        String json = "{\"id\":1,\"type\":\"log\",\"data\":{\"log\":\"中文 line\"}}";
        byte[] frame = InrtLogFrames.encodeJson(json);
        InrtLogFrames.Frame decoded = InrtLogFrames.read(
                new DataInputStream(new ByteArrayInputStream(frame)));
        assertEquals(InrtLogFrames.TYPE_JSON, decoded.type);
        assertEquals(json, InrtLogFrames.parseJson(decoded));
    }

    @Test
    public void gzipJsonFrameRoundTrips() throws Exception {
        String json = "{\"id\":2,\"type\":\"log\",\"data\":{\"log\":\"gzip\"}}";
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        }
        byte[] frame = InrtLogFrames.encode(
                InrtLogFrames.TYPE_GZIP_JSON, compressed.toByteArray());
        InrtLogFrames.Frame decoded = InrtLogFrames.read(
                new DataInputStream(new ByteArrayInputStream(frame)));
        assertEquals(InrtLogFrames.TYPE_GZIP_JSON, decoded.type);
        assertEquals(json, InrtLogFrames.parseJson(decoded));
    }

    @Test
    public void rejectsOversizeFrameLengthBeforeAllocatingPayload() throws Exception {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(header);
        out.writeInt(InrtLogFrames.MAX_FRAME_LENGTH + 1);
        out.writeInt(InrtLogFrames.TYPE_JSON);
        out.flush();
        boolean failed = false;
        try {
            InrtLogFrames.read(new DataInputStream(new ByteArrayInputStream(header.toByteArray())));
        } catch (java.io.IOException expected) {
            failed = true;
        }
        assertTrue(failed);
    }

    @Test
    public void bridgeSendsDeviceHelloFirstThenLogAndIgnoresCommands() throws Exception {
        InrtLogBridge bridge = new InrtLogBridge(
                0,
                new InrtLogBridge.Hello(
                        "AI.js Pro", "test-device", "test-version", 1234, "test-device-id"),
                message -> { });
        bridge.start();
        int port = waitForPort(bridge);
        assertTrue(port > 0);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            OutputStream output = socket.getOutputStream();

            // Device must send hello first, without waiting for the client.
            InrtLogFrames.Frame helloFrame = InrtLogFrames.read(input);
            String hello = InrtLogFrames.parseJson(helloFrame);
            assertTrue(hello.contains("\"type\":\"hello\""));
            assertTrue(hello.contains("\"product\":\"AI.js Pro\""));
            assertTrue(hello.contains("\"device_name\":\"test-device\""));
            assertTrue(hello.contains("\"server_version\":3"));
            assertTrue(hello.contains("\"device_id\":\"test-device-id\""));

            output.write(InrtLogFrames.encodeJson(
                    "{\"id\":2,\"type\":\"hello\",\"data\":{\"client_version\":4}}"));
            output.flush();
            waitForAttached(bridge);

            bridge.publishLog("hello-log-line");
            InrtLogFrames.Frame logFrame = InrtLogFrames.read(input);
            String log = InrtLogFrames.parseJson(logFrame);
            assertTrue(log.contains("\"type\":\"log\""));
            assertTrue(log.contains("\"log\":\"hello-log-line\""));

            // A command frame must be ignored: no response, then a later log still arrives.
            output.write(InrtLogFrames.encodeJson(
                    "{\"id\":3,\"type\":\"command\",\"data\":{\"command\":\"read_file\"}}"));
            output.flush();
            Thread.sleep(100);
            bridge.publishLog("after-command");
            InrtLogFrames.Frame afterCommand = InrtLogFrames.read(input);
            String after = InrtLogFrames.parseJson(afterCommand);
            assertTrue(after.contains("\"type\":\"log\""));
            assertTrue(after.contains("\"log\":\"after-command\""));
        } finally {
            bridge.close();
        }
    }

    private static int waitForPort(InrtLogBridge bridge) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            int port = bridge.getBoundPort();
            if (port > 0) {
                return port;
            }
            Thread.sleep(10);
        }
        return -1;
    }

    private static void waitForAttached(InrtLogBridge bridge) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (bridge.getAttachedClientCount() > 0) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue("client did not complete hello", bridge.getAttachedClientCount() > 0);
    }
}
