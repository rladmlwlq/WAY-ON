package com.wayon.car.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wayon.car.BuildConfig
import com.wayon.car.Config
import com.wayon.car.car.CarSignals
import com.wayon.car.car.Gear
import com.wayon.car.car.NaviBridge
import com.wayon.car.car.UxRestrictions
import com.wayon.car.server.DepartureCall
import com.wayon.car.server.DepartureMessage
import com.wayon.car.server.DeparturePerson
import com.wayon.car.server.RestAreaApi
import com.wayon.car.server.ServerClient
import com.wayon.car.server.ServerMessage
import com.wayon.car.server.Trip
import com.wayon.car.server.WaypointRequest
import com.wayon.car.car.NaviEvent
import com.wayon.car.car.Place
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import com.wayon.car.server.WayOnApi
import com.wayon.car.server.toApiError
import com.wayon.car.voice.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/**
 * 앱의 두뇌 (car-app-design.md 2장). 화면을 닫아도 서버 통신과 차량 값 읽기를 계속한다.
 * 지금 붙은 것: CarSignals(기어·에너지), NaviBridge(위치·남은 거리), Voice, 휴게소 도착 안내(RestAreaGuide),
 * 에너지 제안·목적지 근처 주차장 판단(DestinationAlerts),
 * 서버 연결(ServerClient: 등록·인증·세션 시작·동승자 목록), QR 화면, 차 위치·경로 전송(TripReporter).
 *
 * 콜백은 모두 lifecycleScope(메인 스레드)에서 처리해서, 상태를 바꾸는 코드가 한 스레드에서만 돈다.
 */
class WayOnService : LifecycleService() {

    lateinit var carSignals: CarSignals
        private set
    private lateinit var navi: NaviBridge
    private lateinit var uxRestrictions: UxRestrictions
    private lateinit var voice: Voice
    private lateinit var myWaypoints: MyWaypoints
    private lateinit var restAreaGuide: RestAreaGuide
    private val destinationAlerts = DestinationAlerts()
    private val reporter = TripReporter()
    private val restAreaApi by lazy { RestAreaApi.create() }
    private val api by lazy { WayOnApi.create() }
    private lateinit var tokenStore: TokenStore
    private lateinit var server: ServerClient
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    /** 서버 연결 쪽 상태. [AppState.server]로 화면에 간다 */
    private val serverUi = MutableStateFlow(ServerUi())
    private var carToken: String? = null
    /** 4401로 다시 등록은 앱을 켤 때마다 1번만 (7-4, 무한 반복 방지) */
    private var reRegistered = false
    private var qrJob: Job? = null
    /** 수락 화면 마감 또는 내비 반영 기다리기 */
    private var requestJob: Job? = null

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private lateinit var restAreaNotifier: RestAreaNotifier

    /** 우리 앱 화면이 보이는지 (보조 창 포함). MainActivity가 onStart/onStop에서 알려 준다 */
    private val uiVisible = MutableStateFlow(false)

    fun setUiVisible(visible: Boolean) {
        uiVisible.value = visible
    }

    inner class LocalBinder : Binder() {
        val service: WayOnService get() = this@WayOnService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        carSignals = CarSignals(applicationContext).also { it.start() }
        navi = NaviBridge(applicationContext).also { it.start() }
        uxRestrictions = UxRestrictions(applicationContext).also { it.start() }
        voice = Voice(applicationContext).also { it.start() }
        myWaypoints = MyWaypoints(applicationContext)
        restAreaNotifier = RestAreaNotifier(applicationContext)
        tokenStore = TokenStore(applicationContext)
        server = ServerClient(lifecycleScope)
        serverUi.update { it.copy(locationConsent = prefs.getBoolean(KEY_CONSENT, false)) }
        restAreaGuide = RestAreaGuide(
            removeWaypoint = myWaypoints::remove,
            keepWaypoints = myWaypoints::keepOnly,
            passengersOnline = { serverUi.value.onlinePassengers.isNotEmpty() },
            say = voice::say,
            report = ::report,
            loadMap = ::loadRestAreaMap,
        )
        wire()
        connectServer()
    }

