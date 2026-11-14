package com.wayon.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wayon.car.Config
import com.wayon.car.server.DepartureMessage
import com.wayon.car.server.DeparturePerson
import com.wayon.car.service.DepartureUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 출발해요 대화 (car-app-design.md 4-5, 출발해요 기획 6-2). 정차 중에만 보인다 (기어 D면 서비스가 닫음).
 * 위: 사람별 마지막 빠른 답장. 가운데: 말풍선 (동승자 왼쪽, 운전자 오른쪽).
 * 아래: [천천히 와요] [빨리 와요], 40자 메시지, [다시 알리기] (1분에 1번), [취소]. [←]는 알림을 열어 둔 채 홈으로.
 * 말풍선 옆 시간과 이름은 카톡처럼 같은 사람·같은 분 묶음에서 시간은 마지막, 이름은 처음에만. 날짜가 바뀌면 날짜 줄.
 * 넓은 창이면 대화와 버튼을 좌우로, 좁은 창(보조 창)이면 위아래로 놓는다.
 * 넓은지는 키패드와 상관없는 창 크기로 정한다. 남은 칸으로 정하면 보조 창에서 키패드가 올라올 때
 * 좌우 배치로 바뀌면서 입력칸이 새로 만들어져 키패드가 바로 닫힌다 (2026-11-13 에뮬레이터)
 */
@Composable
fun DepartureScreen(
    departure: DepartureUi,
    onQuick: (String) -> Unit,
    onText: (String) -> Unit,
    onRemind: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val window = LocalConfiguration.current
    val wide = window.screenWidthDp > window.screenHeightDp
    Box(modifier.fillMaxSize().background(Bg).padding(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Header(departure, onCancel, onBack)
            People(departure.people)
            if (wide) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Bubbles(departure.messages, Modifier.weight(1f).fillMaxSize())
                    Controls(departure, onQuick, onText, onRemind, Modifier.weight(1f))
                }
            } else {
                Bubbles(departure.messages, Modifier.weight(1f).fillMaxWidth())
                Controls(departure, onQuick, onText, onRemind, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Header(d: DepartureUi, onCancel: () -> Unit, onBack: () -> Unit) {
    val minutes by produceState(minutesSince(d.calledAtMs), d.calledAtMs) {
        while (true) {
            value = minutesSince(d.calledAtMs)
            delay(30_000)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 알림은 열어 둔 채 홈으로 (홈의 [대화 보기]로 다시 들어옴)
        TextButton(onClick = onBack) { Text("←", fontSize = 28.sp, color = Navy) }
        Column(Modifier.weight(1f)) {
            Text(
                if (d.callId == null) "🚗 출발 알림 보내는 중…"
                else "🚗 출발 대기 · ${timeText(d.calledAtMs)}에 보냄 (${minutes}분 지남)",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Navy,
            )
            d.destName?.let { name ->
                Text(
                    name + (d.etaS?.let { "까지 ${durationText(it)}" } ?: ""),
                    fontSize = 20.sp,
                    color = Gray,
                )
            }
        }
        TextButton(onClick = onCancel) { Text("취소", fontSize = 22.sp, color = Gray) }
    }
}

/** 🚻민지 ⏳준호 ⚪엄마 (답 없음), 🔕 알림도 못 받는 사람 */
@Composable
private fun People(people: List<DeparturePerson>) {
    if (people.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        people.forEach { p ->
            val icon = p.lastQuickCode?.let { QUICK[it]?.first } ?: if (p.pushEnabled) "⚪" else "🔕"
            Text("$icon ${p.nickname}", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Navy)
        }
    }
}

@Composable
private fun Bubbles(
    messages: List<DepartureMessage>,
    modifier: Modifier,
) {
    val list = rememberLazyListState()
    // 새 말풍선이 오면 맨 아래로
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex)
    }
    // 키패드가 올라오거나 내려가 목록 높이가 바뀌면 최신 말풍선에 맞춘다 (카톡처럼 대화가 키패드 위로 밀려 올라감)
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.viewportSize.height }
            .distinctUntilChanged()
            .collect { if (list.layoutInfo.totalItemsCount > 0) list.scrollToItem(list.layoutInfo.totalItemsCount - 1) }
    }
    LazyColumn(modifier, state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(messages, key = { _, m -> m.messageId }) { i, m ->
            val driver = m.sender.passengerId == null
            val prev = messages.getOrNull(i - 1)
            val next = messages.getOrNull(i + 1)
            // 날짜가 바뀌면 날짜 줄
            if (prev == null || dayKey(prev) != dayKey(m)) Divider(dateText(m))
            // 카톡처럼: 같은 사람이 같은 분에 이어 보낸 묶음에서 이름은 처음, 시간은 마지막에만
            val showName = !driver && (prev == null || !sameGroup(prev, m))
            val showTime = next == null || !sameGroup(m, next)
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = if (driver) Alignment.End else Alignment.Start,
            ) {
                if (showName) Text(m.sender.nickname, fontSize = 16.sp, color = Gray)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (driver && showTime) TimeLabel(m)
                    Text(
                        m.text(),
                        fontSize = 22.sp,
                        color = if (driver) Color.White else Navy,
                        modifier = Modifier
                            .widthIn(max = 520.dp)
                            .background(if (driver) Blue else Color.White, RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    if (!driver && showTime) TimeLabel(m)
                }
            }
        }
    }
}

