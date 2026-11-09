package com.stardust.autojs.core.content;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * task-54 纯层单测：filter 归一化、API 级 flags、句柄生命周期、幂等、注销后不回调、
 * onReceive 异常隔离、owner 线程结束后的兜底清理。
 */
public class BroadcastReceiverRegistryTest {
    private static final class FakeHost implements BroadcastReceiverRegistry.Host {
        final List<String> registered = new ArrayList<String>();
        final List<String> unregistered = new ArrayList<String>();
        boolean failRegister = false;

        @Override public void register(BroadcastReceiverRegistry.Handle handle) {
            if (failRegister) throw new IllegalStateException("platform register failed");
            registered.add(handle.id);
        }

        @Override public void unregister(BroadcastReceiverRegistry.Handle handle) {
            unregistered.add(handle.id);
        }
    }

    private static final class FakeListener implements BroadcastReceiverRegistry.Listener {
        final List<Object> intents = new ArrayList<Object>();
        final List<Throwable> errors = new ArrayList<Throwable>();
        boolean throwOnIntent = false;

        @Override public void onIntent(Object intent) {
            intents.add(intent);
            if (throwOnIntent) throw new RuntimeException("script callback blew up");
        }

        @Override public void onError(Throwable error) {
            errors.add(error);
        }
    }

    private final BroadcastReceiverRegistry registry = new BroadcastReceiverRegistry();

    @Test public void filterNormalizationTrimsDropsEmptyAndDedupes() {
        BroadcastFilter filter = BroadcastFilter.of(
                new String[]{"  demo.a  ", "", null, "demo.a", "demo.b"},
                new String[]{" cat ", "", "cat"}, new String[]{"https"}, "  ", false);
        assertEquals(java.util.Arrays.asList("demo.a", "demo.b"), filter.actions);
        assertEquals(java.util.Arrays.asList("cat"), filter.categories);
        assertEquals(java.util.Arrays.asList("https"), filter.schemes);
        assertEquals(null, filter.permission);
        assertFalse(filter.exported);
    }

    @Test public void filterRejectsMissingAction() {
        try {
            BroadcastFilter.of(new String[]{" ", null}, null, null, null, false);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("至少要有一个非空 action"));
        }
    }

    @Test public void receiverFlagsFollowApiLevelAndExportedChoice() {
        BroadcastFilter safe = BroadcastFilter.of(new String[]{"a"}, null, null, null, false);
        BroadcastFilter exported = BroadcastFilter.of(new String[]{"a"}, null, null, null, true);
        assertEquals(0, safe.receiverFlags(30));
        assertEquals(0, exported.receiverFlags(32));
        assertEquals(BroadcastFilter.RECEIVER_NOT_EXPORTED, safe.receiverFlags(33));
        assertEquals(BroadcastFilter.RECEIVER_EXPORTED, exported.receiverFlags(33));
        assertEquals(BroadcastFilter.RECEIVER_NOT_EXPORTED, safe.receiverFlags(36));
    }

    @Test public void registerReturnsReadableHandleAndCounters() {
        FakeHost host = new FakeHost();
        FakeListener listener = new FakeListener();
        BroadcastReceiverRegistry.Handle handle = registry.register(
                BroadcastFilter.of(new String[]{"demo.a"}, null, null, null, false), false, 33, listener, host);
        assertEquals("bcr-1", handle.id);
        assertTrue(handle.registeredAt > 0);
        assertFalse(handle.exported);
        assertEquals(java.util.Arrays.asList("bcr-1"), host.registered);
        assertEquals(1, registry.activeCount());
        assertEquals(Long.valueOf(1L), registry.counters().get("registered"));
        assertEquals("bcr-1", handle.toString());
    }

    @Test public void unregisterIsIdempotentAndCallsPlatformOnce() {
        FakeHost host = new FakeHost();
        BroadcastReceiverRegistry.Handle handle = registry.register(
                BroadcastFilter.of(new String[]{"demo.a"}, null, null, null, false), false, 33, new FakeListener(), host);
        assertTrue(registry.unregister(handle.id));
        assertFalse(registry.unregister(handle.id));
        assertFalse(registry.unregister("bcr-999"));
        assertFalse(registry.unregister(null));
        assertEquals(java.util.Arrays.asList("bcr-1"), host.unregistered);
        assertEquals(0, registry.activeCount());
        assertEquals(Long.valueOf(1L), registry.counters().get("unregistered"));
    }

    @Test public void dispatchAfterUnregisterDoesNotCallBack() {
        FakeHost host = new FakeHost();
        FakeListener listener = new FakeListener();
        BroadcastReceiverRegistry.Handle handle = registry.register(
                BroadcastFilter.of(new String[]{"demo.a"}, null, null, null, false), false, 33, listener, host);
        assertEquals(1, registry.dispatch(handle.id, "intent-1"));
        registry.unregister(handle.id);
        assertEquals(0, registry.dispatch(handle.id, "intent-2"));
        assertEquals(0, registry.dispatch("bcr-unknown", "intent-3"));
        assertEquals(java.util.Arrays.asList("intent-1"), listener.intents);
    }

    @Test public void callbackExceptionIsIsolatedAndReported() {
        FakeHost host = new FakeHost();
        FakeListener listener = new FakeListener();
        listener.throwOnIntent = true;
        BroadcastReceiverRegistry.Handle handle = registry.register(
                BroadcastFilter.of(new String[]{"demo.a"}, null, null, null, false), false, 33, listener, host);
        assertEquals(2, registry.dispatch(handle.id, "intent-1"));
        assertEquals(1, listener.errors.size());
        assertEquals(1, registry.activeCount());
        assertEquals(Long.valueOf(1L), registry.counters().get("callbackErrors"));
    }

    @Test public void platformRegisterFailureDoesNotLeaveAHandle() {
        FakeHost host = new FakeHost();
        host.failRegister = true;
        try {
            registry.register(BroadcastFilter.of(new String[]{"demo.a"}, null, null, null, false), false, 33,
                    new FakeListener(), host);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("platform register failed"));
        }
        assertEquals(0, registry.activeCount());
        assertEquals(Long.valueOf(0L), registry.counters().get("registered"));
    }

    @Test public void sweepUnregistersHandlesWhoseOwnerThreadEnded() throws Exception {
        FakeHost host = new FakeHost();
        final BroadcastReceiverRegistry.Handle[] slot = new BroadcastReceiverRegistry.Handle[1];
        Thread scriptThread = new Thread(new Runnable() {
            @Override public void run() {
                slot[0] = registry.register(
                        BroadcastFilter.of(new String[]{"demo.a"}, null, null, null, false), false, 33,
                        new FakeListener(), host);
            }
        });
        scriptThread.start();
        scriptThread.join();
        assertNotNull(slot[0]);
        assertEquals(1, registry.activeCount());
        assertEquals(1, registry.sweep());
        assertEquals(0, registry.activeCount());
        assertEquals(java.util.Arrays.asList(slot[0].id), host.unregistered);
        assertEquals(Long.valueOf(1L), registry.counters().get("swept"));
    }

    @Test public void unregisterAllCountsAndClearsEverything() {
        FakeHost host = new FakeHost();
        for (int index = 0; index < 3; index++) {
            registry.register(BroadcastFilter.of(new String[]{"demo." + index}, null, null, null, false), false, 33,
                    new FakeListener(), host);
        }
        assertEquals(3, registry.activeCount());
        assertEquals(3, registry.unregisterAll());
        assertEquals(0, registry.activeCount());
        assertEquals(0, registry.unregisterAll());
        assertEquals(3, host.unregistered.size());
    }
}
