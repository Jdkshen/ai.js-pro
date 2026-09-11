package com.jdkshen.aijspro.network.entity;

import android.text.TextUtils;


import com.jdkshen.aijspro.BuildConfig;

import org.json.JSONObject;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Created by Stardust on 2017/9/20.
 */

public class VersionInfo {

    public int versionCode;
    public String releaseNotes;
    public String versionName;
    public List<Download> downloads;
    public List<OldVersion> oldVersions;
    public int deprecated;
    public String downloadUrl;
    public String downloadDigest;

    public boolean isValid() {
        return downloads != null && !downloads.isEmpty() && versionCode > 0
                && !TextUtils.isEmpty(versionName) && !TextUtils.isEmpty(releaseNotes);
    }

    public OldVersion getOldVersion(int versionCode) {
        if (oldVersions == null) {
            return null;
        }
        for (OldVersion oldVersion : oldVersions) {
            if (oldVersion.versionCode == versionCode) {
                return oldVersion;
            }
        }
        return null;
    }

    /**
     * 弹窗里的「更新历史」列表：按更新源给定的顺序（新→旧）返回，并跳过正在安装的这个版本，
     * 因为它的更新说明已经作为「本次更新」单独展示过了。
     */
    public List<OldVersion> historyForDialog() {
        List<OldVersion> history = new ArrayList<>();
        if (oldVersions == null) {
            return history;
        }
        for (OldVersion oldVersion : oldVersions) {
            if (oldVersion == null) {
                continue;
            }
            if (versionCode > 0 && oldVersion.versionCode == versionCode) {
                continue;
            }
            history.add(oldVersion);
        }
        return history;
    }

    public boolean isNewer() {
        return versionCode > BuildConfig.VERSION_CODE;
    }

    public static VersionInfo current() {
        VersionInfo info = new VersionInfo();
        info.versionCode = BuildConfig.VERSION_CODE;
        info.versionName = BuildConfig.VERSION_NAME;
        info.releaseNotes = "当前暂无可用的新版本。";
        info.downloads = Collections.emptyList();
        info.oldVersions = Collections.emptyList();
        return info;
    }

    @Override
    public String toString() {
        return "UpdateInfo{" +
                "versionCode=" + versionCode +
                ", releaseNotes='" + releaseNotes + '\'' +
                ", versionName='" + versionName + '\'' +
                ", downloads=" + downloads +
                ", oldVersions=" + oldVersions +
                ", deprecated=" + deprecated +
                ", downloadUrl='" + downloadUrl + '\'' +
                '}';
    }


    public static class OldVersion extends JSONObject {

        public int versionCode;
        /** 版本号（展示用，可为空）。 */
        public String versionName;
        /** 发布日期，例如 {@code 2026-09-12}（可为空）。 */
        public String date;
        public String issues;

        /** 「1.0.2 · 2026-09-01」这种展示用标题（缺什么就少显示什么）。 */
        public String displayTitle() {
            StringBuilder builder = new StringBuilder();
            if (!isBlank(versionName)) {
                builder.append(versionName);
            } else if (versionCode > 0) {
                builder.append("versionCode ").append(versionCode);
            }
            if (!isBlank(date)) {
                if (builder.length() > 0) {
                    builder.append("  ·  ");
                }
                builder.append(date);
            }
            if (builder.length() == 0) {
                builder.append("versionCode ").append(versionCode);
            }
            return builder.toString();
        }

        /** 纯 Java 的空串判断：单测里 android.text.TextUtils 是 stub，不能用。 */
        private static boolean isBlank(String value) {
            return value == null || value.trim().isEmpty();
        }

        @Override
        public String toString() {
            return "OldVersion{" +
                    "versionCode=" + versionCode +
                    ", versionName='" + versionName + '\'' +
                    ", date='" + date + '\'' +
                    ", issues='" + issues + '\'' +
                    '}';
        }
    }

    public static class Download extends JSONObject {

        public String name;
        public String url;
        public String digest;

        @Override
        public String toString() {
            return "Download{" +
                    "name='" + name + '\'' +
                    ", url='" + url + '\'' +
                    '}';
        }
    }
}
