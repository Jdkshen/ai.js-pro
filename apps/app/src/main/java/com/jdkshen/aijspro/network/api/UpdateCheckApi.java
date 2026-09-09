package com.jdkshen.aijspro.network.api;

import com.jdkshen.aijspro.network.entity.GitHubRelease;

import io.reactivex.Observable;
import retrofit2.http.GET;
import retrofit2.http.Headers;

/**
 * Created by Stardust on 2017/9/20.
 */

public interface UpdateCheckApi {

    @GET("repos/Jdkshen/ai.js-pro/releases/latest")
    @Headers({
            "Accept: application/vnd.github+json",
            "X-GitHub-Api-Version: 2022-11-28",
            "Cache-Control: no-cache"
    })
    Observable<GitHubRelease> checkForUpdates();

}
