package com.stardust.autojs.core.image.capture;

/** Metadata classifier, intentionally independent of notification text and Android runtime. */
public final class RecordingNotificationProfile {
    public enum Match { ACTIVE, IGNORE, UNKNOWN }

    public static Match classify(String packageName, String channel, boolean ongoing) {
        if (!ongoing) return Match.IGNORE; // Saved/processing notifications do not keep recording active.
        if ("com.android.systemui".equals(packageName)
                && ("screen_record".equals(channel) || "issue_record".equals(channel))) return Match.ACTIVE;
        if (packageName.contains("screenrecorder") || "issue_record".equals(channel)) return Match.UNKNOWN;
        return Match.IGNORE;
    }
}