    private fun wire() {
        val guide = restAreaGuide
        lifecycleScope.launch { myWaypoints.list.collect(guide::onWaypoints) }
        lifecycleScope.launch { navi.location.collect(guide::onLocation) }
        lifecycleScope.launch { navi.drive.collect(guide::onDrive) }
        lifecycleScope.launch { navi.events.collect(guide::onNavi) }
        lifecycleScope.launch { carSignals.gear.collect(guide::onGear) }

        // 에너지 제안·목적지 근처 주차장 (6-5, 6-6): 남은 거리·시간이나 에너지가 바뀔 때마다 본다
        lifecycleScope.launch {
            combine(navi.drive, carSignals.energy) { drive, energy -> destinationAlerts.update(drive?.destination, energy) }
                .collect { it.forEach(::sendAlert) }
        }

        // 정차 = 기어 P이고 주행 규제 없음. 화면(AppState.parked)과 같은 기준
        val parked = combine(carSignals.gear, uxRestrictions.restricted, UxRestrictions::isParked)

        // 도착 알림 (A+B): 앱이 보이면 카드로 충분하고, 안 보이면 알림. 한 번 본 도착은 다시 알리지 않는다.
        // 도착은 내비 도착 콜백으로 달리는 중에도 생기므로, 알림은 정차했을 때만 띄운다 (주행 중 heads-up 금지)
        lifecycleScope.launch {
            var seen: String? = null
            combine(guide.ui, uiVisible, parked) { ui, visible, p -> Triple(ui.arrived, visible, p) }.collect { (card, visible, isParked) ->
                when {
                    card == null -> {
                        seen = null
                        restAreaNotifier.cancel()
                    }
                    visible -> {
                        seen = card.name
                        restAreaNotifier.cancel()
                    }
                    !isParked -> Unit
                    // 정보를 받은 뒤(또는 실패한 뒤, 최대 연결 대기 5초) 한 번 띄운다.
                    // 바로 띄우면 heads-up에 "불러오는 중"만 보이고, 내용이 바뀌어도 다시 내려오지 않는다
                    card.map == null && !card.loadFailed -> Unit
                    seen != card.name -> restAreaNotifier.show(card)
                }
            }
        }

        lifecycleScope.launch {
            combine(
                combine(carSignals.gear, carSignals.energy, navi.location, ::Triple),
                combine(navi.drive, serverUi, ::Pair),
                myWaypoints.list,
                guide.ui,
                uxRestrictions.restricted,
            ) { (gear, energy, location), (drive, server), waypoints, restArea, ux ->
                AppState(gear, energy, location, drive, waypoints, restArea, ux, server)
            }.collect { _state.value = it }
        }

        // 세션 동안 차 위치·경로·기어·에너지를 서버로 (app-api.md 5-3 ~ 5-6).
        // 세션이 열리거나 다시 붙으면 서버는 이전 값을 모르므로 지금 값을 모두 다시 보낸다
        lifecycleScope.launch {
            serverUi.map { it.online && it.trip != null }.distinctUntilChanged().collect { on ->
                if (on) {
                    reporter.reset()
                    report(reporter.location(navi.location.value))
                    report(reporter.gear(carSignals.gear.value))
                    report(reporter.energy(carSignals.energy.value))
                    report(reporter.route(navi.drive.value, navi.destination.value, navi.waypoints.value, myWaypoints.list.value))
                }
            }
        }
        lifecycleScope.launch {
            navi.location.collect {
                report(reporter.location(it))
                reporter.flush().forEach(::report)
            }
        }
        lifecycleScope.launch { carSignals.gear.collect { report(reporter.gear(it)) } }
        lifecycleScope.launch { carSignals.energy.collect { report(reporter.energy(it)) } }
        lifecycleScope.launch {
            combine(navi.drive, navi.destination, navi.waypoints, myWaypoints.list, reporter::route).collect(::report)
        }

        lifecycleScope.launch { server.events.collect(::onServerEvent) }
        // 기어가 D가 되면 QR 화면을 바로 닫는다 (4-2, 주행 중 QR 금지)
        lifecycleScope.launch { parked.collect { if (!it) closeQr() } }
        // 출발해요: 기어 D면 실제로 출발한 것 → 알림을 닫고 홈으로 (6-8). 기어를 못 읽는 순간(UNKNOWN)은 출발로 보지 않는다
        lifecycleScope.launch {
            carSignals.gear.collect { if (it == Gear.DRIVE && serverUi.value.departure != null) closeDeparture("DEPARTED") }
        }
    }

    /** 휴게소 접근·도착·떠남 (app-api.md 5-9). 끊겨 있으면 버린다 (7-2) */
    private fun report(type: String, requestId: Long) {
        if (!server.send(type, mapOf("requestId" to requestId))) log("끊겨서 못 보냄: $type requestId=$requestId")
    }

