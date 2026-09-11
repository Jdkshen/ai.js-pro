package com.jdkshen.aijspro.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;
import android.text.TextUtils;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.jakewharton.retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import com.jdkshen.aijspro.BuildConfig;
import com.jdkshen.aijspro.Pref;
import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.network.api.UpdateCheckApi;
import com.jdkshen.aijspro.network.entity.GitHubRelease;
import com.jdkshen.aijspro.network.entity.UpdateManifest;
import com.jdkshen.aijspro.network.entity.VersionInfo;
import com.jdkshen.aijspro.tool.SimpleObserver;
import com.stardust.util.NetworkUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.reactivex.Observable;
import io.reactivex.annotations.NonNull;
import io.reactivex.schedulers.Schedulers;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Created by Stardust on 2017/9/20.
 */

public class VersionService {

    private static final String KEY_DEPRECATED = "KEY_DEPRECATED";
    private static final String KEY_DEPRECATED_VERSION_CODE = "KEY_DEPRECATED_VERSION_CODE";
    private static final Pattern VERSION_CODE_IN_BODY = Pattern.compile(
            "(?im)^\\s*versionCode\\s*[:=]\\s*(\\d+)\\s*$");
    private static final Pattern VERSION_CODE_IN_TAG = Pattern.compile(
            "(?:\\+|[-_.]vc)(\\d+)$", Pattern.CASE_INSENSITIVE);
    /** 历史更新最多展示多少个版本：够回看，又不会把弹窗撑得太大。 */
    static final int MAX_HISTORY = 20;
    private static final String[] KNOWN_ABIS = {
            "arm64-v8a", "armeabi-v7a", "x86_64", "x86"
    };

    private static VersionService sInstance = new VersionService();
    private boolean mDeprecated = false;
    private VersionInfo mVersionInfo;
    private SharedPreferences mSharedPreferences;
    private Retrofit mRetrofit;

    public VersionService() {
        mRetrofit = new Retrofit.Builder()
                .baseUrl("https://api.github.com/")
                .addConverterFactory(GsonConverterFactory.create(new GsonBuilder()
                        .setLenient()
                        .create()))
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .build();
    }

    public static VersionService getInstance() {
        return sInstance;
    }

    public Observable<VersionInfo> checkForUpdates() {
        return mRetrofit.create(UpdateCheckApi.class)
                .listReleases()
                .map(VersionService::fromGitHubReleases)
                .onErrorResumeNext(error -> {
                    // A repository without a published release returns 404. Treat that as
                    // "already latest" instead of surfacing a network failure to the user.
                    if (isNotFound(error)) {
                        return Observable.just(VersionInfo.current());
                    }
                    return Observable.error(error);
                })
                .subscribeOn(Schedulers.io());
    }

    /** 自建更新源（{@code update.json}）的网址；留空表示用内置的 GitHub Releases。 */
    public static String updateSourceUrl(Context context) {
        try {
            String value = Pref.getPrefString(context.getString(R.string.key_update_source_url), "");
            return value == null ? "" : value.trim();
        } catch (Exception e) {
            // 读设置失败不该让「检查更新」整个不可用，退回内置源。
            return "";
        }
    }

    /**
     * 检查更新：配了自建更新源就走自建源（可以放局域网或自己的服务器，不依赖 GitHub），
     * 否则走内置的 GitHub Releases。
     */
    public Observable<VersionInfo> checkForUpdates(Context context) {
        final String source = updateSourceUrl(context);
        if (isEmpty(source)) {
            return checkForUpdates();
        }
        return Observable.fromCallable(() -> fromManifest(readText(source), source))
                .subscribeOn(Schedulers.io());
    }

    /** 纯 Java 的空串判断：解析路径不依赖 android.text.TextUtils，单测里才能直接跑。 */
    private static boolean isEmpty(CharSequence value) {
        return value == null || value.length() == 0;
    }

