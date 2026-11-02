package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TvdbLoginRequestDto(
    @Json(name = "apikey") val apiKey: String
)

@JsonClass(generateAdapter = true)
data class TvdbLoginResponseDto(
    @Json(name = "data") val data: TvdbTokenDto? = null
)

@JsonClass(generateAdapter = true)
data class TvdbTokenDto(
    @Json(name = "token") val token: String? = null
)

@JsonClass(generateAdapter = true)
data class TvdbRemoteIdResponseDto(
    @Json(name = "data") val data: List<TvdbRemoteIdResultDto>? = null
)

@JsonClass(generateAdapter = true)
data class TvdbRemoteIdResultDto(
    @Json(name = "series") val series: TvdbSeriesRefDto? = null
)

@JsonClass(generateAdapter = true)
data class TvdbSeriesRefDto(
    @Json(name = "id") val id: Long? = null
)

@JsonClass(generateAdapter = true)
data class TvdbSeriesResponseDto(
    @Json(name = "data") val data: TvdbSeriesDto? = null
)

@JsonClass(generateAdapter = true)
data class TvdbSeriesDto(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "airsTime") val airsTime: String? = null,
    @Json(name = "airsDays") val airsDays: TvdbAirsDaysDto? = null,
    @Json(name = "nextAired") val nextAired: String? = null,
    @Json(name = "originalCountry") val originalCountry: String? = null,
    @Json(name = "country") val country: String? = null,
    @Json(name = "originalNetwork") val originalNetwork: TvdbCompanyDto? = null,
    @Json(name = "latestNetwork") val latestNetwork: TvdbCompanyDto? = null,
    @Json(name = "remoteIds") val remoteIds: List<TvdbRemoteIdDto>? = null
)

@JsonClass(generateAdapter = true)
data class TvdbRemoteIdDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "type") val type: Int? = null,
    @Json(name = "sourceName") val sourceName: String? = null
)

@JsonClass(generateAdapter = true)
data class TvdbAirsDaysDto(
    @Json(name = "monday") val monday: Boolean? = null,
    @Json(name = "tuesday") val tuesday: Boolean? = null,
    @Json(name = "wednesday") val wednesday: Boolean? = null,
    @Json(name = "thursday") val thursday: Boolean? = null,
    @Json(name = "friday") val friday: Boolean? = null,
    @Json(name = "saturday") val saturday: Boolean? = null,
    @Json(name = "sunday") val sunday: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class TvdbCompanyDto(
    @Json(name = "name") val name: String? = null,
    @Json(name = "country") val country: String? = null
)
