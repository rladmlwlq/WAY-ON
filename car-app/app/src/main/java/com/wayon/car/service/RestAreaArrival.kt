package com.wayon.car.service

import com.wayon.car.Config
import com.wayon.car.car.Gear
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** `myWaypoints` 중 종류가 휴게소인 것 1개. [requestId]가 없으면 운전자가 직접 넣은 것 (서버에 안 보냄) */
data class RestAreaStop(
    val name: String,
    val requestId: Long?,
    val lat: Double,
    val lng: Double,
)

/**
 * 휴게소 접근·도착 판단 (A1, car-app-design.md 6-7). SDK·서버를 모르는 순수 로직이라 단위 시험으로 확인한다.
 *
 * ```
 * 가는중 ─ 남은 거리 ≤ 2km ─▶ 접근 ─ 도착 콜백 또는 기어 P ─▶ 도착 ─ 기어 D ─▶ 떠남(목록에서 뺌)
 * 가는중 ─ 도착 콜백 (2km 판단을 못 했을 때) ─▶ 도착
 * ```
 *
 * 호출하는 쪽(`Rules`)이 할 일:
 * - `myWaypoints`가 바뀌면 [sync]. 운전자가 내비에서 지운 경유지는 여기서 빠진다.
 * - `onDrivingInfo`가 오면 [onRemain]. 경유지 이름으로 남은 거리를 못 찾으면 [straightLineM]으로 대신 채운다.
 * - `onWayPointArrived`면 [onArrivedCallback], 기어가 바뀌면 [onGear].
 * - 나온 [Event]마다: `requestId`가 있으면 `restarea.approach|arrived|left` 전송 (app-api.md 5-9),
 *   화면 바꾸기, 접근은 동승자가 없을 때 음성. [Event.Left]면 `myWaypoints`에서 지운다.
 *
 * 상태는 메모리에만 둔다. 앱을 다시 켜면 모두 가는중부터 다시 본다.
 */
class RestAreaArrival(private val approachM: Int = Config.REST_AREA_APPROACH_M) {

    enum class Phase { GOING, APPROACH, ARRIVED }

    sealed interface Event {
        val stop: RestAreaStop

        data class Approach(override val stop: RestAreaStop) : Event
        data class Arrived(override val stop: RestAreaStop) : Event
        data class Left(override val stop: RestAreaStop) : Event
    }

    private class Tracked(var stop: RestAreaStop, var phase: Phase = Phase.GOING, var lastRemainM: Int? = null)

    // 이름이 키. onDrivingInfo·onWayPointArrived가 이름으로만 알려 준다
    private val tracked = LinkedHashMap<String, Tracked>()

    fun phase(name: String): Phase? = tracked[name]?.phase

    /** 지금 휴게소 경유지 목록으로 맞춘다. 새로 생긴 것은 가는중, 없어진 것은 뺀다 (이벤트 없음) */
    fun sync(stops: List<RestAreaStop>) {
        val names = stops.map { it.name }.toSet()
        tracked.keys.retainAll(names)
        stops.forEach { stop ->
            val t = tracked[stop.name]
            if (t == null) tracked[stop.name] = Tracked(stop) else t.stop = stop
        }
    }

    /** 경유지 이름 → 남은 거리(m). 가는중이던 것이 [approachM] 이하가 되면 접근 (한 번만) */
    fun onRemain(remainByName: Map<String, Int>): List<Event> {
        val events = mutableListOf<Event>()
        remainByName.forEach { (name, remainM) ->
            val t = tracked[name] ?: return@forEach
            t.lastRemainM = remainM
            if (t.phase == Phase.GOING && remainM <= approachM) {
                t.phase = Phase.APPROACH
                events += Event.Approach(t.stop)
            }
        }
        return events
    }

    /** 내비 경유지 도착 콜백. 가는중·접근 어느 쪽에서든 도착 */
    fun onArrivedCallback(name: String): List<Event> {
        val t = tracked[name] ?: return emptyList()
        if (t.phase == Phase.ARRIVED) return emptyList()
        t.phase = Phase.ARRIVED
        return listOf(Event.Arrived(t.stop))
    }

    /**
     * 기어 P: 접근 중인 휴게소에 도착 (여럿이면 가장 가까운 것 하나).
     * 기어 D: 도착해 있던 휴게소를 떠남, 목록에서 뺀다.
     * R·N·모름은 주차하거나 값을 못 읽은 것이라 무시한다.
     */
    fun onGear(gear: Gear): List<Event> = when (gear) {
        Gear.PARK -> {
            if (tracked.values.any { it.phase == Phase.ARRIVED }) {
                emptyList()
            } else {
                tracked.values
                    .filter { it.phase == Phase.APPROACH }
                    .minByOrNull { it.lastRemainM ?: Int.MAX_VALUE }
                    ?.let {
                        it.phase = Phase.ARRIVED
                        listOf(Event.Arrived(it.stop))
                    }
                    ?: emptyList()
            }
        }
        Gear.DRIVE -> {
            val left = tracked.values.filter { it.phase == Phase.ARRIVED }
            left.forEach { tracked.remove(it.stop.name) }
            left.map { Event.Left(it.stop) }
        }
        Gear.REVERSE, Gear.NEUTRAL, Gear.UNKNOWN -> emptyList()
    }

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0

        /** 두 좌표의 직선거리(m). 내비가 경유지 남은 거리를 주지 않을 때 대신 쓴다 */
        fun straightLineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Int {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
            return (EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))).toInt()
        }
    }
}