    /** 5-3 ~ 5-6. 세션이 없거나 끊겨 있으면 버린다 (다시 붙으면 reset으로 다시 감) */
    private fun report(out: TripReporter.Out?) {
        if (out == null || serverUi.value.trip == null) return
        server.send(out.type, out.data)
    }

    /** 에너지 제안·목적지 근처 주차장 (app-api.md 5-7, 5-8). 끊겨 있으면 버린다 (7-2) */
    private fun sendAlert(alert: DestinationAlerts.Alert) {
        val (type, data) = when (alert) {
            is DestinationAlerts.Alert.EnergyLow -> DestinationAlerts.TYPE_ENERGY_LOW to mapOf(
                "energyType" to alert.energyType.name, "rangeM" to alert.rangeM, "remainDistM" to alert.remainDistM,
            )
            is DestinationAlerts.Alert.ParkingNearDest -> DestinationAlerts.TYPE_PARKING_NEAR_DEST to mapOf(
                "remainDistM" to alert.remainDistM, "remainTimeS" to alert.remainTimeS,
            )
        }
        if (!server.send(type, data)) log("끊겨서 못 보냄: $type $data")
    }

    // ---- 서버 연결 (car-app-design.md 6-1, 6-2, 7-2, 7-4) ----

    private fun connectServer() {
        lifecycleScope.launch {
            val token = withContext(Dispatchers.IO) { tokenStore.load() } ?: registerVehicle()
            carToken = token
            server.start(token)
        }
    }

    /** 2-1. 서버가 꺼져 있으면 1·2·4 … 30초 간격으로 될 때까지 */
    private suspend fun registerVehicle(): String {
        var wait = 1_000L
        while (true) {
            val result = runCatching { api.registerVehicle().carToken }
            result.getOrNull()?.let { token ->
                withContext(Dispatchers.IO) { tokenStore.save(token) }
                return token
            }
            log("차량 등록 실패: ${result.exceptionOrNull()?.message}")
            delay(wait)
            wait = ServerClient.nextRetryMs(wait)
        }
    }

    private fun onServerEvent(event: ServerClient.Event) {
        when (event) {
            is ServerClient.Event.Message -> onServerMessage(event.message)
            ServerClient.Event.Disconnected -> {
                serverUi.update { it.copy(online = false) }
                closeRequestIfWaiting() // 끊기면 선점 결과를 못 받는다 (6-4 상태도)
            }
            is ServerClient.Event.Rejected -> onRejected(event.code)
        }
    }

    private fun onServerMessage(message: ServerMessage) {
        when (message.type) {
            "auth.ok" -> {
                val trip = message.trip()
                serverUi.update { it.copy(online = true, trip = trip) }
                if (trip == null) startTrip()
                loadPassengers()
                // 앱을 다시 켰을 때 열린 출발 알림이 있으면 대화를 되살린다
                message.openCall()?.let { openDeparture(it, message.openCallPeople()) }
            }
            "ack" -> when (message.reqId) {
                REQ_TRIP_START -> message.ackTripId()?.let { id -> serverUi.update { it.copy(trip = Trip(id)) } }
                REQ_DEPARTURE_CALL -> message.departureCall()?.let(::onDepartureCalled)
                REQ_ACCEPT -> applyRequest()
            }
            "passengers.update" -> serverUi.update { it.copy(passengers = message.passengers()) }
            "waypoint.request" -> message.waypointRequest()?.let(::showRequest)
            "waypoint.expired" -> if (message.requestId() == serverUi.value.request?.request?.requestId) closeRequestIfWaiting()
            "departure.status" -> {
                val people = message.departurePeople()
                // ack(callId)보다 먼저 와도 버리지 않는다: 보내는 중이면 그대로 담아 둔다
                if (serverUi.value.departure?.callId == null) {
                    serverUi.update { ui -> ui.departure?.let { ui.copy(departure = it.copy(people = people)) } ?: ui }
                } else {
                    updateDeparture(message.callId()) { it.copy(people = people) }
                }
            }
            "departure.message" -> message.departureMessage()?.let { m ->
                updateDeparture(m.callId) { it.copy(messages = mergeMessages(it.messages, listOf(m))) }
            }
            "departure.closed" -> if (message.callId() == serverUi.value.departure?.callId) {
                serverUi.update { it.copy(departure = null) }
                if (message.closedReason() == "EXPIRED") serverUi.update { it.copy(notice = "출발 알림이 30분이 지나 끝났어요") }
            }
            "error" -> {
                log("서버 오류: reqId=${message.reqId} code=${message.errorCode()}")
                when (message.reqId) {
                    REQ_DEPARTURE_CALL -> serverUi.update {
                        it.copy(departure = null, notice = message.errorMessage() ?: "출발 알림을 보내지 못했어요")
                    }
                    REQ_DEPARTURE -> departureError(message.errorMessage() ?: "보내지 못했어요")
                    // REQUEST_EXPIRED 등: 선점 못 함 → 내비를 부르지 않고 닫는다 (6-4)
                    REQ_ACCEPT -> closeRequestIfWaiting()
                }
            }
        }
    }

