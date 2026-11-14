package com.wayon.car.server

import com.wayon.car.Config
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

/** 판매 순위 1칸. price는 메뉴와 이름이 맞을 때만 */
data class TopItem(val rank: Int, val name: String, val store: String?, val price: Int?)

/**
 * app-api.md 3-9 휴게소 지도. 서버 `RestAreaDto.MapView`와 같은 모양.
 * [lat]·[lng]는 휴게소 건물(지도 중심). [places]는 실제 위치가 있는 장소, [building]은 건물 마커에 모은 정보.
 */
data class RestAreaMap(
    val restAreaId: String,
    val name: String,
    val direction: String?,
    val lat: Double,
    val lng: Double,
    val places: List<Place> = emptyList(),
    val building: Building = Building(),
    val topMonth: String? = null,
) {
    /** kind: FOOD / CAFE / STORE / GAS / EV / TOILET / ATM. 우리 매장 정보와 맞으면 영업시간·openNow·topItems가 있음 */
    data class Place(
        val placeId: String,
        val name: String,
        val kind: String,
        val lat: Double,
        val lng: Double,
        val open: String? = null,
        val close: String? = null,
        val openNow: Boolean? = null,
        val topItems: List<TopItem> = emptyList(),
    )

    data class Building(
        val foodCourt: FoodCourt = FoodCourt(),
        val otherStores: List<Store> = emptyList(),
        val facilities: List<Facility> = emptyList(),
    )

    data class FoodCourt(val menus: List<Menu> = emptyList(), val topItems: List<RankedItem> = emptyList())

    /** 식당가 판매 순위 1칸. store는 파는 곳, placeId는 그 가게가 지도에 있으면 장소 ID */
    data class RankedItem(val rank: Int, val name: String, val store: String?, val placeId: String?, val price: Int?)

    /** 식당가 메뉴. store는 파는 곳(메뉴 이름 앞 괄호), 없으면 식당가 공용. placeId는 지도에 있으면 장소 ID */
    data class Menu(
        val name: String,
        val store: String? = null,
        val placeId: String? = null,
        val price: Int?,
        val best: Boolean = false,
        val recommended: Boolean = false,
        val premium: Boolean = false,
    )

    /** 지도에서 위치를 못 찾은 매장 */
    data class Store(
        val name: String,
        val open: String? = null,
        val close: String? = null,
        val openNow: Boolean? = null,
        val topItems: List<TopItem> = emptyList(),
    )

    data class Facility(val code: String, val name: String, val detail: String?, val open: String?, val close: String?)

    /** 지금 영업 중인 매장 이름 (지도 위 + 위치 모르는 매장) */
    fun openStoreNames(): List<String> =
        places.filter { it.openNow == true }.map { it.name.substringBefore(' ') } +
            building.otherStores.filter { it.openNow == true }.map { it.name }
}

/**
 * 휴게소 지도 (3-9). 도착 화면에 쓴다.
 * WayOnApi(2-2)가 생기면 그쪽으로 옮긴다. 차량 토큰 헤더도 그때 붙인다.
 */
interface RestAreaApi {

    @GET("api/rest-areas/{restAreaId}/map")
    suspend fun map(@Path("restAreaId") restAreaId: String): RestAreaMap

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
