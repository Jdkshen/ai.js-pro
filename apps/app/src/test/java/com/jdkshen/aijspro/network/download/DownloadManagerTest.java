package com.jdkshen.aijspro.network.download;

import com.jdkshen.aijspro.network.api.DownloadApi;
import com.jakewharton.retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.Observable;
import io.reactivex.observers.TestObserver;
import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import retrofit2.Retrofit;
import retrofit2.http.GET;
import retrofit2.http.Streaming;
import retrofit2.http.Url;

import static org.junit.Assert.*;

public class DownloadManagerTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    interface RawDownloadApi {
        @GET @Streaming Observable download(@Url String url);
    }

    private static Retrofit retrofit() {
        return new Retrofit.Builder().baseUrl("http://127.0.0.1/")
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .client(new OkHttpClient()).build();
    }

    @Test public void rawObservableReproducesTheReleaseAdapterFailure() {
        try {
            retrofit().create(RawDownloadApi.class).download("http://127.0.0.1/file.apk");
            fail("Raw return type must be rejected before starting a request");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unable to create call adapter"));
            assertNotNull(expected.getCause());
            assertTrue(expected.getCause().getMessage().contains("parameterized"));
        }
    }

    @Test public void productionApiKeepsItsStreamingBodyContract() throws Exception {
        Method method = DownloadApi.class.getMethod("download", String.class);
        assertTrue(method.getGenericReturnType() instanceof ParameterizedType);
        ParameterizedType type = (ParameterizedType) method.getGenericReturnType();
        assertEquals(Observable.class, type.getRawType());
        assertEquals(ResponseBody.class, type.getActualTypeArguments()[0]);
        assertNotNull(method.getAnnotation(GET.class));
        assertNotNull(method.getAnnotation(Streaming.class));
        assertNotNull(retrofit().create(DownloadApi.class).download("http://127.0.0.1/file.apk"));
    }

    @Test public void adapterInitializationFailureIsDeliveredAsAnObservableError() throws Exception {
        DownloadManager manager = new DownloadManager();
        Field api = DownloadManager.class.getDeclaredField("mDownloadApi");
        api.setAccessible(true);
        api.set(manager, (DownloadApi) url -> {
            throw new IllegalArgumentException("Unable to create call adapter");
        });
        File target = new File(temporary.getRoot(), "missing/failed.apk");
        manager.download("http://127.0.0.1/failed.apk", target.getPath()).test()
                .awaitDone(5, TimeUnit.SECONDS)
                .assertError(IllegalArgumentException.class).assertNotComplete();
        assertFalse(target.exists());
    }

    @Test public void actualHttpDownloadCreatesItsDirectoryAndPreservesBytes() throws Exception {
        byte[] expected = new byte[131079];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) (i * 31);
        AtomicInteger requests = new AtomicInteger();
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(10000);
        ExecutorService fixture = Executors.newSingleThreadExecutor();
        Future<?> served = fixture.submit(() -> {
            try (Socket socket = server.accept()) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.US_ASCII));
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                requests.incrementAndGet();
                OutputStream output = socket.getOutputStream();
                output.write(("HTTP/1.1 200 OK\r\nContent-Length: " + expected.length
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(expected);
                output.flush();
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        });
        try {
            File target = new File(temporary.getRoot(), "updates/AI-js-Pro.apk");
            TestObserver<Integer> observer = new DownloadManager().download(
                    "http://127.0.0.1:" + server.getLocalPort() + "/update.apk", target.getPath())
                    .test().awaitDone(10, TimeUnit.SECONDS).assertComplete().assertNoErrors();
            assertTrue(observer.values().contains(100));
            assertEquals(1, requests.get());
            assertArrayEquals(expected, Files.readAllBytes(target.toPath()));
            served.get(5, TimeUnit.SECONDS);
        } finally {
            server.close();
            fixture.shutdownNow();
        }
    }
}
