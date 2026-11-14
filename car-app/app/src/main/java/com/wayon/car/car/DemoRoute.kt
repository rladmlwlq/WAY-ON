package com.wayon.car.car

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 시연 주행의 경로 한 벌 (car-app-design.md 9장). 서버가 만든 경로선(route.update `path`)을 따라
 * "출발점에서 m미터 간 곳"의 위치·방향과 경유지·목적지까지 남은 거리를 계산한다. SDK를 모르는 순수 로직.
 *
 * @param path [lng, lat] 점 목록 (app-api.md 7-1 순서 그대로)
 * @param stops 경유지 (경로선 위 가장 가까운 점을 그 경유지 자리로 본다)
 * @param speedMps 실제 속도 (경로 거리 / 경로 시간). 남은 시간 계산과 1배속 이동에 쓴다
 * @param destination 목적지. 지나간 경유지를 내비에서 뺄 때 다시 넣는다
 */
class DemoRoute(path: List<DoubleArray>, val stops: List<Place>, val speedMps: Double, val destination: Place? = null) {

    private val lat = path.map { it[1] }
    private val lng = path.map { it[0] }
    /** 각 점까지 누적 거리(m) */
    private val cum = DoubleArray(path.size)

    init {
        require(path.size >= 2) { "경로 점이 2개 이상이어야 해요" }
        for (i in 1 until path.size) cum[i] = cum[i - 1] + meters(lat[i - 1], lng[i - 1], lat[i], lng[i])
    }

    val totalM: Double get() = cum.last()

    /** 경유지 이름 → 경로상 위치(m). 앞 경유지 뒤에서 찾아 경로가 겹쳐도 순서를 지킨다 */
    val stopAtM: Map<String, Double> = buildMap {
        var from = 0
        for (s in stops) {
            val i = nearestIndex(s.lat, s.lng, from)
            put(s.name, cum[i])
            from = i
        }
    }

    /** 지금 위치에서 가장 가까운 경로 점의 누적 거리. 새 경로를 받았을 때 이어서 달리는 데 쓴다 */
    fun nearestM(lat: Double, lng: Double): Double = cum[nearestIndex(lat, lng, 0)]

    /** 출발점에서 [m]미터 간 곳 */
    fun locationAt(m: Double, atMs: Long): CarLocation {
        val d = m.coerceIn(0.0, totalM)
        var i = cum.indexOfFirst { it >= d }.coerceAtLeast(1)
        // 길이 0인 구간(같은 점이 두 번)은 방향을 못 구해서 다음 구간을 쓴다
        while (i < cum.size - 1 && cum[i] == cum[i - 1]) i++
        val seg = cum[i] - cum[i - 1]
        val t = if (seg == 0.0) 1.0 else (d - cum[i - 1]) / seg
        return CarLocation(
            lat = lat[i - 1] + (lat[i] - lat[i - 1]) * t,
            lng = lng[i - 1] + (lng[i] - lng[i - 1]) * t,
            heading = bearing(lat[i - 1], lng[i - 1], lat[i], lng[i]),
            atMs = atMs,
        )
    }

    /** [m]미터 간 곳에서 [targetM]까지 남은 거리·시간 */
    fun remain(name: String, m: Double, targetM: Double): Remain {
        val left = (targetM - m).coerceAtLeast(0.0)
        return Remain(name, left.roundToInt(), (left / speedMps).roundToInt())
    }

    private fun nearestIndex(la: Double, ln: Double, from: Int): Int =
        (from until lat.size).minBy { meters(la, ln, lat[it], lng[it]) }

    companion object {
        /**
         * 휴게소를 거치는 시연 경로: (지금 위치, 에뮬레이터는 성남) → 경부선 기흥(부산)휴게소 → 수원역.
         * 휴게소 좌표·코드는 server/src/main/resources/db/seed/rest_area.csv 000005
         */
        val REST_AREA_STOP = Place("기흥(부산)휴게소", 37.23509, 127.10519)
        const val REST_AREA_EXT_ID = "ra:000005"
        val REST_AREA_DEST = Place("수원역", 37.265790, 127.000095)

        /** 경로 시간을 모를 때 (시속 60km) */
        const val DEFAULT_SPEED_MPS = 60 / 3.6

        /** 짧은 거리라 평면 근사 */
        internal fun meters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val y = (lat2 - lat1) * 111_320.0
            val x = (lng2 - lng1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
            return sqrt(x * x + y * y)
        }

        /** 북쪽 0도, 시계 방향 */
        internal fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Int {
            val y = (lat2 - lat1)
            val x = (lng2 - lng1) * cos(Math.toRadians((lat1 + lat2) / 2))
            if (x == 0.0 && y == 0.0) return 0
            return Math.toDegrees(atan2(x, y)).roundToInt().mod(360)
        }
    }
}
