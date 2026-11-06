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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.wayon.car.service.AppState
import com.wayon.car.service.WayOnService
import com.wayon.car.ui.HomeScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 화면 1개 (car-app-design.md 2장). 앱이 켜지면 WayOnService를 띄우고 붙어서 AppState를 그린다.
 * 화면은 상태를 읽기만 한다. 화면 6개 중 지금은 홈만 있다 (4장).
 */
class MainActivity : ComponentActivity() {

    // 알림 권한은 없어도 동작한다. 거부하면 상단 알림과 heads-up만 안 보임 (7-3)
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // 서비스에 붙기 전에는 빈 상태를 보여 준다
    private val emptyState = MutableStateFlow(AppState())
    private val stateSource = mutableStateOf<StateFlow<AppState>>(emptyState)
    private var service: WayOnService? = null

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

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by stateSource.value.collectAsState()
                    HomeScreen(state)
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

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
