package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.TvdbLoginRequestDto
import com.nuvio.tv.data.remote.dto.TvdbLoginResponseDto
import com.nuvio.tv.data.remote.dto.TvdbRemoteIdResponseDto
import com.nuvio.tv.data.remote.dto.TvdbSeriesResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface TvdbApi {

    @POST("login")
    suspend fun login(@Body body: TvdbLoginRequestDto): Response<TvdbLoginResponseDto>

    @GET("search/remoteid/{remoteId}")
    suspend fun searchRemoteId(
        @Header("Authorization") authorization: String,
        @Path("remoteId") remoteId: String
    ): Response<TvdbRemoteIdResponseDto>

    @GET("series/{id}/extended")
    suspend fun seriesExtended(
        @Header("Authorization") authorization: String,
        @Path("id") id: Long,
        @Query("short") short: Boolean = true
    ): Response<TvdbSeriesResponseDto>
}
