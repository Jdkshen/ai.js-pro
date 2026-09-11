package com.jdkshen.aijspro.network.entity;

import com.google.gson.annotations.SerializedName;

import java.util.Collections;
import java.util.List;

/** Minimal GitHub Releases response used by the in-app updater. */
public class GitHubRelease {

    @SerializedName("tag_name")
    public String tagName;
    public String name;
    public String body;
    @SerializedName("html_url")
    public String htmlUrl;
    /** ISO-8601 发布时间（{@code 2026-09-12T08:00:00Z}），用于历史更新列表。 */
    @SerializedName("published_at")
    public String publishedAt;
    public boolean draft;
    public boolean prerelease;
    public List<Asset> assets = Collections.emptyList();

    public static class Asset {
        public String name;
        @SerializedName("browser_download_url")
        public String browserDownloadUrl;
        public long size;
        public String digest;
    }
}
