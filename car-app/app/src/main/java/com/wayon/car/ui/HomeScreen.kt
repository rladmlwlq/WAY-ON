package com.wayon.car.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wayon.car.service.AppState
import com.wayon.car.service.ArrivedCard

/**
 * 홈 (car-app-design.md 4-1). 동승자 줄, 목적지 남은 시간, 휴게소 안내, 정차 중 [동승자 연결].
 * 정차 중이고 받을 동승자가 있으면 [출발해요]. [주변 장소] 버튼은 그 기능을 붙일 때 더한다.
 * 주행 중에는 짧은 문구만 보인다. 휴게소에 도착(기어 P)하면 휴게소 지도로 바뀐다 (4-7).
 */
@Composable
fun HomeScreen(
    state: AppState,
    modifier: Modifier = Modifier,
    onOpenQr: () -> Unit = {},
    onConsent: () -> Unit = {},
    onDeparture: () -> Unit = {},
) {
    val arrived = state.restArea.arrived
    // [출발해요]: 정차 중이고 알림을 받을 동승자가 1명 이상일 때 (출발해요 기획 7장)
    val canDepart = state.parked && state.server.locationConsent && state.server.departureReceivers.isNotEmpty()
    // 도착은 내비 도착 콜백으로 달리는 중에도 생길 수 있다. 정차(기어 P, 주행 규제 없음)일 때만 지도를 연다
    val map = arrived?.map
    if (state.parked && map != null) {
        Column(modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("WAY:ON", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(map.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                // 휴게소 지도 위 [출발해요] (4-7)
                if (canDepart) DepartureButton(onDeparture)
            }
            RestAreaMapScreen(map, state.location, Modifier.weight(1f))
        }
        return
    }

    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        val server = state.server
        Text("WAY:ON", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)

        Text(passengersLine(state), style = MaterialTheme.typography.titleLarge)

        Text(destinationLine(state), style = MaterialTheme.typography.titleLarge)

        state.restArea.approaching?.let {
            Text("곧 $it", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        server.notice?.let { Text(it, style = MaterialTheme.typography.titleMedium) }

        // 지도 정보를 받기 전이거나 못 받았을 때
        if (state.parked && arrived != null) LoadingCard(arrived)

        // 버튼은 정차 중에만 (4-1). 동의 전에는 세션을 열 수 없어 동의부터 (7-3)
        if (state.parked) {
            if (!server.locationConsent) {
                ConsentCard(onConsent)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (canDepart) DepartureButton(onDeparture)
                    OutlinedButton(onClick = onOpenQr, modifier = Modifier.heightIn(min = 64.dp)) {
                        Text("동승자 연결", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}

/** 큰 버튼 하나 (출발해요 기획 6-1) */
@Composable
private fun DepartureButton(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.heightIn(min = 64.dp)) {
        Text("🚗 출발해요", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ConsentCard(onConsent: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("위치 공유에 동의해야 동승자와 연결돼요", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "연결된 동승자 폰에 이 차의 현재 위치와 경로가 보여요. 위치 기록은 남기지 않아요.",
                style = MaterialTheme.typography.titleMedium,
            )
            Button(onClick = onConsent) { Text("동의하기", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

/** 주행 중에도 보이는 한 줄 (4-1). 사람 수는 지금 폰이 붙어 있는 동승자 */
private fun passengersLine(state: AppState): String {
    val server = state.server
    val online = server.onlinePassengers
    return when {
        server.locationConsent && server.online && server.trip == null -> "연결 준비 중"
        online.isEmpty() -> "연결된 동승자 없음"
        else -> "동승자 ${online.size}명 연결됨 · ${online.joinToString(" · ") { it.nickname }}"
    }
}

@Composable
private fun LoadingCard(card: ArrivedCard) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(card.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                if (card.loadFailed) "휴게소 정보를 불러오지 못했어요" else "휴게소 지도를 불러오는 중…",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private fun destinationLine(state: AppState): String {
    val dest = state.drive?.destination ?: return "내비에서 목적지를 설정해 주세요"
    return "${dest.name}까지 ${durationText(dest.timeS)}"
}

/** 분은 올림. 3725초 → "1시간 3분", 50초 → "1분" */
internal fun durationText(seconds: Int): String {
    val totalMin = maxOf(1, (seconds + 59) / 60)
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h == 0 -> "${m}분"
        m == 0 -> "${h}시간"
        else -> "${h}시간 ${m}분"
    }
}
