package com.wayon.car.server

import com.google.gson.JsonParser
import com.wayon.car.service.mergeMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 출발해요 메시지 해석 (app-api.md 5-12, 7장) */
class DepartureMessageTest {

    private fun message(type: String, data: String) =
        ServerMessage(type, null, JsonParser.parseString(data).asJsonObject)

    @Test
    fun `auth ok의 openCall과 사람별 상태`() {
        val m = message(
            "auth.ok",
            """{"role":"CAR","openCall":{"callId":40,"calledAt":"2026-11-09T05:00:00Z","etaS":5400,"destName":"부산역",
                "people":[{"passengerId":12,"nickname":"민지","lastQuickCode":"TOILET","pushEnabled":true},
                          {"passengerId":13,"nickname":"준호","lastQuickCode":null,"pushEnabled":false}]}}""",
        )
        assertEquals(DepartureCall(40, "2026-11-09T05:00:00Z", 5400, "부산역"), m.openCall())
        assertEquals(listOf("TOILET", null), m.openCallPeople().map { it.lastQuickCode })
        assertNull(message("auth.ok", """{"openCall":null}""").openCall())
    }

    @Test
    fun `departure call의 ack은 목적지가 없어도 읽는다`() {
        val ack = message("ack", """{"callId":41,"calledAt":"2026-11-09T05:00:00Z","etaS":null,"destName":null}""")
        assertEquals(DepartureCall(41, "2026-11-09T05:00:00Z", null, null), ack.departureCall())
    }

    @Test
    fun `운전자 메시지는 sender passengerId가 null`() {
        val m = message(
            "departure.message",
            """{"messageId":302,"callId":40,"sender":{"passengerId":null,"nickname":"운전자"},
                "kind":"TEXT","quickCode":null,"body":"B동 앞이에요","sentAt":"2026-11-09T05:01:00Z"}""",
        ).departureMessage()!!
        assertNull(m.sender.passengerId)
        assertEquals("B동 앞이에요", m.body)
        assertNull(message("departure.message", """{"callId":40}""").departureMessage())
    }

    @Test
    fun `지난 대화와 실시간 메시지는 id로 합친다`() {
        fun msg(id: Long) = DepartureMessage(id, 40, DepartureSender(12, "민지"), "QUICK", "TOILET", null, "")
        assertEquals(listOf(1L, 2L, 3L), mergeMessages(listOf(msg(1), msg(3)), listOf(msg(3), msg(2))).map { it.messageId })
    }
}
