package com.stardust.autojs.project;

import com.stardust.util.MD5;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * 打包脚本的密钥派生。
 *
 * <p><b>新方案（推荐）</b>：密钥不再由包名/版本号这类公开字段直接推导，而是
 * {@code SHA-256(前缀 + 包名 + 随机盐 + 签名证书指纹)}。随机盐每个包都不同、
 * 且写进产物自带的 project.json；签名证书指纹把密钥**绑定到打包用的签名身份**——
 * 用别的证书重签（典型的重打包/二次修改）就再也解不开脚本。
 *
 * <p><b>旧方案（兼容）</b>：{@code MD5(packageName + versionName + mainScriptFile)} /
 * {@code MD5(buildId + name)}。老产物里没有盐字段，运行端继续走这条路，保证已发布的
 * 打包应用不会因为升级运行时而打不开。
 */
public final class ScriptKeyDerivation {

    /** 分离密钥与向量的域前缀：同一个盐在两个用途上派生出不同的值。 */
    private static final String KEY_PREFIX = "aijspro-script-key|";
    private static final String VECTOR_PREFIX = "aijspro-script-vec|";

    /** 盐长度（字节）：128 位随机数足够，且能以 32 个十六进制字符写进 project.json。 */
    public static final int SALT_BYTES = 16;

    private ScriptKeyDerivation() {
    }

    /** 生成新的随机盐（十六进制，小写）。 */
    public static String randomSaltHex() {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return toHex(salt);
    }

    /**
     * 新方案密钥：AES-256 的 32 字节密钥（以 64 个十六进制字符表示，取 SHA-256 摘要前 32 字节）。
     */
    public static String deriveKey(String packageName, String saltHex, String certificateFingerprint) {
        String material = KEY_PREFIX + nullToEmpty(packageName) + "|" + nullToEmpty(saltHex) + "|"
                + nullToEmpty(certificateFingerprint);
        return sha256Hex(material).substring(0, 32);
    }

    /** 新方案向量：AES/CBC 的 16 字节 IV（取 SHA-256 摘要前 16 个十六进制字符）。 */
    public static String deriveVector(String saltHex, String certificateFingerprint) {
        String material = VECTOR_PREFIX + nullToEmpty(saltHex) + "|" + nullToEmpty(certificateFingerprint);
        return sha256Hex(material).substring(0, 16);
    }

    /** 旧方案密钥（无盐的老产物）。 */
    public static String legacyKey(String packageName, String versionName, String mainScriptFile) {
        return MD5.md5(nullToEmpty(packageName) + nullToEmpty(versionName) + nullToEmpty(mainScriptFile));
    }

    /** 旧方案向量（无盐的老产物）。 */
    public static String legacyVector(String buildId, String name) {
        return MD5.md5(nullToEmpty(buildId) + nullToEmpty(name)).substring(0, 16);
    }

    /** 证书指纹是否可用于派生（非空且不是占位值）。 */
    public static boolean hasCertificateFingerprint(String fingerprint) {
        return fingerprint != null && fingerprint.length() >= 32;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(text.getBytes("UTF-8")));
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
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
}
