package com.jdkshen.aijspro.network;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CancellationException;

import io.reactivex.Observable;
import io.reactivex.functions.Function;

/** Built-in public release acceleration and a single direct-download fallback. */
public final class GitHubUpdateProxy {
    public static final String PREFIX = "https://gh-proxy.com/";
    public static final String UPDATE_SOURCE = PREFIX
            + "https://github.com/Jdkshen/ai.js-pro/releases/latest/download/update-gh-proxy.json";

    private GitHubUpdateProxy() {
    }

    static boolean isDefaultProxySource(String url) {
        if (url == null) return false;
        int fragmentAt = url.indexOf('#');
        String resource = fragmentAt < 0 ? url : url.substring(0, fragmentAt);
        return resource.equals(UPDATE_SOURCE) || resource.startsWith(UPDATE_SOURCE + "?");
    }

    static String manifestRequestUrl(String url, long now) {
        if (url == null) return null;
        int fragmentAt = url.indexOf('#');
        String resource = fragmentAt < 0 ? url : url.substring(0, fragmentAt);
        String fragment = fragmentAt < 0 ? "" : url.substring(fragmentAt);
        if (isDefaultProxySource(resource)) {
            return resource + (resource.contains("?") ? "&" : "?")
                    + "_aijs_update=" + now + fragment;
        }
        return url;
    }

    public static String directDownloadUrl(String url) {
        if (url == null || !url.startsWith(PREFIX)) return null;
        String direct = url.substring(PREFIX.length());
        try {
            URI uri = URI.create(direct);
            if ("https".equals(uri.getScheme()) && "github.com".equals(uri.getHost())
                    && uri.getUserInfo() == null && uri.getPort() == -1
                    && uri.getPath().contains("/releases/download/")) {
                return direct;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    public static <T> Observable<T> downloadWithFallback(String url,
            Function<String, Observable<T>> downloader) {
        return Observable.defer(() -> downloader.apply(url)).onErrorResumeNext(error -> {
            String direct = directDownloadUrl(url);
            boolean networkFailure = error instanceof IOException
                    || error instanceof retrofit2.HttpException
                    || error instanceof com.jakewharton.retrofit2.adapter.rxjava2.HttpException;
            if (direct == null || !networkFailure || error instanceof CancellationException) {
                return Observable.error(error);
            }
            return Observable.defer(() -> downloader.apply(direct));
        });
    }
}
