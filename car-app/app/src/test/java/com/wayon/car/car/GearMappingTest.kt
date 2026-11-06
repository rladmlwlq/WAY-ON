package com.wayon.car.car

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import org.junit.Assert.assertEquals
import org.junit.Test

class GearMappingTest {

    @Test
    fun `P R N은 그대로`() {
        assertEquals(Gear.PARK, VehicleGear.GEAR_PARK.toGear())
        assertEquals(Gear.REVERSE, VehicleGear.GEAR_REVERSE.toGear())
        assertEquals(Gear.NEUTRAL, VehicleGear.GEAR_NEUTRAL.toGear())
    }

    @Test
    fun `D와 수동 1~9단은 모두 DRIVE`() {
        assertEquals(Gear.DRIVE, VehicleGear.GEAR_DRIVE.toGear())
        listOf(
            VehicleGear.GEAR_1, VehicleGear.GEAR_2, VehicleGear.GEAR_3,
            VehicleGear.GEAR_4, VehicleGear.GEAR_5, VehicleGear.GEAR_6,
            VehicleGear.GEAR_7, VehicleGear.GEAR_8, VehicleGear.GEAR_9,
        ).forEach { assertEquals(Gear.DRIVE, it.toGear()) }
    }

    @Test
    fun `모르거나 null이면 UNKNOWN (화면은 주행 중으로 봄)`() {
        assertEquals(Gear.UNKNOWN, VehicleGear.GEAR_UNKNOWN.toGear())
        assertEquals(Gear.UNKNOWN, (null as VehicleGear?).toGear())
    }
}
