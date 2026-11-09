package com.wayon.car.car

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import com.wayon.car.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 플랫폼 주행 규제 (Pleos 주행 규제 개발 가이드: `CarUxRestrictionsManager`).
 * DO를 선언한 앱도 규제 중이면 스스로 제한해야 하므로, 화면·알림은 이 값과 기어를 함께 본다 ([isParked]).
 *
 * - [restricted]: true = 규제 중(주행 중 취급), false = 규제 없음, null = 못 받음(차 서비스 없음)
 * - 못 받으면 기어만으로 판단한다. 기어 P만 정차로 보는 쪽이 플랫폼 기준보다 엄격해서 안전은 그대로다.
 */
class UxRestrictions(private val context: Context) {

    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    private val _restricted = MutableStateFlow<Boolean?>(null)
    val restricted: StateFlow<Boolean?> = _restricted.asStateFlow()

    fun start() {
        // android.car가 없는 기기면 클래스 자체가 없어 Error가 난다. runCatching은 Throwable까지 잡는다
        runCatching {
            val c = Car.createCar(context) ?: error("차 서비스 없음")
            car = c
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            manager = m
            update(m.currentCarUxRestrictions)
            m.registerListener { update(it) }
        }.onFailure { log("주행 규제 못 받음, 기어로만 판단: ${it.message}") }
    }

    fun stop() {
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
        manager = null
        car = null
    }

    private fun update(r: CarUxRestrictions?) {
        val value = r?.isRequiresDistractionOptimization
        if (_restricted.value != value) log("주행 규제 $value (${r?.activeRestrictions})")
        _restricted.value = value
    }

    private fun log(text: String) {
        if (BuildConfig.DEBUG) Log.d(CarSignals.TAG, text)
    }

    companion object {
        /** 정차: 기어 P이고 플랫폼 규제가 없을 때. 규제 값을 못 받으면(null) 기어만 본다 */
        fun isParked(gear: Gear, restricted: Boolean?): Boolean = gear == Gear.PARK && restricted != true
    }
}
