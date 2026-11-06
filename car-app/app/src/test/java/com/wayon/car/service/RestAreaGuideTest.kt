package com.wayon.car.service

import com.wayon.car.car.CarLocation
import com.wayon.car.car.DriveInfo
import com.wayon.car.car.Gear
import com.wayon.car.car.NaviEvent
import com.wayon.car.car.Remain
import com.wayon.car.server.RestAreaDetail
import com.wayon.car.ui.durationText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestAreaGuideTest {

    private val gyeongsan = MyWaypoint("경산(서울)휴게소", MyWaypoint.TYPE_REST_AREA, 35.878589, 128.809738, 88, "ra:000048")
    private val byDriver = MyWaypoint("칠곡(서울)휴게소", MyWaypoint.TYPE_REST_AREA, 35.99, 128.40, null, "ra:000050")
    private val charger = MyWaypoint("어느충전소", "EV_CHARGER", 36.0, 128.0, 90, "ev:ME1")

    private val said = mutableListOf<String>()
    private val reported = mutableListOf<Pair<String, Long>>()
    private val loaded = mutableListOf<String>()
    private val removed = mutableListOf<String>()
    private var kept: Set<String>? = null
    private var passengers = false

    private val guide = RestAreaGuide(
        removeWaypoint = { removed += it },
        keepWaypoints = { kept = it },
        passengersOnline = { passengers },
        say = { said += it },
        report = { type, id -> reported += type to id },
        loadDetail = { _, extId -> loaded += extId },
        arrival = RestAreaArrival(approachM = 2_000),
    )

    private fun drive(vararg wp: Pair<String, Int>) =
        DriveInfo(Remain("부산역", 100_000, 3_600), wp.map { Remain(it.first, it.second, 60) })

    @Test
    fun `접근-도착-떠남이 화면·음성·서버·목록으로 나간다`() {
        guide.onWaypoints(listOf(gyeongsan, charger))

        guide.onDrive(drive(gyeongsan.name to 1_500))
        assertEquals("경산(서울)휴게소", guide.ui.value.approaching)
        assertEquals(listOf("곧 경산(서울)휴게소입니다"), said)

        guide.onGear(Gear.PARK)
        assertNull(guide.ui.value.approaching)
        assertEquals("경산(서울)휴게소", guide.ui.value.arrived?.name)
        assertEquals(listOf("ra:000048"), loaded)

        guide.onGear(Gear.DRIVE)
        assertNull(guide.ui.value.arrived)
        assertEquals(listOf(gyeongsan.name), removed)

        assertEquals(
            listOf(
                RestAreaGuide.TYPE_APPROACH to 88L,
                RestAreaGuide.TYPE_ARRIVED to 88L,
                RestAreaGuide.TYPE_LEFT to 88L,
            ),
            reported,
        )
    }

    @Test
    fun `동승자가 있으면 말하지 않는다`() {
        passengers = true
        guide.onWaypoints(listOf(gyeongsan))
        guide.onDrive(drive(gyeongsan.name to 1_000))
        assertTrue(said.isEmpty())
        assertEquals(gyeongsan.name, guide.ui.value.approaching)
    }

    @Test
    fun `운전자가 직접 넣은 휴게소는 서버에 보내지 않는다`() {
        guide.onWaypoints(listOf(byDriver))
        guide.onNavi(NaviEvent.WaypointArrived(byDriver.name))
        assertTrue(reported.isEmpty())
        assertEquals(byDriver.name, guide.ui.value.arrived?.name)
    }

    @Test
    fun `내비 이름으로 못 찾으면 직선거리로 접근을 판단한다`() {
        guide.onWaypoints(listOf(gyeongsan))
        guide.onLocation(CarLocation(35.870, 128.809738, 0, 0)) // 약 970m 남쪽
        guide.onDrive(drive("내비가 붙인 다른 이름" to 900))
        assertEquals(gyeongsan.name, guide.ui.value.approaching)
    }

    @Test
    fun `경유지 삭제 뒤 다음 남은 거리에서 내비에 남은 것만 둔다`() {
        guide.onWaypoints(listOf(gyeongsan, byDriver))
        guide.onDrive(drive(gyeongsan.name to 50_000, byDriver.name to 90_000))
        assertNull(kept)

        guide.onNavi(NaviEvent.WaypointRemoved)
        guide.onDrive(drive(byDriver.name to 89_000))
        assertEquals(setOf(byDriver.name), kept)
    }

    @Test
    fun `도착한 휴게소는 내비에서 빠져도 지우지 않는다`() {
        guide.onWaypoints(listOf(gyeongsan))
        guide.onNavi(NaviEvent.WaypointArrived(gyeongsan.name))
        guide.onNavi(NaviEvent.WaypointRemoved)
        guide.onDrive(drive())
        assertEquals(setOf(gyeongsan.name), kept)
    }

    @Test
    fun `카드 정보는 지금 카드의 휴게소일 때만 넣는다`() {
        guide.onWaypoints(listOf(gyeongsan))
        guide.onNavi(NaviEvent.WaypointArrived(gyeongsan.name))
        val detail = RestAreaDetail("000048", gyeongsan.name, "경부선", "서울방향", 0.0, 0.0, topMonth = null)

        guide.onDetail("다른휴게소", detail)
        assertNull(guide.ui.value.arrived?.detail)

        guide.onDetail(gyeongsan.name, detail)
        assertEquals(detail, guide.ui.value.arrived?.detail)

        guide.onDetail(gyeongsan.name, null)
        assertTrue(guide.ui.value.arrived!!.loadFailed)
    }

    @Test
    fun `남은 시간 표시는 분 단위 올림`() {
        assertEquals("1분", durationText(50))
        assertEquals("1시간 3분", durationText(3_725))
        assertEquals("3시간 30분", durationText(12_600))
        assertEquals("2시간", durationText(7_200))
    }
}
