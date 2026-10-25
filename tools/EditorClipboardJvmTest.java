package com.jdkshen.aijspro.ui.editor;

/**
 * Offline JVM checks for the pure part of the editor clipboard bridge.
 * Android ClipboardManager itself still requires a device/instrumentation test.
 */
public final class EditorClipboardJvmTest {
    private static int checks;

    public static void main(String[] args) {
        check(!EditorClipboard.hasText(null), "null has no copyable text");
        check(!EditorClipboard.hasText(""), "empty string has no copyable text");
        check(EditorClipboard.hasText(" "), "whitespace is copyable");
        check(EditorClipboard.hasText("中文"), "non-ASCII text is copyable");
        check(EditorClipboard.pastedText(null) == null, "null paste is rejected");
        check(EditorClipboard.pastedText("") == null, "empty paste is rejected");
        check("a\r\nb".equals(EditorClipboard.pastedText("a\r\nb")), "newlines are preserved");
        check("\uD83D\uDE00".equals(EditorClipboard.pastedText("\uD83D\uDE00")), "emoji is preserved");
        check("ok".equals(EditorClipboard.RESULT_OK), "ok status matches the JS bridge contract");
        check("empty".equals(EditorClipboard.RESULT_EMPTY), "empty status matches the JS bridge contract");
        check("failed".equals(EditorClipboard.RESULT_FAILED), "failed status matches the JS bridge contract");
        System.out.println("PASS: " + checks + " JVM clipboard policy checks");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
