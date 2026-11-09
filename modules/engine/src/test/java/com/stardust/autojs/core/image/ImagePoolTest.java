package com.stardust.autojs.core.image;

import com.stardust.util.ResourceMonitor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link ImagePool} / {@link ImagePools} 的纯 JVM 单测（不加载 Android / OpenCV native）。
 *
 * <p>池语义只依赖「资源形状」，所以这里用假资源 + 假 releaser 就能把 借/还/上限/回收时机/护栏
 * 全部测到；真实 {@code Mat} / {@code MatOfPoint} 的 native 释放与真机日志需要真机验收。
 */
public class ImagePoolTest {

    private final List<String> messages = new ArrayList<>();
    private int poolBaseline;

    @Before
    public void installReporter() {
        ImagePools.setReporter(messages::add);
        ImagePools.setDiagnosticsEnabled(true);
        ImagePools.drainAll("test-setup");
        messages.clear();
        poolBaseline = ImagePools.openPoolCount();
    }

    @After
    public void restoreGlobals() {
        ImagePools.setStrictMode(false);
        ImagePools.drainAll("test-cleanup");
        ImagePools.setReporter(null);
        ImagePools.setDiagnosticsEnabled(true);
        messages.clear();
    }

    @Test
    public void borrowThenRecycleReleasesExactlyOnce() {
        ImagePool pool = new ImagePool();
        FakeResource resource = borrow(pool);

        assertEquals(1, pool.pendingCount());
        assertEquals(1, pool.borrowed());
        assertEquals(0, pool.returned());

        assertTrue(pool.recycle(resource));
        assertEquals(0, pool.pendingCount());
        assertEquals(1, pool.returned());
        assertEquals(1, resource.releaseCount);

        assertFalse("重复归还不应二次 release", pool.recycle(resource));
        assertEquals(1, resource.releaseCount);

        pool.close();
        assertEquals(poolBaseline, ImagePools.openPoolCount());
    }

    @Test
    public void foreignResourceIsNotReleasedByThisPool() {
        ImagePool pool = new ImagePool();
        FakeResource foreign = new FakeResource();
        assertFalse(pool.recycle(foreign));
        assertEquals(0, foreign.releaseCount);
        pool.close();
    }

    @Test
    public void closeForceReleasesOutstandingAndIsIdempotent() {
        ImagePool pool = new ImagePool();
        FakeResource first = borrow(pool);
        FakeResource second = borrow(pool);

        pool.close();
        assertEquals(1, first.releaseCount);
        assertEquals(1, second.releaseCount);
        assertEquals(0, pool.pendingCount());
        assertEquals(2, pool.forced());
        assertTrue(pool.isClosed());

        pool.close();
        assertEquals("close 必须幂等", 1, first.releaseCount);
        assertEquals(poolBaseline, ImagePools.openPoolCount());
    }

    @Test
    public void releaseKeepsPoolUsable() {
        ImagePool pool = new ImagePool();
        FakeResource first = borrow(pool);
        pool.release();
        assertEquals(1, first.releaseCount);
        assertEquals(0, pool.pendingCount());
        assertFalse(pool.isClosed());

        FakeResource second = borrow(pool);
        assertEquals(1, pool.pendingCount());
        assertTrue(pool.recycle(second));
        assertEquals(1, second.releaseCount);
        pool.close();
    }

    @Test
    public void makeAfterCloseFails() {
        ImagePool pool = new ImagePool();
        pool.close();
        try {
            pool.make(new FakeResource(), () -> { });
            fail("close 之后登记应当失败");
        } catch (IllegalStateException expected) {
            assertTrue(String.valueOf(expected.getMessage()), expected.getMessage().contains("close"));
        }
    }

    /** task-71（A1-1）：默认口径 = 警告 + 强制回收，**不抛**（墙上钉住，避免哪天被改成默认抛而破坏现有脚本）。 */
    @Test
    public void defaultGuardWarnsAndForcesRecycleWithoutThrowing() {
        assertFalse("默认不能是严格模式", ImagePools.isStrictMode());
        ImagePool pool = new ImagePool();
        FakeResource resource = borrow(pool);

        int forced = ImagePools.drainAll("unit-test");

        assertEquals(1, forced);
        assertEquals(1, resource.releaseCount);
        assertTrue(String.join(System.lineSeparator(), messages).contains("未归还"));
        pool.close();
    }

    /** task-71（A1-1）：严格模式下抛**具名异常**，但异常发生在强制回收之后（资源一定已释放）。 */
    @Test
    public void strictModeThrowsNamedExceptionAfterForcingRecycle() {
        ImagePools.setStrictMode(true);
        assertTrue(ImagePools.isStrictMode());
        ImagePool pool = new ImagePool();
        FakeResource resource = borrow(pool);

        UnrecycledImageDetectedException thrown = null;
        try {
            ImagePools.drainAll("unit-test");
            fail("严格模式必须抛 UnrecycledImageDetectedException");
        } catch (UnrecycledImageDetectedException expected) {
            thrown = expected;
        } finally {
            ImagePools.setStrictMode(false);
        }

        assertEquals("unit-test", thrown.getReason());
        assertEquals(1, thrown.getForced());
        assertEquals(1, resource.releaseCount);
        assertEquals("抛异常时资源必须已经释放", 0, pool.pendingCount());
        assertTrue(String.valueOf(thrown.getMessage()), thrown.getMessage().contains("严格模式"));
        assertTrue(String.valueOf(thrown.getMessage()), thrown.getMessage().contains("pool.recycle(x)"));
        assertTrue("要带分配点: " + thrown.getSites(), thrown.getSites().toString().contains("ImagePoolTest."));
        pool.close();
    }

