package com.stardust.autojs.core.http;

import org.junit.Test;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

import static org.junit.Assert.*;

public class MutableOkHttpTest {
    @Test public void repeatedTimeoutChangesKeepOneRetryInterceptor() {
        MutableOkHttp http = new MutableOkHttp();
        Interceptor retry = http.client().interceptors().get(0);
        for (int i = 1; i <= 10; i++) {
            http.setTimeout(i * 1000L);
            assertEquals(i * 1000, http.client().readTimeoutMillis());
            assertEquals(1, http.client().interceptors().size());
            assertSame(retry, http.client().interceptors().get(0));
        }
    }

    @Test public void customClientInterceptorSurvivesTimeoutChanges() {
        MutableOkHttp http = new MutableOkHttp();
        Interceptor custom = chain -> chain.proceed(chain.request());
        http.muteClient(new OkHttpClient.Builder().addInterceptor(custom));
        for (int i = 1; i <= 5; i++) {
            http.setTimeout(2000L * i);
            assertEquals(2, http.client().interceptors().size());
            assertSame(custom, http.client().interceptors().get(0));
        }
    }
}
