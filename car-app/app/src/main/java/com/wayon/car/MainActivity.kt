package com.wayon.car

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    // 임시: 위치 소스 확인 (LocationCheck.kt 참고)
    private lateinit var locationCheck: LocationCheck
    private var fusedText by mutableStateOf("확인 중…")
    private var naviText by mutableStateOf("확인 중…")

    private val fusedPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) locationCheck.startFused() else fusedText = "[Fused] 권한 거부됨"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locationCheck = LocationCheck(
            context = this,
            onFused = { fusedText = it },
            onNavi = { naviText = it },
        )
        startLocationCheck()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("WAY:ON", style = MaterialTheme.typography.headlineLarge)
                        Text("FusedLocation", style = MaterialTheme.typography.titleMedium)
                        Text(fusedText)
                        Text("NaviHelper getCurrentLocationInfo()", style = MaterialTheme.typography.titleMedium)
                        Text(naviText)
                    }
                }
            }
        }
    }

    private fun startLocationCheck() {
        locationCheck.startNavi()

        val granted = ContextCompat.checkSelfPermission(this, LocationCheck.FUSED_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        when {
            !locationCheck.fusedSupported -> locationCheck.startFused() // 미지원 메시지만 표시
            granted -> locationCheck.startFused()
            else -> fusedPermission.launch(LocationCheck.FUSED_PERMISSION)
        }
    }

    override fun onDestroy() {
        locationCheck.stop()
        super.onDestroy()
    }
}
