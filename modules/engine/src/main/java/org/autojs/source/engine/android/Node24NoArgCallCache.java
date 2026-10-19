// SPDX-License-Identifier: MIT
package org.autojs.source.engine.android;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-job bindings for exact, successfully executed zero-argument Java requests.
 * Only metadata/receivers are retained; results and failures are never cached.
 */
final class Node24NoArgCallCache {
    static final int MAX_ENTRIES = 128;
    static final int MAX_REQUEST_CHARS = 1024;
    private final LinkedHashMap<String, Call> mCalls = new LinkedHashMap<String, Call>(16, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Call> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    private boolean mClosed;

    static final class Call {
        final Method method;
        final Object target;

        Call(Method method, Object target) {
            this.method = method;
            this.target = target;
        }
    }

    synchronized Call get(String request) {
        return request == null || request.length() > MAX_REQUEST_CHARS ? null : mCalls.get(request);
    }

    synchronized void put(String request, Method method, Object target) {
        if (method == null || method.getParameterTypes().length != 0) {
            throw new IllegalArgumentException("Only resolved zero-argument methods can be bound");
        }
        if (!mClosed && request != null && request.length() <= MAX_REQUEST_CHARS) {
            mCalls.put(request, new Call(method, target));
        }
    }

    /** Loading another dex changes class resolution, so all prior bindings expire. */
    synchronized void clear() {
        mCalls.clear();
    }

    synchronized void close() {
        mClosed = true;
        mCalls.clear();
    }

    synchronized int size() {
        return mCalls.size();
    }
}
