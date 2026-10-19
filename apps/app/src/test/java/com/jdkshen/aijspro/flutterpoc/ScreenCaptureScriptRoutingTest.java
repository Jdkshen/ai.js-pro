package com.jdkshen.aijspro.flutterpoc;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 纯 JVM 能力探测测试。
 *
 * <p>{@code ScreenCaptureScriptRouting} 在 flutterpoc debug 源集，运行本测试需带
 * {@code -PflutterPoc=true}（例如 {@code :app:testMiuixCompatDebugUnitTest}）。
 */
public class ScreenCaptureScriptRoutingTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void detectsGlobalAndNamespacedCaptureEntryPoints() throws Exception {
        assertEquals(Collections.singletonList("requestScreenCapture"),
                apis("if (!requestScreenCapture('portrait')) throw new Error('denied');"));
        assertEquals(Collections.singletonList("requestScreenCapture"),
                apis("images.requestScreenCapture('portrait');"));
        assertEquals(Collections.singletonList("captureScreen"),
                apis("images.captureScreen();"));
        assertEquals(Collections.singletonList("captureScreen"),
                apis("$images.captureScreen();"));
        assertEquals(Collections.singletonList("captureScreen"),
                apis("const frame = captureScreen({ mode: 'full', fresh: false });"));
        assertEquals(Arrays.asList("requestScreenCapture", "captureScreen"),
                apis("$images.requestScreenCapture();\ncaptureScreen();"));
    }

    @Test
    public void detectsSharedAsyncAndWindowSampleApis() throws Exception {
        String source = "var token = images.shareScreenCapture();\n"
                + "images.useSharedScreenCapture(token);\n"
                + "images.getScreenCaptureOptions();\n"
                + "images.isSharedScreenCapture();\n"
                + "images.setImageCaptureCallback(onFrame);\n"
                + "$images.on(\"screen_capture\", function (frame) {});\n"
                + "images.captureWindow({ packageName: 'com.example' });\n"
                + "images.listCaptureWindows();\n"
                + "images.isWindowCaptureAvailable();\n"
                + "images.stopScreenCapture();\n";

        assertEquals(Arrays.asList(
                        "shareScreenCapture",
                        "useSharedScreenCapture",
                        "getScreenCaptureOptions",
                        "isSharedScreenCapture",
                        "setImageCaptureCallback",
                        "$images.on(\"screen_capture\")",
                        "captureWindow",
                        "listCaptureWindows",
                        "isWindowCaptureAvailable",
                        "stopScreenCapture"),
                apis(source));
    }

    @Test
    public void ignoresCommentsDocumentationAndStringContents() throws Exception {
        String source = "/**\n"
                + " * 文档示例：images.requestScreenCapture()、captureScreen()\n"
                + " * 异步：$images.on(\"screen_capture\", fn)\n"
                + " */\n"
                + "// requestScreenCapture();\n"
                + "/* images.captureWindow({}); images.useSharedScreenCapture(token); */\n"
                + "const first = 'captureScreen() / images.requestScreenCapture()';\n"
                + "const second = \"$images.on('screen_capture', fn)\";\n"
                + "const third = `images.captureWindow({})`;\n"
                + "events.on('screen_capture', function () {});\n"
                + "$images.on('other_event', function () {});\n"
                + "console.log(first, second, third);\n";

        assertTrue(apis(source).isEmpty());
    }

    @Test
    public void ignoresOrdinaryImageProcessingCalls() throws Exception {
        String source = "const source = images.read('a.png');\n"
                + "const point = images.findImage(source, template);\n"
                + "const color = images.pixel(source, point.x, point.y);\n"
                + "images.clip(source, 0, 0, 16, 16);\n"
                + "requestScreenCaptureOptions();\n"
                + "captureScreenFrame();\n";

        assertTrue(apis(source).isEmpty());
    }

    @Test
    public void toleratesCommentsAndOptionalChainingBetweenTokens() throws Exception {
        assertEquals(Collections.singletonList("captureScreen"),
                apis("images /* receiver */ . // separator\n captureScreen?.();"));
        assertEquals(Collections.singletonList("$images.on(\"screen_capture\")"),
                apis("$images /* receiver */ . /* method */ on /* call */ "
                        + "(\"screen_capture\", function () {});"));
    }

    @Test
    public void ignoresUnrelatedReceiversAndFunctionDeclarations() throws Exception {
        assertTrue(apis("function captureScreen() { return null; }\n"
                + "camera.captureScreen(); camera?.requestScreenCapture();").isEmpty());
        assertEquals(Collections.singletonList("requestScreenCapture"),
                apis("globalThis.requestScreenCapture();"));
    }

    private List<String> apis(String source) throws Exception {
        File script = temporaryFolder.newFile();
        Files.write(script.toPath(), source.getBytes(StandardCharsets.UTF_8));
        return ScreenCaptureScriptRouting.localCaptureApis(script);
    }
}
