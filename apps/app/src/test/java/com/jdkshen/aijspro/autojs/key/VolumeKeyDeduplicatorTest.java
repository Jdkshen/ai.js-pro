package com.jdkshen.aijspro.autojs.key;

import org.junit.Test;
import static org.junit.Assert.*;

public class VolumeKeyDeduplicatorTest {
    @Test public void sameSourceRepeatedPressIsNeverSuppressed() {
        VolumeKeyDeduplicator gate = new VolumeKeyDeduplicator();
        assertTrue(gate.accept(24, "accessibility", 1000));
        assertTrue(gate.accept(24, "accessibility", 1100));
        assertTrue(gate.accept(24, "accessibility", 2000));
    }
    @Test public void otherSourceOnlySuppressesTheSamePress() {
        VolumeKeyDeduplicator gate = new VolumeKeyDeduplicator();
        assertTrue(gate.accept(24, "accessibility", 1000));
        assertFalse(gate.accept(24, "shell", 1010));
        assertTrue(gate.accept(24, "shell", 1500));
        assertFalse(gate.accept(24, "accessibility", 1510));
        assertTrue(gate.accept(24, "shell", 1600));
    }
    @Test public void downCannotPoisonTheNextUp() {
        VolumeKeyDeduplicator gate = new VolumeKeyDeduplicator();
        assertTrue(gate.accept(25, "accessibility", 1000));
        assertTrue(gate.accept(24, "shell", 1010));
        assertTrue(gate.accept(25, "shell", 1300));
    }
}
