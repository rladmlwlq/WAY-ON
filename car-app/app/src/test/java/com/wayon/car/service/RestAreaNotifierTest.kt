package com.wayon.car.service

import com.wayon.car.server.RestAreaDetail
import org.junit.Assert.assertEquals
import org.junit.Test

class RestAreaNotifierTest {

    private fun detail(brands: List<Pair<String, Boolean?>>, facilities: List<String>) = RestAreaDetail(
        restAreaId = "000555", name = "매송(목포)휴게소", routeName = "서해안선", direction = "목포방향",
        lat = 0.0, lng = 0.0, topMonth = null,
        brands = brands.map { RestAreaDetail.Brand(it.first, "08:00", "20:00", it.second) },
        facilities = facilities.map { RestAreaDetail.Facility("00", it, null, null, null, "REST_AREA") },
    )

    @Test
    fun `영업 중 매장은 2개까지 이름, 나머지는 개수`() {
        val card = ArrivedCard(
            "매송(목포)휴게소", "ra:000555",
            detail(
                listOf("할리스커피" to true, "던킨도너츠" to true, "CU" to true, "닫은가게" to false),
                listOf("수유실", "샤워실", "수유실"),
            ),
        )
        assertEquals("영업 중 할리스커피 · 던킨도너츠 외 1곳\n편의시설 수유실 · 샤워실", RestAreaNotifier.bodyText(card))
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
            RestAreaNotifier.bodyText(ArrivedCard("x", null, detail(emptyList(), emptyList()))),
        )
    }
}
