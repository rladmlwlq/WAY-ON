package com.wayon.car.car

import org.junit.Assert.assertEquals
import org.junit.Test

class DemoRouteTest {

    // 북쪽으로 약 1.1km씩 두 구간 → 동쪽 한 구간 (경도 127, 위도 37 부근)
    private val path = listOf(
        doubleArrayOf(127.0, 37.0),
        doubleArrayOf(127.0, 37.01),
        doubleArrayOf(127.0, 37.02),
        doubleArrayOf(127.01, 37.02),
    )
    private val route = DemoRoute(path, listOf(Place("휴게소", 37.0201, 127.0)), speedMps = 10.0)

    @Test
    fun `경로 길이와 경유지 자리`() {
        assertEquals(1113.2 * 2 + 888.9, route.totalM, 5.0)
        assertEquals(1113.2 * 2, route.stopAtM.getValue("휴게소"), 1.0)
    }

    @Test
    fun `간 거리만큼의 위치와 방향`() {
        val mid = route.locationAt(1113.2 / 2, 0)
        assertEquals(37.005, mid.lat, 1e-4)
        assertEquals(127.0, mid.lng, 1e-9)
        assertEquals(0, mid.heading)
        assertEquals(90, route.locationAt(route.totalM - 10, 0).heading)
        // 끝을 넘으면 끝에 선다
        assertEquals(127.01, route.locationAt(route.totalM + 500, 0).lng, 1e-9)
    }

    @Test
    fun `남은 거리와 시간은 실제 속도로`() {
        val r = route.remain("목적지", route.totalM - 100, route.totalM)
        assertEquals(100, r.distM)
        assertEquals(10, r.timeS)
        assertEquals(0, route.remain("목적지", route.totalM + 1, route.totalM).distM)
    }

    @Test
    fun `가까운 경로 점에서 이어 달린다`() {
        assertEquals(1113.2, route.nearestM(37.0101, 127.0001), 1.0)
    }
}
