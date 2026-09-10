package com.jdkshen.aijspro.autojs.build.sign;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the v1 (JAR) signature files that live in {@code META-INF/}.
 *
 * <p>Structure mirrors what jarsigner/apksig emit (and what the Android verifier expects):
 * {@code CERT.RSA} is a PKCS#7 SignedData whose signature covers the raw {@code CERT.SF}
 * bytes, {@code CERT.SF} carries the whole-manifest digest, and {@code MANIFEST.MF} carries
 * one SHA-256 section per packaged entry.
 */
final class ApkSignerV1 {

    private static final String MANIFEST_VERSION = "Manifest-Version: 1.0";
    private static final String SIGNATURE_VERSION = "Signature-Version: 1.0";
    private static final String CREATED_BY = "1.0 (Android)";

    /** No line in a JAR manifest may exceed 72 bytes; longer ones continue with a leading space. */
    private static final int MAX_LINE_LENGTH = 72;

    private static final String OID_PKCS7_SIGNED_DATA = "1.2.840.113549.1.7.2";
    private static final String OID_PKCS7_DATA = "1.2.840.113549.1.7.1";

    private ApkSignerV1() {
    }

    static final class ManifestFile {

        final byte[] bytes;
        final Map<String, byte[]> sections;

        ManifestFile(byte[] bytes, Map<String, byte[]> sections) {
            this.bytes = bytes;
            this.sections = sections;
        }
    }

    static ManifestFile buildManifest(SigningKey key, Map<String, byte[]> entryDigests)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLine(out, MANIFEST_VERSION);
        writeLine(out, "Created-By: " + CREATED_BY);
        writeBlankLine(out);

        Map<String, byte[]> sections = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : entryDigests.entrySet()) {
            ByteArrayOutputStream section = new ByteArrayOutputStream();
            writeLine(section, "Name: " + entry.getKey());
            writeLine(section, key.getDigestAttributeName() + ": " + base64(entry.getValue()));
            writeBlankLine(section);
            byte[] sectionBytes = section.toByteArray();
            sections.put(entry.getKey(), sectionBytes);
            out.write(sectionBytes, 0, sectionBytes.length);
        }
        return new ManifestFile(out.toByteArray(), sections);
    }

    static byte[] buildSignatureFile(SigningKey key, ManifestFile manifest, boolean signedWithV2)
            throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLine(out, SIGNATURE_VERSION);
        writeLine(out, "Created-By: " + CREATED_BY);
        writeLine(out, key.getDigestAttributeName() + "-Manifest: " + base64(sha256(manifest.bytes)));
        if (signedWithV2) {
            // Rollback protection: an APK that carries both a v1 and a v2 signature must say so,
            // otherwise a platform that prefers v2 could be tricked into validating it as v1.
            writeLine(out, "X-Android-APK-Signed: 2");
        }
        writeBlankLine(out);

        for (Map.Entry<String, byte[]> entry : manifest.sections.entrySet()) {
            writeLine(out, "Name: " + entry.getKey());
            writeLine(out, key.getDigestAttributeName() + ": " + base64(sha256(entry.getValue())));
            writeBlankLine(out);
        }
        return out.toByteArray();
    }

    static byte[] buildPkcs7Signature(SigningKey key, byte[] signatureFileBytes) throws Exception {
        Signature signature = Signature.getInstance(key.getJarSignatureAlgorithm());
        signature.initSign(key.getPrivateKey());
        signature.update(signatureFileBytes);
        byte[] signed = signature.sign();

        X509Certificate signerCertificate = key.getCertificate();
        byte[] digestAlgorithm = DerWriter.sequence(DerWriter.oid(key.getDigestOid()), DerWriter.NULL);
        byte[] encryptionAlgorithm = DerWriter.sequence(DerWriter.oid(key.getPkcs7EncryptionOid()),
                DerWriter.NULL);
        byte[] issuerAndSerialNumber = DerWriter.sequence(
                signerCertificate.getIssuerX500Principal().getEncoded(),
                DerWriter.integer(signerCertificate.getSerialNumber()));
        byte[] signerInfo = DerWriter.sequence(
                DerWriter.integer(1),
                issuerAndSerialNumber,
                digestAlgorithm,
                encryptionAlgorithm,
                DerWriter.octetString(signed));

        X509Certificate[] chain = key.getCertificateChain();
        byte[][] certificates = new byte[chain.length][];
        for (int i = 0; i < chain.length; i++) {
            certificates[i] = chain[i].getEncoded();
        }

        byte[] signedData = DerWriter.sequence(
                DerWriter.integer(1),
                DerWriter.set(DerWriter.sequence(DerWriter.oid(key.getDigestOid()), DerWriter.NULL)),
                DerWriter.sequence(DerWriter.oid(OID_PKCS7_DATA)),
                DerWriter.implicitSet(0, certificates),
                DerWriter.set(signerInfo));

        return DerWriter.sequence(
                DerWriter.oid(OID_PKCS7_SIGNED_DATA),
                DerWriter.explicit(0, signedData));
    }

    static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static void writeBlankLine(ByteArrayOutputStream out) throws IOException {
        out.write('\r');
        out.write('\n');
    }

    private static void writeLine(ByteArrayOutputStream out, String line) throws IOException {
        byte[] bytes = line.getBytes("UTF-8");
        int position = 0;
        int capacity = MAX_LINE_LENGTH;
        while (true) {
            int take = Math.min(capacity, bytes.length - position);
            out.write(bytes, position, take);
            out.write('\r');
            out.write('\n');
            position += take;
            if (position >= bytes.length) {
                return;
            }
            // Continuation lines start with a single space which counts towards the 72 bytes.
            out.write(' ');
            capacity = MAX_LINE_LENGTH - 1;
        }
    }

    private static String base64(byte[] data) {
        final char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
                .toCharArray();
        StringBuilder builder = new StringBuilder(((data.length + 2) / 3) * 4);
        for (int i = 0; i < data.length; i += 3) {
            int remaining = data.length - i;
            int b0 = data[i] & 0xFF;
            int b1 = remaining > 1 ? data[i + 1] & 0xFF : 0;
            int b2 = remaining > 2 ? data[i + 2] & 0xFF : 0;
            builder.append(alphabet[b0 >>> 2]);
            builder.append(alphabet[((b0 & 0x03) << 4) | (b1 >>> 4)]);
            builder.append(remaining > 1 ? alphabet[((b1 & 0x0F) << 2) | (b2 >>> 6)] : '=');
            builder.append(remaining > 2 ? alphabet[b2 & 0x3F] : '=');
        }
        return builder.toString();
    }

    @SuppressWarnings("unused")
    private static byte[] ascii(String value) throws UnsupportedEncodingException {
        return value.getBytes("US-ASCII");
    }
}
