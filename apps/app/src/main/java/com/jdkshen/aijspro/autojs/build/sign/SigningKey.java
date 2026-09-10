package com.jdkshen.aijspro.autojs.build.sign;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * A signing key (private key + X.509 chain) plus the algorithm identifiers the APK v1/v2
 * signature formats need.
 */
public final class SigningKey {

    /** Keystore container formats we attempt, in order, when the caller does not pin one. */
    private static final String[] KEYSTORE_TYPES = {"PKCS12", "JKS", "BKS"};

    private final PrivateKey privateKey;
    private final X509Certificate[] certificateChain;

    public SigningKey(PrivateKey privateKey, X509Certificate[] certificateChain) {
        if (privateKey == null || certificateChain == null || certificateChain.length == 0) {
            throw new IllegalArgumentException("private key and certificate chain are required");
        }
        this.privateKey = privateKey;
        this.certificateChain = certificateChain;
    }

    public PrivateKey getPrivateKey() {
        return privateKey;
    }

    public X509Certificate[] getCertificateChain() {
        return certificateChain;
    }

    public X509Certificate getCertificate() {
        return certificateChain[0];
    }

    public String getSubjectName() {
        return getCertificate().getSubjectX500Principal().getName();
    }

    /** Colon separated uppercase SHA-256 fingerprint of the signing certificate. */
    public String getCertificateFingerprint() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(getCertificate().getEncoded());
            StringBuilder builder = new StringBuilder(digest.length * 3);
            for (byte b : digest) {
                if (builder.length() > 0) {
                    builder.append(':');
                }
                builder.append(String.format(Locale.US, "%02X", b));
            }
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public String getKeyAlgorithm() {
        return privateKey.getAlgorithm().toUpperCase(Locale.US);
    }

    private boolean isRsa() {
        return getKeyAlgorithm().contains("RSA");
    }

    /** JCA name used for the JAR (v1) signature over the .SF file. */
    public String getJarSignatureAlgorithm() {
        return isRsa() ? "SHA256withRSA" : "SHA256withECDSA";
    }

    /** APK signature scheme v2 algorithm id: 0x0103 = RSASSA-PKCS1-v1_5 + SHA2-256. */
    public int getV2SignatureAlgorithmId() {
        return isRsa() ? 0x0103 : 0x0201;
    }

    /** OID of the JAR digest algorithm (plain SHA-256, not the combined signature OID). */
    public String getDigestOid() {
        return "2.16.840.1.101.3.4.2.1";
    }

    /** OID used as the PKCS#7 digest-encryption algorithm, mirroring what apksig emits. */
    public String getPkcs7EncryptionOid() {
        return isRsa() ? "1.2.840.113549.1.1.1" : "1.2.840.10045.4.3.2";
    }

    /** Manifest/JAR attribute name that carries the entry digest, e.g. {@code SHA-256-Digest}. */
    public String getDigestAttributeName() {
        return "SHA-256-Digest";
    }

    /**
     * Loads the first usable private key entry from a keystore file.
     *
     * <p>The keystore container type is not always knowable from the file extension, and the
     * JKS implementation is not guaranteed to be present on every Android release, so every
     * candidate type is attempted and the last failure is reported when none of them works.
     */
    public static SigningKey load(File file, String type, char[] storePassword, String alias,
                                  char[] keyPassword) throws GeneralSecurityException, IOException {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        if (type != null && type.trim().length() > 0) {
            types.add(type.trim().toUpperCase(Locale.US));
        }
        for (String candidate : KEYSTORE_TYPES) {
            types.add(candidate);
        }

        Exception last = null;
        List<String> attempted = new ArrayList<>();
        for (String candidateType : types) {
            KeyStore keyStore;
            try {
                keyStore = KeyStore.getInstance(candidateType);
            } catch (GeneralSecurityException e) {
                last = e;
                attempted.add(candidateType + "(不支持)");
                continue;
            }
            try {
                FileInputStream in = new FileInputStream(file);
                try {
                    keyStore.load(in, storePassword);
                } finally {
                    in.close();
                }
                String entryAlias = resolveAlias(keyStore, alias);
                Key key = keyStore.getKey(entryAlias, keyPassword);
                if (!(key instanceof PrivateKey)) {
                    throw new GeneralSecurityException("别名 " + entryAlias + " 不是私钥条目");
                }
                Certificate[] chain = keyStore.getCertificateChain(entryAlias);
                if (chain == null || chain.length == 0) {
                    throw new GeneralSecurityException("别名 " + entryAlias + " 没有证书链");
                }
                List<X509Certificate> certificates = new ArrayList<>();
                for (Certificate certificate : chain) {
                    if (certificate instanceof X509Certificate) {
                        certificates.add((X509Certificate) certificate);
                    }
                }
                if (certificates.isEmpty()) {
                    throw new GeneralSecurityException("证书不是 X.509 格式");
                }
                return new SigningKey((PrivateKey) key,
                        certificates.toArray(new X509Certificate[0]));
            } catch (Exception e) {
                last = e;
                attempted.add(candidateType);
            }
        }
        throw new GeneralSecurityException(
                "无法读取密钥库（已尝试 " + attempted + "）："
                        + (last == null ? "未知错误" : last.getMessage()), last);
    }

    private static String resolveAlias(KeyStore keyStore, String alias)
            throws GeneralSecurityException {
        if (alias != null && alias.trim().length() > 0) {
            String trimmed = alias.trim();
            if (!keyStore.containsAlias(trimmed)) {
                throw new GeneralSecurityException("密钥库中没有别名 " + trimmed);
            }
            return trimmed;
        }
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String candidate = aliases.nextElement();
            if (keyStore.isKeyEntry(candidate)) {
                return candidate;
            }
        }
        throw new GeneralSecurityException("密钥库里没有可用的私钥别名");
    }
}
