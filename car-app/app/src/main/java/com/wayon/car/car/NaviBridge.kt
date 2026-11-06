package com.wayon.car.car

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointCmdType
import ai.pleos.playground.navi.data.CurrentLocationInfo
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

    private val _events = MutableSharedFlow<NaviEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<NaviEvent> = _events.asSharedFlow()

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val it = currentLocationInfo
            _location.value = CarLocation(it.latitude, it.longitude, it.heading.toInt().mod(360), System.currentTimeMillis())
        }

        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            _drive.value = drivingInfo.toDriveInfo()
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
        }

        override fun onRouteStarted(routeStartInfo: ai.pleos.playground.navi.data.RouteStartInfo) {
            emit(NaviEvent.RouteStarted)
        }

        override fun onRouteCancelled() {
            emit(NaviEvent.RouteCancelled)
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
            },
            onFailed = { log("내비 연결 실패: ${it.message}") },
        )
    }

    fun stop() {
        main.removeCallbacks(poll)
        connected = false
        runCatching { navi.removeListener(listener) }
        navi.release()
    }

    private fun emit(event: NaviEvent) {
        _events.tryEmit(event)
    }

    private fun log(text: String) {
        if (BuildConfig.DEBUG) Log.d(CarSignals.TAG, text)
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
