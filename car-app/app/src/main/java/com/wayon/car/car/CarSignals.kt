package com.wayon.car.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.RangeRemainingListener
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.wayon.car.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 서버로 보내는 기어 값 (app-api.md 5-6 `car.gear`) */
enum class Gear { PARK, DRIVE, REVERSE, NEUTRAL, UNKNOWN }

/** app-api.md 5-5 `car.energy`의 `energyType` */
enum class EnergyType { FUEL, ELECTRIC }

/**
 * 연료·배터리 값. 연료차·전기차를 알려 주는 API가 없어서, 값이 나오는 쪽으로 [type]을 정한다
 * (car-app-design.md 10장: 에뮬레이터 차는 전기차라 연료 %가 오류로 옴).
 */
data class Energy(
    val type: EnergyType? = null,
    val pct: Int? = null,
    val rangeM: Int? = null,
)

/**
 * Vehicle SDK로 기어, 주행 가능 거리, 연료·배터리 %를 읽는다 (car-app-design.md 2장).
 *
 * - Vehicle SDK는 서비스 연결 전 호출을 쌓아 두었다가 연결되면 실행하므로 [start] 직후 바로 등록한다.
 * - 기어를 못 읽으면 [Gear.UNKNOWN]이고, 화면은 이를 주행 중으로 본다 (5장, 안전한 쪽).
 * - 권한 CAR_DRIVE, CAR_ENERGY는 normal이라 매니페스트 선언만 필요하다.
 */
class CarSignals(context: Context) {

    private val vehicle = Vehicle(context)

    private val _gear = MutableStateFlow(Gear.UNKNOWN)
    val gear: StateFlow<Gear> = _gear.asStateFlow()

    private val _energy = MutableStateFlow(Energy())
    val energy: StateFlow<Energy> = _energy.asStateFlow()

    // 전기차·연료차 판단용 원값. 둘 다 오면 배터리를 우선한다.
    @Volatile private var fuelPct: Int? = null
    @Volatile private var batteryPct: Int? = null

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            val mapped = gear.toGear()
            if (_gear.value != mapped) log("기어 $gear → $mapped")
            _gear.value = mapped
        }
        override fun onFailed(e: Exception) = log("기어 읽기 실패: ${e.message}")
    }

    private val rangeListener = object : RangeRemainingListener {
        override fun onRangeRemainingChanged(rangeRemaining: Float?) {
            val m = rangeRemaining?.toInt()
            if (_energy.value.rangeM != m) log("주행 가능 거리 ${m}m")
            _energy.update { it.copy(rangeM = m) }
        }
        override fun onFailed(e: Exception) = log("주행 가능 거리 읽기 실패: ${e.message}")
    }

    private val fuelListener = object : FuelPercentListener {
        override fun onFuelPercentUpdated(value: Int?) {
            fuelPct = value
            refreshType()
        }
        // 전기차에서는 속성이 없어 실패가 정상이다
        override fun onFailed(e: Exception) = log("연료 % 없음 (전기차면 정상): ${e.message}")
    }

    private val batteryListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
            batteryPct = value
            refreshType()
        }
        override fun onFailed(e: Exception) = log("배터리 % 없음 (연료차면 정상): ${e.message}")
    }

    /** 리스너 등록 직후 한 번 현재 값을 채운다. 리스너는 값이 바뀔 때만 오기 때문. */
    @Suppress("DEPRECATION") // Odometer 주행 가능 거리: Pleos가 deprecated 표시, 내부는 같은 RANGE_REMAINING 속성
    @SuppressLint("MissingPermission")
    fun start() {
        vehicle.initialize()

        vehicle.getGear().registerGearSelection(gearListener)
        vehicle.getOdometer().registerRangeRemainingListener(rangeListener)
        vehicle.getFuel().registerFuelPercent(fuelListener)
        vehicle.getEvBattery().registerEvBatteryStateOfCharge(batteryListener)

        vehicle.getGear().getCurrentGearSelectionState(
            onComplete = gearListener::onSelected,
            onFailed = gearListener::onFailed,
        )
        vehicle.getOdometer().getRangeRemainingDistance(
            onComplete = rangeListener::onRangeRemainingChanged,
            onFailed = rangeListener::onFailed,
        )
        vehicle.getFuel().getFuelPercent(
            onComplete = fuelListener::onFuelPercentUpdated,
            onFailed = fuelListener::onFailed,
        )
        vehicle.getEvBattery().getEvBatteryStateOfCharge(
            onComplete = batteryListener::onEvBatteryStateOfChargeUpdated,
            onFailed = batteryListener::onFailed,
        )
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun stop() {
        runCatching { vehicle.getGear().unregisterGearSelection(gearListener) }
        runCatching { vehicle.getOdometer().unregisterRangeRemainingListener(rangeListener) }
        runCatching { vehicle.getFuel().unregisterFuelPercent(fuelListener) }
        runCatching { vehicle.getEvBattery().unregisterEvBatteryStateOfCharge(batteryListener) }
        vehicle.release()
    }

    private fun refreshType() {
        val battery = batteryPct
        val fuel = fuelPct
        val (type, pct) = when {
            battery != null -> EnergyType.ELECTRIC to battery
            fuel != null -> EnergyType.FUEL to fuel
            else -> null to null
        }
        val before = _energy.value
        if (before.type != type || before.pct != pct) log("에너지 $type $pct%")
        _energy.update { it.copy(type = type, pct = pct) }
    }

    private fun log(text: String) {
        // 릴리스에서는 로그를 남기지 않는다 (Pleos 보안 가이드, car-app-design.md 14장)
        if (BuildConfig.DEBUG) Log.d(TAG, text)
    }

    companion object {
        const val TAG = "WAYON_CAR"
    }
}

/** 수동 변속 1~9단도 앞으로 가는 중이므로 DRIVE로 본다. */
internal fun VehicleGear?.toGear(): Gear = when (this) {
    VehicleGear.GEAR_PARK -> Gear.PARK
    VehicleGear.GEAR_REVERSE -> Gear.REVERSE
    VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
    VehicleGear.GEAR_DRIVE,
    VehicleGear.GEAR_1, VehicleGear.GEAR_2, VehicleGear.GEAR_3,
    VehicleGear.GEAR_4, VehicleGear.GEAR_5, VehicleGear.GEAR_6,
    VehicleGear.GEAR_7, VehicleGear.GEAR_8, VehicleGear.GEAR_9 -> Gear.DRIVE
    VehicleGear.GEAR_UNKNOWN, null -> Gear.UNKNOWN
}
