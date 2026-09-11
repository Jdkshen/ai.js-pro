package com.stardust.autojs.script;

import org.junit.Test;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.GeneratedClassLoader;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 打包编译等级（encryptLevel ≥ 2）用的编译器单测。
 *
 * <p>在 JVM 上直接验证：编译产物是合法 class、能加载、跑出来的结果与源码一致，
 * 而且字节码里不出现脚本源码（否则「编译」等于没做）。
 */
public class ScriptCompilerTest {

    /**
     * 记录字节的类加载器：Rhino 编译时通过 defineClass 把生成的类交给我们，
     * 这里既存下来又正常定义到 JVM，模拟 Android 侧 {@code AndroidClassLoader} 的行为。
     */
    private static final class RecordingLoader extends ClassLoader implements GeneratedClassLoader {
        private final Map<String, byte[]> sink;

        RecordingLoader(Map<String, byte[]> sink) {
            this.sink = sink;
        }

        @Override
        public Class<?> defineClass(String name, byte[] data) {
            sink.put(name, data);
            return super.defineClass(name, data, 0, data.length);
        }

        @Override
        public void linkClass(Class<?> aClass) {
            // JVM 不需要额外动作
        }
    }

    private static ScriptCompiler.CompiledClass compile(String source) throws Exception {
        Map<String, byte[]> sink = new HashMap<>();
        ContextFactory factory = ScriptCompiler.factoryFor(new RecordingLoader(sink));
        return ScriptCompiler.compile(source, "main.js", factory, sink);
    }

    @Test
    public void compiledScriptRunsAndKeepsResult() throws Exception {
        String source = "var out = [];"
                + "for (var i = 0; i < 3; i++) { out.push(i * 2); }"
                + "out.join(',');";

        ScriptCompiler.CompiledClass compiled = compile(source);
        byte[] bytecode = compiled.bytes;

        assertTrue("编译产物不能为空", bytecode.length > 0);
        assertTrue("入口类名不能用空", compiled.className != null && compiled.className.length() > 0);
        // class 文件魔数 CAFEBABE
        assertEquals((byte) 0xCA, bytecode[0]);
        assertEquals((byte) 0xFE, bytecode[1]);
        assertEquals((byte) 0xBA, bytecode[2]);
        assertEquals((byte) 0xBE, bytecode[3]);

        Class<?> clazz = new RecordingLoader(new HashMap<String, byte[]>())
                .defineClass(compiled.className, bytecode);
        Object instance = clazz.newInstance();
        assertTrue("生成物必须实现 Rhino 的 Script 接口", instance instanceof Script);

        Context context = Context.enter();
        try {
            context.setOptimizationLevel(ScriptCompiler.OPTIMIZATION_LEVEL);
            Scriptable scope = context.initStandardObjects();
            Object result = ((Script) instance).exec(context, scope);
            assertEquals("编译后行为必须与源码一致", "0,2,4", String.valueOf(result));
        } finally {
            Context.exit();
        }
    }

    @Test
    public void compiledBytecodeDoesNotContainScriptSource() throws Exception {
        String source = "console.log('UNIQUE_MARKER_STRING_12345');";

        byte[] bytecode = compile(source).bytes;
        String asText = new String(bytecode, StandardCharsets.ISO_8859_1);

        // 字符串常量本身会留在常量池（脚本要用它），但整段源码不能出现：
        // 关掉 setGeneratingSource 就是为了不带上源码编码。
        assertFalse("编译产物里不该出现整段源码", asText.contains(source));
        assertFalse("编译产物里不该出现代码行的原文",
                asText.contains("console.log('UNIQUE_MARKER_STRING_12345');"));
    }

    @Test
    public void classNameComesFromScriptNameAndIsLoadable() throws Exception {
        String source = "var a = 1;";
        ScriptCompiler.CompiledClass first = compile(source);

        assertTrue("类名应当由 Rhino 按脚本名派生", first.className.contains("main_js"));
        assertTrue("类名必须是合法标识符",
                first.className.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"));
        // 同一个类名定义两次（不同加载器）不能报错：运行时会为每次执行新建加载器。
        new RecordingLoader(new HashMap<String, byte[]>()).defineClass(first.className, first.bytes);
        new RecordingLoader(new HashMap<String, byte[]>()).defineClass(first.className, first.bytes);
    }
}

