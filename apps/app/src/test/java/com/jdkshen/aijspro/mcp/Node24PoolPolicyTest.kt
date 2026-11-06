package com.jdkshen.aijspro.mcp

import org.autojs.source.engine.android.Node24Pool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test
    fun fullQueueFailsFastWithConcurrencyMessage() {
        val pool = Node24Pool::class.java
        val slotsField = pool.getDeclaredField("sSlots").apply { isAccessible = true }
        val waitersField = pool.getDeclaredField("sWaiters").apply { isAccessible = true }
        val slots = slotsField.get(null) as Array<*>
        try {
            for (slot in slots) {
                val slotObject = requireNotNull(slot)
                slotObject.javaClass.getDeclaredField("busy").apply { isAccessible = true }.setBoolean(slotObject, true)
            }
            waitersField.setInt(null, Node24Pool.maxWaiters())
            try {
                Node24Pool.acquire(null)
                fail("队列已满时不应继续排队")
            } catch (expected: Node24Pool.PoolBusyException) {
                val message = expected.message.orEmpty()
                assertTrue(message, message.contains("并发上限 3 个"))
                assertTrue(message, message.contains("排队队列已满"))
            }
        } finally {
            for (slot in slots) {
                val slotObject = requireNotNull(slot)
                slotObject.javaClass.getDeclaredField("busy").apply { isAccessible = true }.setBoolean(slotObject, false)
            }
            waitersField.setInt(null, 0)
        }
    }

    @Test
    fun busyPoolQueuesAreBoundedWithExplicitMessages() {
        assertEquals(5, Node24Pool.maxWaiters())
        assertEquals(30_000L, Node24Pool.acquireTimeoutMillis())
        assertTrue(Node24Pool.hasQueueCapacity(0))
        assertTrue(Node24Pool.hasQueueCapacity(4))
        assertFalse(Node24Pool.hasQueueCapacity(5))
        assertFalse(Node24Pool.hasQueueCapacity(-1))
        assertTrue(Node24Pool.queueFullMessage().contains("并发上限 3 个"))
        assertTrue(Node24Pool.queueFullMessage().contains("排队队列已满"))
        assertTrue(Node24Pool.waitTimeoutMessage().contains("并发上限 3 个"))
        assertTrue(Node24Pool.waitTimeoutMessage().contains("30s"))
    }
}
