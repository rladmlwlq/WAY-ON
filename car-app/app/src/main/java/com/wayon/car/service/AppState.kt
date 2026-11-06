package com.wayon.car.service

import com.wayon.car.car.DriveInfo
import com.wayon.car.car.Energy
import com.wayon.car.car.Gear

/**
 * 화면이 읽는 상태 한 덩어리 (car-app-design.md 2-2, 3장). 서비스 안에서만 바뀐다.
 * 지금 있는 것만 넣었다. online, trip, passengers, pendingRequest, call 등은 기능을 붙일 때 더한다.
 */
data class AppState(
    val gear: Gear = Gear.UNKNOWN,
    val energy: Energy = Energy(),
    val drive: DriveInfo? = null,
    val myWaypoints: List<MyWaypoint> = emptyList(),
    val restArea: RestAreaUi = RestAreaUi(),
) {
    /** 기어를 못 읽으면 주행 중으로 본다 (5장, 안전한 쪽) */
    val parked: Boolean get() = gear == Gear.PARK
}