@Composable
private fun Controls(
    d: DepartureUi,
    onQuick: (String) -> Unit,
    onText: (String) -> Unit,
    onRemind: () -> Unit,
    modifier: Modifier,
) {
    var text by remember { mutableStateOf("") }
    val ready = d.callId != null
    val remindLeftS by produceState(secondsLeft(d.remindLockedUntilMs), d.remindLockedUntilMs) {
        while (true) {
            value = secondsLeft(d.remindLockedUntilMs)
            if (value == 0) break
            delay(1_000)
        }
    }
    val send = {
        if (text.isNotBlank()) {
            onText(text)
            text = ""
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DRIVER_QUICK.forEach { code ->
                Button(
                    onClick = { onQuick(code) },
                    enabled = ready,
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                ) { Text(QUICK.getValue(code).second, fontSize = 22.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(Config.MESSAGE_MAX_LEN) },
                enabled = ready,
                singleLine = true,
                placeholder = { Text("메시지 (${Config.MESSAGE_MAX_LEN}자)", fontSize = 20.sp) },
                textStyle = MaterialTheme.typography.titleLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = send, enabled = ready && text.isNotBlank(), modifier = Modifier.heightIn(min = 64.dp)) {
                Text("보내기", fontSize = 22.sp)
            }
        }
        OutlinedButton(
            onClick = onRemind,
            enabled = ready && remindLeftS == 0,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        ) {
            Text(if (remindLeftS > 0) "다시 알리기 (${remindLeftS}초 뒤)" else "답 없는 사람에게 다시 알리기", fontSize = 22.sp)
        }
        d.error?.let { Text(it, fontSize = 20.sp, color = MaterialTheme.colorScheme.error) }
    }
}

/** 빠른 답장 코드 → (아이콘, 문구) */
private val QUICK = mapOf(
    "TOILET" to ("🚻" to "화장실이에요"),
    "IN_LINE" to ("⏳" to "줄 서 있어요"),
    "COMING" to ("🏃" to "가는 중이에요"),
    "TAKE_TIME" to ("🙂" to "천천히 와요"),
    "HURRY" to ("⏰" to "빨리 와요"),
)
private val DRIVER_QUICK = listOf("TAKE_TIME", "HURRY")

@Composable
private fun Divider(text: String) {
    Text(
        text,
        fontSize = 16.sp,
        color = Gray,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun TimeLabel(m: DepartureMessage) {
    Text(timeText(m.sentMs()), fontSize = 15.sp, color = Gray)
}

/** 메시지 시각은 한국 시간으로 (기기 시간대와 상관없이, 폰 화면과 같게) */
private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
private val TIME = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)
private val DATE = DateTimeFormatter.ofPattern("yyyy년 M월 d일 EEEE", Locale.KOREAN)

private fun DepartureMessage.sentMs(): Long =
    runCatching { Instant.parse(sentAt).toEpochMilli() }.getOrDefault(0L)

/** "오후 3:05" */
private fun timeText(ms: Long): String = TIME.format(Instant.ofEpochMilli(ms).atZone(SEOUL))

/** "2026년 11월 9일 일요일" */
private fun dateText(m: DepartureMessage): String = DATE.format(Instant.ofEpochMilli(m.sentMs()).atZone(SEOUL))

private fun dayKey(m: DepartureMessage) = Instant.ofEpochMilli(m.sentMs()).atZone(SEOUL).toLocalDate()

private fun minuteKey(m: DepartureMessage) = m.sentMs() / 60_000

/** 같은 출발 알림, 같은 사람, 같은 분 */
private fun sameGroup(a: DepartureMessage, b: DepartureMessage) =
    a.callId == b.callId && a.sender.passengerId == b.sender.passengerId && minuteKey(a) == minuteKey(b)

private fun DepartureMessage.text(): String =
    if (kind == "QUICK") QUICK[quickCode]?.let { "${it.first} ${it.second}" } ?: (quickCode ?: "") else body ?: ""

private fun minutesSince(ms: Long) = maxOf(0L, (System.currentTimeMillis() - ms) / 60_000).toInt()

private fun secondsLeft(untilMs: Long) = maxOf(0L, (untilMs - System.currentTimeMillis() + 999) / 1_000).toInt()

// Navy·Blue·Gray는 QrScreen.kt의 동승자 화면 공통 색 (같은 값)
private val Bg = Color(0xFFF7F9FC)
