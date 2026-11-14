package com.wayon.car.car

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointCmdType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.DrivingState
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wayon.car.BuildConfig
import com.wayon.car.Config
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** 차 위치 (NaviHelper `getCurrentLocationInfo`). FusedLocation은 에뮬레이터에서 미국 좌표라 쓰지 않음 (10장) */
data class CarLocation(val lat: Double, val lng: Double, val heading: Int, val atMs: Long)

/** 목적지 또는 경유지 하나의 남은 거리(m)·시간(초) */
data class Remain(val name: String, val distM: Int, val timeS: Int)

/** `onDrivingInfo`. 목적지가 없으면 [destination]이 null. 경유지 좌표는 없음 (그래서 앱이 기억함) */
data class DriveInfo(val destination: Remain?, val waypoints: List<Remain>)

/** 목적지·경유지 좌표 (`getDestinationInfo`, `getWaypointInfo`). 서버 길찾기(app-api.md 5-4)에 쓴다 */
data class Place(val name: String, val lat: Double, val lng: Double)

/**
 * 시연 주행 (car-app-design.md 9장). [hasRoute]가 false면 서버 경로선을 아직 못 받음.
 * [speed]는 배속 (1, 5, 20). [remainM]은 경로 끝까지 남은 거리.
 * [pausedAt]: 경유지에 닿아 서 있는 중이면 그 이름. 기어 P → D 또는 [계속]이면 다시 달린다
 */
data class DemoState(
    val running: Boolean = false,
    val speed: Int = 5,
    val hasRoute: Boolean = false,
    val remainM: Int? = null,
    val arrived: Boolean = false,
    val pausedAt: String? = null,
)

/** 상태가 아니라 한 번 일어나는 일 */
sealed interface NaviEvent {
    data class WaypointArrived(val name: String) : NaviEvent
    /** 운전자가 내비에서 경유지를 지움. 어느 것인지는 다음 [DriveInfo]의 경유지 이름으로 판단 (index를 믿지 않음, 10장) */
    data object WaypointRemoved : NaviEvent
    data object WaypointAdded : NaviEvent
    data object RouteStarted : NaviEvent
    data object RouteCancelled : NaviEvent
    data class Error(val code: String) : NaviEvent
}

/**
 * 내장 내비(NaviHelper)와 연결 (car-app-design.md 2장, 10장).
 *
 * - 위치는 콜백이 없어서 [Config.LOCATION_INTERVAL_MS]마다 `getCurrentLocationInfo()`로 묻는다.
 * - 남은 거리·시간은 내비가 `onDrivingInfo`로 알려 준다.
 * - 경유지 추가·목적지 변경(6-4)은 아직 없다. 넣을 때 이 클래스에 더한다.
 * - 권한 NAVI_ROUTE는 normal이라 매니페스트 선언만 필요하다.
 */
class NaviBridge(context: Context) {

    private val navi = NaviHelper(context)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var connected = false

    private val _location = MutableStateFlow<CarLocation?>(null)
    val location: StateFlow<CarLocation?> = _location.asStateFlow()

    private val _drive = MutableStateFlow<DriveInfo?>(null)
    val drive: StateFlow<DriveInfo?> = _drive.asStateFlow()

    /** 목적지 좌표. 목적지·경유지 구성이 바뀔 때마다 다시 묻는다 */
    private val _destination = MutableStateFlow<Place?>(null)
    val destination: StateFlow<Place?> = _destination.asStateFlow()

    private val _waypoints = MutableStateFlow<List<Place>>(emptyList())
    val waypoints: StateFlow<List<Place>> = _waypoints.asStateFlow()

    private val _events = MutableSharedFlow<NaviEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<NaviEvent> = _events.asSharedFlow()

