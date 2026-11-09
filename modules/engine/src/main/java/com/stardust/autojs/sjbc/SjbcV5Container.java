package com.stardust.autojs.sjbc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * SJBC v5 容器（用户 L3 方案）：HKDF-SHA256 派生双密钥 + AES-256-GCM 双层 AEAD。
 *
 * <p>与用户 PC 工具**逐字段对齐**（三方对拍：{@code pc-tools/pack_v5.js} / {@code m8/native/sjbc_v5.c} / 本类）：
 * <pre>
 * header : 'SJBE'(4) | ver=5 | kdf=1(HKDF-SHA256) | cipher=1(AES-256-GCM) | flags=0
 * body   : ivP(12) | tagP(16) | poolLen(4, big-endian) | poolEnc | ivC(12) | tagC(16) | codeEnc
 * keys   : master = HKDF-SHA256(ikm=certSha256(32B), salt='sjbc-m9-cert', info='master', 32)
 *          kPool  = HKDF-SHA256(master, salt='sjbc-v5-salt', info='pool', 32)
 *          kCode  = HKDF-SHA256(master, salt='sjbc-v5-salt', info='code', 32)
 * pool   : 行式文本 'SJBC5' / 'ENTRIES n o1..on' / 'N i num' | 'S i str'（str 里 \\ 与 \n 已转义）
 * code   : 原始字节码
 * </pre>
 * 两层各自认证 ⇒ 改池或改码都会在对应层被拒（AEAD）。纯 JCE，无 Android 依赖（可 JVM 单测）。
 */
public final class SjbcV5Container {

    public static final String MAGIC = "SJBE";
    public static final int VERSION = 5;
    public static final String SALT_MASTER = "sjbc-m9-cert";
    public static final String INFO_MASTER = "master";
    public static final String SALT_SUB = "sjbc-v5-salt";
    public static final String INFO_POOL = "pool";
    public static final String INFO_CODE = "code";
    private static final int HEADER_BYTES = 8;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private SjbcV5Container() {
    }

    /** 解开的容器：池文本 + 原始字节码 + 池条目。 */
    public static final class Opened {
        public final String poolText;
        public final byte[] code;
        /** 池里的值，下标与字节码里的常量号一致（数字为 Double，字符串为 String）。 */
        public final List<Object> pool;
        /** ENTRIES 声明的常量下标序列。 */
        public final int[] entries;

        Opened(String poolText, byte[] code, List<Object> pool, int[] entries) {
            this.poolText = poolText;
            this.code = code;
            this.pool = pool;
            this.entries = entries;
        }
    }

    /** 解容器；magic/版本/两层 AEAD 任一不符都抛 {@link SjbcException}。 */
    public static Opened open(byte[] blob, byte[] master) {
        if (blob == null || blob.length < HEADER_BYTES) {
            throw new SjbcException("SJBC 容器太短：" + (blob == null ? "null" : blob.length + " 字节"));
        }
        String magic = new String(blob, 0, 4, StandardCharsets.US_ASCII);
        if (!MAGIC.equals(magic)) {
            throw new SjbcException("SJBC magic 不是 " + MAGIC + "：实际 " + magic);
        }
        int version = blob[4] & 0xFF;
        if (version != VERSION) {
            throw new SjbcException("未知 SJBC 容器版本 " + version + "（本实现只支持 " + VERSION + "）");
        }
        int kdf = blob[5] & 0xFF;
        int cipher = blob[6] & 0xFF;
        if (kdf != 1 || cipher != 1) {
            throw new SjbcException("不支持的 kdf/cipher：kdf=" + kdf + " cipher=" + cipher);
        }
        byte[] kPool = hkdfSha256(master, SALT_SUB.getBytes(StandardCharsets.UTF_8),
                INFO_POOL.getBytes(StandardCharsets.UTF_8), 32);
        byte[] kCode = hkdfSha256(master, SALT_SUB.getBytes(StandardCharsets.UTF_8),
                INFO_CODE.getBytes(StandardCharsets.UTF_8), 32);
        int o = HEADER_BYTES;
        byte[] ivP = slice(blob, o, GCM_IV_BYTES, "ivP"); o += GCM_IV_BYTES;
        byte[] tagP = slice(blob, o, 16, "tagP"); o += 16;
        if (o + 4 > blob.length) {
            throw new SjbcException("SJBC 容器在 poolLen 处被截断");
        }
        int poolLen = ((blob[o] & 0xFF) << 24) | ((blob[o + 1] & 0xFF) << 16)
                | ((blob[o + 2] & 0xFF) << 8) | (blob[o + 3] & 0xFF);
        o += 4;
        if (poolLen < 0 || o + poolLen > blob.length) {
            throw new SjbcException("SJBC poolLen 越界：" + poolLen + "（剩余 " + (blob.length - o) + "）");
        }
        byte[] poolEnc = Arrays.copyOfRange(blob, o, o + poolLen); o += poolLen;
        byte[] ivC = slice(blob, o, GCM_IV_BYTES, "ivC"); o += GCM_IV_BYTES;
        byte[] tagC = slice(blob, o, 16, "tagC"); o += 16;
        byte[] codeEnc = Arrays.copyOfRange(blob, o, blob.length);
        byte[] poolPlain;
        byte[] code;
        try {
            poolPlain = gcmDecrypt(kPool, ivP, tagP, poolEnc);
        } catch (GeneralSecurityException e) {
            throw new SjbcException("SJBC 池层解密失败（AEAD 拒绝：证书指纹不对或池被篡改）", e);
        }
        try {
            code = gcmDecrypt(kCode, ivC, tagC, codeEnc);
        } catch (GeneralSecurityException e) {
            throw new SjbcException("SJBC 码层解密失败（AEAD 拒绝：证书指纹不对或码被篡改）", e);
        }
        String poolText = new String(poolPlain, StandardCharsets.UTF_8);
        return parsePool(poolText, code);
    }

