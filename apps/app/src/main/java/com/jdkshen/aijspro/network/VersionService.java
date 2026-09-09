package com.jdkshen.aijspro.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;
import android.text.TextUtils;

import com.google.gson.GsonBuilder;
import com.jakewharton.retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import com.jdkshen.aijspro.BuildConfig;
import com.jdkshen.aijspro.network.api.UpdateCheckApi;
import com.jdkshen.aijspro.network.entity.GitHubRelease;
import com.jdkshen.aijspro.network.entity.VersionInfo;
import com.jdkshen.aijspro.tool.SimpleObserver;
import com.stardust.util.NetworkUtils;

import java.util.ArrayList;
import java.util.Collections;
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
                .checkForUpdates()
                .map(VersionService::fromGitHubRelease)
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

    static boolean isNotFound(Throwable error) {
        // This project still uses Jake Wharton's legacy RxJava2 adapter, whose HTTP
        // exception is not retrofit2.HttpException even though both expose code().
        return (error instanceof com.jakewharton.retrofit2.adapter.rxjava2.HttpException
                && ((com.jakewharton.retrofit2.adapter.rxjava2.HttpException) error).code() == 404)
                || (error instanceof retrofit2.HttpException
                && ((retrofit2.HttpException) error).code() == 404);
    }

    static VersionInfo fromGitHubRelease(GitHubRelease release) {
        if (release == null || release.draft) {
            return VersionInfo.current();
        }
        VersionInfo info = new VersionInfo();
        info.versionName = releaseVersionName(release);
        info.versionCode = releaseVersionCode(release, info.versionName);
        info.releaseNotes = TextUtils.isEmpty(release.body)
                ? "查看 GitHub Releases 获取本次更新说明。" : release.body;
        info.deprecated = 0;
        info.oldVersions = Collections.emptyList();
        info.downloads = new ArrayList<>();

        GitHubRelease.Asset preferred = null;
        int preferredScore = Integer.MIN_VALUE;
        if (release.assets != null) {
            for (GitHubRelease.Asset asset : release.assets) {
                if (asset == null || TextUtils.isEmpty(asset.name)
                        || TextUtils.isEmpty(asset.browserDownloadUrl)
                        || !isCompatibleApkAsset(asset.name, BuildConfig.RHINO_COMPAT,
                        Build.SUPPORTED_ABIS)) {
                    continue;
                }
                VersionInfo.Download download = new VersionInfo.Download();
                download.name = asset.name;
                download.url = asset.browserDownloadUrl;
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
        }
        if (info.downloads.isEmpty() && !TextUtils.isEmpty(release.htmlUrl)) {
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
        String value = !TextUtils.isEmpty(release.tagName) ? release.tagName : release.name;
        if (TextUtils.isEmpty(value)) return BuildConfig.VERSION_NAME;
        value = value.trim().replaceFirst("^[vV]", "");
        value = VERSION_CODE_IN_TAG.matcher(value).replaceFirst("");
        return value.isEmpty() ? BuildConfig.VERSION_NAME : value;
    }

    private static int releaseVersionCode(GitHubRelease release, String versionName) {
        Matcher body = VERSION_CODE_IN_BODY.matcher(release.body == null ? "" : release.body);
        if (body.find()) return parsePositiveInt(body.group(1), BuildConfig.VERSION_CODE);
        Matcher tag = VERSION_CODE_IN_TAG.matcher(release.tagName == null ? "" : release.tagName);
        if (tag.find()) return parsePositiveInt(tag.group(1), BuildConfig.VERSION_CODE);
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
        // Cache the cold Retrofit stream so those two subscribers share one HTTP request.
        Observable<VersionInfo> observable = checkForUpdates().cache();
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
