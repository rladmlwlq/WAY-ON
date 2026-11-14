package com.wayon.car.server

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerMessageTest {

    private fun message(type: String, data: String) =
        ServerMessage(type, null, JsonParser.parseString(data).asJsonObject)

    @Test
    fun `auth ok의 trip이 null이면 세션 없음`() {
        assertNull(message("auth.ok", """{"role":"CAR","trip":null,"openCall":null}""").trip())
        assertEquals(
            Trip("a1b2"),
            message("auth.ok", """{"role":"CAR","trip":{"tripId":"a1b2","startedAt":"2026-11-09T00:00:00Z"}}""").trip(),
        )
    }

    @Test
    fun `passengers update를 읽고 모양이 틀린 줄은 건너뛴다`() {
        val list = message(
            "passengers.update",
            """{"passengers":[
                {"passengerId":12,"nickname":"민지","online":true,"pushEnabled":false,"lastConnectedAt":"…"},
                {"nickname":"id 없음"},
                "문자열"
            ]}""",
        ).passengers()
        assertEquals(listOf(Passenger(12, "민지", online = true, pushEnabled = false)), list)
    }

    @Test
    fun `passengers가 없으면 빈 목록`() {
        assertEquals(emptyList<Passenger>(), message("passengers.update", "{}").passengers())
    }

    @Test
    fun `waypoint request를 읽고 action이 틀리면 버린다`() {
        val data = """{"requestId":88,"requestedBy":"민지","action":"ADD","expiresAt":"2026-11-09T08:20:10Z",
            "place":{"type":"REST_AREA","extId":"ra:000048","name":"경산휴게소","address":null,"lat":35.86,"lng":128.8}}"""
        val r = message("waypoint.request", data).waypointRequest()
        assertEquals(WaypointRequest(88, "민지", "REST_AREA", "ra:000048", "경산휴게소", null, 35.86, 128.8, "ADD",
            "2026-11-09T08:20:10Z"), r)
        assertNull(message("waypoint.request", data.replace("\"ADD\"", "\"DELETE\"")).waypointRequest())
        assertEquals(88L, message("waypoint.expired", """{"requestId":88}""").requestId())
    }

    @Test
    fun `재접속 간격은 2배씩 최대 30초`() {
        assertEquals(2_000L, ServerClient.nextRetryMs(1_000))
        assertEquals(30_000L, ServerClient.nextRetryMs(16_000))
        assertEquals(30_000L, ServerClient.nextRetryMs(30_000))
    }
}
