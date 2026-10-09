package com.jdkshen.aijspro.network;

import com.jdkshen.aijspro.network.entity.VersionInfo;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.Observable;

import static org.junit.Assert.assertEquals;

public class VersionServiceProxyTest {
    @Test
    public void invalidProxyManifestFallsBackToGitHub() {
        AtomicInteger directRequests = new AtomicInteger();
        VersionInfo directInfo = new VersionInfo();
        VersionService service = new VersionService() {
            @Override
            Observable<VersionInfo> checkAccelerated() {
                return Observable.fromCallable(() -> VersionService.fromManifest(
                        "<html>proxy unavailable</html>", GitHubUpdateProxy.UPDATE_SOURCE,
                        true, new String[]{"arm64-v8a"}));
            }

            @Override
            Observable<VersionInfo> checkGitHubDirect() {
                return Observable.defer(() -> {
                    directRequests.incrementAndGet();
                    return Observable.just(directInfo);
                });
            }
        };
        service.checkForUpdates().test().awaitDone(5, java.util.concurrent.TimeUnit.SECONDS)
                .assertValue(directInfo).assertComplete();
        assertEquals(1, directRequests.get());
    }

    @Test
    public void successfulProxyDoesNotContactDirectGitHub() {
        AtomicInteger directRequests = new AtomicInteger();
        VersionInfo proxyInfo = new VersionInfo();
        VersionService service = new VersionService() {
            @Override
            Observable<VersionInfo> checkAccelerated() {
                return Observable.just(proxyInfo);
            }

            @Override
            Observable<VersionInfo> checkGitHubDirect() {
                directRequests.incrementAndGet();
                return Observable.error(new IOException("direct should not be used"));
            }
        };
        service.checkForUpdates().test().awaitDone(5, java.util.concurrent.TimeUnit.SECONDS)
                .assertValue(proxyInfo).assertComplete();
        assertEquals(0, directRequests.get());
    }
}
