package com.jdkshen.aijspro.autojs.build;

import org.junit.Test;

import java.nio.charset.Charset;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 保护等级 2 的 Node 入口载荷：可逆、源码不可读、序言必须保住。
 *
 * <p>没有真实 Android 环境也能覆盖绝大部分风险：这里只依赖 JVM + {@link NodeScriptPayload}，
 * 而它正是 {@code ApkBuilder.writeEntryScript} 在 Node 等级 2 分支调用的那段逻辑。
 */
public class NodeScriptPayloadTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 典型的 Node 工程入口：引擎指令 + UI 模式 + 源码。 */
    private static final String SOURCE = ""
            + "// @engine node24\n"
            + "\"ui\";\n"
            + "const { UiSelector } = require('./lib/selectors');\n"
            + "function greet(name) { return 'hello ' + name; }\n"
            + "console.log(greet('world'));\n";

    // ------------------------------------------------------------------ 序言

    @Test public void keepsEngineDirectiveAndExecutionModeVisible() {
        String prologue = NodeScriptPayload.extractPrologue(SOURCE);
        assertTrue(prologue.contains("// @engine node24"));
        assertTrue(prologue.contains("\"ui\";"));
        assertFalse("代码不能进序言", prologue.contains("require('./lib/selectors')"));
    }

    @Test public void keepsProDirectiveAndDropsLeadingBomFromWrapperBody() {
        String source = "\ufeff\"nodejs\";\nconsole.log(1);\n";
        String prologue = NodeScriptPayload.extractPrologue(source);
        assertTrue(prologue.contains("\"nodejs\";"));
    }

    @Test public void prologueStopsAtFirstCodeLine() {
        String prologue = NodeScriptPayload.extractPrologue("var a = 1;\n\"ui\";\n");
        assertEquals("", prologue);
    }

    @Test public void wrappedTextStartsWithTheSamePrologueSoRuntimeDetectionStillWorks() {
        String wrapped = NodeScriptPayload.wrap(SOURCE, "main.js");
        // JavaScriptSource.engineFromDirective 只看第一行非空内容；这里必须还是引擎指令。
        String firstLine = wrapped.split("\n")[0];
        assertEquals("// @engine node24", firstLine);
        // Node24JavaScriptEngine.parseFeatures 看前 2048 字符里的 "ui" 与 "nodejs"。
        assertTrue(wrapped.substring(0, Math.min(2048, wrapped.length())).contains("\"ui\";"));
    }

    // ------------------------------------------------------------------ 可逆性与不可读性

    @Test public void payloadRoundTripsForAsciiAndUnicodeAndIsNotHumanReadable() {
        // 多字节字符 + U+2028（JS 里当换行）都必须原样穿过 base64 载荷。
        String tail = "console.log('" + '\u4e2d' + "\u6587" + (char) 0x2028 + "');\n";
        String source = SOURCE + tail;
        String wrapped = NodeScriptPayload.wrap(source, "main.js");
        String base64 = NodeScriptPayload.embeddedBase64(wrapped);
        assertNotNull(base64);
        String decoded = new String(Base64.getDecoder().decode(base64), UTF8);
        assertEquals(source, decoded);
        assertFalse("产物里不该还有可读源码", NodeScriptPayload.containsReadableSource(wrapped, source));
    }

    @Test public void chunkedEncodingHandlesPayloadsLargerThanOneChunk() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            builder.append("const value").append(i).append(" = ").append(i).append(";\n");
        }
        String source = builder.toString();
        assertTrue("样例必须超过一个分片", source.length() > 4096 * 3);
        String wrapped = NodeScriptPayload.wrap(source, "main.js");
        String decoded = new String(Base64.getDecoder().decode(
                NodeScriptPayload.embeddedBase64(wrapped)), UTF8);
        assertEquals(source, decoded);
    }

    @Test public void emptySourceStillProducesLoadablePayload() {
        String wrapped = NodeScriptPayload.wrap("", "main.js");
        assertTrue(wrapped.contains("eval(s+'\\n//# sourceURL='"));
        assertEquals("", new String(Base64.getDecoder().decode(
                NodeScriptPayload.embeddedBase64(wrapped)), UTF8));
    }

    @Test public void base64MatchesTheJvmReferenceImplementation() {
        byte[] bytes = new byte[]{(byte) 0xfb, (byte) 0xff, 0x00, 0x10, (byte) 0x80, 0x7f};
        assertEquals(Base64.getEncoder().encodeToString(bytes),
                NodeScriptPayload.base64Of(bytes));
        assertEquals("", NodeScriptPayload.base64Of(new byte[0]));
        assertEquals("Zg==", NodeScriptPayload.base64Of(new byte[]{'f'}));
        assertEquals("Zm8=", NodeScriptPayload.base64Of(new byte[]{'f', 'o'}));
        assertEquals("Zm9v", NodeScriptPayload.base64Of(new byte[]{'f', 'o', 'o'}));
    }

    @Test public void escapesScriptNameIntoTheSourceUrlLiteral() {
        String wrapped = NodeScriptPayload.wrap("1;\n", "it's \"weird\"\n.js");
        assertTrue(wrapped.contains("sourceURL="));
        assertFalse("名字里的换行不能裸写进字符串字面量",
                wrapped.contains("sourceURL=it's"));
    }

    @Test public void proDirectiveGetsARealBindingSoItIsNotADeadStatement() {
        String wrapped = NodeScriptPayload.wrap("\"nodejs\";\nvar a=1;\n", "main.js");
        assertTrue(wrapped.contains("nodejs='nodejs';"));
    }

    @Test public void weirdProDirectiveIsKeptButNotBoundAsIdentifier() {
        String wrapped = NodeScriptPayload.wrap("\"my-plugin v2\";\nvar a=1;\n", "main.js");
        assertTrue(wrapped.contains("\"my-plugin v2\";"));
        assertFalse("含空格的包名不能当标识符绑定", wrapped.contains("my-plugin v2=\""));
    }
}
