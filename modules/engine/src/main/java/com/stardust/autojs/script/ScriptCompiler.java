package com.stardust.autojs.script;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.GeneratedClassLoader;

import java.io.IOException;
import java.util.Map;

/**
 * 把脚本源码编译成 Rhino {@code Script} 实现类的字节码（打包加密等级 ≥ 2 用）。
 *
 * <p>编译产物是标准 Java {@code .class}：里面没有脚本源码文本（{@code setGeneratingSource(false)}
 * 关掉了调试用的源码编码），打包时塞进产物、运行时再交给 {@code AndroidClassLoader}
 * （dx → DexClassLoader）加载执行，逆向者拿到产物读不到原始脚本。
 *
 * <p>实现走 Rhino 自己的编译链（{@code Context.compileString} + 优化等级 ≥ 0），
 * 因为优化器 {@code org.mozilla.javascript.optimizer.Optimizer} 是包内私有、外部无法调用；
 * 想让 Rhino 把生成的类交给我们，就用调用方提供的 {@link ContextFactory} 注入一个
 * 「记录字节 + 正常加载」的 {@link GeneratedClassLoader}（Android 侧见
 * {@code RecordingClassLoader}）。
 *
 * <p>约束：编译端与运行端必须是同一份 Rhino（本仓库由 {@code modules/rhino-language/libs}
 * 统一提供），否则生成的字节码可能无法连接。
 */
public final class ScriptCompiler {

    /** 最高优化等级：与 Rhino 官方 {@code jsc -O} 的上限一致。 */
    public static final int OPTIMIZATION_LEVEL = 9;

    /**
     * 一次编译的产物：入口类名 + 类字节。
     *
     * <p>类名由 Rhino 自己取（由脚本名派生，形如 {@code org.mozilla.javascript.gen.main_js}），
     * 所以必须把它一并带回去：运行时要用这个名字把类加载起来。
     */
    public static final class CompiledClass {
        public final String className;
        public final byte[] bytes;

        CompiledClass(String className, byte[] bytes) {
            this.className = className;
            this.bytes = bytes;
        }
    }

    private ScriptCompiler() {
    }

    /** 把一个 {@link GeneratedClassLoader} 包装成供编译使用的工厂。 */
    public static ContextFactory factoryFor(final GeneratedClassLoader loader) {
        return new ContextFactory() {
            @Override
            protected GeneratedClassLoader createClassLoader(ClassLoader parent) {
                return loader;
            }
        };
    }

    /**
     * 编译脚本，返回入口类的名字与字节。
     *
     * @param source     脚本源码
     * @param scriptName 堆栈里显示的脚本名（如 {@code main.js}），Rhino 会用它派生类名
     * @param factory    编译用的工厂，其 {@link GeneratedClassLoader} 需要把 defineClass
     *                   收到的字节写进 {@code sink}
     * @param sink       由调用方的类加载器填充：类名 → 类字节
     */
    public static CompiledClass compile(String source, String scriptName,
            ContextFactory factory, Map<String, byte[]> sink) throws IOException {
        Context context = factory.enterContext();
        try {
            context.setOptimizationLevel(OPTIMIZATION_LEVEL);
            // 关键：生成 Java 字节码，而不是「解释模式的 AST + 源码副本」。
            // 关掉源码编码，避免产物里出现脚本原文（Rhino 默认会带上供堆栈显示用）。
            context.setGeneratingSource(false);
            context.compileString(source, scriptName, 1, null);
        } finally {
            Context.exit();
        }
        if (sink.isEmpty()) {
            throw new IOException("编译没有产出任何类：scriptName=" + scriptName
                    + "（类加载器是否把 defineClass 的字节写进了 sink？）");
        }
        // 顶层脚本会编译成一个主类（内部函数作为它的方法），因此正常情况下只捕获到一个类。
        String entryName = null;
        byte[] entryBytes = null;
        for (Map.Entry<String, byte[]> entry : sink.entrySet()) {
            if (entryBytes == null) {
                entryName = entry.getKey();
                entryBytes = entry.getValue();
                continue;
            }
            // 万一只捕到多个，优先取 Rhino 为顶层脚本生成的那个（名字里带 gen）。
            if (entry.getKey().contains(".gen.") && !entryName.contains(".gen.")) {
                entryName = entry.getKey();
                entryBytes = entry.getValue();
            }
        }
        if (entryBytes == null || entryBytes.length == 0) {
            throw new IOException("编译产物为空：scriptName=" + scriptName);
        }
        return new CompiledClass(entryName, entryBytes);
    }
}
