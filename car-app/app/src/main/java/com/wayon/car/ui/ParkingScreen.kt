package com.wayon.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wayon.car.server.ParkingSpot
import com.wayon.car.server.ParkingSuggestion

/**
 * 목적지 근처 공영주차장 (car-app-design.md 4-4 주차장 탭, 6-6). 동승자가 없을 때 서버가 차로 보낸 목록을
 * 정차 중에만 보여 준다. 줄을 누르면 아래에 "목적지를 바꿀까요?" → 운전자가 고른 것이라 수락 화면 없이 바로 내비에 넣는다.
 * 넓은 창·좁은 창 모두 한 줄 목록이라 배치를 나누지 않는다.
 */
@Composable
fun ParkingScreen(
    suggestion: ParkingSuggestion,
    onChoose: (ParkingSpot) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var cheap by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<ParkingSpot?>(null) }
    val places = if (cheap) {
        suggestion.places.sortedWith(compareBy<ParkingSpot> { it.fee1h ?: Int.MAX_VALUE }.thenBy { it.distM })
    } else {
        suggestion.places.sortedBy { it.distM }
    }

    Column(
        modifier.fillMaxSize().background(BgTop).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", fontSize = 28.sp, color = Navy) }
            Column(Modifier.weight(1f)) {
                Text("🅿️ 목적지 근처 공영주차장", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Navy)
                suggestion.destName?.let { Text("$it 근처", fontSize = 20.sp, color = Gray) }
            }
            TextButton(onClick = onClose) { Text("닫기", fontSize = 22.sp, color = Gray) }
        }

        if (places.isEmpty()) {
            Text("근처 2km 안에 공영주차장 정보가 없어요", fontSize = 24.sp, color = Gray)
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SortButton("가까운 순", !cheap, Modifier.weight(1f)) { cheap = false }
            SortButton("싼 순", cheap, Modifier.weight(1f)) { cheap = true }
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(places, key = { it.extId }) { p ->
                val on = picked?.extId == p.extId
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(if (on) Blue.copy(alpha = 0.12f) else Color.White, RoundedCornerShape(12.dp))
                        .clickable { picked = p }
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(p.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Navy, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(facts(p), fontSize = 18.sp, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        picked?.let { p ->
            Text("목적지를 ${p.name}(으)로 바꿀까요?", fontSize = 22.sp, color = Navy, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onChoose(p) }, modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
                    Text("목적지로 설정", fontSize = 22.sp)
                }
                OutlinedButton(onClick = { picked = null }, modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
                    Text("취소", fontSize = 22.sp)
                }
            }
        }
    }
}

@Composable
private fun SortButton(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (on) {
        Button(onClick = onClick, modifier = modifier.heightIn(min = 56.dp)) { Text(text, fontSize = 20.sp) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 56.dp)) { Text(text, fontSize = 20.sp) }
    }
}

/** "350m · 1시간 1,200원 · 운영 중 · 빈자리 23" (아는 것만, 폰 웹 ParkingCard와 같은 규칙) */
internal fun facts(p: ParkingSpot): String {
    val parts = mutableListOf(if (p.distM < 1000) "${p.distM}m" else "%.1fkm".format(p.distM / 1000.0))
    parts += when {
        p.feeType == "FREE" -> "무료"
        p.fee1h == null -> "요금 정보 없음"
        else -> "1시간 ${"%,d".format(p.fee1h)}원"
    }
    when (p.openNow) {
        true -> parts += "운영 중"
        false -> parts += "운영 시간 아님"
        null -> Unit
    }
    if (p.nowParked != null && p.totalSpaces != null) parts += "빈자리 ${maxOf(0, p.totalSpaces - p.nowParked)}"
    else if (p.totalSpaces != null) parts += "${p.totalSpaces}면"
    return parts.joinToString(" · ")
}