    // ---- 출발해요 (car-app-design.md 4-5, 6-8, 출발해요 기획) ----

    /**
     * 홈의 [출발해요]. 정차 중이고 받을 동승자가 있을 때만. 목적지 이름·남은 시간을 같이 보내 알림 문구에 쓴다.
     * 서버 ack(callId)를 받을 때까지 대화 화면은 "보내는 중"으로 연다.
     */
    fun callDeparture() {
        val s = _state.value
        val ui = serverUi.value
        if (!s.parked || ui.departure != null || ui.departureReceivers.isEmpty()) return
        val dest = s.drive?.destination
        val data = if (dest == null) emptyMap() else mapOf("destName" to dest.name, "etaS" to dest.timeS)
        if (!server.send("departure.call", data, REQ_DEPARTURE_CALL)) {
            serverUi.update { it.copy(notice = "서버에 연결되지 않아 출발 알림을 보내지 못했어요") }
            return
        }
        serverUi.update { it.copy(departure = DepartureUi(destName = dest?.name, etaS = dest?.timeS)) }
    }

    /** [천천히 와요] [빨리 와요] */
    fun sendDepartureQuick(code: String) = sendDepartureMessage(mapOf("kind" to "QUICK", "quickCode" to code))

    /** 텍스트 1~40자, 정차 중에만 (기획 7장) */
    fun sendDepartureText(text: String) {
        val body = text.trim().take(Config.MESSAGE_MAX_LEN)
        if (body.isEmpty() || !_state.value.parked) return
        sendDepartureMessage(mapOf("kind" to "TEXT", "body" to body))
    }

    /** [다시 알리기]: 아직 답이 없는 사람에게 다시 푸시. 1분에 1번 */
    fun remindDeparture() {
        val d = serverUi.value.departure ?: return
        val callId = d.callId ?: return
        if (System.currentTimeMillis() < d.remindLockedUntilMs) return
        if (!server.send("departure.remind", mapOf("callId" to callId), REQ_DEPARTURE)) {
            departureError("연결이 끊겨서 보내지 못했어요")
            return
        }
        updateDeparture(callId) { it.copy(remindLockedUntilMs = System.currentTimeMillis() + Config.REMIND_INTERVAL_MS) }
    }

    /** [취소] */
    fun cancelDeparture() = closeDeparture("CANCELLED")

    private fun closeDeparture(reason: String) {
        val callId = serverUi.value.departure?.callId
        if (callId != null) server.send("departure.close", mapOf("callId" to callId, "reason" to reason), REQ_DEPARTURE)
        serverUi.update { it.copy(departure = null) }
    }

    private fun onDepartureCalled(call: DepartureCall) {
        // ack 전에 취소했거나 출발했으면 서버의 알림도 닫는다
        if (serverUi.value.departure == null) {
            server.send("departure.close", mapOf("callId" to call.callId, "reason" to "CANCELLED"), REQ_DEPARTURE)
            return
        }
        openDeparture(call, serverUi.value.departure?.people ?: emptyList())
    }

