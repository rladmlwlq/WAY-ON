package com.wayon.car.service

import com.wayon.car.car.Energy
import com.wayon.car.car.EnergyType
import com.wayon.car.car.Remain
import com.wayon.car.service.DestinationAlerts.Alert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationAlertsTest {

    private val alerts = DestinationAlerts(energyMarginM = 20_000, parkingDistM = 10_000, parkingTimeS = 300)

    private fun busan(distM: Int, timeS: Int = distM / 25) = Remain("부산역", distM, timeS)
    private fun fuel(rangeM: Int?) = Energy(EnergyType.FUEL, 30, rangeM)

    @Test
    fun `주행 가능 거리가 남은 거리 + 20km보다 작으면 한 번만 보낸다`() {
        assertTrue(alerts.update(busan(100_000), fuel(130_000)).isEmpty())
        assertEquals(
            listOf(Alert.EnergyLow(EnergyType.FUEL, 115_000, 100_000)),
            alerts.update(busan(100_000), fuel(115_000)),
        )
        assertTrue(alerts.update(busan(90_000), fuel(105_000)).isEmpty())
    }

    @Test
    fun `주유해서 조건이 풀린 뒤 다시 모자라면 또 보낸다`() {
        alerts.update(busan(100_000), fuel(110_000))
        // 조금 늘었지만 아직 모자람: 다시 안 보냄
        assertTrue(alerts.update(busan(99_000), fuel(112_000)).isEmpty())
        // 주유
        assertTrue(alerts.update(busan(98_000), fuel(500_000)).isEmpty())
        assertEquals(1, alerts.update(busan(98_000), fuel(110_000)).size)
    }

    @Test
    fun `주행 가능 거리가 줄다가 조건이 풀려도 늘지 않았으면 다시 보내지 않는다`() {
        alerts.update(busan(100_000), fuel(110_000))
        assertTrue(alerts.update(busan(60_000), fuel(90_000)).isEmpty()) // 연비가 좋아 조건이 풀림
        assertTrue(alerts.update(busan(55_000), fuel(70_000)).isEmpty())
    }

    @Test
    fun `목적지가 바뀌면 에너지·주차장을 다시 본다`() {
        alerts.update(busan(8_000), fuel(10_000))
        val next = alerts.update(Remain("서울역", 8_000, 600), fuel(10_000))
        assertEquals(2, next.size)
    }

    @Test
    fun `목적지가 없거나 에너지 종류·거리를 모르면 비교하지 않는다`() {
        assertTrue(alerts.update(null, fuel(1_000)).isEmpty())
        assertTrue(alerts.update(busan(100_000), Energy(null, null, 1_000)).isEmpty())
        assertTrue(alerts.update(busan(100_000), fuel(null)).isEmpty())
        // 나중에 값이 들어오면 그때 보낸다
        assertEquals(1, alerts.update(busan(100_000), fuel(1_000)).size)
    }

    @Test
    fun `목적지 10km 이하 또는 5분 이하가 처음 되면 주차장을 한 번 보낸다`() {
        assertTrue(alerts.update(busan(30_000, 1_200), fuel(null)).isEmpty())
        assertEquals(
            listOf(Alert.ParkingNearDest("부산역", 9_800, 840)),
            alerts.update(busan(9_800, 840), fuel(null)),
        )
        assertTrue(alerts.update(busan(5_000, 200), fuel(null)).isEmpty())
    }

    @Test
    fun `남은 시간 5분 이하만으로도 보낸다`() {
        assertEquals(
            listOf(Alert.ParkingNearDest("부산역", 12_000, 290)),
            alerts.update(busan(12_000, 290), fuel(null)),
        )
    }

    @Test
    fun `남은 거리·시간이 0이면 경로 계산 전으로 보고 보내지 않는다`() {
        assertTrue(alerts.update(busan(0, 0), fuel(1_000)).isEmpty())
        assertEquals(2, alerts.update(busan(9_000, 600), fuel(1_000)).size)
    }
}
