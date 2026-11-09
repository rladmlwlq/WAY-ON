package com.wayon.car.service

import com.wayon.car.Config
import com.wayon.car.car.Energy
import com.wayon.car.car.EnergyType
import com.wayon.car.car.Remain

/**
 * 목적지 1개당 한 번 보내는 알림 판단 (car-app-design.md 6-5, 6-6, app-api.md 5-7, 5-8).
 * SDK·서버를 모르는 순수 로직이라 단위 시험으로 확인한다.
 *
 * - 에너지 제안 (F6): 주행 가능 거리 < 목적지 남은 거리 + [energyMarginM] → `energy.low`.
 *   보낸 뒤에는 주유·충전으로 주행 가능 거리가 늘어 조건이 풀려야 다시 본다. 조금 늘었는데 아직 모자라면 다시 보내지 않는다.
 * - 목적지 근처 주차장 (N5): 남은 거리 ≤ [parkingDistM] 또는 남은 시간 ≤ [parkingTimeS] → `parking.near-dest`.
 * - 목적지가 바뀌면(이름 기준, 없어진 것 포함) 둘 다 지운다. 목적지가 없으면 비교하지 않는다.
 *
 * 호출하는 쪽: 목적지 남은 거리·시간(`onDrivingInfo`)이나 에너지가 바뀔 때마다 [update]를 부르고,
 * 나온 [Alert]를 서버로 보낸다. 동승자가 없을 때의 음성·장소 목록은 서버가 돌려주는 `*.suggest`로 한다 (6-5 4번).
 *
 * 상태는 메모리에만 둔다 (3장 `alerted`). 앱을 다시 켜면 처음부터 다시 본다.
 */
class DestinationAlerts(
    private val energyMarginM: Int = Config.ENERGY_MARGIN_M,
    private val parkingDistM: Int = Config.PARKING_NEAR_DIST_M,
    private val parkingTimeS: Int = Config.PARKING_NEAR_TIME_S,
) {

    sealed interface Alert {
        /** app-api.md 5-7 */
        data class EnergyLow(val energyType: EnergyType, val rangeM: Int, val remainDistM: Int) : Alert

        /** app-api.md 5-8 */
        data class ParkingNearDest(val remainDistM: Int, val remainTimeS: Int) : Alert
    }

    private var destName: String? = null

    /** 에너지 알림을 보냈을 때의 주행 가능 거리. null이면 아직 안 보냄 */
    private var energyAlertedAtM: Int? = null
    private var parkingAlerted = false

    fun update(destination: Remain?, energy: Energy): List<Alert> {
        if (destination?.name != destName) {
            destName = destination?.name
            energyAlertedAtM = null
            parkingAlerted = false
        }
        if (destination == null) return emptyList()
        return listOfNotNull(energyAlert(destination, energy), parkingAlert(destination))
    }

    private fun energyAlert(destination: Remain, energy: Energy): Alert? {
        val rangeM = energy.rangeM ?: return null
        // 연료·전기를 모르면 서버가 주유소·충전소 중 무엇을 찾을지 못 정하므로 기다린다
        val type = energy.type ?: return null
        val remainM = destination.distM
        if (remainM <= 0) return null // 경로 계산 전
        val low = rangeM < remainM + energyMarginM

        val alertedAt = energyAlertedAtM
        if (alertedAt != null) {
            // 주유·충전 뒤: 늘어서 조건이 풀리면 다시 볼 수 있게 지운다
            if (!low && rangeM > alertedAt) energyAlertedAtM = null
            return null
        }
        if (!low) return null
        energyAlertedAtM = rangeM
        return Alert.EnergyLow(type, rangeM, remainM)
    }

    private fun parkingAlert(destination: Remain): Alert? {
        if (parkingAlerted) return null
        val distM = destination.distM
        val timeS = destination.timeS
        // 0 이하는 아직 계산 전으로 본다 (경로를 막 잡았을 때 0으로 오면 바로 보내지 않게)
        val near = (distM > 0 && distM <= parkingDistM) || (timeS > 0 && timeS <= parkingTimeS)
        if (!near) return null
        parkingAlerted = true
        return Alert.ParkingNearDest(distM, timeS)
    }

    companion object {
        const val TYPE_ENERGY_LOW = "energy.low"
        const val TYPE_PARKING_NEAR_DEST = "parking.near-dest"
    }
}
