package com.stardust.autojs.core.looper;

/** Timer ownership encoded in an exactly representable JavaScript number. */
public final class TimerId {
    public static final int MAX_TIMER_ID = 0x1fffff;
    private TimerId() {}

    public static double encode(int timerId, int callbackId) {
        if (timerId < 0 || timerId > MAX_TIMER_ID || callbackId <= 0)
            throw new IllegalArgumentException("Invalid timer or callback ID");
        return (double) (((long) timerId << 32) | (callbackId & 0xffffffffL));
    }

    public static int timerId(double id) {
        if (!Double.isFinite(id) || id <= 0 || id != Math.rint(id) || id > 9007199254740991d || (int) (long) id <= 0)
            return -1;
        return (int) ((long) id >>> 32);
    }

    public static int callbackId(double id, int expectedTimerId) {
        if (timerId(id) != expectedTimerId || (int) (long) id <= 0)
            throw new IllegalArgumentException("ID does not belong to this timer");
        return (int) (long) id;
    }
}
