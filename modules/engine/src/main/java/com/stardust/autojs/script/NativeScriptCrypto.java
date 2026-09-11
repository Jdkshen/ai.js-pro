package com.stardust.autojs.script;

import java.io.IOException;

/**
 * 脚本解密的原生入口（{@code libaijscrypto.so}）。
 *
 * <p>把「密钥派生 + AES-256-CBC 解密 + 去填充」搬到原生侧，Java 只递进密文与
 * 派生材料（包名 / 随机盐 / 签名指纹），拿回明文。这样反编译 dex 看不到密钥派生过程，
 * 也不能靠 hook Java 侧的 {@code Cipher} 直接捞明文与密钥。
 *
 * <p>库不存在（例如被裁剪的 ABI 或原生库没打进产物）时 {@link #isAvailable()} 为 false，
 * 调用方应回退到 JVM 的 {@link com.stardust.autojs.engine.encryption.ScriptEncryption}，
 * 保证打包应用在任何环境下都能跑起来。
 */
public final class NativeScriptCrypto {

    private static final String LIBRARY = "aijscrypto";

    /** 原生库是否可用（构造时探测一次，失败不抛异常）。 */
    private static final boolean AVAILABLE = loadLibrary();

    private NativeScriptCrypto() {
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    private static boolean loadLibrary() {
        try {
            System.loadLibrary(LIBRARY);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    /**
     * 原生实现的密码学自检（SHA-256 与 AES-256-CBC 的 NIST 标准向量）。
     *
     * @return 空字符串表示通过，否则是失败说明；原生库不可用时返回提示文本。
     */
    public static String selfTest() {
        if (!AVAILABLE) {
            return "lib" + LIBRARY + ".so 未加载";
        }
        return nativeSelfTest();
    }

    /**
     * 按「随机盐 + 签名指纹」方案解密一段打包脚本。
     *
     * @param data        完整文件（含加密头），只有 {@code [offset, offset + length)} 是密文
     * @param offset      密文起始位置
     * @param length      密文长度（应为 16 的整数倍）
     * @param packageName 产物包名
     * @param salt        产物里的随机盐（十六进制）
     * @param fingerprint 产物里的签名证书指纹
     * @return 明文；参数或填充非法时抛 {@link IOException}
     */
    public static byte[] decrypt(byte[] data, int offset, int length,
                                 String packageName, String salt, String fingerprint) throws IOException {
        if (!AVAILABLE) {
            throw new IOException("lib" + LIBRARY + ".so 未加载，无法用原生方式解密");
        }
        try {
            return nativeDecrypt(data, offset, length, packageName, salt, fingerprint);
        } catch (IllegalArgumentException error) {
            throw new IOException("原生解密失败：" + error.getMessage(), error);
        }
    }

    /**
     * 读取「嵌在本库尾部」的脚本载荷（打包时的 {@code scriptStorage=native} 形态）。
     *
     * <p>原生代码用 {@code dladdr} 找到自己的 .so 路径，读文件末尾的 footer
     * （magic + 长度 + SHA-256）并校验后返回载荷。产物里因此没有脚本文件。
     *
     * @return 加密载荷（含文件头）；库不可用、没有 footer 或校验不过时返回 null
     */
    public static byte[] readEmbeddedPayload() {
        if (!AVAILABLE) {
            return null;
        }
        return nativeReadEmbeddedPayload();
    }

    private static native byte[] nativeDecrypt(byte[] data, int offset, int length,
                                               String packageName, String salt, String fingerprint);

    /** 读取嵌在自身 .so 尾部的脚本载荷（没有时返回 null）。 */
    private static native byte[] nativeReadEmbeddedPayload();

    private static native String nativeSelfTest();
}
