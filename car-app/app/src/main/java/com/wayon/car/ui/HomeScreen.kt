package com.wayon.car.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
 * 홈 (car-app-design.md 4-1). 지금은 목적지 남은 시간과 휴게소 안내만 있다.
 * 동승자 줄, [출발해요]·[동승자 연결]·[주변 장소] 버튼은 그 기능을 붙일 때 더한다.
 * 주행 중에는 짧은 문구만 보인다. 휴게소에 도착(기어 P)하면 휴게소 지도로 바뀐다 (4-7).
 */
@Composable
fun HomeScreen(state: AppState, modifier: Modifier = Modifier) {
    val arrived = state.restArea.arrived
    // 도착은 기어 P일 때만 생기고 D가 되면 지워진다. 그래도 주행 중에는 보이지 않게 한 번 더 막는다
    val map = arrived?.map
    if (state.parked && map != null) {
        Column(modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("WAY:ON", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(map.name, style = MaterialTheme.typography.titleLarge)
            }
            RestAreaMapScreen(map, state.location, Modifier.weight(1f))
        }
        return
    }

    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("WAY:ON", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)

        Text(destinationLine(state), style = MaterialTheme.typography.titleLarge)

        state.restArea.approaching?.let {
            Text("곧 $it", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        // 지도 정보를 받기 전이거나 못 받았을 때
        if (state.parked && arrived != null) LoadingCard(arrived)
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
