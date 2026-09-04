package com.jdkshen.aijspro.network.api

import kotlinx.coroutines.Deferred
import com.jdkshen.aijspro.network.entity.topic.Category
import com.jdkshen.aijspro.network.entity.topic.Post
import com.jdkshen.aijspro.network.entity.topic.Topic
import retrofit2.http.GET
import retrofit2.http.Path

interface TopicApi {

    @GET("/api/category/{cid}")
    fun getCategory(@Path("cid") cid: Long): Deferred<Category>

    @GET("/api/topic/{tid}")
    fun getTopic(@Path("tid") pid: Long): Deferred<Topic>


}