    private fun openDeparture(call: DepartureCall, people: List<DeparturePerson>) {
        val calledAt = runCatching { Instant.parse(call.calledAt).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
        serverUi.update {
            it.copy(
                departure = DepartureUi(
                    callId = call.callId,
                    calledAtMs = calledAt,
                    etaS = call.etaS,
                    destName = call.destName,
                    people = people,
                    messages = it.departure?.takeIf { d -> d.callId == call.callId }?.messages ?: emptyList(),
                    // 서버도 [출발해요] 뒤 1분은 다시 알리기를 받지 않는다
                    remindLockedUntilMs = calledAt + Config.REMIND_INTERVAL_MS,
                ),
            )
        }
        loadDepartureMessages(call.callId)
    }

    /** 2-5 지난 대화. 그사이 실시간으로 온 메시지와 합친다 */
    private fun loadDepartureMessages(callId: Long) {
        val token = carToken ?: return
        lifecycleScope.launch {
            runCatching { api.departureMessages(WayOnApi.bearer(token)) }
                .onSuccess { res -> updateDeparture(callId) { it.copy(messages = mergeMessages(res.messages, it.messages)) } }
                .onFailure { log("지난 대화 실패: ${it.message}") }
        }
    }

    private fun sendDepartureMessage(data: Map<String, Any>) {
        val callId = serverUi.value.departure?.callId ?: return
        if (!server.send("departure.message", data + ("callId" to callId), REQ_DEPARTURE)) {
            departureError("연결이 끊겨서 보내지 못했어요")
        }
    }

    private fun departureError(text: String) {
        val callId = serverUi.value.departure?.callId ?: return
        updateDeparture(callId) { it.copy(error = text) }
        lifecycleScope.launch {
            delay(Config.FAIL_TOAST_MS)
            updateDeparture(callId) { if (it.error == text) it.copy(error = null) else it }
        }
    }

    /** 지금 열린 알림(callId)일 때만 바꾼다 */
    private fun updateDeparture(callId: Long?, change: (DepartureUi) -> DepartureUi) {
        serverUi.update { ui ->
            val d = ui.departure
            if (d == null || callId == null || d.callId != callId) ui else ui.copy(departure = change(d))
        }
    }

    // ---- 경유지 요청 (car-app-design.md 4-3, 6-4) ----

    /**
     * 수락 화면을 띄운다. 떠 있으면 새 요청으로 바꾸고 10초를 다시 센다 (서버가 이전 것을 EXPIRED로 바꿈).
     * **그대로 두면 10초 뒤 자동으로 수락한다** (2026-11-09 결정: 운전 중 화면을 누르지 않아도 되게, [거절]만 누르면 됨).
     * 마감은 서버 expiresAt 대신 받은 때부터 10초로 센다 (기기·서버 시각이 어긋나도 10초 보이게). 서버는 5초 여유를 둔다
     */
    private fun showRequest(request: WaypointRequest) {
        val current = serverUi.value.request
        if (current != null && current.phase >= RequestUi.Phase.APPLYING) return // 내비에 넣는 중이면 끝날 때까지
        requestJob?.cancel()
        val expiresAt = System.currentTimeMillis() + Config.ACCEPT_TIMEOUT_MS
        serverUi.update { it.copy(request = RequestUi(request, expiresAt)) }
        requestJob = lifecycleScope.launch {
            delay(Config.ACCEPT_TIMEOUT_MS)
            acceptRequest()
        }
    }

    /** 수락 화면 [추가] 또는 10초 자동 수락. 서버 선점(ack)을 기다리는 동안 버튼을 잠근다 */
    fun acceptRequest() {
        val ui = serverUi.value.request ?: return
        if (ui.phase != RequestUi.Phase.SHOWING) return
        if (!server.send("waypoint.accept", mapOf("requestId" to ui.request.requestId), REQ_ACCEPT)) {
            closeRequest()
            return
        }
        serverUi.update { it.copy(request = ui.copy(phase = RequestUi.Phase.CLAIMING)) }
    }

    /** 수락 화면 [거절] */
    fun rejectRequest() {
        val ui = serverUi.value.request ?: return
        if (ui.phase != RequestUi.Phase.SHOWING) return
        server.send("waypoint.reject", mapOf("requestId" to ui.request.requestId))
        closeRequest()
    }

    /**
     * 선점 성공(ack) → 내비 호출 → 성공 콜백(경유지 ADD / 목적지 RouteStarted) 또는 오류·60초 → waypoint.result.
     * 성공한 경유지는 myWaypoints에 좌표·requestId와 함께 저장한다 (car.route 좌표, 휴게소 도착 안내 A1).
     * shortcut: 결과를 보낼 때 끊겨 있으면 버린다 (서버에는 ACCEPTED로 남음). 다시 붙은 뒤 보내기는 시험 후 추가
     */
    private fun applyRequest() {
        val ui = serverUi.value.request ?: return
        if (ui.phase != RequestUi.Phase.CLAIMING) return
        requestJob?.cancel()
        serverUi.update { it.copy(request = ui.copy(phase = RequestUi.Phase.APPLYING)) }
        val r = ui.request
        val add = r.action == "ADD"
        requestJob = lifecycleScope.launch {
            // 내비 콜백을 놓치지 않게 먼저 듣기 시작한 뒤 부른다
            val outcome = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(Config.APPLY_TIMEOUT_MS) {
                    navi.events.first {
                        it is NaviEvent.Error || (add && it is NaviEvent.WaypointAdded) || (!add && it is NaviEvent.RouteStarted)
                    }
                }
            }
            val place = Place(r.name, r.lat, r.lng)
            val existing = navi.drive.value?.waypoints?.size ?: 0
            val called = when {
                !add -> navi.requestRoute(place, r.address)
                existing >= Config.MAX_WAYPOINTS -> false // 내비가 아무 응답 없이 무시하므로 부르지 않는다 (6-4)
                else -> navi.addWaypoint(place, r.address, existing)
            }
            val event = if (called) outcome.await() else null.also { outcome.cancel() }
            val ok = event != null && event !is NaviEvent.Error
            if (ok) {
                if (add) myWaypoints.add(MyWaypoint(r.name, r.type, r.lat, r.lng, r.requestId, r.extId))
                server.send("waypoint.result", mapOf("requestId" to r.requestId, "ok" to true))
                closeRequest()
            } else {
                val error = (event as? NaviEvent.Error)?.code ?: if (called) "TIMEOUT" else "NAVI_UNAVAILABLE"
                server.send(
                    "waypoint.result",
                    mapOf("requestId" to r.requestId, "ok" to false, "error" to error, "message" to "내비에 넣지 못했어요"),
                )
                serverUi.update { it.copy(request = ui.copy(phase = RequestUi.Phase.FAILED)) }
                delay(Config.FAIL_TOAST_MS)
                closeRequest()
            }
        }
    }

