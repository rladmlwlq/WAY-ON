package com.wayon.car

import ai.pleos.playground.vehicle.Vehicle
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 임시 확인용: 에너지 제안(F6)에 쓸 차량 값이 에뮬레이터/차량에서 실제로 나오는지 봅니다.
 *
 * - 주행 가능 거리: Odometer.getRangeRemainingDistance() (단위 m)
 *   Pleos가 deprecated(WARNING)로 표시하고 Google RANGE_REMAINING 속성을 쓰라고 안내합니다.
 *   내부는 같은 RANGE_REMAINING 속성을 읽으므로, 값이 나오면 이대로 쓰고 나중에 바꿀지 정합니다.
 * - 연료 %: Fuel.getFuelPercent(), 배터리 %: EvBattery.getEvBatteryStateOfCharge()
 *   둘 중 값이 나오는 쪽으로 연료차/전기차를 판단합니다 (공개 API에 연료 종류 조회가 없음).
 * - 기어: Gear.getCurrentGearSelectionState() (P에서만 화면을 여는 데 씀)
 *
 * 권한은 모두 normal이라 매니페스트 선언만 필요합니다 (CAR_DRIVE, CAR_ENERGY).
 * Logcat 필터: tag:WAYON_CAR
 * 확인이 끝나면 정식 코드로 옮기고 이 파일은 지웁니다.
 */
class VehicleCheck(
    context: Context,
    private val onText: (String) -> Unit,
) {
    private val vehicle = Vehicle(context)
    private val main = Handler(Looper.getMainLooper())
    private val values = linkedMapOf(
        "주행 가능 거리" to "…",
        "연료" to "…",
        "배터리" to "…",
        "기어" to "…",
    )

    private val poll = object : Runnable {
        override fun run() {
            readAll()
            main.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        vehicle.initialize()
        // 서비스 연결 완료 콜백이 없어서 잠깐 기다린 뒤 읽기 시작합니다.
        main.postDelayed(poll, FIRST_DELAY_MS)
    }

    fun stop() {
        main.removeCallbacks(poll)
        vehicle.release()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission") // CAR_DRIVE, CAR_ENERGY는 normal 권한
    private fun readAll() {
        vehicle.getOdometer().getRangeRemainingDistance(
            onComplete = { m -> put("주행 가능 거리", m?.let { "%.1f km (%.0f m)".format(it / 1000, it) } ?: "null") },
            onFailed = { put("주행 가능 거리", "오류: ${it.message}") },
        )
        vehicle.getFuel().getFuelPercent(
            onComplete = { put("연료", it?.let { p -> "$p %" } ?: "null") },
            onFailed = { put("연료", "오류: ${it.message}") },
        )
        vehicle.getEvBattery().getEvBatteryStateOfCharge(
            onComplete = { put("배터리", it?.let { p -> "$p %" } ?: "null") },
            onFailed = { put("배터리", "오류: ${it.message}") },
        )
        vehicle.getGear().getCurrentGearSelectionState(
            onComplete = { put("기어", it?.name ?: "null") },
            onFailed = { put("기어", "오류: ${it.message}") },
        )
    }

    private fun put(key: String, value: String) {
        main.post {
            if (values[key] != value) Log.i(TAG, "$key = $value")
            values[key] = value
            onText(values.entries.joinToString("\n") { "${it.key}: ${it.value}" })
        }
    }

    companion object {
        const val TAG = "WAYON_CAR"
        private const val FIRST_DELAY_MS = 1_000L
        private const val POLL_MS = 5_000L
    }
}
