package com.wayon.car

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.wayon.car.service.AppState
import com.wayon.car.service.WayOnService
import com.wayon.car.ui.DepartureScreen
import androidx.compose.material3.lightColorScheme
import com.wayon.car.ui.Blue
import com.wayon.car.ui.HomeScreen
import com.wayon.car.ui.EnergyScreen
import com.wayon.car.ui.ParkingScreen
import com.wayon.car.ui.PassengersScreen
import com.wayon.car.ui.RequestScreen
import androidx.compose.foundation.layout.Box
import com.wayon.car.ui.QrScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 화면 1개 (car-app-design.md 2장). 앱이 켜지면 WayOnService를 띄우고 붙어서 AppState를 그린다.
 * 화면은 상태를 읽기만 하고, 버튼은 서비스 함수를 부른다. 화면 6개 중 지금은 홈·QR·휴게소 지도·출발해요 대화가 있다 (4장).
 */
class MainActivity : ComponentActivity() {

    // 알림 권한은 없어도 동작한다. 거부하면 상단 알림과 heads-up만 안 보임 (7-3)
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // 서비스에 붙기 전에는 빈 상태를 보여 준다
    private val emptyState = MutableStateFlow(AppState())
    private val stateSource = mutableStateOf<StateFlow<AppState>>(emptyState)
    // 화면의 버튼이 부를 수 있게 상태로 둔다
    private var service by mutableStateOf<WayOnService?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val bound = (binder as? WayOnService.LocalBinder)?.service ?: return
            service = bound
            stateSource.value = bound.state
            // 화면이 보이는 동안 붙어 있으므로 붙으면 "보임". 도착 알림은 화면이 안 보일 때만 띄운다 (6-7)
            bound.setUiVisible(true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            stateSource.value = emptyState
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotificationPermission()
        startForegroundService(Intent(this, WayOnService::class.java))
        // 창은 늘 전체 크기로 두고 키패드·시스템 바만큼 안쪽 여백을 준다.
        // 창을 줄이면(adjustResize만) 키패드의 빈 곳(위 띠, 숫자판 옆) 뒤가 검게 보인다
        enableEdgeToEdge()

        setContent {
            // 버튼 기본색(보라)을 동승자 화면·폰 웹과 같은 파랑으로
            MaterialTheme(colorScheme = lightColorScheme(primary = Blue)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by stateSource.value.collectAsState()
                    // 동승자 관리는 화면에서만 여닫는다. 정차 중에만 보이고, QR처럼 주행하면 닫는다 (4-6)
                    var managing by remember { mutableStateOf(false) }
                    LaunchedEffect(state.parked) { if (!state.parked) managing = false }
                    // [←]로 대화에서 나온 출발 알림. 알림은 열린 채라 홈의 [대화 보기]로 다시 들어간다
                    var hiddenCallId by rememberSaveable { mutableStateOf<Long?>(null) }
                    // 목적지 근처 주차장 목록 (6-6). 정차 중에만, 주행하면 닫는다
                    var parkingOpen by remember { mutableStateOf(false) }
                    LaunchedEffect(state.parked) { if (!state.parked) parkingOpen = false }
                    // 경로 위 충전소·주유소 목록 (6-5). 정차 중에만, 주행하면 닫는다
                    var energyOpen by remember { mutableStateOf(false) }
                    // 휴게소 지도에서 [←]로 나온 휴게소 이름 (4-7). 다른 휴게소에 도착하면 다시 지도가 뜬다
                    var hiddenRestArea by rememberSaveable { mutableStateOf<String?>(null) }
                    val arrivedName = state.restArea.arrived?.name
                    LaunchedEffect(state.parked) { if (!state.parked) energyOpen = false }
                    // 키패드가 올라오면 내용만 그만큼 위로 (배경은 키패드 뒤까지 그대로)
                    Box(Modifier.safeDrawingPadding()) {
                        Column {
                            // 모든 화면 위 오프라인 띠 (4장, 7-2)
                            if (!state.server.online) OfflineBar()
                            val departure = state.server.departure
                            when {
                                managing && state.parked -> PassengersScreen(
                                    state.server.passengers,
                                    onBack = { managing = false },
                                    onRemove = { service?.removePassenger(it) },
                                )
                                state.server.qr != null && state.parked -> QrScreen(
                                    state,
                                    onBack = { service?.closeQr() },
                                    onRenew = { service?.openQr() },
                                    onManage = { managing = true },
                                )
                                energyOpen && state.parked && state.server.energy != null -> EnergyScreen(
                                    state.server.energy!!,
                                    onChoose = {
                                        service?.chooseEnergy(it)
                                        energyOpen = false
                                    },
                                    onBack = { energyOpen = false },
                                    onClose = {
                                        service?.closeEnergy()
                                        energyOpen = false
                                    },
                                )
                                parkingOpen && state.parked && state.server.parking != null -> ParkingScreen(
                                    state.server.parking!!,
                                    onChoose = {
                                        service?.chooseParking(it)
                                        parkingOpen = false
                                    },
                                    onBack = { parkingOpen = false },
                                    onClose = {
                                        service?.closeParking()
                                        parkingOpen = false
                                    },
                                )
                                // 출발해요 대화는 정차 중에만 (기어 D면 서비스가 닫는다, 4-5)
                                departure != null && state.parked &&
                                    departure.callId.let { it == null || it != hiddenCallId } -> DepartureScreen(
                                    departure,
                                    onQuick = { service?.sendDepartureQuick(it) },
                                    onText = { service?.sendDepartureText(it) },
                                    onRemind = { service?.remindDeparture() },
                                    onCancel = { service?.cancelDeparture() },
                                    onBack = { hiddenCallId = departure.callId },
                                )
                                else -> HomeScreen(
                                    state,
                                    onOpenQr = { service?.openQr() },
                                    onConsent = { service?.giveLocationConsent() },
                                    onDeparture = {
                                        // 열린 알림이 있으면 대화로 돌아가고, 없으면 새로 보낸다
                                        if (departure != null) hiddenCallId = null else service?.callDeparture()
                                    },
                                    onManage = { managing = true },
                                    onParking = { parkingOpen = true },
                                    onEnergy = { energyOpen = true },
                                    mapHidden = arrivedName != null && arrivedName == hiddenRestArea,
                                    onHideMap = { hiddenRestArea = arrivedName },
                                    onShowMap = { hiddenRestArea = null },
                                )
                            }
                        }
                        // 경유지 요청 수락 (4-3): 어느 화면이든 그 위에, 주행 중에도
                        state.server.request?.let {
                            RequestScreen(it, onAccept = { service?.acceptRequest() }, onReject = { service?.rejectRequest() })
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, WayOnService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        service?.setUiVisible(false)
        service = null
        unbindService(connection)
        stateSource.value = emptyState
        super.onStop()
    }

    @Composable
    private fun OfflineBar() {
        Text(
            "서버 연결 끊김 · 다시 연결하는 중",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
