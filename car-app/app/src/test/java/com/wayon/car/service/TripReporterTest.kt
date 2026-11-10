package com.wayon.car.service

import com.wayon.car.car.CarLocation
import com.wayon.car.car.DriveInfo
import com.wayon.car.car.Energy
import com.wayon.car.car.EnergyType
import com.wayon.car.car.Gear
import com.wayon.car.car.Place
import com.wayon.car.car.Remain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TripReporterTest {

    private var t = 1_000_000L
    private val r = TripReporter { t }

    private fun drive(destDist: Int, vararg wps: Remain) = DriveInfo(Remain("부산역", destDist, 600), wps.toList())
    private val busan = Place("부산역", 35.115, 129.041)

    @Test
    fun `위치는 5초 간격, 기어는 바뀔 때만`() {
        val loc = CarLocation(36.6, 127.4, 90, 0)
        assertNotNull(r.location(loc))
        t += 2_000
        assertNull(r.location(loc))
        t += 3_000
        assertEquals("car.location", r.location(loc)?.type)

        assertEquals(mapOf("gear" to "DRIVE"), r.gear(Gear.DRIVE)?.data)
        assertNull(r.gear(Gear.DRIVE))
        assertNotNull(r.gear(Gear.PARK))
    }

    @Test
    fun `목적지 좌표를 모르면 보내지 않고, 남은 거리만 바뀌면 30초에 1번`() {
        assertNull(r.route(drive(5000), null, emptyList(), emptyList()))
        val first = r.route(drive(5000), busan, emptyList(), emptyList())
        assertEquals(129.041, (first?.data?.get("destination") as Map<*, *>)["lng"])

        t += 5_000
        assertNull(r.route(drive(4900), busan, emptyList(), emptyList()))
        t += 20_000
        assertEquals(emptyList<TripReporter.Out>(), r.flush())
        t += 5_000 // 처음 보낸 뒤 30초: 미뤄 둔 4900을 보낸다
        assertEquals(4900, (r.flush().single().data["destination"] as Map<*, *>)["remainDistM"])
    }

    @Test
    fun `경유지가 바뀌면 바로 보내고 우리가 넣은 경유지는 좌표와 requestId를 붙인다`() {
        r.route(drive(5000), busan, emptyList(), emptyList())
        t += 1_000
        val mine = MyWaypoint("경산휴게소", MyWaypoint.TYPE_REST_AREA, 35.86, 128.80, requestId = 88)
        val out = r.route(drive(5000, Remain("경산휴게소", 2000, 100), Remain("직접 넣은 곳", 3000, 200)), busan, emptyList(), listOf(mine))
        val wps = out?.data?.get("waypoints") as List<*>
        assertEquals(mapOf("index" to "FIRST", "name" to "경산휴게소", "lat" to 35.86, "lng" to 128.80,
            "remainDistM" to 2000, "remainTimeS" to 100, "requestId" to 88L), wps[0])
        assertNull((wps[1] as Map<*, *>)["lat"]) // 좌표 모름 → 서버가 빼고 계산
    }

    @Test
    fun `목적지가 없어지면 destination null`() {
        r.route(drive(5000), busan, emptyList(), emptyList())
        val out = r.route(DriveInfo(null, emptyList()), null, emptyList(), emptyList())
        assertEquals(mapOf("destination" to null, "waypoints" to emptyList<Any>()), out?.data)
    }

    @Test
    fun `에너지는 1분에 1번, 미룬 값은 flush가 보내고 EV로 바꾼다`() {
        assertEquals("EV", r.energy(Energy(EnergyType.ELECTRIC, 50, 300_000))?.data?.get("energyType"))
        t += 10_000
        assertNull(r.energy(Energy(EnergyType.ELECTRIC, 49, 298_000)))
        assertEquals(emptyList<TripReporter.Out>(), r.flush())
        t += 50_000
        assertEquals(49, r.flush().single().data["energyPct"])
    }

    @Test
    fun `reset 하면 지금 값을 모두 다시 보낸다`() {
        r.gear(Gear.PARK)
        r.energy(Energy(EnergyType.FUEL, 40, 200_000))
        r.route(drive(5000), busan, emptyList(), emptyList())
        r.reset()
        assertNotNull(r.gear(Gear.PARK))
        assertEquals(listOf("car.energy", "car.route"), r.flush().map { it.type })
    }
}