    /** 读一个文本资源（更新源 JSON），带超时与明确的错误信息。 */
    static String readText(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-cache");
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("更新源返回 HTTP " + code);
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                return stripByteOrderMark(out.toString("UTF-8"));
            }
        } finally {
            connection.disconnect();
        }
    }

    /** 手工编辑过的 JSON 很容易带上 BOM（记事本/PowerShell 默认就这么存），Gson 会直接报错。 */
    static String stripByteOrderMark(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF') {
            return text.substring(1);
        }
        return text;
    }

    /**
     * 把自建更新源的 {@code update.json} 转成界面用的 {@link VersionInfo}。
     *
     * <p>挑选规则与 GitHub 源完全一致（compat/lite + ABI），因此两种情况下的行为可以互相推理；
     * 相对地址按 {@code update.json} 所在目录解析，方便把整套东西丢进一个目录里。
     */
    static VersionInfo fromManifest(String json, String sourceUrl) throws IOException {
        return fromManifest(json, sourceUrl, BuildConfig.RHINO_COMPAT, Build.SUPPORTED_ABIS);
    }

    /** 可注入 compat / ABI 的重载：单测里不依赖 Build / BuildConfig。 */
    static VersionInfo fromManifest(String json, String sourceUrl, boolean compat,
            String[] supportedAbis) throws IOException {
        UpdateManifest manifest;
        json = stripByteOrderMark(json);
        try {
            manifest = new GsonBuilder().setLenient().create().fromJson(json, UpdateManifest.class);
        } catch (JsonSyntaxException e) {
            throw new IOException("更新源内容不是有效的 JSON", e);
        }
        if (manifest == null) {
            throw new IOException("更新源内容不是有效的 JSON");
        }
        if (manifest.versionCode <= 0) {
            throw new IOException("更新源里的 versionCode 无效");
        }
        VersionInfo info = new VersionInfo();
        info.versionCode = manifest.versionCode;
        info.versionName = isEmpty(manifest.versionName)
                ? String.valueOf(manifest.versionCode) : manifest.versionName.trim();
        info.releaseNotes = isEmpty(manifest.releaseNotes)
                ? "更新源未提供更新说明。" : manifest.releaseNotes;
        info.deprecated = manifest.deprecated;
        info.oldVersions = new ArrayList<>();
        if (manifest.oldVersions != null) {
            for (UpdateManifest.Note note : manifest.oldVersions) {
                if (note == null || note.versionCode <= 0) {
                    continue;
                }
                VersionInfo.OldVersion oldVersion = new VersionInfo.OldVersion();
                oldVersion.versionCode = note.versionCode;
                oldVersion.versionName = note.versionName;
                oldVersion.date = note.date;
                oldVersion.issues = note.issues;
                info.oldVersions.add(oldVersion);
            }
        }
        info.downloads = new ArrayList<>();
        VersionInfo.Download preferred = null;
        int preferredScore = Integer.MIN_VALUE;
        if (manifest.assets != null) {
            for (UpdateManifest.Asset asset : manifest.assets) {
                if (asset == null || isEmpty(asset.url)
                        || !isAbiSupported(asset.abi, supportedAbis)) {
                    continue;
                }
                String name = isEmpty(asset.name) ? fileNameOf(asset.url) : asset.name;
                if (!isCompatibleApkAsset(name, compat, supportedAbis)) {
                    continue;
                }
                VersionInfo.Download download = new VersionInfo.Download();
                download.name = name;
                download.url = resolveUrl(sourceUrl, asset.url);
                download.digest = asset.sha256;
                info.downloads.add(download);
                int score = preferredAssetScore(name, compat, supportedAbis)
                        + (isEmpty(asset.abi) ? 0 : 1);
                if (score > preferredScore) {
                    preferred = download;
                    preferredScore = score;
                }
            }
        }
        if (preferred == null && !isEmpty(manifest.apkUrl)) {
            VersionInfo.Download download = new VersionInfo.Download();
            download.name = fileNameOf(manifest.apkUrl);
            download.url = resolveUrl(sourceUrl, manifest.apkUrl);
            download.digest = manifest.apkSha256;
            info.downloads.add(download);
            preferred = download;
        }
        if (preferred == null) {
            throw new IOException("更新源里没有适用于当前设备的 APK");
        }
        // 「直接下载」用自动挑出来的那一套，把它放到列表最前，下载按钮的顺序就稳定了。
        info.downloads.remove(preferred);
        info.downloads.add(0, preferred);
        info.downloadUrl = preferred.url;
        info.downloadDigest = preferred.digest;
        return info;
    }

    /** asset 里显式写的 ABI 是否适用于本机；留空表示不限制。 */
    static boolean isAbiSupported(String abi, String[] supportedAbis) {
        if (isEmpty(abi)) {
            return true;
        }
        if (supportedAbis == null) {
            return false;
        }
        String value = abi.trim();
        for (String supported : supportedAbis) {
            if (value.equalsIgnoreCase(supported)) {
                return true;
            }
        }
        return false;
    }

    /** 解析更新源里的下载地址：绝对地址直接用，相对地址相对 update.json 解析。 */
    static String resolveUrl(String sourceUrl, String url) throws IOException {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        try {
            return new URL(new URL(sourceUrl), url).toString();
        } catch (MalformedURLException e) {
            throw new IOException("更新源里的下载地址无效：" + url, e);
        }
    }

    private static String fileNameOf(String url) {
        String value = url;
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int slash = value.lastIndexOf('/');
        String name = slash >= 0 ? value.substring(slash + 1) : value;
        return name.isEmpty() ? "update.apk" : name;
    }

    static boolean isNotFound(Throwable error) {
        // This project still uses Jake Wharton's legacy RxJava2 adapter, whose HTTP
        // exception is not retrofit2.HttpException even though both expose code().
        return (error instanceof com.jakewharton.retrofit2.adapter.rxjava2.HttpException
                && ((com.jakewharton.retrofit2.adapter.rxjava2.HttpException) error).code() == 404)
                || (error instanceof retrofit2.HttpException
                && ((retrofit2.HttpException) error).code() == 404);
    }

    /**
     * 发布列表 → 最新版本 + 历史更新。
     *
     * <p>GitHub 按发布时间倒序返回，因此第一个非草稿/非预览的发布就是最新版本，
     * 其余比它旧的发布组成「更新历史」（同时也能让界面在下载前先看一眼历次改动）。
     */
    static VersionInfo fromGitHubReleases(List<GitHubRelease> releases) {
        if (releases == null || releases.isEmpty()) {
            return VersionInfo.current();
        }
        GitHubRelease latest = null;
        for (GitHubRelease release : releases) {
            if (release == null || release.draft || release.prerelease) {
                continue;
            }
            latest = release;
            break;
        }
        if (latest == null) {
            return VersionInfo.current();
        }
        VersionInfo info = fromGitHubRelease(latest);
        info.oldVersions = new ArrayList<>();
        for (GitHubRelease release : releases) {
            if (release == null || release == latest || release.draft || release.prerelease) {
                continue;
            }
            if (info.oldVersions.size() >= MAX_HISTORY) {
                break;
            }
            VersionInfo.OldVersion oldVersion = new VersionInfo.OldVersion();
            oldVersion.versionName = releaseVersionName(release);
            oldVersion.versionCode = releaseVersionCode(release, oldVersion.versionName, 0);
            oldVersion.date = releaseDate(release.publishedAt);
            oldVersion.issues = isEmpty(release.body) ? "" : release.body;
            info.oldVersions.add(oldVersion);
        }
        return info;
    }

    /** ISO-8601 发布时间取前 10 位当日期（{@code 2026-09-12T08:00:00Z} → {@code 2026-09-12}）。 */
    static String releaseDate(String publishedAt) {
        if (isEmpty(publishedAt)) {
            return "";
        }
        String value = publishedAt.trim();
        int time = value.indexOf('T');
        return time > 0 ? value.substring(0, time) : value;
    }

    static VersionInfo fromGitHubRelease(GitHubRelease release) {
        if (release == null || release.draft) {
            return VersionInfo.current();
        }
        VersionInfo info = new VersionInfo();
        info.versionName = releaseVersionName(release);
        info.versionCode = releaseVersionCode(release, info.versionName);
        info.releaseNotes = isEmpty(release.body)
                ? "查看 GitHub Releases 获取本次更新说明。" : release.body;
        info.deprecated = 0;
        info.oldVersions = Collections.emptyList();
        info.downloads = new ArrayList<>();

        GitHubRelease.Asset preferred = null;
        int preferredScore = Integer.MIN_VALUE;
        if (release.assets != null) {
            for (GitHubRelease.Asset asset : release.assets) {
                if (asset == null || isEmpty(asset.name)
                        || isEmpty(asset.browserDownloadUrl)
                        || !isCompatibleApkAsset(asset.name, BuildConfig.RHINO_COMPAT,
                        Build.SUPPORTED_ABIS)) {
                    continue;
                }
                VersionInfo.Download download = new VersionInfo.Download();
                download.name = asset.name;
                download.url = asset.browserDownloadUrl;
                download.digest = asset.digest;
                info.downloads.add(download);
                int score = preferredAssetScore(asset.name, BuildConfig.RHINO_COMPAT,
                        Build.SUPPORTED_ABIS);
                if (score > preferredScore) {
                    preferred = asset;
                    preferredScore = score;
                }
            }
        }
        if (preferred != null) {
            info.downloadUrl = preferred.browserDownloadUrl;
            info.downloadDigest = preferred.digest;
        }
        if (info.downloads.isEmpty() && !isEmpty(release.htmlUrl)) {
            VersionInfo.Download page = new VersionInfo.Download();
            page.name = "打开 GitHub Releases";
            page.url = release.htmlUrl;
            info.downloads.add(page);
        }
        return info;
    }

    static boolean isCompatibleApkAsset(String name, boolean compat, String[] supportedAbis) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".apk")) return false;
        if (compat && lower.contains("lite")) return false;
        if (!compat && lower.contains("compat")) return false;

        String assetAbi = null;
        for (String knownAbi : KNOWN_ABIS) {
            if (lower.contains(knownAbi)) {
                assetAbi = knownAbi;
                break;
            }
        }
        if (assetAbi == null) return true;
        if (supportedAbis == null) return false;
        for (String supportedAbi : supportedAbis) {
            if (assetAbi.equalsIgnoreCase(supportedAbi)) return true;
        }
        return false;
    }

    private static int preferredAssetScore(String name, boolean compat, String[] supportedAbis) {
        String lower = name.toLowerCase(Locale.ROOT);
        int score = 0;
        if (compat) {
            if (lower.contains("compat")) score += 8;
        } else {
            if (lower.contains("lite")) score += 8;
        }
        String abi = supportedAbis == null || supportedAbis.length == 0
                ? "" : supportedAbis[0].toLowerCase(Locale.ROOT);
        if (!abi.isEmpty() && lower.contains(abi)) score += 4;
        return score;
    }

    private static String releaseVersionName(GitHubRelease release) {
        String value = !isEmpty(release.tagName) ? release.tagName : release.name;
        if (isEmpty(value)) return BuildConfig.VERSION_NAME;
        value = value.trim().replaceFirst("^[vV]", "");
        value = VERSION_CODE_IN_TAG.matcher(value).replaceFirst("");
        return value.isEmpty() ? BuildConfig.VERSION_NAME : value;
    }

    private static int releaseVersionCode(GitHubRelease release, String versionName) {
        return releaseVersionCode(release, versionName, BuildConfig.VERSION_CODE);
    }

    /**
     * 发布里的 versionCode：优先读发布说明里的 {@code versionCode:} 行，其次读标签后缀，
     * 都读不到时用 {@code fallback}（最新版本用当前版本号兼容旧行为，历史条目用 0）。
     */
    private static int releaseVersionCode(GitHubRelease release, String versionName, int fallback) {
        Matcher body = VERSION_CODE_IN_BODY.matcher(release.body == null ? "" : release.body);
        if (body.find()) return parsePositiveInt(body.group(1), fallback);
        Matcher tag = VERSION_CODE_IN_TAG.matcher(release.tagName == null ? "" : release.tagName);
        if (tag.find()) return parsePositiveInt(tag.group(1), fallback);
        if (fallback <= 0) {
            return 0;
        }
        return compareVersions(versionName, BuildConfig.VERSION_NAME) > 0
                ? BuildConfig.VERSION_CODE + 1 : BuildConfig.VERSION_CODE;
    }

    private static int parsePositiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static int compareVersions(String left, String right) {
        String[] a = (left == null ? "" : left).replaceFirst("[-+].*$", "").split("\\.");
        String[] b = (right == null ? "" : right).replaceFirst("[-+].*$", "").split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? parsePositiveInt(a[i].replaceAll("\\D.*$", ""), 0) : 0;
            int bv = i < b.length ? parsePositiveInt(b[i].replaceAll("\\D.*$", ""), 0) : 0;
            if (av != bv) return av < bv ? -1 : 1;
        }
        return 0;
    }


    private void readDeprecatedFromPref(Context context) {
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (mSharedPreferences.getInt(KEY_DEPRECATED_VERSION_CODE, 0) < BuildConfig.VERSION_CODE) {
            mSharedPreferences.edit().remove(KEY_DEPRECATED_VERSION_CODE)
                    .putBoolean(KEY_DEPRECATED, false)
                    .apply();
        }
        mDeprecated = mSharedPreferences.getBoolean(KEY_DEPRECATED, false);
    }


    public void readDeprecatedFromPrefIfNeeded(Context context) {
        if (mSharedPreferences == null) {
            readDeprecatedFromPref(context);
        }
    }

    public boolean isCurrentVersionDeprecated() {
        return mDeprecated;
    }

    public String getCurrentVersionIssues() {
        if (mVersionInfo == null)
            return null;
        VersionInfo.OldVersion oldVersion = mVersionInfo.getOldVersion(BuildConfig.VERSION_CODE);
        if (oldVersion == null)
            return null;
        return oldVersion.issues;
    }

    public Observable<VersionInfo> checkForUpdatesIfNeededAndUsingWifi(Context context) {
        if (mVersionInfo == null) {
            return checkUpdateIfUsingWifi(context);
        }
        return Observable.just(mVersionInfo);

    }

    private Observable<VersionInfo> checkUpdateIfUsingWifi(Context context) {
        if (!NetworkUtils.isWifiAvailable(context)) {
            return Observable.empty();
        }
        // This method both updates the deprecation cache and returns the result to its caller.
        // Cache the cold stream so those two subscribers share one HTTP request.
        Observable<VersionInfo> observable = checkForUpdates(context).cache();
        observable.subscribe(new SimpleObserver<VersionInfo>() {
            @Override
            public void onNext(@NonNull VersionInfo versionInfo) {
                if (versionInfo.isValid()) {
                    setVersionInfo(versionInfo);
                }
            }

            @Override
            public void onError(@NonNull Throwable e) {
                e.printStackTrace();
            }
        });
        return observable;
    }

    private void setVersionInfo(VersionInfo result) {
        mDeprecated = BuildConfig.VERSION_CODE <= result.deprecated;
        mVersionInfo = result;
        if (mDeprecated) {
            mSharedPreferences.edit().putBoolean(KEY_DEPRECATED, mDeprecated)
                    .putInt(KEY_DEPRECATED_VERSION_CODE, BuildConfig.VERSION_CODE)
                    .apply();
        }
    }
}
