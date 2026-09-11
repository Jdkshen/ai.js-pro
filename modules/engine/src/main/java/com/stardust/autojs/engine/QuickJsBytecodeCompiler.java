package com.stardust.autojs.engine;

import java.io.IOException;

/**
 * QuickJS 字节码编译入口（打包加密等级 ≥ 2 用）。
 *
 * <p>{@link QuickJsNativeBridge} 是包内私有的桥，打包端（应用内 ApkBuilder）需要在这里
 * 拿一个公开入口。编译不依赖运行中的引擎：原生侧临时建 runtime/context 编译完即释放。
 *
 * <p>字节码与 quickjs 版本严格绑定：打包端与运行端必须来自同一份仓库构建，
 * 运行端 {@code JS_ReadObject} 会做格式校验，不匹配时会干净报错而不是崩溃。
 */
public final class QuickJsBytecodeCompiler {

    private QuickJsBytecodeCompiler() {
    }

    public static byte[] compile(String source, String scriptName) throws IOException {
        try {
            byte[] bytecode = QuickJsNativeBridge.compileToBytecode(source, scriptName);
            if (bytecode == null || bytecode.length == 0) {
                throw new IOException("QuickJS 编译没有产出字节码：" + scriptName);
            }
            return bytecode;
        } catch (UnsatisfiedLinkError error) {
            // 该 ABI 没有打包 QuickJS（例如 lite 变体）时给出明确原因，而不是抛 UnsatisfiedLinkError。
            throw new IOException("当前构建不包含 QuickJS 原生库，无法编译脚本", error);
        }
    }
}
