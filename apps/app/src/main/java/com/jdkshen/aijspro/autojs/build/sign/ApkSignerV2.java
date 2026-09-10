package com.jdkshen.aijspro.autojs.build.sign;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * APK Signature Scheme v2: content digest and signing block generation.
 *
 * <p>The digest is a two level Merkle tree over three sections - the ZIP entries, the central
 * directory and the end of central directory record - split into 1 MiB chunks. Each chunk is
 * hashed as {@code 0xa5 || uint32le(size) || chunk}, and the top level digest is
 * {@code SHA-256(0x5a || uint32le(chunkCount) || chunkDigests)}.
 *
 * <p>Because the digest is taken over the <em>unsigned</em> APK, the central directory offset
 * stored in the end of central directory record is already the position the signing block
 * will occupy, which is exactly what the verifier reconstructs (it treats that field as the
 * signing block offset) - so no placeholder size has to be guessed.
 */
final class ApkSignerV2 {

    /** ID of the APK Signature Scheme v2 block inside the APK Signing Block. */
    private static final int BLOCK_ID = 0x7109871a;

    private static final int CHUNK_SIZE = 1 << 20;

    private static final byte[] MAGIC = {
            'A', 'P', 'K', ' ', 'S', 'i', 'g', ' ', 'B', 'l', 'o', 'c', 'k', ' ', '4', '2'
    };

    private ApkSignerV2() {
    }

    static byte[] computeContentDigest(RandomAccessFile apk, long centralDirOffset,
                                       long centralDirSize, long eocdOffset, long eocdSize)
            throws Exception {
        List<byte[]> chunkDigests = new ArrayList<>();
        addChunkDigests(apk, 0, centralDirOffset, chunkDigests);
        addChunkDigests(apk, centralDirOffset, centralDirSize, chunkDigests);
        addChunkDigests(apk, eocdOffset, eocdSize, chunkDigests);

        MessageDigest top = MessageDigest.getInstance("SHA-256");
        top.update((byte) 0x5a);
        top.update(u32(chunkDigests.size()));
        for (byte[] chunkDigest : chunkDigests) {
            top.update(chunkDigest);
        }
        return top.digest();
    }

    static byte[] buildSigningBlock(SigningKey key, byte[] contentDigest) throws Exception {
        int algorithmId = key.getV2SignatureAlgorithmId();

        byte[] digestElement = concat(u32(algorithmId), lengthPrefixed(contentDigest));
        byte[] digestsSequence = lengthPrefixed(digestElement);

        X509Certificate[] chain = key.getCertificateChain();
        byte[] certificatesSequence = new byte[0];
        for (X509Certificate certificate : chain) {
            certificatesSequence = concat(certificatesSequence, lengthPrefixed(certificate.getEncoded()));
        }

        byte[] signedData = concat(
                lengthPrefixed(digestsSequence),
                lengthPrefixed(certificatesSequence),
                lengthPrefixed(new byte[0]));

        Signature signature = Signature.getInstance(key.getJarSignatureAlgorithm());
        signature.initSign(key.getPrivateKey());
        signature.update(signedData);
        byte[] signatureBytes = signature.sign();

        byte[] signatureElement = concat(u32(algorithmId), lengthPrefixed(signatureBytes));
        byte[] signaturesSequence = lengthPrefixed(signatureElement);

        byte[] signer = concat(
                lengthPrefixed(signedData),
                lengthPrefixed(signaturesSequence),
                lengthPrefixed(key.getCertificate().getPublicKey().getEncoded()));
        byte[] schemeBlock = lengthPrefixed(lengthPrefixed(signer));

        byte[] pair = concat(u64(4L + schemeBlock.length), u32(BLOCK_ID), schemeBlock);
        // "size of block" counts everything after the field itself, i.e. pairs + trailer + magic.
        long sizeField = pair.length + 24L;
        return concat(u64(sizeField), pair, u64(sizeField), MAGIC);
    }

    private static void addChunkDigests(RandomAccessFile apk, long offset, long size,
                                        List<byte[]> out) throws Exception {
        byte[] buffer = new byte[CHUNK_SIZE];
        long position = offset;
        long remaining = size;
        while (remaining > 0) {
            int want = (int) Math.min(buffer.length, remaining);
            apk.seek(position);
            apk.readFully(buffer, 0, want);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((byte) 0xa5);
            digest.update(u32(want));
            digest.update(buffer, 0, want);
            out.add(digest.digest());
            position += want;
            remaining -= want;
        }
    }

    static byte[] lengthPrefixed(byte[] value) {
        return concat(u32(value.length), value);
    }

    static byte[] u32(int value) {
        return new byte[]{
                (byte) value,
                (byte) (value >>> 8),
                (byte) (value >>> 16),
                (byte) (value >>> 24)
        };
    }

    static byte[] u64(long value) {
        return new byte[]{
                (byte) value,
                (byte) (value >>> 8),
                (byte) (value >>> 16),
                (byte) (value >>> 24),
                (byte) (value >>> 32),
                (byte) (value >>> 40),
                (byte) (value >>> 48),
                (byte) (value >>> 56)
        };
    }

    static int readU32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    static void writeU32(byte[] data, int offset, long value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) {
            size += part.length;
        }
        byte[] out = new byte[size];
        int position = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, position, part.length);
            position += part.length;
        }
        return out;
    }

    /** True when the bytes at {@code offset} start with a central directory header signature. */
    static boolean isCentralDirectoryHeader(byte[] data, int offset) {
        return offset + 4 <= data.length
                && data[offset] == 'P' && data[offset + 1] == 'K'
                && data[offset + 2] == 0x01 && data[offset + 3] == 0x02;
    }

    static IOException notAnApk(String detail) {
        return new IOException("打包产物不是有效的 APK（" + detail + "）");
    }
}
