package com.jdkshen.aijspro.network;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;

import io.reactivex.Observable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GitHubUpdateProxyTest {
    private static final String DIRECT = "https://github.com/Jdkshen/ai.js-pro/releases/download/dev-782/app.apk";
    private static final String PROXY = GitHubUpdateProxy.PREFIX + DIRECT;

    @Test
    public void previouslyConfiguredBuiltInProxyUsesDefaultFallbackPolicy() {
        assertTrue(GitHubUpdateProxy.isDefaultProxySource(GitHubUpdateProxy.UPDATE_SOURCE));
        assertTrue(GitHubUpdateProxy.isDefaultProxySource(GitHubUpdateProxy.UPDATE_SOURCE + "?channel=dev-783"));
        assertFalse(GitHubUpdateProxy.isDefaultProxySource("http://192.168.10.7:8080/update.json"));
        assertFalse(GitHubUpdateProxy.isDefaultProxySource(GitHubUpdateProxy.UPDATE_SOURCE + ".invalid"));
        assertFalse(GitHubUpdateProxy.isDefaultProxySource(null));
    }

    @Test
    public void mutableLatestManifestGetsFreshUrlForEveryCheck() {
        assertEquals(GitHubUpdateProxy.UPDATE_SOURCE + "?_aijs_update=100",
                GitHubUpdateProxy.manifestRequestUrl(GitHubUpdateProxy.UPDATE_SOURCE, 100));
        assertEquals(GitHubUpdateProxy.UPDATE_SOURCE + "?_aijs_update=200",
                GitHubUpdateProxy.manifestRequestUrl(GitHubUpdateProxy.UPDATE_SOURCE, 200));
        assertEquals(GitHubUpdateProxy.UPDATE_SOURCE + "?channel=dev-783&_aijs_update=200",
                GitHubUpdateProxy.manifestRequestUrl(GitHubUpdateProxy.UPDATE_SOURCE + "?channel=dev-783", 200));
    }

    @Test
    public void freshnessParameterDoesNotChangeApkOrCustomSourceUrls() {
        assertEquals(PROXY, GitHubUpdateProxy.manifestRequestUrl(PROXY, 100));
        String custom = "http://192.168.10.7:8080/update.json";
        assertEquals(custom, GitHubUpdateProxy.manifestRequestUrl(custom, 100));
    }

    @Test
    public void failedProxyRetriesOriginalUrlOnce() {
        List<String> requests = new ArrayList<>();
        GitHubUpdateProxy.downloadWithFallback(PROXY, url -> {
            requests.add(url);
            return url.equals(PROXY) ? Observable.error(new IOException("unavailable"))
                    : Observable.just("apk");
        }).test().assertValue("apk").assertComplete();
        assertEquals(Arrays.asList(PROXY, DIRECT), requests);
    }

    @Test
    public void successfulProxyDoesNotDownloadTwice() {
        List<String> requests = new ArrayList<>();
        GitHubUpdateProxy.downloadWithFallback(PROXY, url -> {
            requests.add(url);
            return Observable.just("apk");
        }).test().assertValue("apk").assertComplete();
        assertEquals(Arrays.asList(PROXY), requests);
    }

    @Test
    public void cancelDoesNotRestartDownload() {
        List<String> requests = new ArrayList<>();
        GitHubUpdateProxy.downloadWithFallback(PROXY, url -> {
            requests.add(url);
            return Observable.error(new CancellationException("cancelled"));
        }).test().assertError(CancellationException.class);
        assertEquals(Arrays.asList(PROXY), requests);
    }

    @Test
    public void failedDirectDownloadIsReturnedWithoutLoop() {
        List<String> requests = new ArrayList<>();
        GitHubUpdateProxy.downloadWithFallback(PROXY, url -> {
            requests.add(url);
            return Observable.error(new IOException("unavailable"));
        }).test().assertError(IOException.class);
        assertEquals(Arrays.asList(PROXY, DIRECT), requests);
    }

    @Test
    public void customSourcesAreNotRewritten() {
        assertNull(GitHubUpdateProxy.directDownloadUrl("http://192.168.10.7:8080/app.apk"));
        assertNull(GitHubUpdateProxy.directDownloadUrl(DIRECT));
        assertNull(GitHubUpdateProxy.directDownloadUrl(GitHubUpdateProxy.PREFIX + "https://example.com/app.apk"));
        assertNull(GitHubUpdateProxy.directDownloadUrl(GitHubUpdateProxy.PREFIX + "https://github.com.evil.test/releases/download/v1/a.apk"));
        assertNull(GitHubUpdateProxy.directDownloadUrl(GitHubUpdateProxy.PREFIX + "https://user@github.com/releases/download/v1/a.apk"));
        assertEquals(DIRECT, GitHubUpdateProxy.directDownloadUrl(PROXY));
    }

    @Test
    public void verificationFailureAfterDownloadDoesNotTriggerNetworkFallback() {
        List<String> requests = new ArrayList<>();
        GitHubUpdateProxy.downloadWithFallback(PROXY, url -> {
            requests.add(url);
            return Observable.just("apk");
        }).flatMap(apk -> Observable.error(new IOException("SHA-256 mismatch")))
                .test().assertError(IOException.class);
        assertEquals(Arrays.asList(PROXY), requests);
    }
}
