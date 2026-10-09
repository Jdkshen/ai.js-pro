package com.stardust.autojs.core.image.capture;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Explicit, process-local leases. The registry itself never keeps a session alive. */
public final class SharedCaptureRegistry<T extends AutoCloseable> {
    private final Map<String, Session> sessions = new HashMap<>();

    public synchronized Lease publish(T resource) {
        String token = UUID.randomUUID().toString();
        Session session = new Session(token, resource);
        sessions.put(token, session);
        return new Lease(session);
    }

    public synchronized Lease acquire(String token) {
        Session session = sessions.get(token);
        if (session == null) throw new IllegalStateException("共享截图已结束或共享凭据无效");
        session.references++;
        return new Lease(session);
    }

    public synchronized int size() { return sessions.size(); }

    private final class Session {
        final String token;
        final T resource;
        int references = 1;
        Session(String token, T resource) { this.token = token; this.resource = resource; }
    }

    public final class Lease implements AutoCloseable {
        private final Session session;
        private boolean closed;
        private Lease(Session session) { this.session = session; }
        public String token() { return session.token; }

        /** Serializes capture AND copying the frame before another consumer can close it. */
        public <R> R use(Function<T, R> operation) {
            synchronized (session) {
                if (closed) throw new IllegalStateException("当前引擎已停止共享截图");
                return operation.apply(session.resource);
            }
        }

        @Override public void close() {
            synchronized (session) {
                if (closed) return;
                closed = true;
                boolean last;
                synchronized (SharedCaptureRegistry.this) {
                    last = --session.references == 0;
                    if (last) sessions.remove(session.token);
                }
                if (last) {
                    try { session.resource.close(); }
                    catch (Exception error) { throw new IllegalStateException("释放共享截图失败", error); }
                }
            }
        }
    }
}
