package com.jdkshen.aijspro.ui.doc;

/** Only bundled documentation may run the offline search script. */
public final class DocumentationUrlPolicy {
    public static final String HOME = "file:///android_asset/docs/api-reference.html";
    public static final String BACK_ACTION = "aijs-docs://back";
    public static final String COPY_MESSAGE = "aijs-docs:copy";
    public static final int MAX_COPY_CHARACTERS = 128 * 1024;
    private static final String PREFIX = "file:///android_asset/docs/";
    public static boolean isLocalDocument(String url) {
        if (url == null || !url.startsWith(PREFIX)) return false;
        String path = url.substring(PREFIX.length()).split("[?#]", 2)[0];
        return !path.isEmpty() && !path.contains("..") && !path.contains("%") && !path.contains("\\");
    }
    public static boolean allowsSearchScript(String url) {
        return url != null && HOME.equals(url.split("[?#]", 2)[0]);
    }
    public static boolean isExternalDocument(String url) {
        return url != null && (url.startsWith("https://") || url.startsWith("http://"));
    }
    public static boolean isCopyRequest(String page, String current, String message) {
        return COPY_MESSAGE.equals(message) && allowsSearchScript(page) && allowsSearchScript(current);
    }
    public static boolean isCopyableCode(String code) {
        return code != null && !code.isEmpty() && code.length() <= MAX_COPY_CHARACTERS;
    }
    private DocumentationUrlPolicy() {}
}