    /** 아직 버튼을 누르기 전이거나 선점을 기다리는 중이면 닫는다 (만료·끊김·거절당함) */
    private fun closeRequestIfWaiting() {
        val phase = serverUi.value.request?.phase ?: return
        if (phase == RequestUi.Phase.SHOWING || phase == RequestUi.Phase.CLAIMING) closeRequest()
    }

    private fun closeRequest() {
        requestJob?.cancel()
        requestJob = null
        serverUi.update { it.copy(request = null) }
    }

    /** 6-2. 위치 공유에 동의했을 때만 세션을 연다. 이미 있으면 서버가 그것을 돌려준다 */
    private fun startTrip() {
        if (serverUi.value.locationConsent) {
            server.send("trip.start", mapOf("locationConsent" to true), REQ_TRIP_START)
        }
    }

    /** 설정 [해제] (6-10). 서버가 passengers.update도 보내지만 바로 빼서 화면이 기다리지 않게 */
    fun removePassenger(passengerId: Long) {
        val token = carToken ?: return
        lifecycleScope.launch {
            runCatching { api.deletePassenger(WayOnApi.bearer(token), passengerId) }
                .onSuccess { serverUi.update { s -> s.copy(passengers = s.passengers.filter { it.passengerId != passengerId }) } }
                .onFailure { e -> serverUi.update { it.copy(notice = "해제하지 못했어요. ${e.toApiError().message}") } }
        }
    }

    private fun loadPassengers() {
        val token = carToken ?: return
        lifecycleScope.launch {
            runCatching { api.passengers(WayOnApi.bearer(token)) }
                .onSuccess { res -> serverUi.update { it.copy(passengers = res.passengers) } }
                .onFailure { log("동승자 목록 실패: ${it.message}") }
        }
    }

