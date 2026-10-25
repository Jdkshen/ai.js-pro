package com.jdkshen.aijspro.inrt.autojs;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 轻量 JVM 测试：不依赖 Android，固定跨源去重不丢下一次按键。
 */
public class VolumeKeyDeduplicatorTest {

    private static final int VOLUME_UP = 24;
    private static final int VOLUME_DOWN = 25;

    @Test
    public void sameSourceRepeatedPressIsNeverSuppressed() {
        VolumeKeyDeduplicator gate = new VolumeKeyDeduplicator();
        assertTrue(gate.accept(VOLUME_UP, "accessibility", 1000));
        assertTrue(gate.accept(VOLUME_UP, "accessibility", 1100));
        assertTrue(gate.accept(VOLUME_UP, "accessibility", 2000));
    }

    @Test
    public void otherSourceOnlySuppressesTheSamePress() {
        VolumeKeyDeduplicator gate = new VolumeKeyDeduplicator();
        assertTrue(gate.accept(VOLUME_UP, "accessibility", 1000));
        assertFalse(gate.accept(VOLUME_UP, "shell", 1010));
        assertTrue(gate.accept(VOLUME_UP, "shell", 1500));
        assertFalse(gate.accept(VOLUME_UP, "accessibility", 1510));
        assertTrue(gate.accept(VOLUME_UP, "shell", 1600));
    }

    @Test
    public void downCannotPoisonTheNextUp() {
        VolumeKeyDeduplicator gate = new VolumeKeyDeduplicator();
        assertTrue(gate.accept(VOLUME_DOWN, "accessibility", 1000));
        assertTrue(gate.accept(VOLUME_UP, "accessibility", 1001));
        assertFalse(gate.accept(VOLUME_UP, "shell", 1100));
        assertTrue(gate.accept(VOLUME_UP, "shell", 1300));
    }

    @Test
    public void staleCrossSourceEventIsAccepted() {
        VolumeKeyDeduplicator gate = new VolumeKeyDeduplicator();
        assertTrue(gate.accept(VOLUME_UP, "accessibility", 1000));
        assertTrue(gate.accept(VOLUME_UP, "shell", 1300));
    }

    @Test
    public void clockGoingBackwardsDoesNotDropTheNextPress() {
        VolumeKeyDeduplicator gate = new VolumeKeyDeduplicator();
        assertTrue(gate.accept(VOLUME_UP, "accessibility", 2000));
        assertTrue(gate.accept(VOLUME_UP, "shell", 1000));
    }
}
