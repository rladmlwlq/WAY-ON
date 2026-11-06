package com.wayon.car.service

import com.wayon.car.car.CarLocation
import com.wayon.car.car.DriveInfo
import com.wayon.car.car.Gear
import com.wayon.car.car.NaviEvent
import com.wayon.car.server.RestAreaDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 홈 화면의 휴게소 부분 (car-app-design.md 4-1, 6-7) */
data class RestAreaUi(
    /** 주행 중 한 줄: `곧 ○○휴게소`. 도착하면 지움 */
    val approaching: String? = null,
    /** 도착 카드. 떠나면(기어 D) 지움 */
    val arrived: ArrivedCard? = null,
)

data class ArrivedCard(
    val name: String,
    val extId: String?,
    /** 서버 3-7 응답. 불러오는 중이거나 실패하면 null */
    val detail: RestAreaDetail? = null,
    val loadFailed: Boolean = false,
)

/**
 * 휴게소 도착 안내 (A1)를 앱에 잇는 곳. 판단은 [RestAreaArrival], 여기는 입력을 넣고 결과를 나눠 준다.
 *
 * 입력: `myWaypoints`, 내비 남은 거리·이벤트, 차 위치, 기어.
 * 출력: 화면([ui]), 음성([say], 동승자가 없을 때 접근만), 서버([report], `requestId`가 있을 때만),
 * 도착 카드 정보 요청([loadDetail]), 떠난 휴게소는 `myWaypoints`에서 지움.
 */
class RestAreaGuide(
    private val removeWaypoint: (name: String) -> Unit,
    private val keepWaypoints: (names: Set<String>) -> Unit,
    private val passengersOnline: () -> Boolean,
    private val say: (text: String) -> Unit,
    private val report: (type: String, requestId: Long) -> Unit,
    private val loadDetail: (name: String, extId: String) -> Unit,
    private val arrival: RestAreaArrival = RestAreaArrival(),
) {
    private val _ui = MutableStateFlow(RestAreaUi())
    val ui: StateFlow<RestAreaUi> = _ui.asStateFlow()

    private var stops: Map<String, MyWaypoint> = emptyMap()
    private var lastLocation: CarLocation? = null

    /** 운전자가 내비에서 경유지를 지웠다는 콜백 뒤, 다음 남은 거리 정보로 어느 것인지 맞춘다 */
    private var pruneOnNextDrive = false

    fun onWaypoints(list: List<MyWaypoint>) {
        stops = list.filter { it.isRestArea }.associateBy { it.name }
        arrival.sync(stops.values.map { RestAreaStop(it.name, it.requestId, it.lat, it.lng) })
        // 목록에서 빠진 휴게소의 한 줄 안내는 지운다. 도착 카드는 기어 D까지 둔다
        _ui.update { ui -> if (ui.approaching != null && ui.approaching !in stops) ui.copy(approaching = null) else ui }
    }

    fun onLocation(location: CarLocation?) {
        lastLocation = location
    }

    fun onDrive(drive: DriveInfo?) {
        if (drive == null) return
        val inNavi = drive.waypoints.associate { it.name to it.distM }

        if (pruneOnNextDrive) {
            pruneOnNextDrive = false
            // 이미 도착한 휴게소는 내비 목록에서 빠져도 떠날 때(기어 D)까지 둔다
            val arrived = stops.keys.filter { arrival.phase(it) == RestAreaArrival.Phase.ARRIVED }
            keepWaypoints(inNavi.keys + arrived)
        }

        // 이름으로 못 찾으면 차 위치와 기억한 좌표의 직선거리로 대신 (6-7)
        val loc = lastLocation
        val remain = stops.values.mapNotNull { stop ->
            val m = inNavi[stop.name]
                ?: loc?.let { RestAreaArrival.straightLineM(it.lat, it.lng, stop.lat, stop.lng) }
            m?.let { stop.name to it }
        }.toMap()
        handle(arrival.onRemain(remain))
    }

    fun onNavi(event: NaviEvent) {
        when (event) {
            is NaviEvent.WaypointArrived -> handle(arrival.onArrivedCallback(event.name))
            NaviEvent.WaypointRemoved -> pruneOnNextDrive = true
            else -> Unit
        }
    }

    fun onGear(gear: Gear) = handle(arrival.onGear(gear))

    /** [loadDetail] 결과. 그사이 다른 휴게소 카드로 바뀌었으면 버린다 */
    fun onDetail(name: String, detail: RestAreaDetail?) {
        _ui.update { ui ->
            val card = ui.arrived
            if (card?.name != name) ui
            else ui.copy(arrived = card.copy(detail = detail, loadFailed = detail == null))
        }
    }

    private fun handle(events: List<RestAreaArrival.Event>) {
        events.forEach { event ->
            val stop = event.stop
            val extId = stops[stop.name]?.extId
            when (event) {
                is RestAreaArrival.Event.Approach -> {
                    _ui.update { it.copy(approaching = stop.name) }
                    if (!passengersOnline()) say("곧 ${stop.name}입니다")
                    stop.requestId?.let { report(TYPE_APPROACH, it) }
                }
                is RestAreaArrival.Event.Arrived -> {
                    _ui.update { it.copy(approaching = null, arrived = ArrivedCard(stop.name, extId)) }
                    stop.requestId?.let { report(TYPE_ARRIVED, it) }
                    if (extId != null) loadDetail(stop.name, extId) else onDetail(stop.name, null)
                }
                is RestAreaArrival.Event.Left -> {
                    _ui.update { ui ->
                        ui.copy(
                            arrived = ui.arrived?.takeIf { it.name != stop.name },
                            approaching = ui.approaching?.takeIf { it != stop.name },
                        )
                    }
                    stop.requestId?.let { report(TYPE_LEFT, it) }
                    removeWaypoint(stop.name)
                }
            }
        }
    }

    companion object {
        // app-api.md 5-9
        const val TYPE_APPROACH = "restarea.approach"
        const val TYPE_ARRIVED = "restarea.arrived"
        const val TYPE_LEFT = "restarea.left"
    }
}
