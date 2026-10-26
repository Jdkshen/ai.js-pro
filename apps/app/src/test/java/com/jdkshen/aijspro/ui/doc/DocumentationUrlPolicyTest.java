package com.jdkshen.aijspro.ui.doc;

import org.junit.Test;
import static org.junit.Assert.*;

public class DocumentationUrlPolicyTest {
    @Test public void homeAndLocalChaptersStayInApp() {
        for (String url : new String[]{DocumentationUrlPolicy.HOME,
                "file:///android_asset/docs/files.html#files_read_path",
                "file:///android_asset/docs/mcp-guide.html"}) assertTrue(url, DocumentationUrlPolicy.isLocalDocument(url));
    }
    @Test public void onlyTheBundledSearchPageRunsJavaScript() {
        assertTrue(DocumentationUrlPolicy.allowsSearchScript(DocumentationUrlPolicy.HOME));
        assertTrue(DocumentationUrlPolicy.allowsSearchScript(DocumentationUrlPolicy.HOME + "#files"));
        for (String url : new String[]{null, "file:///android_asset/docs/files.html", "https://example.org/api-reference.html",
                "file:///sdcard/api-reference.html", DocumentationUrlPolicy.HOME + ".other"})
            assertFalse(String.valueOf(url), DocumentationUrlPolicy.allowsSearchScript(url));
    }
    @Test public void localNavigationCannotEscapeTheDocumentationDirectory() {
        for (String url : new String[]{null, "file:///sdcard/a.html", "file:///android_asset/editor/editor.html",
                "file:///android_asset/docs/../editor.html", "file:///android_asset/docs/%2e%2e/editor.html",
                "file:///android_asset/docs/..\\editor.html", "javascript:alert(1)"})
            assertFalse(String.valueOf(url), DocumentationUrlPolicy.isLocalDocument(url));
    }
    @Test public void externalNavigationUsesHttpOrHttpsOnly() {
        assertTrue(DocumentationUrlPolicy.isExternalDocument("https://example.org/docs"));
        assertTrue(DocumentationUrlPolicy.isExternalDocument("http://192.168.10.7/docs"));
        assertFalse(DocumentationUrlPolicy.isExternalDocument("intent://other"));
        assertFalse(DocumentationUrlPolicy.isExternalDocument("file:///data/data/secret"));
    }
    @Test public void clipboardRequiresBothTheRequestAndCurrentPageToBeBundledApi() {
        String home = DocumentationUrlPolicy.HOME + "?theme=dark";
        assertTrue(DocumentationUrlPolicy.isCopyRequest(home, home, DocumentationUrlPolicy.COPY_MESSAGE));
        for (String untrusted : new String[]{null, "file:///android_asset/docs/files.html",
                "https://example.org/api-reference.html", "file:///sdcard/api-reference.html"}) {
            assertFalse(DocumentationUrlPolicy.isCopyRequest(untrusted, home, DocumentationUrlPolicy.COPY_MESSAGE));
            assertFalse(DocumentationUrlPolicy.isCopyRequest(home, untrusted, DocumentationUrlPolicy.COPY_MESSAGE));
        }
        assertFalse(DocumentationUrlPolicy.isCopyRequest(home, home, "other"));
    }
    @Test public void codeCopiesAreBoundedAndNeverSilentlyTruncated() {
        assertFalse(DocumentationUrlPolicy.isCopyableCode(null));
        assertFalse(DocumentationUrlPolicy.isCopyableCode(""));
        assertTrue(DocumentationUrlPolicy.isCopyableCode("const x = '中文';\n  x();\n"));
        char[] max = new char[DocumentationUrlPolicy.MAX_COPY_CHARACTERS];
        java.util.Arrays.fill(max, 'x');
        assertTrue(DocumentationUrlPolicy.isCopyableCode(new String(max)));
        assertFalse(DocumentationUrlPolicy.isCopyableCode(new String(max) + "x"));
    }
}