    private fun onRejected(code: Int) {
        serverUi.update { it.copy(online = false, trip = null) }
        if (code == ServerClient.CLOSE_REPLACED) {
            serverUi.update { it.copy(notice = "다른 기기에서 이 차량으로 연결했어요") }
            return
        }
        // 4401: 토큰이 안 먹음 (앱 데이터 삭제, 서버 DB 초기화 등) → 지우고 새 차량으로 등록 (7-4)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { tokenStore.clear() }
            carToken = null
            if (reRegistered) {
                serverUi.update { it.copy(notice = "서버에 연결할 수 없어요. 앱을 다시 켜 주세요") }
                return@launch
            }
            reRegistered = true
            myWaypoints.clear()
            serverUi.update {
                it.copy(passengers = emptyList(), notice = "동승자 연결이 초기화됐어요. QR로 다시 연결해 주세요")
            }
            val token = registerVehicle()
            carToken = token
            server.start(token)
        }
    }

    /** 홈의 [동의하기] (6-1, 7-3) */
    fun giveLocationConsent() {
        prefs.edit().putBoolean(KEY_CONSENT, true).apply()
        serverUi.update { it.copy(locationConsent = true) }
        if (serverUi.value.online && serverUi.value.trip == null) startTrip()
    }

    // ---- QR (car-app-design.md 4-2, 6-3) ----

    /**
     * 정차 중에만 연다. QR 하나로 만료(3분)까지 여러 명이 연결하고, 만료되면 화면이 열린 동안 새로 받는다.
     * 4명이 차면 QR을 숨기고 빈자리가 날 때까지 기다린다. [새로 만들기]·[다시 시도]도 이 함수.
     */
    fun openQr() {
        if (!_state.value.parked) return
        qrJob?.cancel()
        serverUi.update { it.copy(qr = QrUi()) }
        qrJob = lifecycleScope.launch {
            while (isActive) {
                serverUi.first { !it.passengersFull }
                val token = carToken
                if (token == null) {
                    serverUi.update { it.copy(qr = QrUi(error = "서버에 연결하는 중이에요. 잠시 뒤에 다시 시도해 주세요")) }
                    return@launch
                }
                serverUi.update { it.copy(qr = QrUi()) }
                val code = runCatching { api.issueConnectCode(WayOnApi.bearer(token)) }.getOrElse { e ->
                    serverUi.update { it.copy(qr = QrUi(error = e.toApiError().message)) }
                    return@launch
                }
                val expiresAt = runCatching { Instant.parse(code.expiresAt).toEpochMilli() }
                    .getOrDefault(System.currentTimeMillis() + QR_FALLBACK_TTL_MS)
                serverUi.update { it.copy(qr = QrUi(code.url, expiresAt)) }
                // 만료되면 새 코드. 그 전에 4명이 차면 위로 가서 빈자리를 기다린다
                // 기기 시각이 서버와 크게 어긋나도 코드를 쉴 새 없이 다시 받지 않게 최소 10초
                withTimeoutOrNull((expiresAt - System.currentTimeMillis()).coerceAtLeast(10_000L)) {
                    serverUi.first { it.passengersFull }
                }
            }
        }
    }

    fun closeQr() {
        qrJob?.cancel()
        qrJob = null
        if (serverUi.value.qr != null) serverUi.update { it.copy(qr = null) }
    }

    private fun loadRestAreaMap(name: String, extId: String) {
        val id = RestAreaApi.restAreaIdOf(extId) ?: return restAreaGuide.onMap(name, null)
        lifecycleScope.launch {
            val map = runCatching { restAreaApi.map(id) }
                .onFailure { log("휴게소 정보 불러오기 실패: ${it.message}") }
                .getOrNull()
            restAreaGuide.onMap(name, map)
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        server.stop()
        voice.stop()
        uxRestrictions.stop()
        navi.stop()
        carSignals.stop()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "WAY:ON 연결", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("WAY:ON 연결 중")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        // location 타입은 Android 위치 권한이 필요해서 specialUse를 씀 (car-app-design.md 7-1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun log(text: String) {
        if (BuildConfig.DEBUG) Log.d(CarSignals.TAG, text)
    }

    private companion object {
        const val CHANNEL_ID = "wayon_connection"
        const val NOTIFICATION_ID = 1
        const val PREFS = "wayon"
        const val KEY_CONSENT = "locationConsent"
        const val REQ_TRIP_START = "trip.start"
        const val REQ_DEPARTURE_CALL = "departure.call"
        const val REQ_DEPARTURE = "departure"
        const val REQ_ACCEPT = "waypoint.accept"
        /** 서버 expiresAt을 못 읽었을 때 (app-api.md 9장 QR 만료 3분) */
        const val QR_FALLBACK_TTL_MS = 180_000L
    }
}

/** messageId로 겹친 것을 빼고 순서대로 (지난 대화 + 실시간 메시지) */
internal fun mergeMessages(a: List<DepartureMessage>, b: List<DepartureMessage>): List<DepartureMessage> =
    (a + b).associateBy { it.messageId }.values.sortedBy { it.messageId }
