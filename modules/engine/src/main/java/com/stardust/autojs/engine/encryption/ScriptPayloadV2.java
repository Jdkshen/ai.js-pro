package com.stardust.autojs.engine.encryption;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * task-126：**脚本载荷 v2 格式**（唯一实现；纯 JCE，可离线单测）。
 *
 * <pre>
 * 0..5   6 字节魔数 77 01 17 7F 12 12
 * 6..7   2 字节 flags（低=执行模式，高=载荷类型）
 * 8      1 字节 版本 = 0x02（**旧产物第 9 字节不是 0x02 ⇒ 点名拒绝**）
 * 9..11  3 字节 保留 = 0
 * 12..23 12 字节 **随机 GCM IV**（每个文件都不同，绝不复用）
 * 24..   密文 || 16 字节 GCM tag；AAD = 前 12 字节头
 * </pre>
 *
 * <p>密钥 = {@code SHA-256("aijspro-script-key-v2|包名|盐|证书指纹")} 的 **64 个 hex → 解码 = 真 32 字节**。
 *
 * <p><b>原生库结论（task-126 取证）</b>：{@code modules/engine/src/main/cpp/script_crypto.c} 里只有自实现
 * SHA-256 + AES-CBC（gcm/ghash/galois/ctr 命中数 **0**）⇒ **原生不支持 GCM**。因此 v2 **只走 JVM 路径**；
 * 原生只认 v1，遇到第 9 字节 ≠ 0x02 **必须点名拒绝**（不得静默、不得硬上）。
 */
public final class ScriptPayloadV2 {

    public static final byte[] MAGIC = { 0x77, 0x01, 0x17, 0x7F, 0x12, 0x12 };
    public static final byte VERSION = 0x02;
    public static final int IV_LENGTH = 12;
    public static final int TAG_LENGTH_BITS = 128;
    public static final int HEADER_LENGTH = 24;
    /** AAD = 前 12 字节（魔数+flags+版本+保留）。 */
    public static final int AAD_LENGTH = 12;
    public static final int KEY_LENGTH = 32;
    public static final String KEY_PREFIX_V2 = "aijspro-script-key-v2|";

    /** 旧格式（第 9 字节不是 0x02）点名错误文案（原生与 JVM 两侧共用同一句）。 */
    public static final String LEGACY_REJECTED_MESSAGE =
            "旧加密方案已下线（载荷版本字节不是 0x02），请用新版打包器重新打包该工程。";
    /** 原生路径遇到 v2 时的点名错误（原语不足，不得硬上）。 */
    public static final String NATIVE_UNSUPPORTED_MESSAGE =
            "原生解密库不支持 v2（AES-256-GCM）：v2 载荷必须走 JVM 路径；原生只认 v1。";

    private ScriptPayloadV2() {
    }

