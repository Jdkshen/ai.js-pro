package com.jdkshen.aijspro.ui.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

/** Performs updater preflight checks before handing an APK to the system installer. */
final class UpdatePackageVerifier {

    private UpdatePackageVerifier() {
    }

    static File verify(Context context, File apk, int expectedVersionCode, String expectedDigest)
            throws IOException, PackageManager.NameNotFoundException {
        if (apk == null || !apk.isFile() || apk.length() == 0) {
            throw new IOException("下载的 APK 文件不存在或为空");
        }
        verifyDigest(apk, expectedDigest);

        PackageManager packageManager = context.getPackageManager();
        PackageInfo archive = packageManager.getPackageArchiveInfo(apk.getAbsolutePath(),
                packageInfoFlags());
        if (archive == null) {
            throw new IOException("下载文件不是有效的 APK");
        }
        if (!context.getPackageName().equals(archive.packageName)) {
            throw new IOException("APK 包名不匹配：" + archive.packageName);
        }
        if (expectedVersionCode > 0 && longVersionCode(archive) < expectedVersionCode) {
            throw new IOException("APK 版本低于更新信息中的版本");
        }

        PackageInfo installed = packageManager.getPackageInfo(context.getPackageName(),
                packageInfoFlags());
        if (!hasMatchingSigner(installed, archive)) {
            throw new IOException("APK 签名与当前安装版本不一致");
        }
        return apk;
    }

    static void verifyDigest(File apk, String expectedDigest) throws IOException {
        String normalized = normalizeSha256(expectedDigest);
        if (normalized == null) {
            return;
        }
        String actual = sha256(apk);
        if (!normalized.equals(actual)) {
            throw new IOException("APK SHA-256 校验失败");
        }
    }

    static String normalizeSha256(String digest) throws IOException {
        if (digest == null || digest.trim().isEmpty()) {
            return null;
        }
        String value = digest.trim().toLowerCase(Locale.US);
        if (value.startsWith("sha256:")) {
            value = value.substring("sha256:".length()).trim();
        }
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IOException("更新信息中的 SHA-256 格式无效");
        }
        return value;
    }

    private static String sha256(File file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("设备不支持 SHA-256", impossible);
        }
        byte[] buffer = new byte[32 * 1024];
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static int packageInfoFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    private static long longVersionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode() : info.versionCode;
    }

    private static boolean hasMatchingSigner(PackageInfo installed, PackageInfo archive) {
        Signature[] installedSigners = signatures(installed);
        Signature[] archiveSigners = signatures(archive);
        if (installedSigners == null || installedSigners.length == 0
                || archiveSigners == null || archiveSigners.length == 0) {
            return false;
        }
        for (Signature installedSigner : installedSigners) {
            for (Signature archiveSigner : archiveSigners) {
                if (Arrays.equals(installedSigner.toByteArray(), archiveSigner.toByteArray())) {
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            return info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        }
        return info.signatures;
    }
}