    private val _demo = MutableStateFlow(DemoState())
    val demo: StateFlow<DemoState> = _demo.asStateFlow()
    private var demoRoute: DemoRoute? = null
    /** 시연 경로 출발점에서 간 거리(m) */
    private var demoM = 0.0
    /** 시연 중 지나간 경유지. 내비는 계속 경유지로 알려 주므로 이 이름은 뺀다 */
    private val demoPassed = mutableSetOf<String>()
    /** 내비가 마지막으로 준 남은 거리·시간. 시연 중에는 이름만 이것을 따르고 거리는 경로선으로 계산한다 */
    @Volatile private var naviDrive: DriveInfo? = null
    /** 경유지에 선 뒤 기어 P를 한 번 거쳤는지 */
    private var demoParkedAtStop = false
    private var demoGear = Gear.UNKNOWN

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            if (_demo.value.running) return
            val it = currentLocationInfo
            _location.value = CarLocation(it.latitude, it.longitude, it.heading.toInt().mod(360), System.currentTimeMillis())
        }

        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            val info = drivingInfo.toDriveInfo()
            val old = naviDrive
            naviDrive = info
            if (!_demo.value.running) _drive.value = info
            // 남은 거리·시간은 자주 바뀌니 이름이 바뀔 때만 좌표를 다시 묻는다
            val names = { d: DriveInfo? -> listOf(d?.destination?.name) + d?.waypoints.orEmpty().map { it.name } }
            if (names(old) != names(info)) requestPlaces()
        }

        @Suppress("SENSELESS_COMPARISON")
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            val name = destinationInfo.name
            _destination.value =
                if (name == null || name.isBlank()) null else Place(name, destinationInfo.latitude, destinationInfo.longitude)
        }

        @Suppress("SENSELESS_COMPARISON")
        override fun onWayPointInfo(waypointInfos: List<WaypointInfo>) {
            // 에뮬레이터는 경유지 좌표를 0.0, 0.0으로 준다 (car-app-design.md 10장). 그런 값은 모르는 것으로 둔다
            _waypoints.value = waypointInfos.mapNotNull { w ->
                w?.name?.takeIf { it.isNotBlank() && (w.latitude != 0.0 || w.longitude != 0.0) }
                    ?.let { Place(it, w.latitude, w.longitude) }
            }
        }

        override fun onDestinationChanged() {
            requestPlaces()
        }

        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            val name = waypointArrivedInfo.name
            log("경유지 도착: $name")
            if (!name.isNullOrBlank()) emit(NaviEvent.WaypointArrived(name))
        }

        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
            log("경유지 변경: ${waypointChangedInfo.cmdType}")
            when (waypointChangedInfo.cmdType) {
                WaypointCmdType.REMOVE -> emit(NaviEvent.WaypointRemoved)
                WaypointCmdType.ADD -> emit(NaviEvent.WaypointAdded)
                else -> Unit
            }
            requestPlaces()
        }

        override fun onRouteStarted(routeStartInfo: ai.pleos.playground.navi.data.RouteStartInfo) {
            emit(NaviEvent.RouteStarted)
            requestPlaces()
        }

        override fun onRouteCancelled() {
            emit(NaviEvent.RouteCancelled)
            _destination.value = null
            _waypoints.value = emptyList()
        }

        override fun onError(errorCode: NaviErrorCode) {
            log("내비 오류: $errorCode")
            emit(NaviEvent.Error(errorCode.toString()))
        }
    }

    private val poll = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            if (connected) runCatching { navi.getCurrentLocationInfo() }
            main.postDelayed(this, Config.LOCATION_INTERVAL_MS)
        }
    }

    fun start() {
        navi.initialize(
            onConnected = {
                navi.addListener(listener)
                connected = true
                log("내비 연결됨")
                main.post(poll)
                requestPlaces()
            },
            onFailed = { log("내비 연결 실패: ${it.message}") },
        )
    }

    fun stop() {
        main.removeCallbacks(poll)
        main.removeCallbacks(demoTick)
        connected = false
        runCatching { navi.removeListener(listener) }
        navi.release()
    }

    /**
     * 경유지 추가 (car-app-design.md 6-4). 경유지가 없으면 FIRST, 1개면 SECOND (10장).
     * 성공은 [NaviEvent.WaypointAdded], 실패는 [NaviEvent.Error]로 온다. 부르지 못하면 false
     */
    @SuppressLint("MissingPermission")
    fun addWaypoint(place: Place, address: String?, existing: Int): Boolean {
        if (!connected) return false
        return runCatching {
            navi.addWaypoint(
                RequestWaypointInfo(
                    latitude = place.lat,
                    longitude = place.lng,
                    poiName = place.name,
                    waypointIndex = if (existing == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
                    // 내비 POI ID는 모른다 (카카오·우리 휴게소 ID라서). 좌표·이름으로 넣는다
                    poiId = "",
                    address = address ?: "",
                    poiSubId = "",
                ),
            )
        }.onFailure { log("addWaypoint 실패: ${it.message}") }.isSuccess
    }

    /** 목적지 바꾸기 (SET_DEST). 성공은 [NaviEvent.RouteStarted] */
    @SuppressLint("MissingPermission")
    fun requestRoute(place: Place, address: String?): Boolean {
        if (!connected) return false
        return runCatching {
            navi.requestRoute(
                RouteInfo(
                    latitude = place.lat, longitude = place.lng, poiName = place.name,
                    poiId = "", address = address ?: "", poiSubId = "",
                ),
            )
        }.onFailure { log("requestRoute 실패: ${it.message}") }.isSuccess
    }

    // ---- 시연 주행 (car-app-design.md 9장). 에뮬레이터 차는 움직이지 않아서 서버 경로선을 따라 위치·남은 거리를 대신 준다.
    // 메인 스레드에서만 부른다

    private val demoTick = object : Runnable {
        override fun run() {
            val route = demoRoute
            if (route != null && _demo.value.pausedAt == null) demoM =(demoM + route.speedMps * _demo.value.speed * DEMO_TICK_MS / 1000).coerceAtMost(route.totalM)
            publishDemo()
            main.postDelayed(this, DEMO_TICK_MS)
        }
    }

    /** 서버 경로선 (route.update). 시연 중이면 지금 위치에서 이어 달린다 (경유지를 넣으면 서버가 지금 위치부터 다시 만든다) */
    fun setDemoRoute(route: DemoRoute?) {
        val here = _location.value
        demoRoute = route
        demoM = if (route != null && here != null && _demo.value.running) route.nearestM(here.lat, here.lng) else 0.0
        publishDemo()
    }

    /** 경로 출발점(또는 지금 위치에서 가까운 곳)부터 달린다. 경로가 없으면 아무것도 안 함 */
    fun startDemo() {
        val route = demoRoute ?: return
        if (_demo.value.pausedAt != null) return resumeDemo()
        if (_demo.value.running) return
        val here = _location.value
        demoM = if (here == null) 0.0 else route.nearestM(here.lat, here.lng)
        if (demoM >= route.totalM) demoM = 0.0 // 끝에 서 있으면 처음부터 다시
        demoPassed.clear()
        _demo.value = _demo.value.copy(running = true)
        main.post(demoTick)
        log("시연 주행 시작 ${route.totalM.toInt()}m")
    }

    /** 멈추면 내비가 주는 진짜 위치·남은 거리로 돌아간다 */
    @SuppressLint("MissingPermission")
    fun stopDemo() {
        if (!_demo.value.running) return
        main.removeCallbacks(demoTick)
        _demo.value = _demo.value.copy(running = false, arrived = false, pausedAt = null)
        _drive.value = naviDrive
        if (connected) runCatching { navi.getCurrentLocationInfo() }
        publishDemo()
    }

    fun setDemoSpeed(speed: Int) {
        _demo.value = _demo.value.copy(speed = speed)
    }

    /** 경유지에 서 있을 때: 실제처럼 기어 P(휴게소 지도) → D(떠남)면 다시 달린다 */
    fun onDemoGear(gear: Gear) {
        demoGear = gear
        if (_demo.value.pausedAt == null) return
        if (gear == Gear.PARK) demoParkedAtStop = true
        else if (gear == Gear.DRIVE && demoParkedAtStop) resumeDemo()
    }

    private fun resumeDemo() {
        demoParkedAtStop = false
        _demo.value = _demo.value.copy(pausedAt = null)
        // 에뮬레이터 내비는 차가 안 움직여 지나간 경유지를 스스로 빼지 않는다. 남은 경유지로 경로를 다시 넣어
        // 실제 주행처럼 경유지 수(최대 2)가 줄게 한다. 서버는 지금(시연) 위치부터 경로선을 다시 만든다
        val route = demoRoute ?: return
        val dest = route.destination ?: return
        requestRouteVia(dest, route.stops.filter { it.name !in demoPassed })
    }

    private fun publishDemo() {
        val route = demoRoute
        val s = _demo.value
        var pausedAt = s.pausedAt
        if (s.running && route != null) {
            _location.value = route.locationAt(demoM, System.currentTimeMillis())
            val base = naviDrive
            val waypoints = base?.waypoints.orEmpty().mapNotNull { w ->
                if (w.name in demoPassed) return@mapNotNull null
                // 경로선에 없는 경유지(좌표를 모르는 것)는 내비 값 그대로
                val at = route.stopAtM[w.name] ?: return@mapNotNull w
                if (demoM < at - DEMO_ARRIVE_M) return@mapNotNull route.remain(w.name, demoM, at)
                demoPassed += w.name
                pausedAt = w.name // 경유지(휴게소)에 들어가 선다
                demoParkedAtStop = demoGear == Gear.PARK // 기어를 안 바꾸고 시연하면 이미 P
                emit(NaviEvent.WaypointArrived(w.name))
                null
            }
            _drive.value = DriveInfo(base?.destination?.let { route.remain(it.name, demoM, route.totalM) }, waypoints)
        }
        val left = route?.let { (it.totalM - demoM).coerceAtLeast(0.0).toInt() }
        _demo.value = s.copy(hasRoute = route != null, remainM = left, arrived = s.running && left == 0, pausedAt = pausedAt)
    }

    /** 목적지와 경유지를 한 번에 (시연 경로). 성공은 [NaviEvent.RouteStarted] */
    @SuppressLint("MissingPermission")
    fun requestRouteVia(destination: Place, waypoints: List<Place>): Boolean {
        if (!connected) return false
        return runCatching {
            navi.requestRouteWithWaypoints(
                RouteWithWaypointsRequest(
                    RouteInfo(
                        latitude = destination.lat, longitude = destination.lng, poiName = destination.name,
                        poiId = "", address = "", poiSubId = "",
                    ),
                    waypoints.mapIndexed { i, w ->
                        RequestWaypointInfo(
                            latitude = w.lat, longitude = w.lng, poiName = w.name,
                            waypointIndex = if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
                            poiId = "", address = "", poiSubId = "",
                        )
                    },
                ),
            )
        }.onFailure { log("requestRouteWithWaypoints 실패: ${it.message}") }.isSuccess
    }

    /** 답은 [listener]의 onDestinationInfo·onWayPointInfo로 온다 */
    @SuppressLint("MissingPermission")
    private fun requestPlaces() {
        if (!connected) return
        runCatching {
            navi.getDestinationInfo()
            navi.getWaypointInfo()
        }
    }

    private fun emit(event: NaviEvent) {
        _events.tryEmit(event)
    }

    private fun log(text: String) {
        if (BuildConfig.DEBUG) Log.d(CarSignals.TAG, text)
    }

    private companion object {
        const val DEMO_TICK_MS = 1_000L
        /** 경유지 이만큼 앞이면 도착으로 본다 */
        const val DEMO_ARRIVE_M = 30
    }
}

// 목적지가 없을 때 이름이 비어서 올 수 있다. SDK 타입이 null을 막지 않으므로 둘 다 본다
@Suppress("SENSELESS_COMPARISON", "UNNECESSARY_SAFE_CALL")
internal fun DrivingInfo.toDriveInfo(): DriveInfo {
    val dest = destination?.toRemain()
    val wps = (waypoints ?: emptyList()).mapNotNull { it?.toRemain() }
    return DriveInfo(dest, wps)
}

@Suppress("SENSELESS_COMPARISON")
private fun DrivingState.toRemain(): Remain? {
    val n = name
    if (n == null || n.isBlank()) return null
    return Remain(n, distance, duration)
}
