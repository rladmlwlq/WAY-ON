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
import com.wayon.car.car.CarSignals
import com.wayon.car.car.NaviBridge
import com.wayon.car.car.UxRestrictions
import com.wayon.car.server.RestAreaApi
import com.wayon.car.voice.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 앱의 두뇌 (car-app-design.md 2장). 화면을 닫아도 서버 통신과 차량 값 읽기를 계속한다.
 * 지금 붙은 것: CarSignals(기어·에너지), NaviBridge(위치·남은 거리), Voice, 휴게소 도착 안내(RestAreaGuide).
 * ServerClient가 붙으면 [report]가 실제로 서버에 보낸다.
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
    private val restAreaApi by lazy { RestAreaApi.create() }

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
        restAreaGuide = RestAreaGuide(
            removeWaypoint = myWaypoints::remove,
            keepWaypoints = myWaypoints::keepOnly,
            passengersOnline = { false }, // TODO passengers.update가 붙으면 online 동승자가 있는지로
            say = voice::say,
            report = ::report,
            loadMap = ::loadRestAreaMap,
        )
        wire()
    }

    private fun wire() {
        val guide = restAreaGuide
        lifecycleScope.launch { myWaypoints.list.collect(guide::onWaypoints) }
        lifecycleScope.launch { navi.location.collect(guide::onLocation) }
        lifecycleScope.launch { navi.drive.collect(guide::onDrive) }
        lifecycleScope.launch { navi.events.collect(guide::onNavi) }
        lifecycleScope.launch { carSignals.gear.collect(guide::onGear) }

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
                navi.drive,
                myWaypoints.list,
                guide.ui,
                uxRestrictions.restricted,
            ) { (gear, energy, location), drive, waypoints, restArea, ux ->
                AppState(gear, energy, location, drive, waypoints, restArea, ux)
            }.collect { _state.value = it }
        }
    }

    /** 서버로 보낼 메시지. ServerClient(WebSocket)가 생기면 그쪽 send로 바꾼다 (app-api.md 5-9) */
    private fun report(type: String, requestId: Long) {
        log("서버로 보낼 것 (아직 연결 없음): $type requestId=$requestId")
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
    }
}
