package com.wayon.car.service

import com.wayon.car.server.RestAreaMap
import org.junit.Assert.assertEquals
import org.junit.Test

class RestAreaNotifierTest {

    private fun map(
        places: List<Pair<String, Boolean?>>,
        others: List<Pair<String, Boolean?>>,
        facilities: List<String>,
    ) = RestAreaMap(
        restAreaId = "000555", name = "매송(목포)휴게소", direction = "목포방향", lat = 0.0, lng = 0.0,
        places = places.map { RestAreaMap.Place("kakao:${it.first}", it.first, "CAFE", 0.0, 0.0, openNow = it.second) },
        building = RestAreaMap.Building(
            otherStores = others.map { RestAreaMap.Store(it.first, openNow = it.second) },
            facilities = facilities.map { RestAreaMap.Facility("00", it, null, null, null) },
        ),
    )

    @Test
    fun `영업 중 매장은 2개까지 이름, 나머지는 개수 (지도 위 + 위치 모르는 매장)`() {
        val card = ArrivedCard(
            "매송(목포)휴게소", "ra:000555",
            map(
                places = listOf("할리스 매송휴게소목포방향점" to true, "닫은가게 매송휴게소점" to false, "브알라 매송휴게소점" to null),
                others = listOf("드롭탑(커피전문점)" to true, "외부매장(호두과자)" to null),
                facilities = listOf("수유실", "샤워실", "수유실"),
            ),
        )
        assertEquals("영업 중 할리스 · 드롭탑(커피전문점)\n편의시설 수유실 · 샤워실", RestAreaNotifier.bodyText(card))
    }

    @Test
    fun `정보가 없거나 불러오는 중`() {
        assertEquals("휴게소 정보를 불러오는 중", RestAreaNotifier.bodyText(ArrivedCard("매송(목포)휴게소", null)))
        assertEquals(
            "눌러서 휴게소 정보 보기",
            RestAreaNotifier.bodyText(ArrivedCard("매송(목포)휴게소", null, loadFailed = true)),
        )
        assertEquals(
            "영업 중인 매장 정보 없음",
            RestAreaNotifier.bodyText(ArrivedCard("x", null, map(emptyList(), emptyList(), emptyList()))),
        )
    }
}
