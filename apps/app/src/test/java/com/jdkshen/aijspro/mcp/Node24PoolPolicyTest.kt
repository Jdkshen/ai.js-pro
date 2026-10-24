package com.jdkshen.aijspro.mcp

import org.autojs.source.engine.android.Node24Pool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Node24PoolPolicyTest {
    @Test
    fun busyAndCancelledAcquireNeverFallBackToLegacy() {
        assertFalse(Node24Pool.shouldFallbackToLegacy(Node24Pool.PoolBusyException("busy"), false))
        assertFalse(Node24Pool.shouldFallbackToLegacy(Node24Pool.PoolInterruptedException("cancelled"), false))
        assertFalse(Node24Pool.shouldFallbackToLegacy(IllegalStateException("other", Node24Pool.PoolBusyException("busy")), false))
        assertFalse(Node24Pool.shouldFallbackToLegacy(IllegalStateException("startup"), true))
        assertFalse(Node24Pool.shouldFallbackToLegacy(java.util.concurrent.CancellationException(), false))
        assertFalse(Node24Pool.shouldFallbackToLegacy(InterruptedException(), false))
        assertFalse(Node24Pool.shouldFallbackToLegacy(OutOfMemoryError(), false))
        assertTrue(Node24Pool.isBusyOrCancelled(Node24Pool.PoolBusyException("busy")))
        assertFalse(Node24Pool.isBusyOrCancelled(IllegalStateException("startup")))
    }

    @Test
    fun realPoolStartupFailureStillAllowsLegacyFallback() {
        assertTrue(Node24Pool.shouldFallbackToLegacy(IllegalStateException("cannot bind pool service"), false))
    }

    @Test
    fun capacityIsTheAdvertisedThreeSlots() {
        assertEquals(3, Node24Pool.capacity())
    }
}
