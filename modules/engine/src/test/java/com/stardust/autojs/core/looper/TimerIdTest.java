package com.stardust.autojs.core.looper;
import org.junit.Test;
import static org.junit.Assert.*;
public class TimerIdTest {
    @Test public void sameLocalIdKeepsDifferentThreadOwners() {
        double main = TimerId.encode(0, 1);
        double worker = TimerId.encode(2, 1);
        assertNotEquals(main, worker, 0);
        assertEquals(0, TimerId.timerId(main));
        assertEquals(2, TimerId.timerId(worker));
        assertEquals(1, TimerId.callbackId(worker, 2));
    }
    @Test public void maximumSupportedIdsSurviveJavaScriptDouble() {
        double id = TimerId.encode(TimerId.MAX_TIMER_ID, Integer.MAX_VALUE);
        assertEquals(TimerId.MAX_TIMER_ID, TimerId.timerId(id));
        assertEquals(Integer.MAX_VALUE, TimerId.callbackId(id, TimerId.MAX_TIMER_ID));
    }
    @Test(expected=IllegalArgumentException.class)
    public void cannotCancelAnotherTimersCallbackByLocalNumber() {
        TimerId.callbackId(TimerId.encode(2, 1), 0);
    }
    @Test public void invalidScriptNumbersHaveNoOwner() {
        assertEquals(-1, TimerId.timerId(Double.NaN));
        assertEquals(-1, TimerId.timerId(1.5));
        assertEquals(-1, TimerId.timerId(-1));
    }
}
