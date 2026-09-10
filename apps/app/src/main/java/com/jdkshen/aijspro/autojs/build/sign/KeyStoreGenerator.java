package com.jdkshen.aijspro.autojs.build.sign;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * Creates a self-signed signing identity.
 *
 * <p>Most users have no keystore of their own, and the point of custom signing is to stop
 * every packaged app from sharing one public certificate, so the packager can mint a fresh
 * 2048 bit RSA identity and store it as PKCS#12 (the container every Android release supports).
 */
public final class KeyStoreGenerator {

    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    /** OID of sha256WithRSAEncryption, used for both the signature and the certificate. */
    private static final String SIGNATURE_OID = "1.2.840.113549.1.1.11";

    private static final String PASSWORD_ALPHABET =
            "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private KeyStoreGenerator() {
    }

    public static SigningKey generate(String commonName, String organization, String country)
            throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair keyPair = generator.generateKeyPair();

        Date notBefore = new Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000);
        Date notAfter = new Date(System.currentTimeMillis() + 25L * 365 * 24 * 60 * 60 * 1000);

        // Name ::= SEQUENCE OF RelativeDistinguishedName, RDN ::= SET OF AttributeTypeAndValue,
        // so every attribute gets its own SET (a single SET would make them a multi-valued RDN).
        byte[] subject = DerWriter.sequence(
                DerWriter.set(DerWriter.sequence(DerWriter.oid("2.5.4.3"),
                        DerWriter.utf8String(commonName))),
                DerWriter.set(DerWriter.sequence(DerWriter.oid("2.5.4.10"),
                        DerWriter.utf8String(organization))),
                DerWriter.set(DerWriter.sequence(DerWriter.oid("2.5.4.6"), printable(country))));

        byte[] tbsCertificate = DerWriter.sequence(
                DerWriter.explicit(0, DerWriter.integer(2)),          // version v3
                DerWriter.integer(new BigInteger(64, new SecureRandom()).setBit(63)),
                DerWriter.sequence(DerWriter.oid(SIGNATURE_OID), DerWriter.NULL),
                subject,                                              // issuer == subject
                DerWriter.sequence(DerWriter.utcTime(notBefore), DerWriter.utcTime(notAfter)),
                subject,
                keyPair.getPublic().getEncoded());                     // SubjectPublicKeyInfo

        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initSign(keyPair.getPrivate());
        signature.update(tbsCertificate);

        byte[] certificateBytes = DerWriter.sequence(
                tbsCertificate,
                DerWriter.sequence(DerWriter.oid(SIGNATURE_OID), DerWriter.NULL),
                DerWriter.bitString(signature.sign()));

        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certificateBytes));
        return new SigningKey(keyPair.getPrivate(), new X509Certificate[]{certificate});
    }

    /** Stores the identity as a PKCS#12 keystore so it can be reused for future builds. */
    public static void save(SigningKey key, File target, char[] password, String alias)
            throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry(alias, key.getPrivateKey(), password, key.getCertificateChain());
        FileOutputStream out = new FileOutputStream(target);
        try {
            keyStore.store(out, password);
        } finally {
            out.close();
        }
    }

    public static String randomPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder builder = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            builder.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return builder.toString();
    }

    private static byte[] printable(String value) {
        try {
            return DerWriter.tlv(0x13, value.getBytes("US-ASCII"));
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
