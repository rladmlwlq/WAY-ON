package com.wayon.car.service

import com.wayon.car.car.Gear
import com.wayon.car.service.RestAreaArrival.Event
import com.wayon.car.service.RestAreaArrival.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestAreaArrivalTest {

    private val gyeongsan = RestAreaStop("경산(서울)휴게소", 88, 35.86, 128.80)
    private val chilgok = RestAreaStop("칠곡(서울)휴게소", null, 35.99, 128.40)

    private fun tracker(vararg stops: RestAreaStop) =
        RestAreaArrival(approachM = 2_000).apply { sync(stops.toList()) }

    @Test
    fun `2km 이하가 처음 되면 접근을 한 번만 낸다`() {
        val t = tracker(gyeongsan)
        assertTrue(t.onRemain(mapOf(gyeongsan.name to 5_000)).isEmpty())
        assertEquals(listOf(Event.Approach(gyeongsan)), t.onRemain(mapOf(gyeongsan.name to 2_000)))
        assertTrue(t.onRemain(mapOf(gyeongsan.name to 1_500)).isEmpty())
        assertEquals(Phase.APPROACH, t.phase(gyeongsan.name))
    }

    @Test
    fun `접근 뒤 기어 P면 도착, 그다음 D면 떠나고 목록에서 빠진다`() {
        val t = tracker(gyeongsan)
        t.onRemain(mapOf(gyeongsan.name to 900))
        assertEquals(listOf(Event.Arrived(gyeongsan)), t.onGear(Gear.PARK))
        assertTrue(t.onGear(Gear.PARK).isEmpty())
        assertEquals(listOf(Event.Left(gyeongsan)), t.onGear(Gear.DRIVE))
        assertNull(t.phase(gyeongsan.name))
    }

    @Test
    fun `접근 전에 기어 P는 도착이 아니다 (졸음쉼터, 정체)`() {
        val t = tracker(gyeongsan)
        t.onRemain(mapOf(gyeongsan.name to 8_000))
        assertTrue(t.onGear(Gear.PARK).isEmpty())
        assertTrue(t.onGear(Gear.DRIVE).isEmpty())
        assertEquals(Phase.GOING, t.phase(gyeongsan.name))
    }

    @Test
    fun `도착 콜백은 2km 판단 없이도 도착, 두 번 오면 무시`() {
        val t = tracker(gyeongsan)
        assertEquals(listOf(Event.Arrived(gyeongsan)), t.onArrivedCallback(gyeongsan.name))
        assertTrue(t.onArrivedCallback(gyeongsan.name).isEmpty())
        assertTrue(t.onArrivedCallback("모르는휴게소").isEmpty())
    }

    @Test
    fun `R N 모름은 상태를 바꾸지 않는다`() {
        val t = tracker(gyeongsan)
        t.onArrivedCallback(gyeongsan.name)
        listOf(Gear.REVERSE, Gear.NEUTRAL, Gear.UNKNOWN).forEach { assertTrue(t.onGear(it).isEmpty()) }
        assertEquals(Phase.ARRIVED, t.phase(gyeongsan.name))
    }

    @Test
    fun `둘 다 접근 중이면 기어 P는 가까운 쪽만 도착`() {
        val t = tracker(gyeongsan, chilgok)
        t.onRemain(mapOf(gyeongsan.name to 1_800, chilgok.name to 300))
        assertEquals(listOf(Event.Arrived(chilgok)), t.onGear(Gear.PARK))
        assertEquals(Phase.APPROACH, t.phase(gyeongsan.name))
    }

    @Test
    fun `운전자가 지운 경유지는 sync에서 빠지고 이벤트가 없다`() {
        val t = tracker(gyeongsan, chilgok)
        t.onRemain(mapOf(gyeongsan.name to 1_000))
        t.sync(listOf(chilgok))
        assertNull(t.phase(gyeongsan.name))
        assertTrue(t.onGear(Gear.PARK).isEmpty())
    }

    @Test
    fun `sync는 진행 중인 상태를 유지한다`() {
        val t = tracker(gyeongsan)
        t.onRemain(mapOf(gyeongsan.name to 1_000))
        t.sync(listOf(gyeongsan, chilgok))
        assertEquals(Phase.APPROACH, t.phase(gyeongsan.name))
        assertEquals(Phase.GOING, t.phase(chilgok.name))
    }

    @Test
    fun `직선거리는 위도 0점01도에 약 1112m`() {
        val m = RestAreaArrival.straightLineM(35.86, 128.80, 35.87, 128.80)
        assertTrue("got $m", m in 1_100..1_125)
    }
}
