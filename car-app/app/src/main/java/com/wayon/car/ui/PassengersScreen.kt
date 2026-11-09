package com.wayon.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wayon.car.Config
import com.wayon.car.server.Passenger

/**
 * 설정 중 동승자 관리 (car-app-design.md 4-6, 6-10). 정차 중에만 열린다.
 * 각 줄 [해제] → 확인 한 번 → DELETE. 해제된 폰은 서버가 끊고 "연결이 해제되었습니다"를 띄운다.
 * 시연용 위치 재생, 서버 주소 표시는 그 기능을 붙일 때 더한다.
 */
@Composable
fun PassengersScreen(passengers: List<Passenger>, onBack: () -> Unit, onRemove: (Long) -> Unit) {
    var confirm by remember { mutableStateOf<Passenger?>(null) }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BgTop, BgBottom)))) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("동승자 관리", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Navy)
            Text(
                "${passengers.size}/${Config.MAX_PASSENGERS}명 · 해제하면 그 폰은 QR을 다시 찍어야 연결돼요",
                style = MaterialTheme.typography.titleMedium,
                color = Gray,
                modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
            )

            Column(
                Modifier
                    .width(560.dp)
                    .background(Color.White, RoundedCornerShape(24.dp))
                    .padding(horizontal = 32.dp, vertical = 12.dp),
            ) {
                if (passengers.isEmpty()) {
                    Text(
                        "연결된 동승자가 없어요",
                        style = MaterialTheme.typography.titleLarge,
                        color = Gray,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                passengers.forEach { p -> PassengerRow(p, onRemove = { confirm = p }) }
            }
        }

        // 화면 전체를 덮는 스크롤 본문보다 뒤에 둬야 위에 그려지고 눌린다
        BackButton(onBack)
    }

    confirm?.let { p ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("${p.nickname} 님 연결을 해제할까요?") },
            text = { Text("이 폰에서 보낸 장소 제안도 더는 받지 않아요.", style = MaterialTheme.typography.titleMedium) },
            confirmButton = {
                TextButton(onClick = { onRemove(p.passengerId); confirm = null }) {
                    Text("해제", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("취소", style = MaterialTheme.typography.titleMedium) }
            },
        )
    }
}

@Composable
private fun PassengerRow(p: Passenger, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(12.dp).background(if (p.online) OnlineGreen else Color(0xFFC4CAD4), CircleShape))
        Column(Modifier.weight(1f)) {
            Text(p.nickname, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy)
            Text(
                listOfNotNull(if (p.online) "연결 중" else "폰 꺼짐", if (p.pushEnabled) "출발 알림 받음" else null)
                    .joinToString(" · "),
                style = MaterialTheme.typography.titleMedium,
                color = Gray,
            )
        }
        OutlinedButton(onClick = onRemove) { Text("해제", style = MaterialTheme.typography.titleMedium) }
    }
}

private val OnlineGreen = Color(0xFF1F9D55)
