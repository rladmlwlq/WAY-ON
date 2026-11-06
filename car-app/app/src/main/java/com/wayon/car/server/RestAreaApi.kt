package com.wayon.car.server

import com.wayon.car.Config
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

/** app-api.md 3-7 응답. 서버 `RestAreaDto.Detail`과 같은 모양 */
data class RestAreaDetail(
    val restAreaId: String,
    val name: String,
    val routeName: String?,
    val direction: String?,
    val lat: Double,
    val lng: Double,
    val topItems: List<TopItem> = emptyList(),
    val topMonth: String?,
    val brands: List<Brand> = emptyList(),
    val facilities: List<Facility> = emptyList(),
) {
    data class TopItem(val rank: Int, val name: String, val store: String?, val price: Int?)
    data class Brand(val name: String, val open: String?, val close: String?, val openNow: Boolean?)
    data class Facility(
        val code: String,
        val name: String,
        val detail: String?,
        val open: String?,
        val close: String?,
        val source: String?,
    )
}

/**
 * 휴게소 상세 (3-7). 차 화면 도착 카드에 쓴다.
 * WayOnApi(2-2)가 생기면 그쪽으로 옮긴다. 차량 토큰 헤더도 그때 붙인다.
 */
interface RestAreaApi {

    @GET("api/rest-areas/{restAreaId}")
    suspend fun restArea(@Path("restAreaId") restAreaId: String): RestAreaDetail

    companion object {
        fun create(baseUrl: String = Config.SERVER_URL): RestAreaApi {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            return Retrofit.Builder()
                .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(RestAreaApi::class.java)
        }

        /** `ra:000001` → `000001`. 휴게소가 아니면 null */
        fun restAreaIdOf(extId: String?): String? =
            extId?.takeIf { it.startsWith("ra:") }?.removePrefix("ra:")?.takeIf { it.isNotBlank() }
    }
}
