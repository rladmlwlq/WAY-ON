package com.wayon.car.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.Gson
import com.wayon.car.BuildConfig
import com.wayon.car.car.CarLocation
import com.wayon.car.server.RestAreaMap
import com.wayon.car.server.TopItem
import org.json.JSONArray
import org.json.JSONObject

/** 패널에 보여 줄 것 */
private sealed interface Selection {
    data object Building : Selection
    data class Place(val id: String) : Selection
    data class Cluster(val ids: List<String>) : Selection
}

/**
 * 휴게소 지도 (car-app-design.md 4-7). 도착(기어 P)하면 홈 대신 보인다.
 * 왼쪽(또는 위)은 카카오맵 위성 지도에 실제 위치대로 매장 아이콘, 오른쪽(또는 아래)은 고른 것의 정보.
 * - 매장 아이콘: 영업시간, 영업 중 여부, 잘 팔리는 메뉴(판매 순위)
 * - 휴게소 건물: 식당가 메뉴(베스트·추천), 위치를 모르는 매장, 편의시설
 * - 겹친 아이콘 묶음: 목록 → 고르면 그 매장
 * 지도를 못 띄우면(키 없음, 네트워크) 정보 패널만 보여 준다.
 */
@Composable
fun RestAreaMapScreen(map: RestAreaMap, car: CarLocation?, modifier: Modifier = Modifier) {
    var selection by remember(map.restAreaId) { mutableStateOf<Selection>(Selection.Building) }
    var mapFailed by remember(map.restAreaId) { mutableStateOf(BuildConfig.KAKAO_JS_KEY.isBlank()) }
    // 메뉴의 "지도에서 보기"로 강조할 가게 (지도 위 장소 ID)
    var focusId by remember(map.restAreaId) { mutableStateOf<String?>(null) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight
        val panel: @Composable (Modifier) -> Unit = { m ->
            InfoPanel(
                map, selection,
                onPick = { selection = Selection.Place(it) },
                onShowOnMap = if (mapFailed) null else { id -> focusId = id },
                modifier = m,
            )
        }
        if (mapFailed) {
            panel(Modifier.fillMaxSize())
        } else if (wide) {
            Row(Modifier.fillMaxSize()) {
                // 글자 칸을 넉넉히: 지도와 정보를 같은 너비로
                KakaoMap(map, car, focusId, { selection = it }, { mapFailed = true }, Modifier.weight(1f).fillMaxSize())
                panel(Modifier.weight(1f).fillMaxSize())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                KakaoMap(map, car, focusId, { selection = it }, { mapFailed = true }, Modifier.weight(1f).fillMaxWidth())
                panel(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun KakaoMap(
    map: RestAreaMap,
    car: CarLocation?,
    focusId: String?,
    onSelect: (Selection) -> Unit,
    onFail: () -> Unit,
    modifier: Modifier,
) {
    val main = remember { Handler(Looper.getMainLooper()) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var ready by remember(map.restAreaId) { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // 기본은 내용 높이만큼이라 지도(height:100%)가 0이 된다. 주어진 칸을 꽉 채운다
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // 지도 쪽 함수는 WebView 스레드에서 불리므로 메인으로 넘긴다
                addJavascriptInterface(MapBridge(
                    onReady = {
                        main.post {
                            evaluateJavascript("show(${JSONObject.quote(Gson().toJson(map))})", null)
                            ready = true
                        }
                    },
                    onError = { why ->
                        if (BuildConfig.DEBUG) android.util.Log.d("WAYON_CAR", "지도 실패: $why")
                        main.post(onFail)
                    },
                    onSelect = { id ->
                        main.post { onSelect(if (id == "building") Selection.Building else Selection.Place(id)) }
                    },
                    onCluster = { ids -> main.post { onSelect(Selection.Cluster(ids)) } },
                ), "Android")
                // 디버그 빌드에서만 지도 페이지 오류를 로그로 (Logcat 태그 WAYON_CAR)
                if (BuildConfig.DEBUG) {
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                            android.util.Log.d("WAYON_CAR", "지도: ${m.message()} (${m.lineNumber()})")
                            return true
                        }
                    }
                }
                webViewClient = object : android.webkit.WebViewClient() {
                    // 지도 페이지 밖으로는 못 나가게 한다 (지도 안 카카오 로고·링크 등). 차 화면이 브라우저가 되면
                    // 안 됨 (Pleos 심사 가이드: 운전 중 브라우저 기능 금지). 지도 스크립트·타일 받기는 이동이 아니라 그대로 됨
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                    ): Boolean = true

                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript("load(${JSONObject.quote(BuildConfig.KAKAO_JS_KEY)})", null)
                    }
                }
                // 출처 없이 연다. 카카오 JS 키는 등록된 웹 도메인이 아닌 출처를 막는데(domain mismatched),
                // 출처가 없으면 통과한다 (2026-11-06 확인). 웹 도메인 등록과 상관없이 앱에서 지도가 뜬다
                val html = context.assets.open("rest_area_map.html").bufferedReader().use { it.readText() }
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                web = this
            }
        },
    )

    LaunchedEffect(ready, car) {
        if (ready && car != null) web?.evaluateJavascript("setCar(${car.lat}, ${car.lng})", null)
    }
    LaunchedEffect(ready, focusId) {
        if (ready && focusId != null) web?.evaluateJavascript("focus(${JSONObject.quote(focusId)})", null)
    }
}

