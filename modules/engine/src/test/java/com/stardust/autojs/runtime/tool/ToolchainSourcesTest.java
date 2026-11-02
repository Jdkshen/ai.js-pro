package com.stardust.autojs.runtime.tool;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ToolchainSourcesTest {
    private URL[] sources() throws Exception { return new URL[]{new URL("https://first.example/component.json"),new URL("https://second.example/component.json")}; }

    @Test public void defaultIsPublicAndExplicitLanSourceIsPreserved() throws Exception {
        assertNull(ToolchainSources.installManifest(new String[]{"install","cpp"}));
        assertEquals("https", new URL(ToolchainSources.GITHUB).getProtocol());
        assertEquals("github.com", new URL(ToolchainSources.GITHUB).getHost());
        assertTrue(ToolchainSources.PROXY.endsWith(ToolchainSources.GITHUB));
        assertEquals("gh-proxy.org",new URL(ToolchainSources.PROXY).getHost());
        assertTrue(ToolchainSources.BACKUP_PROXY.endsWith(ToolchainSources.GITHUB));
        String custom="http://192.168.10.7:8080/toolchains/cpp-arm64-v1.json";
        assertEquals(custom,ToolchainSources.installManifest(new String[]{"install","cpp","--manifest",custom}).toString());
        try { ToolchainSources.installManifest(new String[]{"install","cpp","bad"});fail(); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void networkTimeoutFallsBackAndShowsWhichSourceFailed() throws Exception {
        AtomicInteger calls=new AtomicInteger();ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        String result=ToolchainSources.attempt(sources(), url -> {
            if(calls.incrementAndGet()==1)throw new SocketTimeoutException("timed out");
            return "installed";
        },new PrintStream(bytes,true,"UTF-8"));
        assertEquals("installed",result);assertEquals(2,calls.get());
        assertTrue(bytes.toString("UTF-8").contains("正在切换下载源"));
    }

    @Test public void invalidAbiOrConfigurationDoesNotStartAnotherDownload() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        try { ToolchainSources.attempt(sources(), url -> {calls.incrementAndGet();throw new IllegalArgumentException("abi mismatch");},null);fail(); }
        catch (IllegalArgumentException expected) {assertEquals(1,calls.get());}
    }

    @Test public void cancellationDoesNotRetryAndInterruptedCallerDoesNotConnect() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        try { ToolchainSources.attempt(sources(), url -> {calls.incrementAndGet();throw new InterruptedIOException("cancelled");},null);fail(); }
        catch (InterruptedIOException expected) {assertEquals(1,calls.get());}
        Thread.currentThread().interrupt();
        try { ToolchainSources.attempt(sources(), url -> {calls.incrementAndGet();return "bad";},null);fail(); }
        catch (InterruptedIOException expected) {assertEquals(1,calls.get());}
        finally {Thread.interrupted();}
    }

    @Test public void allFailuresRemainDiagnosable() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        try { ToolchainSources.attempt(sources(), url -> {calls.incrementAndGet();throw new IOException(url.getHost());},null);fail(); }
        catch (IOException expected) {
            assertEquals(2,calls.get());assertTrue(expected.getMessage().contains("--manifest"));
            assertEquals("second.example",expected.getCause().getMessage());
            assertEquals("first.example",expected.getCause().getSuppressed()[0].getMessage());
        }
    }
}
