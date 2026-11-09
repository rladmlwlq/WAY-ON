package com.wayon.car.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 홈의 [동승자 관리]·[동승자 연결] */
internal val PrimaryButton = Modifier.width(555.dp).height(120.dp)
internal val PrimaryButtonFont = 66.sp

/** 동의 카드의 [동의하기] */
internal val HomeButton = Modifier.width(370.dp).height(80.dp)
internal val HomeButtonFont = 44.sp

internal val ButtonPadding = PaddingValues(horizontal = 16.dp)

/** 버튼 글자. 버튼이 좁으면 글자를 줄여서 한 줄에 맞춘다 */
@Composable
internal fun ButtonText(text: String, fontSize: TextUnit) {
    FitText(text, fontSize, fontWeight = FontWeight.Bold)
}

/**
 * 말줄임표 대신, 칸을 넘치면 글자를 10%씩 줄여 maxLines 안에 다 보이게 한다 (최소 원래의 절반).
 * 줄이는 동안에는 그리지 않아 깜빡이지 않는다.
 */
@Composable
internal fun FitText(
    text: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) {
    // 글자 수가 바뀔 때만 다시 맞춘다
    var size by remember(text.length, fontSize) { mutableStateOf(fontSize) }
    var fitted by remember(text.length, fontSize) { mutableStateOf(false) }
    Text(
        text,
        modifier = modifier.drawWithContent { if (fitted) drawContent() },
        color = color,
        fontSize = size,
        lineHeight = size * 1.2f,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        letterSpacing = letterSpacing,
        onTextLayout = { layout ->
            if (layout.hasVisualOverflow && size.value > fontSize.value / 2) size *= 0.9f else fitted = true
        },
    )
}
