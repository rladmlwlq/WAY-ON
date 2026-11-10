package com.wayon.car.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/**
 * 출발해요 대화 (car-app-design.md 4-5, 출발해요 기획 6-2). 정차 중에만 보인다 (기어 D면 서비스가 닫음).
 * 위: 사람별 마지막 빠른 답장. 가운데: 말풍선 (동승자 왼쪽, 운전자 오른쪽).
 * 아래: [천천히 와요] [빨리 와요], 40자 메시지, [다시 알리기] (1분에 1번), [취소].
 * 넓은 창이면 대화와 버튼을 좌우로, 좁은 창(보조 창)이면 위아래로 놓는다.
 */
@Composable
fun DepartureScreen(
    departure: DepartureUi,
    onQuick: (String) -> Unit,
    onText: (String) -> Unit,
    onRemind: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(Bg).padding(24.dp)) {
        val wide = maxWidth > maxHeight
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Header(departure, onCancel)
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
private fun Header(d: DepartureUi, onCancel: () -> Unit) {
    val minutes by produceState(minutesSince(d.calledAtMs), d.calledAtMs) {
        while (true) {
            value = minutesSince(d.calledAtMs)
            delay(30_000)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                if (d.callId == null) "🚗 출발 알림 보내는 중…" else "🚗 출발 대기 · ${minutes}분 지남",
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
private fun Bubbles(messages: List<DepartureMessage>, modifier: Modifier) {
    val list = rememberLazyListState()
    // 새 말풍선이 오면 맨 아래로
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex)
    }
    LazyColumn(modifier, state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(messages, key = { _, m -> m.messageId }) { i, m ->
            val driver = m.sender.passengerId == null
            // 지난 출발 알림의 대화 아래에 구분선 (기획 7장 "지난 대화 이어 보기")
            if (i > 0 && messages[i - 1].callId != m.callId) {
                Text(
                    "이번 출발 알림",
                    fontSize = 16.sp,
                    color = Gray,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    textAlign = TextAlign.Center,
                )
            }
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = if (driver) Alignment.End else Alignment.Start,
            ) {
                if (!driver) Text(m.sender.nickname, fontSize = 16.sp, color = Gray)
                Text(
                    m.text(),
                    fontSize = 22.sp,
                    color = if (driver) Color.White else Navy,
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .background(if (driver) Blue else Color.White, RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
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

private fun DepartureMessage.text(): String =
    if (kind == "QUICK") QUICK[quickCode]?.let { "${it.first} ${it.second}" } ?: (quickCode ?: "") else body ?: ""

private fun minutesSince(ms: Long) = maxOf(0L, (System.currentTimeMillis() - ms) / 60_000).toInt()

private fun secondsLeft(untilMs: Long) = maxOf(0L, (untilMs - System.currentTimeMillis() + 999) / 1_000).toInt()

// Navy·Blue·Gray는 QrScreen.kt의 동승자 화면 공통 색 (같은 값)
private val Bg = Color(0xFFF7F9FC)
