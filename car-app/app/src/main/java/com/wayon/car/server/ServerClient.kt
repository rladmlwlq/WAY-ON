package com.wayon.car.server

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wayon.car.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * 서버 WebSocket /ws (app-api.md 4장, car-app-design.md 7-2).
 * 접속 → auth {carToken} → auth.ok. 20초마다 ping. 끊기면 1·2·4 … 30초 뒤 다시 접속.
 * 4401(토큰 무효)·4409(다른 기기에서 접속)는 다시 붙지 않고 [Event.Rejected]로 알린다.
 *
 * OkHttp 콜백은 [scope](서비스의 메인 스레드)로 넘겨서, 상태는 한 스레드에서만 바뀐다.
 */
class ServerClient(
    private val scope: CoroutineScope,
    private val url: String = Config.SERVER_URL.replaceFirst("http", "ws").trimEnd('/') + "/ws",
) {
    sealed interface Event {
        data class Message(val message: ServerMessage) : Event
        /** 끊김. 곧 다시 접속한다 */
        data object Disconnected : Event
        /** 다시 접속하지 않는 닫힘 (4401, 4409) */
        data class Rejected(val code: Int) : Event
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var token: String? = null
    private var socket: WebSocket? = null
    private var authed = false
    private var retryMs = FIRST_RETRY_MS
    private var pingJob: Job? = null
    private var retryJob: Job? = null

    /** 접속 시작. 이미 붙어 있으면 끊고 새 토큰으로 */
    fun start(carToken: String) {
        stop()
        token = carToken
        retryMs = FIRST_RETRY_MS
        open()
    }

    fun stop() {
        token = null
        retryJob?.cancel()
        pingJob?.cancel()
        socket?.close(NORMAL_CLOSE, null)
        socket = null
        authed = false
    }

    /** auth.ok 뒤에만 보낸다. 끊겨 있으면 false (car.* 값은 버림, car-app-design.md 7-2) */
    fun send(type: String, data: Any = emptyMap<String, Any>(), reqId: String? = null): Boolean {
        if (!authed) return false
        val message = buildMap {
            put("type", type)
            if (reqId != null) put("reqId", reqId)
            put("data", data)
        }
        return socket?.send(gson.toJson(message)) == true
    }

    private fun open() {
        val carToken = token ?: return
        socket = client.newWebSocket(Request.Builder().url(url).build(), Listener(carToken))
    }

    private inner class Listener(private val carToken: String) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            // 토큰은 URL에 넣지 않고 첫 메시지로 (Pleos 보안 가이드, app-api.md 0장)
            webSocket.send(gson.toJson(mapOf("type" to "auth", "data" to mapOf("token" to carToken))))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
            val message = ServerMessage(
                type = json.get("type")?.asString ?: return,
                reqId = json.get("reqId")?.takeIf { it.isJsonPrimitive }?.asString,
                data = json.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject(),
            )
            scope.launch {
                if (webSocket !== socket) return@launch
                if (message.type == "auth.ok") onAuthed()
                _events.emit(Event.Message(message))
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            scope.launch { onClosed(webSocket, code) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scope.launch { onClosed(webSocket, null) }
        }
    }

    private fun onAuthed() {
        authed = true
        retryMs = FIRST_RETRY_MS
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(Config.PING_INTERVAL_MS)
                socket?.send(PING)
            }
        }
    }

    /** code가 null이면 연결 실패·끊김 */
    private suspend fun onClosed(webSocket: WebSocket, code: Int?) {
        if (webSocket !== socket) return // 이미 정리했거나 새 연결로 바뀐 소켓
        socket = null
        authed = false
        pingJob?.cancel()
        if (code == CLOSE_AUTH_FAILED || code == CLOSE_REPLACED) {
            token = null
            _events.emit(Event.Rejected(code))
            return
        }
        _events.emit(Event.Disconnected)
        val wait = retryMs
        retryMs = nextRetryMs(retryMs)
        retryJob = scope.launch {
            delay(wait)
            open()
        }
    }

    companion object {
        const val CLOSE_AUTH_FAILED = 4401
        const val CLOSE_REPLACED = 4409
        private const val NORMAL_CLOSE = 1000
        private const val FIRST_RETRY_MS = 1_000L
        private const val PING = """{"type":"ping"}"""

        /** 1·2·4 … 최대 30초 */
        fun nextRetryMs(current: Long): Long = minOf(current * 2, Config.RECONNECT_MAX_MS)
    }
}
