package com.stardust.autojs.engine.module;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.commonjs.module.ModuleScope;
import org.mozilla.javascript.commonjs.module.ModuleScriptProvider;
import org.mozilla.javascript.commonjs.module.Require;

import java.io.File;

/**
 * 1) 把模块 ID 里的空格编码成 %20（Rhino 解析前）；
 * 2) 给原生 require 一个 baseDir：Rhino 的 Require.call 只有在 thisObj instanceof ModuleScope 时
 *    才会用 getBase()/getUri() 解析 ./ 与 ../，否则直接抛
 *    "Can't resolve relative module ID ... outside of a module"。
 *    我们的顶层作用域是 TopLevelScope extends ImporterTopLevel（不是 ModuleScope），所以 jvm-npm 的 require
 *    一旦解析不到、回落到 NativeRequire 时，相对 ID 必然抛错（`.4` 实测）。
 *    这里在调用父类前，把相对 ID 按 baseDir 规范成绝对路径。
 */
public final class PathAwareRequire extends Require {

    private static final long serialVersionUID = 1L;

    /** 当前脚本所在目录（绝对路径）；null 表示未知，行为与旧版一致。 */
    private volatile String baseDir;

    public PathAwareRequire(Context cx, Scriptable scope, ModuleScriptProvider provider) {
        super(cx, scope, provider, null, null, true);
    }

    /** 每次执行脚本前由引擎写入「脚本自身目录」（含子引擎）。 */
    public void setBaseDir(String baseDir) {
        this.baseDir = baseDir;
    }

    public String getBaseDir() {
        return baseDir;
    }

    @Override
    public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        if (args == null || args.length == 0) {
            return super.call(cx, scope, thisObj, args);
        }
        String moduleId = Context.toString(args[0]);
        // 作用域已经是 ModuleScope 时 Rhino 自己能解析相对 ID，别抢它的活。
        boolean needsRewrite = !(thisObj instanceof ModuleScope);
        String rewritten = needsRewrite ? rewriteRelativeId(baseDir, moduleId) : moduleId;
        String encoded = encodeModuleId(rewritten);
        if (rewritten.equals(moduleId) && encoded.equals(moduleId)) {
            // 参数确实没变 → 直接交给父类（保持旧行为）。
            return super.call(cx, scope, thisObj, args);
        }
        Object[] adapted = args.clone();
        adapted[0] = encoded;
        return super.call(cx, scope, thisObj, adapted);
    }

    static String encodeModuleId(String moduleId) {
        return moduleId == null ? null : moduleId.replace(" ", "%20");
    }

    /**
     * 相对 ID（./ 与 ../ 开头）→ 绝对路径。
     * 非相对 ID、baseDir 为空、或规范化失败时原样返回（绝不抛异常）。
     */
    static String rewriteRelativeId(String baseDir, String moduleId) {
        if (baseDir == null || baseDir.isEmpty() || moduleId == null || moduleId.isEmpty()) return moduleId;
        boolean relative = moduleId.startsWith("./") || moduleId.startsWith("../");
        if (!relative) return moduleId;
        try {
            return new File(baseDir, moduleId).getCanonicalPath();
        } catch (Throwable ignored) {
            try {
                return new File(baseDir, moduleId).getAbsolutePath();
            } catch (Throwable ignoredToo) {
                return moduleId;
            }
        }
    }
}
