package com.stardust.autojs.core.image.capture;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.stardust.autojs.core.image.capture.RecordingNotificationProfile.Match.*;

public class RecordingNotificationProfileTest {
    @Test public void systemRecordingActive() {
        assertEquals(ACTIVE, RecordingNotificationProfile.classify("com.android.systemui", "screen_record", true));
    }
    @Test public void mi8IssueRecordingActive() {
        assertEquals(ACTIVE, RecordingNotificationProfile.classify("com.android.systemui", "issue_record", true));
    }
    @Test public void mi8SavedRecordingIsNotActive() {
        assertEquals(IGNORE, RecordingNotificationProfile.classify("com.android.systemui", "issue_record", false));
    }
    @Test public void systemSavedRecordingIsNotActive() {
        assertEquals(IGNORE, RecordingNotificationProfile.classify("com.android.systemui", "screen_record", false));
    }
    @Test public void unknownOemRecorderNeverLooksIdle() {
        assertEquals(UNKNOWN, RecordingNotificationProfile.classify("com.miui.screenrecorder", "recording", true));
    }
    @Test public void arbitraryAppCannotImpersonateSystemRecorder() {
        assertEquals(UNKNOWN, RecordingNotificationProfile.classify("test.app", "issue_record", true));
        assertEquals(IGNORE, RecordingNotificationProfile.classify("test.app", "screen_record", true));
    }
}
