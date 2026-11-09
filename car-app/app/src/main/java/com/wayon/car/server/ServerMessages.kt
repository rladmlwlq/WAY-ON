package com.wayon.car.server

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** 세션 (auth.ok의 trip, trip.start의 ack). app-api.md 4-1, 5-1 */
data class Trip(val tripId: String)

/** 등록 동승자 1명 (passengers.update, app-api.md 7장). online = 지금 폰 페이지가 서버에 붙어 있음 */
data class Passenger(val passengerId: Long, val nickname: String, val online: Boolean, val pushEnabled: Boolean)

/** 서버가 보낸 메시지 1개 {"type", "reqId", "data"} (app-api.md 4-2) */
data class ServerMessage(val type: String, val reqId: String?, val data: JsonObject) {

    /** auth.ok의 trip, 없으면(null) 세션 없음 */
    fun trip(): Trip? = data.obj("trip")?.str("tripId")?.let(::Trip)

    /** ack의 tripId (trip.start 응답) */
    fun ackTripId(): String? = data.str("tripId")

    fun passengers(): List<Passenger> =
        data.getAsJsonArray("passengers")?.mapNotNull { e ->
            val p = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            Passenger(
                passengerId = p.get("passengerId")?.asLong ?: return@mapNotNull null,
                nickname = p.str("nickname") ?: "",
                online = p.get("online")?.asBoolean == true,
                pushEnabled = p.get("pushEnabled")?.asBoolean == true,
            )
        } ?: emptyList()

    /** error 메시지의 code */
    fun errorCode(): String? = data.str("code")
}

private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.str(name: String): String? = get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asString
