package com.jdkshen.aijspro.network.api;

import com.jdkshen.aijspro.network.entity.GitHubRelease;

import java.util.List;

import io.reactivex.Observable;
import retrofit2.http.GET;
import retrofit2.http.Headers;

/**
 * Created by Stardust on 2017/9/20.
 */

public interface UpdateCheckApi {

    /**
     * 拉取发布列表（GitHub 按发布时间倒序返回），用于同时得到最新版本与历史更新。
     * 以前用的是 {@code releases/latest}，改列表后请求数不变，但能多出一份「更新历史」。
     */
    @GET("repos/Jdkshen/ai.js-pro/releases?per_page=30")
    @Headers({
            "Accept: application/vnd.github+json",
            "X-GitHub-Api-Version: 2022-11-28",
            "Cache-Control: no-cache"
    })
    Observable<List<GitHubRelease>> listReleases();

}
