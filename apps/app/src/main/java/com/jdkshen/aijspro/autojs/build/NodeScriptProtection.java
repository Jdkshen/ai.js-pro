package com.jdkshen.aijspro.autojs.build;

import com.stardust.autojs.script.JavaScriptSource;
import com.stardust.autojs.project.ScriptProtection;

/**
 * 「哪个引擎在哪个保护等级下产出什么载荷」的唯一判定处。
 *
 * <p>{@link ApkBuilder} 里分支很多，但真正会变的就是这张表；把它抽出来既避免
 * {@code if/else} 树继续长，也让 JVM 单测能直接断言「等级 × 引擎 → 载荷形态」，
 * 不必启动 Android 环境去跑整个打包流程。
 *
 * <table>
 *   <caption>等级 × 引擎 → 入口载荷</caption>
 *   <tr><th>等级</th><th>Rhino</th><th>QuickJS</th><th>Node 24</th></tr>
 *   <tr><td>0 不加密</td><td colspan="3">源码明文写入 {@code assets/project/}</td></tr>
 *   <tr><td>1 加密</td><td colspan="3">源码 + AES/CBC 密文，文件头载荷类型 = TEXT</td></tr>
 *   <tr><td>2 编译 + 加密</td><td>Rhino class（类型 1）</td><td>QuickJS 字节码（类型 2）</td>
 *       <td><b>自解包加载器</b>（类型仍是 TEXT，见下文）</td></tr>
 * </table>
 *
 * <p>Node 那一格是这份表里唯一「名不副实」的地方：Node 用 V8，而 V8 的 code cache
 * （{@code vm.Script#createCachedData} / {@code NODE_COMPILE_CACHE}）不能跨 V8 版本、
 * 构建标志与平台搬运，生成它还需要在目标运行时里真的跑一次脚本——打包机只有 arm64 的
 * {@code libnode.so} 镜像，没有可执行的 Android Node。所以等级 2 对 Node 产出的是
 * {@link NodeScriptPayload} 的自解包加载器：源码同样不可读、同样 AES 加密，但**不是**
 * 字节码。这个区别必须在日志和文档里说清楚，不能让用户以为拿到了和 Rhino 一样的东西。
 */
final class NodeScriptProtection {

    /** 入口载荷的具体形态（日志与单测用；不参与运行端判定）。 */
    enum PayloadKind {
        /** 源码明文。 */
        PLAIN_TEXT,
        /** 源码 + AES（文件头类型 TEXT）。 */
        ENCRYPTED_TEXT,
        /** Rhino 编译 class + AES。 */
        RHINO_CLASS,
        /** QuickJS 字节码 + AES。 */
        QUICKJS_BYTECODE,
        /** Node 的自解包加载器 + AES（源码不可读，但不是 V8 字节码）。 */
        SOURCE_FREE_TEXT
    }

    private NodeScriptProtection() {
    }

    /**
     * 这个引擎在等级 2 下有没有「真正的编译产物」。
     *
     * <p>只有 Node 返回 false：Rhino 有 {@code ScriptCompiler}（JVM 内可用），
     * QuickJS 有随包的原生编译入口。
     */
    static boolean supportsCompiledPayload(String engineName) {
        return !isNode(engineName);
    }

    /**
     * 等级 2 下这个引擎是否必须拒绝打包。
     *
     * <p>当前没有这种组合：Node 用自解包加载器兜住（可运行、源码不可读），其它引擎有真编译。
     * 保留这个方法是为了让「将来某个引擎既没有编译产物、又不允许降级」有单一落点，
     * 而不是把拒绝逻辑散回 {@link ApkBuilder}。
     */
    static boolean mustRejectCompile(String engineName) {
        return false;
    }

    /**
     * 由等级 + 引擎 + 是否 QuickJS 工程推出入口载荷形态。
     *
     * <p>{@code quickJsEngine} 传 {@code false} 是「明确不是 QuickJS」= Rhino 兼容引擎
     * （{@code ApkBuilder.isQuickJsEngine()} 只在 {@code engine == "quickjs"} 时为 true）；
     * 因此这里不能把「不是 QuickJS」当成 QuickJS 处理。
     */
    static PayloadKind payloadKindOf(int encryptLevel, boolean nodeEntry, boolean quickJsEngine) {
        if (!ScriptProtection.shouldEncrypt(encryptLevel)) {
            return PayloadKind.PLAIN_TEXT;
        }
        if (!ScriptProtection.shouldCompile(encryptLevel)) {
            return PayloadKind.ENCRYPTED_TEXT;
        }
        if (nodeEntry) {
            return PayloadKind.SOURCE_FREE_TEXT;
        }
        return quickJsEngine ? PayloadKind.QUICKJS_BYTECODE : PayloadKind.RHINO_CLASS;
    }

    /** 由引擎名推出载荷形态（打包时引擎名来自 {@code JavaScriptSource.ENGINE_*}）。 */
    static PayloadKind payloadKindOf(int encryptLevel, String engineName, boolean quickJsEngine) {
        return payloadKindOf(encryptLevel, isNode(engineName), quickJsEngine);
    }

    /** 等级 2 + Node 时给日志/界面用的一句话说明（避免把加载器说成字节码）。 */
    static String describe(int encryptLevel, boolean nodeEntry) {
        if (!ScriptProtection.shouldCompile(encryptLevel) || !nodeEntry) {
            return ScriptProtection.describe(encryptLevel);
        }
        return "编译 + 加密（Node 为源码不可读的自解包加载器，不是 V8 字节码）";
    }

    /**
     * 引擎名可能是 {@code JavaScriptSource.ENGINE_NODE24} 全名，也可能是工程配置里的
     * {@code "node24"} / {@code "node"} / {@code "nodejs"} 短名；两者都要认。
     */
    private static boolean isNode(String engineName) {
        if (engineName == null) {
            return false;
        }
        String name = engineName.trim();
        return JavaScriptSource.ENGINE_NODE24.equals(name)
                || "node24".equalsIgnoreCase(name)
                || "node".equalsIgnoreCase(name)
                || "nodejs".equalsIgnoreCase(name);
    }
}
