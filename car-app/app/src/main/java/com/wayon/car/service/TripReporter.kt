package com.wayon.car.service

import com.wayon.car.Config
import com.wayon.car.car.CarLocation
import com.wayon.car.car.DriveInfo
import com.wayon.car.car.Energy
import com.wayon.car.car.EnergyType
import com.wayon.car.car.Gear
import com.wayon.car.car.Place
import java.time.Instant

/**
 * 세션 동안 서버로 보낼 차 상태를 정한다 (app-api.md 5-3 ~ 5-6). SDK·서버를 모르는 순수 로직이라 단위 시험으로 확인한다.
 *
 * - 위치 `car.location`: [Config.LOCATION_INTERVAL_MS]마다 (내비를 그 간격으로 묻는다)
 * - 경로 `car.route`: 목적지·경유지 이름·좌표가 바뀌면 바로, 남은 거리·시간만 바뀌면 [Config.ROUTE_REMAIN_INTERVAL_MS]에 1번
 * - 기어 `car.gear`: 바뀔 때만
 * - 에너지 `car.energy`: 바뀔 때만, [Config.ENERGY_MIN_INTERVAL_MS]에 1번. 그 안에 바뀐 값은 [flush]가 나중에 보낸다
 *
 * 세션이 새로 열리거나 다시 붙으면 [reset]: 서버는 그 상태를 모르므로 지금 값을 모두 다시 보낸다.
 * 반환한 [Out]은 호출하는 쪽이 보낸다. 끊겨서 못 보내도 다시 붙으면 [reset]으로 다시 간다.
 */
class TripReporter(private val now: () -> Long = System::currentTimeMillis) {

    data class Out(val type: String, val data: Map<String, Any?>)

    private var lastLocationAt = Long.MIN_VALUE / 2
    private var sentGear: Gear? = null

    private var energy: Energy? = null
    private var sentEnergy: Energy? = null
    private var energySentAt = Long.MIN_VALUE / 2

    private var route: Out? = null
    private var routeKey: List<Any?>? = null
    private var sentRoute: Out? = null
    private var sentRouteKey: List<Any?>? = null
    private var routeSentAt = Long.MIN_VALUE / 2

    fun reset() {
        lastLocationAt = Long.MIN_VALUE / 2
        sentGear = null
        sentEnergy = null
        energySentAt = Long.MIN_VALUE / 2
        sentRoute = null
        sentRouteKey = null
        routeSentAt = Long.MIN_VALUE / 2
    }

    fun location(loc: CarLocation?): Out? {
        if (loc == null) return null
        val t = now()
        // 내비 폴링이 조금 빨리 와도 건너뛰지 않게 1초 여유
        if (t - lastLocationAt < Config.LOCATION_INTERVAL_MS - 1_000) return null
        lastLocationAt = t
        return Out(
            "car.location",
            mapOf("lat" to loc.lat, "lng" to loc.lng, "heading" to loc.heading, "at" to Instant.ofEpochMilli(loc.atMs).toString()),
        )
    }

    fun gear(gear: Gear): Out? {
        if (gear == sentGear) return null
        sentGear = gear
        return Out("car.gear", mapOf("gear" to gear.name))
    }

    fun energy(value: Energy): Out? {
        energy = value
        return dueEnergy()
    }

    /**
     * 5-4. 목적지 좌표를 아직 모르면 보내지 않는다 (서버 길찾기에 필요).
     * 경유지 좌표는 우리가 넣은 것([mine])이 먼저, 없으면 내비가 알려 준 것, 둘 다 없으면 null
     */
    fun route(drive: DriveInfo?, destination: Place?, naviWaypoints: List<Place>, mine: List<MyWaypoint>): Out? {
        val dest = drive?.destination
        val built: Pair<Out, List<Any?>> = if (dest == null) {
            Out("car.route", mapOf("destination" to null, "waypoints" to emptyList<Any>())) to listOf(null)
        } else {
            val place = destination ?: return null
            val wps = drive.waypoints.mapIndexed { i, w ->
                val own = mine.firstOrNull { it.name == w.name }
                val navi = naviWaypoints.firstOrNull { it.name == w.name } ?: naviWaypoints.getOrNull(i)
                mapOf(
                    "index" to INDEX.getOrElse(i) { "N$i" },
                    "name" to w.name,
                    "lat" to (own?.lat ?: navi?.lat),
                    "lng" to (own?.lng ?: navi?.lng),
                    "remainDistM" to w.distM,
                    "remainTimeS" to w.timeS,
                    "requestId" to own?.requestId,
                )
            }
            val data = mapOf(
                "destination" to mapOf(
                    "name" to dest.name, "lat" to place.lat, "lng" to place.lng,
                    "remainDistM" to dest.distM, "remainTimeS" to dest.timeS,
                ),
                "waypoints" to wps,
            )
            Out("car.route", data) to
                listOf(dest.name, place.lat, place.lng) + wps.map { listOf(it["name"], it["lat"], it["lng"]) }
        }
        route = built.first
        routeKey = built.second
        return dueRoute()
    }

    /** 미뤄 둔 에너지·남은 거리를 간격이 지나면 보낸다. 위치를 보낼 때마다 부른다 */
    fun flush(): List<Out> = listOfNotNull(dueEnergy(), dueRoute())

    private fun dueEnergy(): Out? {
        val value = energy ?: return null
        if (value == sentEnergy) return null
        val t = now()
        if (sentEnergy != null && t - energySentAt < Config.ENERGY_MIN_INTERVAL_MS) return null
        sentEnergy = value
        energySentAt = t
        return Out(
            "car.energy",
            mapOf(
                "energyType" to when (value.type) {
                    EnergyType.FUEL -> "FUEL"
                    EnergyType.ELECTRIC -> "EV" // 서버·DB는 EV (erd.md trip.energy_type)
                    null -> null
                },
                "energyPct" to value.pct,
                "rangeM" to value.rangeM,
            ),
        )
    }

    private fun dueRoute(): Out? {
        val out = route ?: return null
        if (out == sentRoute) return null
        val t = now()
        // 구성이 같고 남은 거리·시간만 바뀌었으면 30초에 1번
        if (routeKey == sentRouteKey && t - routeSentAt < Config.ROUTE_REMAIN_INTERVAL_MS) return null
        sentRoute = out
        sentRouteKey = routeKey
        routeSentAt = t
        return out
    }

    private companion object {
        val INDEX = listOf("FIRST", "SECOND", "THIRD")
    }
}
