package com.jdkshen.aijspro.autojs.build.sign;

import com.stardust.autojs.apkbuilder.Signer;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Signs a packaged APK with a user supplied key, producing a JAR (v1) signature plus an APK
 * Signature Scheme v2 block.
 *
 * <p>Until now every packaged APK was signed by tiny-sign's built-in certificate, which is
 * shared by every copy of the library: the app identity was not unique and updates could not
 * be verified against the developer's own key. This signer keeps the same APK contents but
 * replaces the identity with the caller's certificate.
 */
public final class KeyStoreApkSigner implements Signer {

    private static final String MANIFEST_PATH = "META-INF/MANIFEST.MF";
    private static final String SIGNATURE_FILE_PATH = "META-INF/CERT.SF";
    private static final String SIGNATURE_BLOCK_PATH = "META-INF/CERT.RSA";

    private final SigningKey key;
    private final String label;

    public KeyStoreApkSigner(SigningKey key, String label) {
        this.key = key;
        this.label = label;
    }

    public SigningKey getSigningKey() {
        return key;
    }

    public String getLabel() {
        return label;
    }

    public String describe() {
        String subject = key.getSubjectName();
        if (subject.startsWith("CN=")) {
            int end = subject.indexOf(',');
            if (end > 0) {
                subject = subject.substring(0, end);
            }
        }
        return key.getKeyAlgorithm() + " · " + subject;
    }

    @Override
    public void sign(File workspaceDir, File outputApk) throws Exception {
        writeUnsignedApk(workspaceDir, outputApk);
        insertV2Signature(outputApk);
    }

    /**
     * Writes the workspace as an APK including the v1 signature files. The produced file is a
     * valid v1 signed APK; the v2 block is inserted afterwards because it has to be placed
     * between the entries and the central directory.
     */
    private void writeUnsignedApk(File workspaceDir, File outputApk) throws Exception {
        TreeMap<String, File> files = new TreeMap<>();
        TreeSet<String> directories = new TreeSet<>();
        collect(workspaceDir, "", files, directories);

        Map<String, byte[]> entryDigests = new LinkedHashMap<>();
        for (Map.Entry<String, File> entry : files.entrySet()) {
            entryDigests.put(entry.getKey(), sha256Of(entry.getValue()));
        }

        ApkSignerV1.ManifestFile manifest = ApkSignerV1.buildManifest(key, entryDigests);
        byte[] signatureFile = ApkSignerV1.buildSignatureFile(key, manifest, true);
        byte[] pkcs7 = ApkSignerV1.buildPkcs7Signature(key, signatureFile);

        ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(outputApk), 1 << 16));
        try {
            for (String directory : directories) {
                zip.putNextEntry(new ZipEntry(directory));
                zip.closeEntry();
            }
            byte[] buffer = new byte[1 << 16];
            for (Map.Entry<String, File> entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                InputStream in = new FileInputStream(entry.getValue());
                try {
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        zip.write(buffer, 0, read);
                    }
                } finally {
                    in.close();
                }
                zip.closeEntry();
            }
            putEntry(zip, MANIFEST_PATH, manifest.bytes);
            putEntry(zip, SIGNATURE_FILE_PATH, signatureFile);
            putEntry(zip, SIGNATURE_BLOCK_PATH, pkcs7);
        } finally {
            zip.close();
        }
    }

    private static void putEntry(ZipOutputStream zip, String path, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content);
        zip.closeEntry();
    }

    /**
     * Splices the APK Signing Block in front of the central directory and repoints the central
     * directory offset in the end of central directory record at its new location.
     */
    private void insertV2Signature(File apkFile) throws Exception {
        RandomAccessFile apk = new RandomAccessFile(apkFile, "rw");
        try {
            long fileLength = apk.length();
            long eocdOffset = findEndOfCentralDirectory(apk, fileLength);
            int eocdSize = (int) (fileLength - eocdOffset);
            byte[] eocd = new byte[eocdSize];
            apk.seek(eocdOffset);
            apk.readFully(eocd);

            long centralDirOffset = ApkSignerV2.readU32(eocd, 16) & 0xFFFFFFFFL;
            long centralDirSize = ApkSignerV2.readU32(eocd, 12) & 0xFFFFFFFFL;
            if (centralDirOffset + centralDirSize != eocdOffset) {
                throw ApkSignerV2.notAnApk("中央目录偏移量与结尾记录不符");
            }
            byte[] marker = new byte[4];
            apk.seek(centralDirOffset);
            apk.readFully(marker);
            if (!ApkSignerV2.isCentralDirectoryHeader(marker, 0)) {
                throw ApkSignerV2.notAnApk("中央目录签名缺失");
            }

            byte[] centralDir = new byte[(int) centralDirSize];
            apk.seek(centralDirOffset);
            apk.readFully(centralDir);

            // The digest covers the unsigned file, where the central directory offset stored in
            // the EOCD is already the position the signing block is about to occupy.
            byte[] contentDigest = ApkSignerV2.computeContentDigest(
                    apk, centralDirOffset, centralDirSize, eocdOffset, eocdSize);
            byte[] block = ApkSignerV2.buildSigningBlock(key, contentDigest);

            ApkSignerV2.writeU32(eocd, 16, centralDirOffset + block.length);
            apk.seek(centralDirOffset);
            apk.write(block);
            apk.write(centralDir);
            apk.write(eocd);
            apk.setLength(centralDirOffset + block.length + centralDirSize + eocdSize);
        } finally {
            apk.close();
        }
    }

    private static long findEndOfCentralDirectory(RandomAccessFile apk, long fileLength)
            throws IOException {
        int window = (int) Math.min(fileLength, 0xFFFF + 22 + 128);
        byte[] tail = new byte[window];
        apk.seek(fileLength - window);
        apk.readFully(tail);
        for (int i = window - 22; i >= 0; i--) {
            if (tail[i] == 'P' && tail[i + 1] == 'K' && tail[i + 2] == 0x05 && tail[i + 3] == 0x06) {
                int commentLength = (tail[i + 20] & 0xFF) | ((tail[i + 21] & 0xFF) << 8);
                if (i + 22 + commentLength == window) {
                    return fileLength - window + i;
                }
            }
        }
        throw ApkSignerV2.notAnApk("找不到中央目录结尾记录");
    }

    private static void collect(File directory, String prefix, Map<String, File> files,
                                Collection<String> directories) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        java.util.Arrays.sort(children, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return left.getName().compareTo(right.getName());
            }
        });
        for (File child : children) {
            String name = prefix + child.getName();
            if (prefix.length() == 0 && name.startsWith("META-INF")) {
                // The template ships a previous signature; it is replaced by ours.
                continue;
            }
            if (child.isDirectory()) {
                directories.add(name + "/");
                collect(child, name + "/", files, directories);
            } else {
                files.put(name, child);
            }
        }
    }

    private static byte[] sha256Of(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        InputStream in = new FileInputStream(file);
        try {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        } finally {
            in.close();
        }
        return digest.digest();
    }

    @SuppressWarnings("unused")
    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1 << 16];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }
}
