package com.stardust.autojs.runtime.api;

import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import net.lingala.zip4j.model.UnzipParameters;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.util.Zip4jConstants;

/**
 * PDF §2.35 的 options → zip4j 1.3.2 参数的翻译层。
 *
 * <p>文档原文：「zip 功能来自 zip4j，可阅读相关文档获取更多信息」，选项名与 zip4j 的
 * {@link ZipParameters} 字段一一对应，这里保持同名（大小写不敏感），值也直接沿用
 * `COMP_*` / `ENC_*` / `AES_STRENGTH_*` 这几个字符串常量。
 */
final class ZipSupport {

    private ZipSupport() {
    }

    /** 压缩选项：password / compressionMethod / encryptionMethod / compressionLevel / aesKeyStrength / readHiddenFiles / includeRootFolder / rootFolderInZip。 */
    static ZipParameters zipParameters(Scriptable options) {
        ZipParameters parameters = new ZipParameters();
        String password = stringOption(options, "password");
        String compressionMethod = stringOption(options, "compressionMethod");
        String encryptionMethod = stringOption(options, "encryptionMethod");
        String aesKeyStrength = stringOption(options, "aesKeyStrength");

        if (password != null && !password.isEmpty()) {
            parameters.setPassword(password);
        }
        if (compressionMethod != null) {
            parameters.setCompressionMethod(compressionMethod(compressionMethod));
        }
        if (encryptionMethod != null) {
            parameters.setEncryptionMethod(encryptionMethod(encryptionMethod));
            parameters.setEncryptFiles(parameters.getEncryptionMethod() != Zip4jConstants.ENC_NO_ENCRYPTION);
        } else if (password != null && !password.isEmpty()) {
            // 文档：「如果设置密码但不设置加密方法，则加密方法默认为 AES」
            parameters.setEncryptionMethod(Zip4jConstants.ENC_METHOD_AES);
            parameters.setEncryptFiles(true);
        }
        if (aesKeyStrength != null) {
            parameters.setAesKeyStrength(aesKeyStrength(aesKeyStrength));
        }
        if (parameters.isEncryptFiles()
                && parameters.getEncryptionMethod() == Zip4jConstants.ENC_METHOD_AES
                && parameters.getAesKeyStrength() <= 0) {
            // 文档只说「设置密码但不设置加密方法 → 默认 AES」，没写强度；zip4j 不设强度会直接报
            // `invalid AES key strength`，这里按文档示例（AES_STRENGTH_256）取最强档。
            parameters.setAesKeyStrength(Zip4jConstants.AES_STRENGTH_256);
        }
        Integer level = intOption(options, "compressionLevel");
        if (level != null) {
            parameters.setCompressionLevel(level);
        }
        Boolean readHiddenFiles = boolOption(options, "readHiddenFiles");
        if (readHiddenFiles != null) {
            parameters.setReadHiddenFiles(readHiddenFiles);
        }
        Boolean includeRootFolder = boolOption(options, "includeRootFolder");
        if (includeRootFolder != null) {
            parameters.setIncludeRootFolder(includeRootFolder);
        }
        String rootFolderInZip = stringOption(options, "rootFolderInZip");
        if (rootFolderInZip != null) {
            parameters.setRootFolderInZip(rootFolderInZip);
        }
        return parameters;
    }

    /** 解压选项：只支持 zip4j 暴露的属性开关。 */
    static UnzipParameters unzipParameters(Scriptable options) {
        UnzipParameters parameters = new UnzipParameters();
        Boolean ignoreAttributes = boolOption(options, "ignoreFileAttributes");
        if (ignoreAttributes != null) {
            parameters.setIgnoreAllFileAttributes(ignoreAttributes);
        }
        Boolean ignoreDateTime = boolOption(options, "ignoreDateTimeAttributes");
        if (ignoreDateTime != null) {
            parameters.setIgnoreDateTimeAttributes(ignoreDateTime);
        }
        return parameters;
    }

    static int compressionMethod(String value) {
        String name = normalize(value);
        switch (name) {
            case "COMP_STORE":
            case "STORE":
            case "0":
                return Zip4jConstants.COMP_STORE;
            case "COMP_DEFLATE":
            case "DEFLATE":
            case "8":
                return Zip4jConstants.COMP_DEFLATE;
            default:
                throw new IllegalArgumentException("不支持的 compressionMethod: " + value);
        }
    }

    static int encryptionMethod(String value) {
        String name = normalize(value);
        switch (name) {
            case "ENC_NO_ENCRYPTION":
            case "NO_ENCRYPTION":
                return Zip4jConstants.ENC_NO_ENCRYPTION;
            case "ENC_METHOD_STANDARD":
            case "STANDARD":
                return Zip4jConstants.ENC_METHOD_STANDARD;
            case "ENC_METHOD_AES":
            case "AES":
                return Zip4jConstants.ENC_METHOD_AES;
            default:
                throw new IllegalArgumentException("不支持的 encryptionMethod: " + value);
        }
    }

    static int aesKeyStrength(String value) {
        String name = normalize(value);
        switch (name) {
            case "AES_STRENGTH_128":
            case "128":
                return Zip4jConstants.AES_STRENGTH_128;
            case "AES_STRENGTH_192":
            case "192":
                return Zip4jConstants.AES_STRENGTH_192;
            case "AES_STRENGTH_256":
            case "256":
                return Zip4jConstants.AES_STRENGTH_256;
            default:
                throw new IllegalArgumentException("不支持的 aesKeyStrength: " + value);
        }
    }

    private static String normalize(String value) {
        return value.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
    }

    static String stringOption(Scriptable options, String name) {
        Object value = rawOption(options, name);
        if (value == null) {
            return null;
        }
        if (value instanceof org.mozilla.javascript.Undefined) {
            return null;
        }
        return String.valueOf(value);
    }

    static Integer intOption(Scriptable options, String name) {
        Object value = rawOption(options, name);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof CharSequence) {
            String text = value.toString().trim();
            if (text.isEmpty()) {
                return null;
            }
            return (int) Double.parseDouble(text);
        }
        return null;
    }

    static Boolean boolOption(Scriptable options, String name) {
        Object value = rawOption(options, name);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof CharSequence) {
            String text = value.toString().trim();
            if (text.isEmpty()) {
                return null;
            }
            return Boolean.parseBoolean(text);
        }
        return null;
    }

    private static Object rawOption(Scriptable options, String name) {
        if (options == null) {
            return null;
        }
        Object value = ScriptableObject.getProperty(options, name);
        if (value == null || value == Scriptable.NOT_FOUND) {
            return null;
        }
        if (value instanceof org.mozilla.javascript.Wrapper) {
            value = ((org.mozilla.javascript.Wrapper) value).unwrap();
        }
        return value;
    }
}
