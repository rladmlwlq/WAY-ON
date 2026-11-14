package com.wayon.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wayon.car.Config
import com.wayon.car.car.DemoState
import com.wayon.car.server.Passenger

/**
 * 설정 중 동승자 관리 (car-app-design.md 4-6, 6-10). 정차 중에만 열린다.
 * 각 줄 [해제] → 바로 DELETE (확인 창 없음). 해제된 폰은 서버가 끊고 "연결이 해제되었습니다"를 띄운다.
 * 아래에 시연 주행 (9장). 서버 주소 표시는 그 기능을 붙일 때 더한다.
 */
@Composable
fun PassengersScreen(
    passengers: List<Passenger>,
    demo: DemoState,
    onBack: () -> Unit,
    onRemove: (Long) -> Unit,
    onDemoStart: () -> Unit,
    onDemoStop: () -> Unit,
    onDemoSpeed: (Int) -> Unit,
    onDemoRestArea: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BgTop, BgBottom)))) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("동승자 관리", fontSize = 50.sp, fontWeight = FontWeight.Bold, color = Navy)
            Text(
                "${passengers.size}/${Config.MAX_PASSENGERS}명 · 해제하면 그 폰은 QR을 다시 찍어야 연결돼요",
                fontSize = 24.sp,
                color = Gray,
                modifier = Modifier.padding(top = 12.dp, bottom = 48.dp),
            )

            Column(
                Modifier
                    .width(820.dp)
                    .background(Color.White, RoundedCornerShape(32.dp))
                    .padding(horizontal = 44.dp, vertical = 16.dp),
            ) {
                if (passengers.isEmpty()) {
                    Text(
                        "연결된 동승자가 없어요",
                        fontSize = 32.sp,
                        color = Gray,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                passengers.forEach { p -> PassengerRow(p, onRemove = { onRemove(p.passengerId) }) }
            }

            Spacer(Modifier.height(32.dp))
            DemoCard(demo, onDemoStart, onDemoStop, onDemoSpeed, onDemoRestArea)
        }

        // 화면 전체를 덮는 스크롤 본문보다 뒤에 둬야 위에 그려지고 눌린다
        BackButton(onBack)
    }
}

@Composable
private fun PassengerRow(p: Passenger, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(Modifier.size(18.dp).background(if (p.online) OnlineGreen else Color(0xFFC4CAD4), CircleShape))
        Column(Modifier.weight(1f)) {
            Text(p.nickname, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Navy)
            Text(
                listOfNotNull(if (p.online) "연결 중" else "폰 꺼짐", if (p.pushEnabled) "출발 알림 받음" else null)
                    .joinToString(" · "),
                fontSize = 24.sp,
                color = Gray,
            )
        }
        OutlinedButton(onClick = onRemove, modifier = Modifier.width(120.dp).height(76.dp)) {
            Text("해제", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 시연 주행: 서버 경로선을 따라 차 위치·남은 거리를 움직인다. 기어는 Virtual Console로 바꾼다 */
@Composable
private fun DemoCard(
    demo: DemoState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSpeed: (Int) -> Unit,
    onRestArea: () -> Unit,
) {
    val km = demo.remainM?.let { "%.1fkm".format(it / 1000.0) }
    val status = when {
        !demo.hasRoute -> "내비에서 목적지를 정하면 그 경로를 따라 차가 움직여요"
        demo.arrived -> "도착했어요. [정지]하면 원래 위치로 돌아가요"
        demo.pausedAt != null -> "${demo.pausedAt}에 섰어요 · 기어 P → D 또는 [계속]이면 다시 출발"
        demo.running -> "달리는 중 · 남은 거리 $km"
        else -> "경로 $km · 시작한 뒤 Virtual Console에서 기어를 D로"
    }
    Column(
        Modifier
            .width(820.dp)
            .background(Color.White, RoundedCornerShape(32.dp))
            .padding(horizontal = 44.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("시연 주행", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Navy)
        Text(status, fontSize = 24.sp, color = Gray)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DEMO_SPEEDS.forEach { x ->
                val label = @Composable { Text("${x}배", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                val size = Modifier.height(76.dp)
                if (x == demo.speed) Button(onClick = { onSpeed(x) }, modifier = size) { label() }
                else OutlinedButton(onClick = { onSpeed(x) }, modifier = size) { label() }
            }
            Spacer(Modifier.weight(1f))
            val size = Modifier.width(160.dp).height(76.dp)
            if (demo.pausedAt != null) {
                Button(onClick = onStart, modifier = size) { Text("계속", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
            }
            if (demo.running) {
                OutlinedButton(onClick = onStop, modifier = size) { Text("정지", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
            } else {
                Button(onClick = onStart, enabled = demo.hasRoute, modifier = size) {
                    Text("시작", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        // 미리 정한 시연 경로. 내비 목적지·경유지를 바꾸고, 서버 경로선을 받으면 [시작]이 켜진다
        if (!demo.running) {
            OutlinedButton(onClick = onRestArea, modifier = Modifier.fillMaxWidth().height(76.dp)) {
                Text("기흥휴게소 거쳐 수원역 가는 경로 넣기", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private val DEMO_SPEEDS = listOf(1, 5, 20)

private val OnlineGreen = Color(0xFF1F9D55)
