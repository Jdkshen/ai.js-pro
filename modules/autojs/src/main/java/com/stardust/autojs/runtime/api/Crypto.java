package com.stardust.autojs.runtime.api;

import android.util.Base64;

import com.stardust.autojs.annotation.ScriptInterface;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
