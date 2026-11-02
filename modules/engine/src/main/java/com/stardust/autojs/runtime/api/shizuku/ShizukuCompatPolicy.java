package com.stardust.autojs.runtime.api.shizuku;

final class ShizukuCompatPolicy {
    static final long LEASE_MILLIS = 15000;
    static final int MAX_PENDING = 64;
    private ShizukuCompatPolicy() { }
    static boolean validToken(String token) {
        return token != null && token.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    static boolean expired(long created, long now) { return now < created || now - created >= LEASE_MILLIS; }
    static boolean acceptsUid(int expected, int actual) { return (expected == 0 || expected == 2000) && expected == actual; }
}
