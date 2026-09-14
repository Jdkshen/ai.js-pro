package com.stardust.autojs.execution;

/** Names safe for Android's 15-byte native thread-name limit and proc-file JNI readers. */
public final class ScriptThreadNames {
    private ScriptThreadNames() {
    }

    public static String execution(int executionId) {
        // At most 7 + 8 ASCII bytes, including int overflow into negative IDs.
        return "Script-" + Integer.toHexString(executionId);
    }

    public static String worker(long threadId) {
        // An unsigned long needs at most 13 base-36 digits: 2 + 13 = 15 bytes.
        return "W-" + Long.toUnsignedString(threadId, 36);
    }
}
