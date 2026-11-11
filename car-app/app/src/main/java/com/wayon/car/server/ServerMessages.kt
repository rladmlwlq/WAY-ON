package com.wayon.car.server

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.wayon.car.car.DemoRoute
import com.wayon.car.car.Place

/** 세션 (auth.ok의 trip, trip.start의 ack). app-api.md 4-1, 5-1 */
data class Trip(val tripId: String)

/** 등록 동승자 1명 (passengers.update, app-api.md 7장). online = 지금 폰 페이지가 서버에 붙어 있음 */
data class Passenger(val passengerId: Long, val nickname: String, val online: Boolean, val pushEnabled: Boolean)

/** 동승자의 경유지 요청 (waypoint.request, app-api.md 7-2). action은 ADD | SET_DEST */
data class WaypointRequest(
    val requestId: Long,
    val requestedBy: String,
    val type: String,
    val extId: String?,
    val name: String,
    val address: String?,
    val lat: Double,
    val lng: Double,
    val action: String,
    val expiresAt: String,
)

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

    /** error 메시지의 message (차 화면에 그대로 보여도 되는 문장) */
    fun errorMessage(): String? = data.str("message")

    /** auth.ok의 openCall, departure.call의 ack. callId가 없으면 null */
    fun departureCall(source: JsonObject? = data): DepartureCall? {
        val o = source ?: return null
        return DepartureCall(
            callId = o.get("callId")?.takeIf { it.isJsonPrimitive }?.asLong ?: return null,
            calledAt = o.str("calledAt") ?: "",
            etaS = o.get("etaS")?.takeIf { it.isJsonPrimitive }?.asInt,
            destName = o.str("destName"),
        )
    }

    fun openCall(): DepartureCall? = departureCall(data.obj("openCall"))

    /** departure.status의 people, auth.ok openCall의 people */
    fun departurePeople(source: JsonObject? = data): List<DeparturePerson> =
        source?.getAsJsonArray("people")?.mapNotNull { runCatching { GSON.fromJson(it, DeparturePerson::class.java) }.getOrNull() }
            ?: emptyList()

    fun openCallPeople(): List<DeparturePerson> = departurePeople(data.obj("openCall"))

    /** departure.message */
    fun departureMessage(): DepartureMessage? = runCatching { GSON.fromJson(data, DepartureMessage::class.java) }
        .getOrNull()?.takeIf { it.messageId > 0 }

    fun callId(): Long? = data.get("callId")?.takeIf { it.isJsonPrimitive }?.asLong

    /** departure.closed의 reason (DEPARTED, CANCELLED, EXPIRED) */
    fun closedReason(): String? = data.str("reason")

    private companion object {
        val GSON = Gson()
    }
    /** waypoint.request. 모양이 틀리면 null (화면을 띄우지 않음) */
    fun waypointRequest(): WaypointRequest? {
        val place = data.obj("place") ?: return null
        return WaypointRequest(
            requestId = data.get("requestId")?.takeIf { it.isJsonPrimitive }?.asLong ?: return null,
            requestedBy = data.str("requestedBy") ?: "동승자",
            type = place.str("type") ?: return null,
            extId = place.str("extId"),
            name = place.str("name") ?: return null,
            address = place.str("address"),
            lat = place.get("lat")?.takeIf { it.isJsonPrimitive }?.asDouble ?: return null,
            lng = place.get("lng")?.takeIf { it.isJsonPrimitive }?.asDouble ?: return null,
            action = data.str("action")?.takeIf { it == "ADD" || it == "SET_DEST" } ?: return null,
            expiresAt = data.str("expiresAt") ?: return null,
        )
    }

    /** route.update (app-api.md 7-1) → 시연 주행 경로. 목적지가 없거나 점이 2개 미만이면 null */
    fun demoRoute(): DemoRoute? {
        val path = data.getAsJsonArray("path")?.mapNotNull { e ->
            val p = e.takeIf { it.isJsonArray }?.asJsonArray?.takeIf { it.size() == 2 } ?: return@mapNotNull null
            doubleArrayOf(p[0].asDouble, p[1].asDouble)
        } ?: return null
        if (path.size < 2) return null
        val stops = data.getAsJsonArray("waypoints")?.mapNotNull { e ->
            val w = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            Place(
                w.str("name") ?: return@mapNotNull null,
                w.num("lat") ?: return@mapNotNull null,
                w.num("lng") ?: return@mapNotNull null,
            )
        } ?: emptyList()
        val dist = data.num("distanceM")
        val time = data.num("durationS")
        val speed = if (dist != null && time != null && dist > 0 && time > 0) dist / time else DemoRoute.DEFAULT_SPEED_MPS
        return DemoRoute(path, stops, speed)
    }

    /** waypoint.expired의 requestId */
    fun requestId(): Long? = data.get("requestId")?.takeIf { it.isJsonPrimitive }?.asLong
}

/** 출발해요 (A2, app-api.md 5-12, 7장). 열린 출발 알림 1건 */
data class DepartureCall(val callId: Long, val calledAt: String, val etaS: Int?, val destName: String?)

/** 사람 1명의 마지막 빠른 답장 (null이면 응답 없음) */
data class DeparturePerson(
    val passengerId: Long,
    val nickname: String,
    val lastQuickCode: String?,
    val pushEnabled: Boolean,
)

/** 2-5, 7장 departure.message. sender.passengerId가 null이면 운전자 */
data class DepartureMessage(
    val messageId: Long,
    val callId: Long,
    val sender: DepartureSender,
    val kind: String,
    val quickCode: String?,
    val body: String?,
    val sentAt: String,
)

data class DepartureSender(val passengerId: Long?, val nickname: String)

/** 2-5 응답 */
data class DepartureMessages(val messages: List<DepartureMessage> = emptyList(), val hasMore: Boolean = false)

private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.str(name: String): String? = get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asString

private fun JsonObject.num(name: String): Double? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
