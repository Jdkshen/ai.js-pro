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

    /**
     * JS 侧（init.js）把 `$crypto.Key` / `$crypto.KeyPair` / `$crypto.generateKeyPair`
     * 挂到这三个字段上。Rhino 不允许给 Java 对象加它不认识的属性
     * （`Java class ... has no public instance field or method named "Key"`），
     * 所以必须先在 Java 侧声明字段，JS 才写得进来。
     */
    public Object Key;
    public Object KeyPair;
    public Object generateKeyPair;

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

    // ------------------------------------------------------------------
    // Auto.js Pro 风格：$crypto.encrypt / $crypto.decrypt / $crypto.generateKeyPair
    //
    // 契约取自 335 页第一代 API 文档 2.8（第 73~80 页）：
    //   encrypt(data, key, algorithm[, options]) -> any
    //   decrypt(data, key, algorithm[, options]) -> any
    //   generateKeyPair(algorithm[, length])     -> {publicKey, privateKey}
    //   options: input(string|base64|hex|bytes|file) / output(string|base64|hex|bytes|file)
    //            encoding(默认 utf-8) / dest(output=file 时的路径) / iv(AES 的初始化向量)
    // 默认值：input —— 字符串按 string，其余按 bytes；output —— 加解密按 bytes（摘要按 hex）
    // ------------------------------------------------------------------

    /**
     * 只做"取 java.security.Key"这一件事；JS 侧再包装成 $crypto.Key。
     * 单独暴露成 `__` 前缀，避免与文档里的 generateKeyPair 同名冲突。
     */
    @ScriptInterface
    public java.security.KeyPair __generateKeyPair(String algorithm, Object length) {
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance(algorithm);
            int size = 0;
            if (length != null && !(length instanceof org.mozilla.javascript.Undefined)) {
                size = toInt(length);
            }
            if (size > 0) {
                generator.initialize(size);
            }
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("generateKeyPair failed: " + e.getMessage());
        }
    }

    @ScriptInterface
    public Object encrypt(Object data, Object key, String algorithm) {
        return encrypt(data, key, algorithm, null);
    }

    @ScriptInterface
    public Object encrypt(Object data, Object key, String algorithm, Scriptable options) {
        return doCipher(javax.crypto.Cipher.ENCRYPT_MODE, data, key, algorithm, options);
    }

    @ScriptInterface
    public Object decrypt(Object data, Object key, String algorithm) {
        return decrypt(data, key, algorithm, null);
    }

    @ScriptInterface
    public Object decrypt(Object data, Object key, String algorithm, Scriptable options) {
        return doCipher(javax.crypto.Cipher.DECRYPT_MODE, data, key, algorithm, options);
    }

    private Object doCipher(int mode, Object data, Object key, String algorithm, Scriptable options) {
        if (algorithm == null || algorithm.trim().isEmpty()) {
            throw new RuntimeException("encrypt/decrypt 需要 algorithm，例如 \"AES\"、\"RSA/ECB/PKCS1Padding\"");
        }
        String cipherName = normalizeAlgorithm(algorithm.trim());
        boolean decrypting = mode == javax.crypto.Cipher.DECRYPT_MODE;
        // 输入：默认字符串按 string、其余按 bytes
        String input = getOption(options, "input");
        byte[] source = toBytes(data, input != null ? input : (unwrapJava(data) instanceof CharSequence ? "string" : "bytes"),
                optionOrDefault(options, "encoding", "utf-8"));
        java.security.Key javaKey = resolveKey(key, cipherName);
        try {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(cipherName);
            java.security.spec.AlgorithmParameterSpec iv = resolveIv(options, cipherName);
            if (iv != null) {
                cipher.init(mode, javaKey, iv);
            } else {
                cipher.init(mode, javaKey);
            }
            byte[] out = cipher.doFinal(source);
            return fromBytes(out, optionOrDefault(options, "output", "bytes"), options);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException((decrypting ? "decrypt" : "encrypt") + " failed: " + e.getMessage());
        }
    }

    /** JCE 不认识裸的 "AES"/"RSA"，按文档补上默认模式与填充。 */
    private static String normalizeAlgorithm(String algorithm) {
        String upper = algorithm.toUpperCase(Locale.ROOT);
        if (upper.equals("AES")) {
            return "AES/ECB/PKCS5Padding";
        }
        if (upper.equals("RSA")) {
            return "RSA/ECB/PKCS1Padding";
        }
        return algorithm;
    }

    /**
     * key 自己的格式由 key 自己决定：`new $crypto.KeyPair(pub, priv, {input:'base64'})`
     * 用 key 上的 input；直接传字符串/字节数组则按类型推断。**不能继承 data 的 input 选项** ——
     * `{input:'base64'}` 描述的是密文，把它套到密钥上会把 16 字符的 AES 密钥解成 12 字节。
     */
    private java.security.Key resolveKey(Object key, String cipherName) {
        String family = cipherName.split("/")[0].toUpperCase(Locale.ROOT);
        Object raw = key;
        String rawInput = null;
        if (key instanceof Scriptable) {
            Scriptable scriptable = (Scriptable) key;
            Object inner = unwrapJava(ScriptableObject.getProperty(scriptable, "__key__"));
            if (inner instanceof java.security.Key) {
                return (java.security.Key) inner;
            }
            Object data = unwrapJava(ScriptableObject.getProperty(scriptable, "data"));
            if (data != null && data != Scriptable.NOT_FOUND) {
                raw = data;
                rawInput = getOption(scriptable, "input");
            }
        }
        raw = unwrapJava(raw);
        if (raw instanceof java.security.Key) {
            return (java.security.Key) raw;
        }
        byte[] bytes = toBytes(raw, rawInput != null ? rawInput : (raw instanceof CharSequence ? "string" : "bytes"), "utf-8");
        if (family.startsWith("RSA") || family.startsWith("EC") || family.startsWith("DSA")) {
            String algorithm = family.startsWith("EC") ? "EC" : family.startsWith("DSA") ? "DSA" : "RSA";
            java.security.KeyFactory factory;
            try {
                factory = keyFactoryFor(algorithm);
            } catch (Exception e) {
                throw new RuntimeException("找不到 " + algorithm + " 的 KeyFactory：" + e.getMessage());
            }
            try {
                return factory.generatePublic(new java.security.spec.X509EncodedKeySpec(bytes));
            } catch (Exception publicFailure) {
                try {
                    return factory.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(bytes));
                } catch (Exception privateFailure) {
                    throw new RuntimeException("无法从传入的 key 构造 " + family + " 密钥（" + bytes.length
                            + " 字节；provider=" + factory.getProvider().getName()
                            + "；public=" + publicFailure.getMessage()
                            + "；private=" + privateFailure.getMessage() + "）");
                }
            }
        }
        try {
            return new SecretKeySpec(bytes, family);
        } catch (Exception e) {
            throw new RuntimeException("无法从传入的 key 构造 " + family + " 密钥：" + e.getMessage());
        }
    }

    /**
     * 设备上 AndroidKeyStore 也注册了 RSA/EC 的 KeyFactory，但它只吃
     * KeyGenParameterSpec 生成的密钥（否则报「To generate a key pair in Android Keystore…」），
     * 所以先把这类 provider 跳过去。
     */
    private static java.security.KeyFactory keyFactoryFor(String algorithm) throws Exception {
        for (java.security.Provider provider : java.security.Security.getProviders()) {
            if (provider.getName().contains("AndroidKeyStore")) {
                continue;
            }
            try {
                return java.security.KeyFactory.getInstance(algorithm, provider);
            } catch (Exception ignored) {
                // 这个 provider 没有该算法的 KeyFactory，继续找
            }
        }
        return java.security.KeyFactory.getInstance(algorithm);
    }

    private static java.security.spec.AlgorithmParameterSpec resolveIv(Scriptable options, String cipherName) {
        String iv = getOption(options, "iv");
        Object raw = options == null ? null : ScriptableObject.getProperty(options, "iv");
        if (raw == Scriptable.NOT_FOUND || raw == null) {
            return null;
        }
        raw = unwrapJava(raw);
        byte[] bytes = raw instanceof CharSequence
                ? ((CharSequence) raw).toString().getBytes(StandardCharsets.UTF_8)
                : toPrimitiveBytes(raw);
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        return new javax.crypto.spec.IvParameterSpec(bytes);
    }

    /**
     * Rhino 把 Java 对象/数组包成 NativeJavaObject 传进 Java 方法，直接 `instanceof`
     * 判不出真实类型（例：`pair.getPublic()` 是包装对象，不是 java.security.Key）。
     */
    private static Object unwrapJava(Object value) {
        Object current = value;
        for (int i = 0; i < 4 && current instanceof org.mozilla.javascript.Wrapper; i++) {
            Object inner = ((org.mozilla.javascript.Wrapper) current).unwrap();
            if (inner == null || inner == current) {
                break;
            }
            current = inner;
        }
        return current;
    }

    /**
     * Rhino 里的数字都是 Double（`2048` 会变成 "2048.0"），不能直接 Integer.parseInt。
     */
    private static int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof CharSequence) {
            String text = value.toString().trim();
            if (text.isEmpty()) {
                return 0;
            }
            return (int) Double.parseDouble(text);
        }
        return 0;
    }

    private static byte[] toPrimitiveBytes(Object value) {
        value = unwrapJava(value);
        if (value instanceof byte[]) {
            return (byte[]) value;
        }
        if (value instanceof Object[]) {
            Object[] array = (Object[]) value;
            byte[] out = new byte[array.length];
            for (int i = 0; i < array.length; i++) {
                out[i] = (byte) ((Number) org.mozilla.javascript.Context.jsToJava(array[i], Double.class)).intValue();
            }
            return out;
        }
        if (value instanceof Scriptable) {
            Scriptable scriptable = (Scriptable) value;
            Object lengthValue = ScriptableObject.getProperty(scriptable, "length");
            if (lengthValue instanceof Number) {
                int length = ((Number) lengthValue).intValue();
                byte[] out = new byte[length];
                for (int i = 0; i < length; i++) {
                    Object item = ScriptableObject.getProperty(scriptable, i);
                    out[i] = (byte) org.mozilla.javascript.Context.toNumber(item);
                }
                return out;
            }
        }
        return null;
    }

    private byte[] toBytes(Object data, String input, String encoding) {
        data = unwrapJava(data);
        if (data == null || data instanceof org.mozilla.javascript.Undefined) {
            throw new RuntimeException("encrypt/decrypt 的 data 不能为空");
        }
        String charset = encoding == null || encoding.isEmpty() ? "utf-8" : encoding;
        if ("bytes".equalsIgnoreCase(input)) {
            byte[] primitive = toPrimitiveBytes(data);
            if (primitive != null) {
                return primitive;
            }
        }
        if ("file".equalsIgnoreCase(input)) {
            try {
                return java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(String.valueOf(data)));
            } catch (Exception e) {
                throw new RuntimeException("读取输入文件失败：" + e.getMessage());
            }
        }
        if (data instanceof byte[]) {
            return (byte[]) data;
        }
        String text = String.valueOf(data);
        if ("base64".equalsIgnoreCase(input)) {
            return java.util.Base64.getDecoder().decode(text.trim());
        }
        if ("hex".equalsIgnoreCase(input)) {
            return hexToBytes(text.trim());
        }
        try {
            return text.getBytes(charset);
        } catch (java.io.UnsupportedEncodingException e) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
    }

    private Object fromBytes(byte[] out, String output, Scriptable options) {
        String type = output == null || output.isEmpty() ? "bytes" : output;
        if ("base64".equalsIgnoreCase(type)) {
            return java.util.Base64.getEncoder().encodeToString(out);
        }
        if ("hex".equalsIgnoreCase(type)) {
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        }
        if ("string".equalsIgnoreCase(type)) {
            try {
                return new String(out, optionOrDefault(options, "encoding", "utf-8"));
            } catch (java.io.UnsupportedEncodingException e) {
                return new String(out, StandardCharsets.UTF_8);
            }
        }
        if ("file".equalsIgnoreCase(type)) {
            String dest = getOption(options, "dest");
            if (dest == null) {
                throw new RuntimeException("output 为 file 时必须指定 dest");
            }
            try {
                java.nio.file.Files.write(java.nio.file.Paths.get(dest), out);
            } catch (Exception e) {
                throw new RuntimeException("写出文件失败：" + e.getMessage());
            }
            return dest;
        }
        return out;
    }

    private static String optionOrDefault(Scriptable options, String name, String fallback) {
        String value = getOption(options, name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String option(Scriptable options, String name) {
        return getOption(options, name);
    }
}
