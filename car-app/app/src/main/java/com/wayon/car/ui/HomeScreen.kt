package com.wayon.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onManage: () -> Unit = {},
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

    // QR 화면과 같은 배경·색·가운데 정렬. 크기는 CarUi.kt. 길면 말줄임 대신 글자가 줄어든다 (FitText)
    Column(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val server = state.server
        Text("WAY:ON", fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, color = Navy, letterSpacing = 2.sp)
        Spacer(Modifier.height(40.dp))
        // 목적지 이름이 길면 두 줄까지, 그래도 넘치면 글자를 줄인다
        FitText(destinationLine(state), 64.sp, color = Navy, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
        Spacer(Modifier.height(16.dp))
        FitText(passengersLine(state), 32.sp, color = Gray, textAlign = TextAlign.Center)

        state.restArea.approaching?.let {
            Spacer(Modifier.height(32.dp))
            FitText("곧 $it", 52.sp, color = Blue, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }

        server.notice?.let {
            Spacer(Modifier.height(24.dp))
            Text(it, fontSize = 32.sp, lineHeight = 42.sp, color = Navy, textAlign = TextAlign.Center)
        }

        // 지도 정보를 받기 전이거나 못 받았을 때
        if (state.parked && arrived != null) {
            Spacer(Modifier.height(48.dp))
            LoadingCard(arrived)
        }

        // 버튼은 정차 중에만 (4-1). 동의 전에는 세션을 열 수 없어 동의부터 (7-3)
        if (state.parked) {
            Spacer(Modifier.height(56.dp))
            if (!server.locationConsent) {
                ConsentCard(onConsent)
            } else {
                // [출발해요] (출발해요 기획 6-1): 받을 동승자가 있으면 맨 위에 두 버튼 폭만큼 크게
                if (canDepart) {
                    Button(
                        onClick = onDeparture,
                        modifier = Modifier.width(555.dp * 2 + 32.dp).height(120.dp),
                        contentPadding = ButtonPadding,
                    ) { ButtonText("🚗 출발해요", PrimaryButtonFont) }
                    Spacer(Modifier.height(32.dp))
                }
                // 관리 왼쪽, 연결 오른쪽. 출발해요가 있으면 연결도 테두리 버튼으로 (주 버튼은 하나만)
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onManage, modifier = PrimaryButton, contentPadding = ButtonPadding) {
                        ButtonText("동승자 관리", PrimaryButtonFont)
                    }
                    if (canDepart) {
                        OutlinedButton(onClick = onOpenQr, modifier = PrimaryButton, contentPadding = ButtonPadding) {
                            ButtonText("동승자 연결", PrimaryButtonFont)
                        }
                    } else {
                        Button(onClick = onOpenQr, modifier = PrimaryButton, contentPadding = ButtonPadding) {
                            ButtonText("동승자 연결", PrimaryButtonFont)
                        }
                    }
                }
            }
        }
    }
}

/** 휴게소 지도 위쪽 줄의 [출발해요] (출발해요 기획 6-1, 4-7) */
@Composable
private fun DepartureButton(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.heightIn(min = 64.dp)) {
        Text("🚗 출발해요", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

/** QR 화면 카드와 같은 흰 카드 */
private val WhiteCard = Modifier
    .widthIn(max = 1000.dp)
    .shadow(8.dp, RoundedCornerShape(32.dp), ambientColor = Blue, spotColor = Blue)
    .background(Color.White, RoundedCornerShape(32.dp))
    .padding(48.dp)

@Composable
private fun ConsentCard(onConsent: () -> Unit) {
    Column(WhiteCard, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        FitText("위치 공유에 동의해야 동승자와 연결돼요", 44.sp, color = Navy, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
        FitText(
            "연결된 동승자 폰에 이 차의 현재 위치와 경로가 보여요.\n위치 기록은 남기지 않아요.",
            32.sp,
            color = Gray,
            textAlign = TextAlign.Center,
            maxLines = 3,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onConsent, modifier = HomeButton, contentPadding = ButtonPadding) { ButtonText("동의하기", HomeButtonFont) }
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
    Column(WhiteCard, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        FitText(card.name, 48.sp, color = Navy, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(
            if (card.loadFailed) "휴게소 정보를 불러오지 못했어요" else "휴게소 지도를 불러오는 중…",
            fontSize = 32.sp,
            color = Gray,
        )
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
