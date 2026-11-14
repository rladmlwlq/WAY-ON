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
import com.wayon.car.server.EnergySpot
import com.wayon.car.server.EnergySuggestion

/**
 * 에너지 제안 (car-app-design.md 4-4 주유·충전 탭, 6-5). 동승자가 없을 때 서버가 차로 보낸 경로 앞쪽 충전소·주유소를
 * 정차 중에만 보여 준다. 줄을 누르면 아래에 "경유지로 추가할까요?" → 운전자가 고른 것이라 수락 화면 없이 바로 내비에.
 * 넓은 창·좁은 창 모두 한 줄 목록이라 배치를 나누지 않는다.
 */
@Composable
fun EnergyScreen(
    suggestion: EnergySuggestion,
    onChoose: (EnergySpot) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var picked by remember { mutableStateOf<EnergySpot?>(null) }
    val what = if (suggestion.ev) "충전소" else "주유소"

    Column(
        modifier.fillMaxSize().background(BgTop).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", fontSize = 28.sp, color = Navy) }
            Column(Modifier.weight(1f)) {
                Text(
                    if (suggestion.ev) "🔋 경로 위 충전소" else "⛽ 경로 위 주유소",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Navy,
                )
                Text(
                    listOfNotNull(
                        suggestion.remainDistM?.let { "목적지까지 ${km(it)}" },
                        "주행 가능 ${km(suggestion.rangeM)}",
                    ).joinToString(" · "),
                    fontSize = 20.sp,
                    color = Gray,
                )
            }
            TextButton(onClick = onClose) { Text("닫기", fontSize = 22.sp, color = Gray) }
        }

        if (suggestion.places.isEmpty()) {
            Text("주행 가능 거리 안에서 ${what}를 찾지 못했어요", fontSize = 24.sp, color = Gray)
            return@Column
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(suggestion.places, key = { it.extId }) { p ->
                val on = picked?.extId == p.extId
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(if (on) Blue.copy(alpha = 0.12f) else Color.White, RoundedCornerShape(12.dp))
                        .clickable { picked = p }
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        p.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Navy, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(km(p.distM), p.info).joinToString(" · "), fontSize = 18.sp, color = Gray,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        picked?.let { p ->
            Text("${p.name}을(를) 경유지로 추가할까요?", fontSize = 22.sp, color = Navy, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onChoose(p) }, modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
                    Text("경유지로 추가", fontSize = 22.sp)
                }
                OutlinedButton(onClick = { picked = null }, modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
                    Text("취소", fontSize = 22.sp)
                }
            }
        }
    }
}

private fun km(m: Int) = if (m < 1000) "${m}m" else "%.1fkm".format(m / 1000.0)
