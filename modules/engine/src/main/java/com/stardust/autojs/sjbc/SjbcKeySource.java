package com.stardust.autojs.sjbc;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * task-79 片3：SJBC 主密钥的**来源**（本包签名证书 SHA-256）与 manifest 交叉核对。
 *
 * <p>密钥链（与 `pc-tools/keyderiv.js` 同义）：
 * <pre>certSha256 = SHA-256(本包签名证书 DER)
 * master      = HKDF-SHA256(ikm=certSha256, salt='sjbc-m9-cert', info='master', 32)</pre>
 *
 * <p><b>Android 分支</b>：API ≥ 28 用 {@code GET_SIGNING_CERTIFICATES} + {@link SigningInfo}
 * （优先 {@code apkContentsSigners}，回退 {@code signingCertificateHistory}）；API &lt; 28 用已废弃的
 * {@code GET_SIGNATURES} + {@code PackageInfo.signatures}（有 deprecation 警告，分支里处理）。
 *
 * <p><b>与 `assets/sjbc/manifest.json` 的 `cert` 冲突时以**签名证书**为准</b>并打 warning：
 * manifest 是包内可替换的文本，签名证书才是「只有这把证书签出来的包才解得开」的根据。
 */
public final class SjbcKeySource {

    private static final String TAG = "SjbcKeySource";
    public static final String MANIFEST_ASSET = "sjbc/manifest.json";

    private SjbcKeySource() {
    }

    /** 本包签名证书 SHA-256（32 字节）。取不到时抛 {@link SjbcException}（不静默）。 */
    public static byte[] certSha256(Context context) {
        return sha256(primarySignature(context));
    }

    /** 本包签名证书 SHA-256 的 64 位 hex。 */
    public static String certSha256Hex(Context context) {
        return toHex(certSha256(context));
    }

    /** 主密钥 = HKDF(certSha256)。 */
    public static byte[] master(Context context) {
        return SjbcV5Container.masterFromCertSha256(certSha256(context));
    }

    /** 签名证书 DER：API≥28 走 SigningInfo（apkContentsSigners → 历史），旧版本走 signatures。 */
    public static byte[] primarySignature(Context context) {
        String packageName = context.getPackageName();
        PackageManager manager = context.getPackageManager();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
                SigningInfo signingInfo = info.signingInfo;
                if (signingInfo == null) {
                    throw new SjbcException("本包没有签名信息（signingInfo=null）：" + packageName);
                }
                Signature[] signers = signingInfo.hasMultipleSigners()
                        ? signingInfo.getApkContentsSigners() : signingInfo.getSigningCertificateHistory();
                if (signers == null || signers.length == 0) {
                    throw new SjbcException("本包签名证书列表为空：" + packageName);
                }
                return signers[0].toByteArray();
            }
            @SuppressWarnings("deprecation")
            PackageInfo legacy = manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
            @SuppressWarnings("deprecation")
            Signature[] signatures = legacy.signatures;
            if (signatures == null || signatures.length == 0) {
                throw new SjbcException("本包 signatures 为空（API " + Build.VERSION.SDK_INT + "）：" + packageName);
            }
            return signatures[0].toByteArray();
        } catch (PackageManager.NameNotFoundException e) {
            throw new SjbcException("取本包签名失败（找不到包 " + packageName + "）", e);
        }
    }

    /** 读 `assets/sjbc/manifest.json` 的 `cert`（读不到/没这段/格式不对 ⇒ null，不抛）。 */
    public static String manifestCertHex(Context context) {
        try (InputStream in = context.getAssets().open(MANIFEST_ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            org.json.JSONObject json = new org.json.JSONObject(new String(out.toByteArray(), "UTF-8"));
            String cert = json.optString("cert", null);
            return cert == null || cert.trim().isEmpty() ? null : cert.trim().toLowerCase();
        } catch (Throwable error) {
            Log.i(TAG, "读 assets/" + MANIFEST_ASSET + " 失败（忽略 manifest 交叉核对）：" + error);
            return null;
        }
    }

    /** 冲突裁决（纯函数，便于单测）：**以签名证书为准**；不一致时第 2 个返回 true（调用方打 warning）。 */
    public static boolean shouldWarnManifestMismatch(String signingCertHex, String manifestCertHex) {
        if (manifestCertHex == null) {
            return false;
        }
        return signingCertHex == null || !manifestCertHex.equalsIgnoreCase(signingCertHex);
    }

    /** 选出生效的证书指纹：签名证书优先；manifest 不一致时 warning。 */
    public static String resolveCertSha256Hex(Context context) {
        String signing = certSha256Hex(context);
        String manifest = manifestCertHex(context);
        if (shouldWarnManifestMismatch(signing, manifest)) {
            Log.w(TAG, "manifest.cert 与签名证书不一致：manifest=" + manifest + " 签名=" + signing
                    + " ⇒ 以**签名证书**为准（manifest 可被替换）");
        }
        return signing;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new SjbcException("SHA-256 不可用", e);
        }
    }

    static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}