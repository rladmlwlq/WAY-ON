package com.wayon.car

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 임시 확인용: 에뮬레이터/차량에서 두 위치 소스가 실제로 값을 주는지 비교합니다.
 *
 * - FusedLocation: isSupported() 결과, 위치 구독 결과
 * - NaviHelper: getCurrentLocationInfo()를 5초마다 호출한 결과
 *
 * Logcat 필터: tag:WAYON_LOC
 * 확인이 끝나면 정식 LocationSource로 대체하고 이 파일은 지웁니다.
 */
class LocationCheck(
    context: Context,
    private val onFused: (String) -> Unit,
    private val onNavi: (String) -> Unit,
) {
    private val fused = FusedLocation(context)
    private val navi = NaviHelper(context)
    private val main = Handler(Looper.getMainLooper())

    val fusedSupported: Boolean = fused.isSupported()

    private val fusedListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            val text = location?.let {
                "lat=${it.latitude}, lng=${it.longitude}, heading=${it.heading}, " +
                    "confidence=${it.confidence}, time=${it.timestampMillis}"
            } ?: "null 위치"
            report(onFused, "[Fused] $text")
        }

        override fun onError(exception: Exception) {
            report(onFused, "[Fused] 오류: ${exception.message}")
        }
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val it = currentLocationInfo
            report(
                onNavi,
                "[Navi] lat=${it.latitude}, lng=${it.longitude}, heading=${it.heading}, " +
                    "address=${it.address}, routeId=${it.routeId}, index=${it.currentRouteIndex}"
            )
        }

        override fun onError(errorCode: NaviErrorCode) {
            report(onNavi, "[Navi] 오류: $errorCode")
        }
    }

    private val naviPoll = object : Runnable {
        @SuppressLint("MissingPermission") // NAVI_ROUTE는 normal 권한이라 매니페스트 선언으로 충분
        override fun run() {
            navi.getCurrentLocationInfo()
            main.postDelayed(this, POLL_MS)
        }
    }

    /** FUSED_LOCATION 런타임 권한을 받은 뒤 호출합니다. */
    @SuppressLint("MissingPermission")
    fun startFused() {
        if (!fusedSupported) {
            report(onFused, "[Fused] isSupported=false (위치 서비스 없음)")
            return
        }
        fused.initialize()
        fused.registerFusedLocationCallback(fusedListener)
        report(onFused, "[Fused] isSupported=true, 구독 시작")
    }

    fun startNavi() {
        navi.initialize(
            onConnected = {
                navi.addListener(naviListener)
                report(onNavi, "[Navi] 연결됨, 5초마다 요청")
                main.post(naviPoll)
            },
            onFailed = { report(onNavi, "[Navi] 연결 실패: ${it.message}") },
        )
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        main.removeCallbacks(naviPoll)
        runCatching { navi.removeListener(naviListener) }
        navi.release()
        if (fusedSupported) {
            runCatching { fused.unregisterFusedLocationCallback(fusedListener) }
            fused.release()
        }
    }

    private fun report(target: (String) -> Unit, text: String) {
        Log.i(TAG, text)
        main.post { target(text) }
    }

    companion object {
        const val TAG = "WAYON_LOC"
        const val FUSED_PERMISSION = "pleos.car.permission.FUSED_LOCATION"
        private const val POLL_MS = 5_000L
    }
}