    /** task-71（A1-1）：严格模式下、但没有任何未归还时**不抛**（否则脚本退出路径会被无谓打断）。 */
    @Test
    public void strictModeStaysQuietWhenNothingPending() {
        ImagePools.setStrictMode(true);
        try {
            ImagePool pool = new ImagePool();
            FakeResource resource = borrow(pool);
            assertTrue(pool.recycle(resource));
            assertEquals(0, ImagePools.drainAll("unit-test"));
            pool.close();
        } finally {
            ImagePools.setStrictMode(false);
        }
    }

    @Test
    public void guardReportsUnreturnedWithAllocationSiteAndHint() {
        ImagePool pool = new ImagePool();
        borrow(pool);
        borrow(pool);

        int forced = ImagePools.drainAll("unit-test");

        assertEquals(2, forced);
        assertEquals(0, pool.pendingCount());
        assertEquals(2, pool.forced());
        String joined = String.join(System.lineSeparator(), messages);
        assertTrue(joined, joined.contains("未归还"));
        assertTrue(joined, joined.contains("2 个图像资源"));
        assertTrue(joined, joined.contains("unit-test"));
        assertTrue("护栏要报出分配点: " + joined, joined.contains("ImagePoolTest."));
        assertTrue("护栏要给可抄提示: " + joined, joined.contains("pool.recycle(x)"));
        pool.close();
    }

    @Test
    public void guardStaysSilentWhenEverythingReturned() {
        ImagePool pool = new ImagePool();
        FakeResource resource = borrow(pool);
        assertTrue(pool.recycle(resource));

        assertEquals(0, ImagePools.drainAll("unit-test"));
        assertTrue(messages.toString(), messages.isEmpty());
        pool.close();
    }

    @Test
    public void overflowAboveMaxTrackedWarnsOnceAndKeepsTracking() {
        ImagePool pool = new ImagePool(2);
        borrow(pool);
        borrow(pool);
        borrow(pool);
        borrow(pool);

        assertEquals("继续登记、不丢弃", 4, pool.pendingCount());
        assertEquals(2, pool.overflow());
        assertEquals(2, pool.maxTracked());
        long capWarnings = messages.stream().filter(message -> message.contains("上限")).count();
        assertEquals("上限只报警一次", 1, capWarnings);
        // 超限的资源同样受护栏保护
        assertEquals(4, ImagePools.drainAll("unit-test"));
        pool.close();
    }

    @Test
    public void diagnosticsOffKeepsReleasingButDropsAllocationSite() {
        ImagePools.setDiagnosticsEnabled(false);
        ImagePool pool = new ImagePool(1);
        FakeResource resource = borrow(pool);

        assertEquals(1, ImagePools.drainAll("quiet"));
        assertEquals(1, resource.releaseCount);
        // 关掉诊断只影响「分配点」，护栏本身照常报警并从 resourceId 兜底
        String joined = String.join(System.lineSeparator(), messages);
        assertTrue(joined, joined.contains("未归还"));
        assertTrue("关掉诊断后不应编造分配点: " + joined, !joined.contains("ImagePoolTest."));
        assertTrue(joined, joined.contains("resourceId="));
        messages.clear();

        // 超上限的报警与诊断开关无关，仍然要发（drain 后池已空，再借两次才超过上限 1）
        borrow(pool);
        borrow(pool);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0), messages.get(0).contains("上限"));
        pool.close();
    }

    @Test
    public void statsExposePoolAndProcessState() {
        ImagePool pool = new ImagePool(4);
        borrow(pool);
        String single = pool.stats();
        assertTrue(single, single.contains(quoted("pending") + ":1"));
        assertTrue(single, single.contains(quoted("maxTracked") + ":4"));
        assertTrue(single, single.contains(quoted("closed") + ":false"));

        String all = ImagePools.stats();
        assertTrue(all, all.contains(quoted("pools") + ":"));
        assertTrue(all, all.contains(quoted("pending") + ":"));
        assertTrue(all, all.contains(quoted("overflow") + ":"));

        pool.close();
        assertTrue(pool.stats().contains(quoted("closed") + ":true"));
        assertEquals(poolBaseline, ImagePools.openPoolCount());
    }

    @Test
    public void scriptExitHookDrainsPools() {
        ImagePool pool = new ImagePool();
        FakeResource resource = borrow(pool);

        // 引擎既有的「脚本结束」时序点：ScriptRuntimeGlobals.close() -> ColorMapping.recycleSingleton()
        ColorMapping.recycleSingleton();

        assertEquals(1, resource.releaseCount);
        assertEquals(0, pool.pendingCount());
        pool.close();
    }

    private FakeResource borrow(ImagePool pool) {
        FakeResource resource = new FakeResource();
        pool.make(resource, resource::release);
        return resource;
    }

    private static String quoted(String key) {
        return '"' + key + '"';
    }

    /** 假资源：只数「被 release 了几次」，形状与 Mat / MatOfPoint 一致（含资源 id）。 */
    private static final class FakeResource implements ResourceMonitor.Resource {
        int releaseCount;

        void release() {
            releaseCount++;
        }

        @Override
        public int getResourceId() {
            return System.identityHashCode(this);
        }
    }
}