    /** 从签名证书 SHA-256（32 字节）派生主密钥。 */
    public static byte[] masterFromCertSha256(byte[] certSha256) {
        if (certSha256 == null || certSha256.length != 32) {
            throw new SjbcException("证书指纹必须是 32 字节（实际 " + (certSha256 == null ? "null" : certSha256.length) + "）");
        }
        return hkdfSha256(certSha256, SALT_MASTER.getBytes(StandardCharsets.UTF_8),
                INFO_MASTER.getBytes(StandardCharsets.UTF_8), 32);
    }

    /** 从 64 位 hex 指纹派生主密钥（与 pc-tools keyderiv.js 同义）。 */
    public static byte[] masterFromCertSha256Hex(String hex) {
        if (hex == null) {
            throw new SjbcException("证书指纹为空");
        }
        String clean = hex.replaceAll("[^0-9a-fA-F]", "").toLowerCase();
        if (clean.length() != 64) {
            throw new SjbcException("证书指纹应为 64 位 hex（实际 " + clean.length() + "）");
        }
        return masterFromCertSha256(hexToBytes(clean));
    }

    /** RFC 5869 HKDF-SHA256（extract + expand）；纯 JCE，不用 API 33 的 KDF。 */
    public static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int outLen) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt == null || salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm == null ? new byte[0] : ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] out = new byte[outLen];
            byte[] block = new byte[0];
            int filled = 0;
            int counter = 1;
            while (filled < outLen) {
                mac.reset();
                mac.update(block);
                if (info != null) {
                    mac.update(info);
                }
                mac.update((byte) counter);
                block = mac.doFinal();
                int n = Math.min(block.length, outLen - filled);
                System.arraycopy(block, 0, out, filled, n);
                filled += n;
                counter++;
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new SjbcException("HKDF-SHA256 不可用", e);
        }
    }

    private static byte[] gcmDecrypt(byte[] key, byte[] iv, byte[] tag, byte[] cipherText)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        ByteArrayOutputStream all = new ByteArrayOutputStream(cipherText.length + tag.length);
        all.write(cipherText, 0, cipherText.length);
        all.write(tag, 0, tag.length);
        return cipher.doFinal(all.toByteArray());
    }

    private static Opened parsePool(String poolText, byte[] code) {
        String[] lines = poolText.split("\n", -1);
        if (lines.length < 2 || !"SJBC5".equals(lines[0])) {
            throw new SjbcException("池文本格式不对：首行应为 SJBC5（实际 "
                    + (lines.length == 0 ? "(空)" : lines[0]) + "）");
        }
        String[] entriesLine = lines[1].trim().split(" ");
        if (entriesLine.length < 2 || !"ENTRIES".equals(entriesLine[0])) {
            throw new SjbcException("池文本格式不对：第 2 行应为 ENTRIES n o1..（实际 " + lines[1] + "）");
        }
        int n = Integer.parseInt(entriesLine[1].trim());
        int[] entries = new int[n];
        for (int i = 0; i < n; i++) {
            entries[i] = Integer.parseInt(entriesLine[2 + i].trim());
        }
        List<Object> pool = new ArrayList<>();
        for (int i = 2; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            int firstSpace = line.indexOf(' ');
            if (firstSpace < 0) {
                throw new SjbcException("池文本行格式不对：" + line);
            }
            String kind = line.substring(0, firstSpace);
            String rest = line.substring(firstSpace + 1);
            int secondSpace = rest.indexOf(' ');
            int index = secondSpace < 0 ? Integer.parseInt(rest.trim())
                    : Integer.parseInt(rest.substring(0, secondSpace).trim());
            String value = secondSpace < 0 ? "" : rest.substring(secondSpace + 1);
            while (pool.size() <= index) {
                pool.add(null);
            }
            if ("N".equals(kind)) {
                pool.set(index, Double.parseDouble(value.trim()));
            } else if ("S".equals(kind)) {
                pool.set(index, unescape(value));
            } else {
                throw new SjbcException("池文本行类型未知（应为 N/S）：" + line);
            }
        }
        return new Opened(poolText, code, pool, entries);
    }

    /** 反转 pack_v5.js 的 esc：\\ → \ ，\n → 换行（单遍扫描，避免二次反转义）。 */
    static String unescape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                sb.append(c);
                continue;
            }
            char next = value.charAt(++i);
            if (next == 'n') {
                sb.append('\n');
            } else if (next == '\\') {
                sb.append('\\');
            } else {
                sb.append(next);
            }
        }
        return sb.toString();
    }

    private static byte[] slice(byte[] blob, int offset, int length, String what) {
        if (offset < 0 || offset + length > blob.length) {
            throw new SjbcException("SJBC 容器在 " + what + " 处被截断（需要 " + length + " 字节，剩余 "
                    + Math.max(0, blob.length - offset) + "）");
        }
        return Arrays.copyOfRange(blob, offset, offset + length);
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}