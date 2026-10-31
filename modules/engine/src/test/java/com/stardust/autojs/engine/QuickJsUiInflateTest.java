package com.stardust.autojs.engine;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;

/**
 * QuickJS 公开 {@code ui.inflate} 的 JVM 回归（task-248 / R04）。
 *
 * <p>真正的 QuickJS 要整机才能跑，但 {@code modules/ui-accessibility.js} 里
 * 「参数校验 → parent 归一化 → 调宿主 inflater」这段胶水是纯 JS，用 Rhino + 假宿主就能钉住
 * （手法同 {@link QuickJsPluginsModuleTest}）：假宿主按 QuickJS bootstrap 的真实样子摆
 * {@code ui} / {@code __aiRegisterCallback} / {@code Java} / {@code engines}。
 *
 * <p>这里锁的是公开入口存在、parent/attachToParent 语义、错误处理，以及宿主方法契约
 * （引擎 id → {@code QuickJsHostBridge.forEngine} → {@code uiInflateView(xml, parent, attach)}）。
 */
public class QuickJsUiInflateTest {

    @Test
    public void exposesPublicInflateEntry() throws Exception {
        assertEquals("function", evaluate("typeof ui.inflate;"));
    }

    @Test
    public void inflateGoesThroughTheRunningEngineBridge() throws Exception {
        assertEquals("com.stardust.autojs.engine.QuickJsHostBridge",
                evaluate("ui.inflate('<frame/>'); typedClass;"));
        assertEquals("7", evaluate("ui.inflate('<frame/>'); engineId;"));
    }

    @Test
    public void defaultParentIsNullAndAttachIsFalse() throws Exception {
        assertEquals("1:null:false:<frame/>", evaluate("ui.inflate('<frame/>'); lastCall();"));
    }

    @Test
    public void uiProxyParentIsResolvedByItsId() throws Exception {
        assertEquals("1:root:false:<text/>",
                evaluate("ui.inflate('<text/>', { __id: 'root' }); lastCall();"));
    }

    @Test
    public void stringParentIsForwarded() throws Exception {
        assertEquals("1:someid:false:<frame/>",
                evaluate("ui.inflate('<frame/>', 'someid'); lastCall();"));
    }

    @Test
    public void attachToParentIsForwarded() throws Exception {
        assertEquals("1:null:true:<frame/>",
                evaluate("ui.inflate('<frame/>', null, true); lastCall();"));
        assertEquals("1:root:true:<frame/>",
                evaluate("ui.inflate('<frame/>', { __id: 'root' }, 1); lastCall();"));
    }

    @Test
    public void floatyViewParentUsesItsJavaView() throws Exception {
        assertEquals("1:javaView:false:<frame/>",
                evaluate("ui.inflate('<frame/>', { __floatyView: true, javaView: javaView }); lastCall();"));
    }

    @Test
    public void xmlObjectWithToXMLStringIsAccepted() throws Exception {
        assertEquals("1:null:false:<frame id=\"x\"/>",
                evaluate("ui.inflate({ toXMLString: function () { return '<frame id=\"x\"/>'; } }); lastCall();"));
    }

    @Test
    public void invalidXmlIsRejectedBeforeCallingTheHost() throws Exception {
        assertEquals("type,type,type,type,type|0", evaluate(
                "var messages = [];"
                        + "[null, undefined, 5, '', '   '].forEach(function (bad) {"
                        + "  try { ui.inflate(bad); messages.push('no'); }"
                        + "  catch (e) { messages.push(e instanceof TypeError ? 'type' : 'other'); }"
                        + "});"
                        + "messages.join(',') + '|' + callCount;"));
    }

    @Test
    public void hostFailureIsWrappedWithCallerContext() throws Exception {
        assertEquals("ui.inflate 失败：boom", evaluate(
                "hostError = 'boom'; var message = '';"
                        + "try { ui.inflate('<frame/>'); } catch (e) { message = e.message; }"
                        + "hostError = null; message;"));
    }

    @Test
    public void missingJavaBridgeFailsLoudly() throws Exception {
        assertEquals("ok", evaluate(
                "var saved = Java; Java = null; var message = '';"
                        + "try { ui.inflate('<frame/>'); } catch (e) { message = e.message; }"
                        + "Java = saved; message.indexOf('QuickJS Java 桥') >= 0 ? 'ok' : message;"));
    }

    @Test
    public void returnsTheHostView() throws Exception {
        assertEquals("true", evaluate("(ui.inflate('<frame/>') === fakeView) + '';"));
    }

    private static String evaluate(String script) throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/assets/modules/ui-accessibility.js")), StandardCharsets.UTF_8);
        Context cx = Context.enter();
        try {
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setOptimizationLevel(-1);
            Scriptable scope = cx.initStandardObjects();
            cx.evaluateString(scope, setup(), "setup", 1, null);
            cx.evaluateString(scope, source, "ui-accessibility", 1, null);
            return Context.toString(cx.evaluateString(scope, script, "test", 1, null));
        } finally {
            Context.exit();
        }
    }

    /** 假宿主：ui 全局 / __aiRegisterCallback / Java.type / engines，外加记录调用的 last/callCount。 */
    private static String setup() {
        return String.join("\n",
                "var typedClass = '';",
                "var engineId = -1;",
                "var callCount = 0;",
                "var last = null;",
                "var hostError = null;",
                "var fakeView = { __fakeView: true };",
                "var javaView = { __javaObject: true };",
                "var ui = {};",
                "var __aiRegisterCallback = function () { return 1; };",
                "var engines = { myEngine: function () { return { id: 7 }; } };",
                "var Java = {",
                "  type: function (name) {",
                "    typedClass = String(name);",
                "    return {",
                "      forEngine: function (id) {",
                "        engineId = id;",
                "        return {",
                "          uiInflateView: function (xml, parent, attach) {",
                "            callCount++;",
                "            last = { xml: xml, parent: parent, attach: attach };",
                "            if (hostError) throw new Error(hostError);",
                "            return fakeView;",
                "          }",
                "        };",
                "      }",
                "    };",
                "  }",
                "};",
                "function describeParent(parent) {",
                "  if (parent === null) return 'null';",
                "  if (parent === javaView) return 'javaView';",
                "  return String(parent);",
                "}",
                "function lastCall() {",
                "  return callCount + ':' + describeParent(last.parent) + ':' + last.attach + ':' + last.xml;",
                "}");
    }
}
