package com.wayon.car.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.wayon.car.Config
import com.wayon.car.service.AppState
import kotlinx.coroutines.delay

/**
 * QR (car-app-design.md 4-2). 정차 중에만 보인다 (주행 중이면 서비스가 닫음).
 * QR 하나로 만료(3분)까지 여러 명이 찍는다. 4명이 차면 QR 대신 안내.
 */
@Composable
fun QrScreen(
    state: AppState,
    onBack: () -> Unit,
    onRenew: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val qr = state.server.qr ?: return
    val passengers = state.server.passengers

    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BgTop, BgBottom)))) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("WAY:ON", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Navy, letterSpacing = 1.sp)
            Spacer(Modifier.height(24.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Blue)) { append("QR") }
                    append("로 간편하게 연결하세요")
                },
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = Navy,
            )
            Spacer(Modifier.height(8.dp))
            Text("폰 카메라로 찍으면 WAY:ON에 연결돼요 · 한 QR로 여러 명", style = MaterialTheme.typography.titleMedium, color = Gray)
            Spacer(Modifier.height(32.dp))

            Column(
                Modifier
                    .width(420.dp)
                    .shadow(8.dp, RoundedCornerShape(24.dp), ambientColor = Blue, spotColor = Blue)
                    .background(Color.White, RoundedCornerShape(24.dp))
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                when {
                    state.server.passengersFull -> {
                        Message("동승자는 ${Config.MAX_PASSENGERS}명까지예요.\n한 명을 해제하면 QR이 다시 보여요")
                        Button(onClick = onManage) { Text("동승자 관리", style = MaterialTheme.typography.titleMedium) }
                    }
                    qr.error != null -> {
                        Message(qr.error)
                        Button(onClick = onRenew) { Text("다시 시도", style = MaterialTheme.typography.titleMedium) }
                    }
                    qr.url == null -> Message("QR을 만드는 중…")
                    else -> {
                        QrImage(qr.url)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("${remainingText(qr.expiresAtMs)} 남음", style = MaterialTheme.typography.titleLarge, color = Navy)
                            OutlinedButton(onClick = onRenew) { Text("새로 만들기", style = MaterialTheme.typography.titleMedium) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                if (passengers.isEmpty()) "아직 연결된 동승자가 없어요"
                else "연결됨: ${passengers.joinToString(" · ") { it.nickname }} (${passengers.size}/${Config.MAX_PASSENGERS})",
                style = MaterialTheme.typography.titleLarge,
                color = if (passengers.isEmpty()) Gray else Navy,
            )
        }

        // 화면 전체를 덮는 스크롤 본문보다 뒤에 둬야 위에 그려지고 눌린다
        BackButton(onBack)
    }
}

/** 왼쪽 위 [← 돌아가기] (QR·동승자 관리 공통) */
@Composable
internal fun BackButton(onBack: () -> Unit) {
    TextButton(onClick = onBack, modifier = Modifier.padding(16.dp)) {
        Text("← 돌아가기", style = MaterialTheme.typography.titleLarge, color = Navy)
    }
}

// 동승자 화면(QR·동승자 관리)과 폰 웹(index.css)이 같은 색을 쓴다
internal val Navy = Color(0xFF14213D)
internal val Blue = Color(0xFF2B6CF6)
internal val Gray = Color(0xFF6B7280)
internal val BgTop = Color(0xFFF7F9FC)
internal val BgBottom = Color(0xFFEAF0FB)

@Composable
private fun Message(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = Navy, textAlign = TextAlign.Center)
}

/** QR은 테마와 상관없이 흰 바탕 검은 점이어야 폰 카메라가 잘 읽는다. 네 모서리에 파란 스캔 표시 */
@Composable
private fun QrImage(url: String) {
    val bitmap = remember(url) { qrBitmap(url, QR_PX) }
    Image(
        bitmap = bitmap,
        contentDescription = "동승자 연결 QR 코드",
        filterQuality = FilterQuality.None, // 점이 번지지 않게
        modifier = Modifier
            .size(300.dp)
            .drawWithContent {
                drawContent()
                val len = 36.dp.toPx()
                val w = 4.dp.toPx()
                val (r, b) = size.width to size.height
                listOf(
                    Offset(0f, 0f) to Offset(len, 0f), Offset(0f, 0f) to Offset(0f, len),
                    Offset(r, 0f) to Offset(r - len, 0f), Offset(r, 0f) to Offset(r, len),
                    Offset(0f, b) to Offset(len, b), Offset(0f, b) to Offset(0f, b - len),
                    Offset(r, b) to Offset(r - len, b), Offset(r, b) to Offset(r, b - len),
                ).forEach { (s, e) -> drawLine(Blue, s, e, w, StrokeCap.Round) }
            }
            .padding(20.dp),
    )
}

/** "1:42". 1초마다 다시 그린다 */
@Composable
private fun remainingText(expiresAtMs: Long): String {
    val now by produceState(System.currentTimeMillis(), expiresAtMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val seconds = ((expiresAtMs - now).coerceAtLeast(0) + 999) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private const val QR_PX = 512

private fun qrBitmap(text: String, size: Int): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i ->
        if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}
