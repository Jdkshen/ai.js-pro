package com.stardust.autojs.script;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * 「脚本载荷嵌进原生库」用的尾部格式。
 *
 * <p>打包时把加密后的脚本载荷（<b>含</b> {@link EncryptedScriptFileHeader} 那份文件头）追加到产物里
 * 各个 ABI 的 {@code libaijscrypto.so} 末尾，再在最后补一段定长 footer：
 *
 * <pre>
 *   [ 原生库原始内容 ][ 脚本载荷（密文） ][ magic "AIJSPv1" ][ 载荷长度(小端 uint32) ][ SHA-256(载荷) ]
 * </pre>
 *
 * <p>这样产物里根本没有脚本文件：解包 APK 只能看到原生库，脚本藏在 ELF 段的后面（加载器只看程序头，
 * 追加的字节不影响加载）。运行端由原生代码读自己的文件尾部取回载荷（见 {@code NativeScriptCrypto}），
 * 长度与摘要都不对就当没有——即被截断/篡改的产物会明确报「脚本数据读不出来」，而不是悄悄跑起来。
 *
 * <p>这个类不依赖 Android，打包端与单元测试共用；native 侧的同名逻辑在
 * {@code modules/engine/src/main/cpp/script_crypto.c}，两边的格式必须一致（真机测试逐字节比对）。
 */
public final class EmbeddedScriptFooter {

    /** magic 长度固定 8 字节，改动它等于换格式，老产物会被判为「没有内嵌脚本」。 */
    private static final byte[] MAGIC = {'A', 'I', 'J', 'S', 'P', 'v', '1', 0};

    /** 长度字段：小端 uint32。 */
    private static final int LENGTH_SIZE = 4;

    /** 摘要：SHA-256。 */
    private static final int DIGEST_SIZE = 32;

    /** footer 总长度：8 + 4 + 32。 */
    public static final int SIZE = MAGIC.length + LENGTH_SIZE + DIGEST_SIZE;

    /** 载荷长度上限（512 MB）：用来挡明显损坏的长度字段，避免按损坏值去分配内存。 */
    private static final long MAX_PAYLOAD_SIZE = 512L * 1024 * 1024;

    private EmbeddedScriptFooter() {
    }

    /**
     * 生成「原生库 + 载荷 + footer」的完整字节。
     *
     * @param library 原生的库内容（通常从工作区读出来）
     * @param payload 加密后的脚本载荷
     */
    public static byte[] embed(byte[] library, byte[] payload) {
        byte[] digest = sha256(payload);
        ByteArrayOutputStream out = new ByteArrayOutputStream(library.length + payload.length + SIZE);
        out.write(library, 0, library.length);
        out.write(payload, 0, payload.length);
        out.write(MAGIC, 0, MAGIC.length);
        out.write((payload.length >>> 0) & 0xFF);
        out.write((payload.length >>> 8) & 0xFF);
        out.write((payload.length >>> 16) & 0xFF);
        out.write((payload.length >>> 24) & 0xFF);
        out.write(digest, 0, digest.length);
        return out.toByteArray();
    }

    /**
     * 从完整文件内容里取回脚本载荷。
     *
     * @return 载荷；没有 footer、长度不合理或摘要对不上时返回 null
     */
    public static byte[] payloadOf(byte[] file) {
        if (file == null || file.length < SIZE) {
            return null;
        }
        int footerStart = file.length - SIZE;
        for (int i = 0; i < MAGIC.length; i++) {
            if (file[footerStart + i] != MAGIC[i]) {
                return null;
            }
        }
        int lengthOffset = footerStart + MAGIC.length;
        long length = (file[lengthOffset] & 0xFFL)
                | ((file[lengthOffset + 1] & 0xFFL) << 8)
                | ((file[lengthOffset + 2] & 0xFFL) << 16)
                | ((file[lengthOffset + 3] & 0xFFL) << 24);
        if (length <= 0 || length > MAX_PAYLOAD_SIZE || length > footerStart) {
            return null;
        }
        int payloadStart = footerStart - (int) length;
        byte[] payload = Arrays.copyOfRange(file, payloadStart, footerStart);
        byte[] expected = Arrays.copyOfRange(file, lengthOffset + LENGTH_SIZE, file.length);
        return Arrays.equals(sha256(payload), expected) ? payload : null;
    }

    /** 文件是否带内嵌脚本载荷。 */
    public static boolean hasPayload(byte[] file) {
        return payloadOf(file) != null;
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