/** 지도(JS) → 앱. @JavascriptInterface 함수는 이름 그대로 JS에서 Android.onReady() 등으로 부른다 */
private class MapBridge(
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit,
    private val onSelect: (String) -> Unit,
    private val onCluster: (List<String>) -> Unit,
) {
    @JavascriptInterface fun onReady() = onReady.invoke()
    @JavascriptInterface fun onError(why: String) = onError.invoke(why)
    @JavascriptInterface fun onSelect(id: String) = onSelect.invoke(id)
    @JavascriptInterface fun onCluster(idsJson: String) {
        val arr = JSONArray(idsJson)
        onCluster.invoke((0 until arr.length()).map { arr.getString(it) })
    }
}

@Composable
private fun InfoPanel(
    map: RestAreaMap,
    selection: Selection,
    onPick: (String) -> Unit,
    onShowOnMap: ((String) -> Unit)?,
    modifier: Modifier,
) {
    // 누른 메뉴 줄 (한 번에 하나만 펼침)
    var open by remember(map.restAreaId, selection) { mutableStateOf<String?>(null) }
    val row = RowState(open, { key -> open = if (open == key) null else key }, onShowOnMap)
    LazyColumn(
        modifier = modifier.background(MaterialTheme.colorScheme.surface).padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        when (selection) {
            Selection.Building -> buildingInfo(map, row)
            is Selection.Place -> {
                val p = map.places.firstOrNull { it.placeId == selection.id }
                if (p == null) buildingInfo(map, row) else placeInfo(p)
            }
            is Selection.Cluster -> {
                item { Title("이 건물의 매장 ${selection.ids.size}곳") }
                items(map.places.filter { it.placeId in selection.ids }) { p ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = ROW_MIN).clickable { onPick(p.placeId) }
                            .padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${kindIcon(p.kind)}  ${p.name.substringBefore(' ')}", style = MaterialTheme.typography.titleLarge)
                        Text(openText(p.openNow), style = MaterialTheme.typography.titleMedium, color = openColor(p.openNow))
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun LazyListScope.placeInfo(p: RestAreaMap.Place) {
    item { Title("${kindIcon(p.kind)}  ${p.name.substringBefore(' ')}") }
    item { Text(p.name, style = MaterialTheme.typography.bodyMedium, color = Color.Gray) }
    if (p.open != null) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${p.open} ~ ${p.close}", style = MaterialTheme.typography.titleMedium)
                Text(openText(p.openNow), style = MaterialTheme.typography.titleMedium, color = openColor(p.openNow))
            }
        }
    }
    if (p.topItems.isNotEmpty()) {
        item { Section("잘 팔리는 메뉴") }
        items(p.topItems) { TopRow(it) }
    } else if (p.kind in listOf("FOOD", "CAFE", "STORE")) {
        item { Text("이 매장의 메뉴 정보는 아직 없어요", style = MaterialTheme.typography.bodyLarge, color = Color.Gray) }
    }
}

private fun LazyListScope.buildingInfo(map: RestAreaMap, row: RowState) {
    val b = map.building
    item { Title("🏢  ${map.name}") }
    item {
        Text("건물 안 식당가에서 파는 메뉴예요. 누르면 파는 곳이 보여요", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
    }
    if (b.foodCourt.topItems.isNotEmpty()) {
        item { Section("식당가 인기 메뉴") }
        items(b.foodCourt.topItems) { t ->
            MenuRow(
                key = "top-${t.rank}", row = row, store = t.store, placeId = t.placeId,
                title = "${t.rank}위  ${t.name}" + if (t.rank == 1) "  · 가장 잘 팔려요" else "",
                bold = t.rank == 1, price = t.price,
            )
        }
    }
    if (b.foodCourt.menus.isNotEmpty()) {
        item { Section("식당가 메뉴 ${b.foodCourt.menus.size}개") }
        items(b.foodCourt.menus) { m ->
            MenuRow(
                key = "menu-${m.name}-${m.store}", row = row, store = m.store, placeId = m.placeId,
                title = m.name, price = m.price,
                chips = listOfNotNull("베스트".takeIf { m.best }, "추천".takeIf { m.recommended }),
            )
        }
    }
    if (b.otherStores.isNotEmpty()) {
        item { Section("그 밖의 매장 (지도에 위치 없음)") }
        items(b.otherStores) { s ->
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (s.open != null) Text("${s.open} ~ ${s.close}", style = MaterialTheme.typography.bodyLarge)
                    if (s.openNow != null) Text(openText(s.openNow), color = openColor(s.openNow))
                }
                s.topItems.forEach { TopRow(it) }
            }
        }
    }
    if (b.facilities.isNotEmpty()) {
        item { Section("편의시설") }
        item {
            Text(b.facilities.map { it.name }.distinct().joinToString(" · "), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** 메뉴 줄 펼침 상태. onShowOnMap이 null이면 지도가 없어서 "지도에서 보기"를 숨김 */
private class RowState(val open: String?, val toggle: (String) -> Unit, val onShowOnMap: ((String) -> Unit)?)

/** 메뉴 1줄. 누르면 파는 곳이 펼쳐지고, 그 가게가 지도에 있으면 [지도에서 보기] */
@Composable
private fun MenuRow(
    key: String,
    row: RowState,
    store: String?,
    placeId: String?,
    title: String,
    price: Int?,
    bold: Boolean = false,
    chips: List<String> = emptyList(),
) {
    val expanded = row.open == key
    Column(
        Modifier.fillMaxWidth()
            .background(if (expanded) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable { row.toggle(key) }
            .heightIn(min = ROW_MIN)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
                chips.forEach { Chip(it) }
            }
            Text(priceText(price), style = MaterialTheme.typography.titleLarge)
        }
        if (expanded) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "파는 곳  " + (store ?: "식당가 (휴게소 건물 안)"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val show = row.onShowOnMap
                if (placeId != null && show != null) {
                    Text(
                        "지도에서 보기 ›",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { show(placeId) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun Title(text: String) =
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

@Composable
private fun Section(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 12.dp),
)

@Composable
private fun TopRow(t: TopItem) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            "${t.rank}위  ${t.name}" + if (t.rank == 1) "  · 가장 잘 팔려요" else "",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (t.rank == 1) FontWeight.Bold else FontWeight.Normal,
        )
        Text(priceText(t.price), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Chip(text: String) = Box(
    Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
        .padding(horizontal = 8.dp, vertical = 2.dp),
) { Text(text, style = MaterialTheme.typography.labelMedium) }

/** 손가락으로 누르기 쉬운 줄 높이 (옆 줄이 같이 눌리지 않게) */
private val ROW_MIN = 64.dp

private fun kindIcon(kind: String) = when (kind) {
    "FOOD" -> "🍽️"; "CAFE" -> "☕"; "STORE" -> "🏪"; "GAS" -> "⛽"
    "EV" -> "🔌"; "TOILET" -> "🚻"; "ATM" -> "🏧"; else -> "📍"
}

internal fun priceText(price: Int?) = price?.let { "%,d원".format(it) } ?: ""

private fun openText(openNow: Boolean?) = when (openNow) {
    true -> "영업 중"; false -> "영업 끝"; null -> ""
}

private fun openColor(openNow: Boolean?) = if (openNow == false) Color.Gray else Color(0xFF2F855A)

