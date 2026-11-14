package com.wayon.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wayon.car.Config
import com.wayon.car.service.RequestUi
import kotlinx.coroutines.delay

/**
 * 요청 수락 (car-app-design.md 4-3). waypoint.request가 오면 어느 화면이든 그 위에 띄운다.
 * 주행 중에도 뜨는 유일한 화면이라 글자는 크게, 버튼은 홈과 같은 크기로, 문장은 짧게.
 * 그대로 두면 10초 뒤 자동으로 추가된다 (운전자는 원치 않을 때만 [거절]).
 */
@Composable
fun RequestScreen(ui: RequestUi, onAccept: () -> Unit, onReject: () -> Unit) {
    val r = ui.request
    // 뒤 화면을 누르지 못하게 막는 어두운 바탕
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 1200.dp)
                .fillMaxWidth(0.8f)
                .background(Color.White, RoundedCornerShape(32.dp))
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("${r.requestedBy}님의 요청", fontSize = 36.sp, color = Gray, fontWeight = FontWeight.Bold)
            FitText(r.name, 64.sp, color = Navy, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
            FitText(
                listOfNotNull(typeLabel(r.type), r.address).joinToString(" · "),
                32.sp,
                color = Gray,
                textAlign = TextAlign.Center,
            )
            Text(
                if (r.action == "SET_DEST") "목적지를 여기로 바꿀까요?" else "경유지로 추가할까요?",
                fontSize = 44.sp,
                color = Navy,
            )

            when (ui.phase) {
                RequestUi.Phase.SHOWING, RequestUi.Phase.CLAIMING -> {
                    Countdown(ui.expiresAtMs, if (r.action == "SET_DEST") "목적지를 바꿔요" else "경유지를 추가해요")
                    val locked = ui.phase == RequestUi.Phase.CLAIMING
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        // 거절은 덜 눈에 띄게 좁게, 높이는 [추가]와 같게
                        OutlinedButton(onClick = onReject, enabled = !locked, modifier = Modifier.width(370.dp).height(120.dp), contentPadding = ButtonPadding) {
                            ButtonText("거절", PrimaryButtonFont)
                        }
                        Button(onClick = onAccept, enabled = !locked, modifier = PrimaryButton, contentPadding = ButtonPadding) {
                            ButtonText(if (locked) "확인 중…" else if (r.action == "SET_DEST") "바꾸기" else "추가", PrimaryButtonFont)
                        }
                    }
                }
                RequestUi.Phase.APPLYING -> Text("내비에 넣는 중…", fontSize = 44.sp, color = Blue, fontWeight = FontWeight.Bold)
                RequestUi.Phase.FAILED -> Text("추가하지 못했어요", fontSize = 44.sp, color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 남은 시간 막대와 "그대로 두면 7초 뒤 자동으로 경유지를 추가해요". 0이 되면 서비스가 자동 수락한다 */
@Composable
private fun Countdown(expiresAtMs: Long, autoAction: String) {
    val now by produceState(System.currentTimeMillis(), expiresAtMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(200)
        }
    }
    val leftMs = (expiresAtMs - now).coerceAtLeast(0)
    LinearProgressIndicator(
        progress = { leftMs.toFloat() / Config.ACCEPT_TIMEOUT_MS },
        modifier = Modifier.widthIn(min = 600.dp).height(12.dp),
        color = Blue,
    )
    FitText(
        "그대로 두면 ${(leftMs + 999) / 1000}초 뒤 자동으로 $autoAction",
        36.sp,
        color = Blue,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
}

private fun typeLabel(type: String) = when (type) {
    "REST_AREA" -> "휴게소"
    "RESTAURANT" -> "음식점"
    "GAS_STATION" -> "주유소"
    "EV_CHARGER" -> "충전소"
    "PARKING" -> "주차장"
    else -> null
}
