package com.stardust.autojs.core.image.capture;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.stardust.autojs.core.image.capture.AutoCapturePolicy.Recording.*;

public class AutoCapturePolicyTest {
    @Test public void unknownNeverPrompts() {
        AutoCapturePolicy policy = new AutoCapturePolicy();
        assertFalse(policy.update(UNKNOWN, 0, true));
        assertFalse(policy.update(UNKNOWN, 10000, true));
    }
    @Test public void idleMustRemainStable() {
        AutoCapturePolicy policy = new AutoCapturePolicy();
        assertFalse(policy.update(IDLE, 0, true));
        assertFalse(policy.update(IDLE, 1499, true));
        assertTrue(policy.update(IDLE, 1500, true));
    }
    @Test public void permissionPendingDoesNotPromptAgain() {
        AutoCapturePolicy policy = new AutoCapturePolicy();
        policy.update(IDLE, 0, true);
        assertTrue(policy.update(IDLE, 2000, true));
        assertFalse(policy.update(IDLE, 5000, true));
    }
    @Test public void canceledConsentSuppressedUntilNewRecording() {
        AutoCapturePolicy policy = new AutoCapturePolicy();
        policy.update(IDLE, 0, true);
        assertTrue(policy.update(IDLE, 2000, true));
        policy.complete(false);
        assertFalse(policy.update(IDLE, 5000, true));
        assertFalse(policy.update(UNKNOWN, 6000, true));
        assertFalse(policy.update(IDLE, 7000, true));
        assertFalse(policy.update(IDLE, 9000, true));
        assertFalse(policy.update(ACTIVE, 10000, true));
        assertFalse(policy.update(IDLE, 11000, true));
        assertTrue(policy.update(IDLE, 12500, true));
    }
    @Test public void disconnectResetsIdleDebounce() {
        AutoCapturePolicy policy = new AutoCapturePolicy();
        policy.update(IDLE, 0, true);
        policy.update(UNKNOWN, 1000, true);
        assertFalse(policy.update(IDLE, 2000, true));
        assertFalse(policy.update(IDLE, 3000, true));
        assertTrue(policy.update(IDLE, 3500, true));
    }
    @Test public void activeAndHighSpeedNeverPrompt() {
        AutoCapturePolicy policy = new AutoCapturePolicy();
        assertFalse(policy.update(ACTIVE, 0, true));
        assertFalse(policy.update(ACTIVE, 5000, true));
        assertFalse(policy.update(IDLE, 6000, false));
        assertFalse(policy.update(IDLE, 9000, false));
    }
    @Test public void recordingRestartWhilePermissionPendingPreventsNewPrompt() {
        AutoCapturePolicy policy = new AutoCapturePolicy();
        policy.update(IDLE, 0, true);
        assertTrue(policy.update(IDLE, 2000, true));
        assertFalse(policy.update(ACTIVE, 3000, true));
        assertFalse(policy.update(IDLE, 4000, true));
        assertFalse(policy.update(IDLE, 6000, true));
    }
}
