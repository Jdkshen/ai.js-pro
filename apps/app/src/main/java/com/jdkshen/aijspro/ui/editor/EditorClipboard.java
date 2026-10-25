package com.jdkshen.aijspro.ui.editor;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import org.json.JSONObject;

/**
 * Android clipboard access for the Ace bridge.
 *
 * <p>The editor's copy/cut/paste actions call these methods synchronously from the
 * WebView bridge thread while the current Ace selection is still alive. Avoid
 * posting the clipboard read/write through the UI queue after dropping the
 * selection: that is exactly how an empty selection reaches the clipboard.</p>
 */
final class EditorClipboard {
    static final String RESULT_OK = "ok";
    static final String RESULT_EMPTY = "empty";
    static final String RESULT_FAILED = "failed";

    private EditorClipboard() {
    }

    /** Empty strings are not useful clipboard content. Whitespace is. */
    static boolean hasText(CharSequence text) {
        return text != null && text.length() > 0;
    }

    static String pastedText(CharSequence text) {
        return hasText(text) ? text.toString() : null;
    }

    static String write(Context context, CharSequence text) {
        if (!hasText(text)) return RESULT_EMPTY;
        ClipboardManager clipboard = clipboard(context);
        if (clipboard == null) return RESULT_FAILED;
        String value = text.toString();
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("AI.js Pro", value));
            return matches(clipboard, value) ? RESULT_OK : RESULT_FAILED;
        } catch (RuntimeException error) {
            return RESULT_FAILED;
        }
    }

    /** Returns a small JSON envelope so null/empty/error are distinguishable in JS. */
    static String readJson(Context context) {
        ClipboardManager clipboard = clipboard(context);
        if (clipboard == null) return failure("unavailable");
        try {
            if (!clipboard.hasPrimaryClip()) return failure("empty");
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return failure("empty");
            ClipData.Item item = clip.getItemAt(0);
            CharSequence text = item.getText();
            if (!hasText(text)) text = item.coerceToText(context);
            if (!hasText(text)) return failure("empty");
            return "{\"ok\":true,\"text\":" + JSONObject.quote(text.toString()) + "}";
        } catch (RuntimeException error) {
            return failure("error");
        }
    }

    private static ClipboardManager clipboard(Context context) {
        if (context == null) return null;
        Object service = context.getSystemService(Context.CLIPBOARD_SERVICE);
        return service instanceof ClipboardManager ? (ClipboardManager) service : null;
    }

    private static boolean matches(ClipboardManager clipboard, String expected) {
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return false;
        CharSequence actual = clip.getItemAt(0).getText();
        return actual != null && expected.contentEquals(actual);
    }

    private static String failure(String reason) {
        return "{\"ok\":false,\"reason\":" + JSONObject.quote(reason) + "}";
    }
}