    /** v2 密钥：SHA-256 的 **64 hex 解码成真 32 字节**（不是把 hex 文本当密钥）。 */
    public static byte[] deriveKeyV2(String packageName, String saltHex, String certificateFingerprint) {
        String material = KEY_PREFIX_V2 + nullToEmpty(packageName) + "|" + nullToEmpty(saltHex) + "|"
                + nullToEmpty(certificateFingerprint);
        byte[] digest = sha256(material.getBytes(StandardCharsets.UTF_8));
        String hex = toHex(digest);
        byte[] key = new byte[KEY_LENGTH];
        for (int i = 0; i < KEY_LENGTH; i++) {
            key[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return key;
    }

    /** 生成 12 字节随机 IV（绝不复用）。 */
    public static byte[] randomIv() {
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        return iv;
    }

    /**
     * 载荷是否是 v2：**三级结构判定**（Lead task-126 拍板）——魔数 ✓ **且** 第 9 字节 == 0x02 **且**
     * 9..11 三个保留字节全为 0。
     *
     * <p>为什么要看保留字节：**v1 头里第 9 字节（offset 8）是 CBC 密文首字节**（v1 没有版本字节，
     * 真机实测 v1 头 12 字节 = {@code 77 01 17 7f 12 12 00 02 <密文首字节…>}）⇒ 旧产物有 **1/256** 的概率
     * 恰好等于 0x02。加上「保留字节必须为 0」这一条，误判概率从 1/256 降到 1/2^32（0x02 + 三个 0 同时出现）；
     * 而且即使真的误判，也只会得到一句「完整性校验失败（也可能是旧格式被误判…）」而**不会**被当成解密失败静默。
     */
    public static boolean isV2(byte[] payload) {
        return payload != null && payload.length > HEADER_LENGTH && hasMagic(payload) && payload[8] == VERSION
                && payload[9] == 0 && payload[10] == 0 && payload[11] == 0;
    }

    /** 是否是「像我们的载荷但不是 v2」（含上面那 1/256 的歧义情形）⇒ 一律按旧格式点名拒绝。 */
    public static boolean isLegacyPayload(byte[] payload) {
        return payload != null && payload.length > 8 && hasMagic(payload) && !isV2(payload);
    }

    /**
     * 打包：header(24) || ciphertext || tag(16)。
     *
     * <p><b>参数语义（Lead 2026-11-10 澄清 + 改名）</b>：第 3 个参数写 {@code header[6]}、第 4 个写 {@code header[7]}，
     * 而 v1 起 flags 就是**大端** {@code byte6*256 + byte7} ⇒ 按 v1 语义「低字节=执行模式、高字节=载荷类型」，
     * 调用方必须传 <b>(payloadType, executionMode)</b>（曾因参数名 {@code flagsLow/flagsHigh} 引起歧义，已改名）。</p>
     */
    public static byte[] encrypt(byte[] plain, byte[] key, int payloadType, int executionMode) throws Exception {
        return encrypt(plain, key, payloadType, executionMode, randomIv());
    }

    /** 打包（指定 IV，仅供测试/确定性场景）。参数语义同上：{@code (payloadType, executionMode)}。 */
    public static byte[] encrypt(byte[] plain, byte[] key, int payloadType, int executionMode, byte[] iv)
            throws Exception {
        requireKey(key);
        if (iv == null || iv.length != IV_LENGTH) {
            throw new IllegalArgumentException("GCM IV 必须 " + IV_LENGTH + " 字节");
        }
        byte[] header = new byte[HEADER_LENGTH];
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        header[6] = (byte) (payloadType & 0xFF);   // 高字节 = 载荷类型
        header[7] = (byte) (executionMode & 0xFF); // 低字节 = 执行模式
        header[8] = VERSION;
        System.arraycopy(iv, 0, header, 12, IV_LENGTH);
        byte[] aad = Arrays.copyOf(header, AAD_LENGTH);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        cipher.updateAAD(aad);
        byte[] sealed = cipher.doFinal(plain);
        byte[] out = new byte[HEADER_LENGTH + sealed.length];
        System.arraycopy(header, 0, out, 0, HEADER_LENGTH);
        System.arraycopy(sealed, 0, out, HEADER_LENGTH, sealed.length);
        return out;
    }

    /** 解包：任何校验失败都抛**可读**的具名错误（GCM tag 不匹配即完整性失败）。 */
    public static byte[] decrypt(byte[] payload, byte[] key) throws Exception {
        requireKey(key);
        if (payload == null || payload.length <= HEADER_LENGTH) {
            throw new IllegalArgumentException("脚本载荷太短：" + (payload == null ? -1 : payload.length));
        }
        if (!hasMagic(payload)) {
            throw new IllegalArgumentException("脚本载荷魔数不匹配（不是本产品的加密载荷）");
        }
        // 三级判定：魔数 ✓ 但「不是 v2」⇒ 一律旧格式点名拒绝（含 1/256 的歧义情形，见 isV2 注释）。
        if (!isV2(payload)) {
            throw new IllegalStateException(LEGACY_REJECTED_MESSAGE);
        }
        byte[] header = Arrays.copyOf(payload, HEADER_LENGTH);
        byte[] iv = Arrays.copyOfRange(payload, 12, 12 + IV_LENGTH);
        byte[] sealed = Arrays.copyOfRange(payload, HEADER_LENGTH, payload.length);
        if (sealed.length < TAG_LENGTH_BITS / 8) {
            throw new IllegalArgumentException("v2 载荷没有完整 GCM tag（长度 " + sealed.length + "）");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        cipher.updateAAD(Arrays.copyOf(header, AAD_LENGTH));
        try {
            return cipher.doFinal(sealed);
        } catch (javax.crypto.AEADBadTagException failure) {
            throw new IllegalStateException("脚本载荷完整性校验失败（GCM tag 不匹配：密文/头部被篡改，或密钥不对——包名/盐/证书指纹不匹配；"
                    + "也可能是旧格式被误判）", failure);
        }
    }

    private static void requireKey(byte[] key) {
        if (key == null || key.length != KEY_LENGTH) {
            throw new IllegalArgumentException("AES-256 密钥必须 " + KEY_LENGTH + " 字节（当前 "
                    + (key == null ? -1 : key.length) + "）");
        }
    }

    private static boolean hasMagic(byte[] payload) {
        if (payload.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (payload[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}