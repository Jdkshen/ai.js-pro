package com.stardust.autojs.project;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * 运行端的签名信息：拿当前应用自己的签名证书指纹。
 *
 * <p>格式与打包端 {@code SigningKey.getCertificateFingerprint()} 保持一致
 * （SHA-256、冒号分隔、大写），这样两边的密钥派生与一致性校验才能对上。
 */
public final class AppSignature {

    private AppSignature() {
    }

    /**
     * 当前应用的签名证书指纹；取不到时返回 null（例如被系统裁剪过的环境）。
     */
    public static String certificateFingerprint(Context context) {
        try {
            PackageManager manager = context.getPackageManager();
            String packageName = context.getPackageName();
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = manager.getPackageInfo(packageName,
                        PackageManager.GET_SIGNING_CERTIFICATES);
                signatures = info.signingInfo == null
                        ? null
                        : info.signingInfo.getApkContentsSigners();
            } else {
                //noinspection deprecation
                PackageInfo info = manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
                //noinspection deprecation
                signatures = info.signatures;
            }
            if (signatures == null || signatures.length == 0) {
                return null;
            }
            return fingerprintOf(signatures[0].toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    /** 计算证书 DER 字节的指纹（SHA-256、冒号分隔、大写）。 */
    public static String fingerprintOf(byte[] certificateDer) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificateDer);
            StringBuilder builder = new StringBuilder(digest.length * 3);
            for (byte b : digest) {
                if (builder.length() > 0) {
                    builder.append(':');
                }
                builder.append(String.format(Locale.US, "%02X", b));
            }
            return builder.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
