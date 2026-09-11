package com.jdkshen.aijspro.network.entity;

import java.util.List;

/**
 * 自建更新源（{@code update.json}）的数据结构。
 *
 * <p>放在自己的服务器或局域网共享目录里，App 里把「更新源」填成这个 JSON 的网址即可：
 * 检查更新、下载、校验（SHA-256 + 包名 + 签名）与安装都走内置流程，不依赖 GitHub。
 *
 * <pre>{@code
 * {
 *   "versionCode": 466,
 *   "versionName": "1.0.3",
 *   "releaseNotes": "## 更新内容\n- ...",
 *   "apkUrl": "aijspro-miuix-compat-arm64-v8a.apk",
 *   "apkSha256": "sha256:....",
 *   "deprecated": 0,
 *   "assets": [
 *     { "name": "aijspro-miuix-compat-arm64-v8a.apk", "url": "....apk", "abi": "arm64-v8a" }
 *   ],
 *   "oldVersions": [ { "versionCode": 465, "issues": "..." } ]
 * }
 * }</pre>
 *
 * <p>字段都可以省略，但至少要能给出一套「适用于当前设备」的 APK；{@code apkUrl} 与
 * {@code assets} 里的 {@code url} 都支持相对路径（相对 {@code update.json} 所在目录解析）。
 */
public class UpdateManifest {

    /** 新版本的 versionCode，必须大于当前安装版本的才会提示更新。 */
    public int versionCode;

    /** 新版本号（展示用）。 */
    public String versionName;

    /** 更新说明，支持 Markdown（和 GitHub 源一致）。 */
    public String releaseNotes;

    /** 默认下载地址（相对路径按 update.json 所在目录解析）。 */
    public String apkUrl;

    /** 上面那个 APK 的 SHA-256，可带 {@code sha256:} 前缀；填了就会在下载后强校验。 */
    public String apkSha256;

    /** 小于等于该 versionCode 的安装版本会被判定为「太旧」，0 = 不判定。 */
    public int deprecated;

    /** 多套 APK（按 ABI / compat / lite 区分）；填了它就会按当前设备自动挑一套。 */
    public List<Asset> assets;

    /** 多个历史版本的更新说明（从新到旧）；发布脚本会自动累积写入。 */
    public List<Note> oldVersions;

    /** 更新源里的一个 APK。 */
    public static class Asset {
        /** 文件名，兼容 GitHub 资产命名规则（含 compat/lite 与 ABI 后缀）。 */
        public String name;
        /** 下载地址，支持相对路径。 */
        public String url;
        /** 该 APK 适用的 ABI（arm64-v8a / armeabi-v7a / x86_64 / x86），留空表示通用。 */
        public String abi;
        /** 该 APK 的 SHA-256（可选）。 */
        public String sha256;
    }

    /** 某个历史版本的说明。 */
    public static class Note {
        public int versionCode;
        /** 版本号（展示用，可选）。 */
        public String versionName;
        /** 发布日期，例如 {@code 2026-09-12}（可选）。 */
        public String date;
        /** 该版本的更新说明，支持 Markdown。 */
        public String issues;
    }
}
