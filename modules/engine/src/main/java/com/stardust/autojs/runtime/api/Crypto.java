package com.stardust.autojs.runtime.api;

import android.util.Base64;

import com.stardust.autojs.annotation.ScriptInterface;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/**
 * Crypto helpers: MD5 / SHA-1 / SHA-256 / HMAC-SHA256 / Base64.
 * Aligns with the Auto.js Pro "消息处理(加密、摘要、编码)" sample category.
 */
public class Crypto {

    @ScriptInterface
    public String md5(String data) {
        return digest("MD5", data);
    }

    @ScriptInterface
    public String sha1(String data) {
        return digest("SHA-1", data);
    }

    @ScriptInterface
    public String sha256(String data) {
        return digest("SHA-256", data);
    }

    public String digest(String algorithm, String data) {
        // Auto.js Pro 的写法是 digest(data, algorithm)，本引擎原来的是 digest(algorithm, data)：
        // 两个参数里只有一个是算法名，按这个特征自动识别顺序，两种写法都能用。
        if (!isAlgorithm(algorithm) && isAlgorithm(data)) {
            return digestInternal(data, algorithm);
        }
        return digestInternal(algorithm, data);
    }

    /**
     * Auto.js Pro 的 `digest(data, algorithm, {output: 'base64'|'hex'})`。
     */
    public String digest(String data, String algorithm, Scriptable options) {
        String output = getOption(options, "output");
        String hex = digestInternal(algorithm, data);
        if ("base64".equalsIgnoreCase(output)) {
            return Base64.encodeToString(hexToBytes(hex), Base64.NO_WRAP);
        }
        return hex;
    }

    private static boolean isAlgorithm(String text) {
        if (text == null) {
            return false;
        }
        String name = text.trim().toUpperCase(Locale.ROOT).replace("_", "-");
        return name.matches("MD[2-5]") || name.matches("SHA-?(1|224|256|384|512)")
                || name.startsWith("SHA3") || name.startsWith("MD5");
    }

    private static String getOption(Scriptable options, String name) {
        if (options == null) {
            return null;
        }
        Object value = ScriptableObject.getProperty(options, name);
        return value == Scriptable.NOT_FOUND || value == null ? null : value.toString();
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private String digestInternal(String algorithm, String data) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] bytes = md.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("digest failed: " + e.getMessage());
        }
    }

    @ScriptInterface
    public String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("hmac failed: " + e.getMessage());
        }
    }

    @ScriptInterface
    public String base64Encode(String data) {
        return Base64.encodeToString(data.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    @ScriptInterface
    public String base64Decode(String data) {
        return new String(Base64.decode(data, Base64.DEFAULT), StandardCharsets.UTF_8);
    }
}
