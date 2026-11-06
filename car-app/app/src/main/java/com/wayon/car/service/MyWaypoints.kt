package com.wayon.car.service

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 우리가 넣은 경유지 1개 (car-app-design.md 3장 `myWaypoints`).
 * [requestId]가 없으면 운전자가 차 화면에서 직접 고른 것 (서버에 기록 안 함).
 * [extId]는 장소 ID (`ra:000001` 등). 휴게소 정보 카드를 불러올 때 쓴다.
 */
data class MyWaypoint(
    val name: String,
    val type: String,
    val lat: Double,
    val lng: Double,
    val requestId: Long? = null,
    val extId: String? = null,
) {
    val isRestArea: Boolean get() = type == TYPE_REST_AREA

    companion object {
        const val TYPE_REST_AREA = "REST_AREA"
    }
}

/**
 * `myWaypoints` 보관. 앱을 다시 켜도 `car.route`에 좌표를 넣으려고 파일에 둔다.
 * 경유지는 2개까지라 파일은 작다. 경유지 반영(6-4)이 [add]를, 휴게소 떠남·운전자 삭제가 [remove]를 부른다.
 */
class MyWaypoints(context: Context) {

    private val file = File(context.filesDir, "my_waypoints.json")

    private val _list = MutableStateFlow(read())
    val list: StateFlow<List<MyWaypoint>> = _list.asStateFlow()

    fun add(waypoint: MyWaypoint) {
        _list.update { old -> old.filterNot { it.name == waypoint.name } + waypoint }
        write()
    }

    fun remove(name: String) {
        _list.update { old -> old.filterNot { it.name == name } }
        write()
    }

    /** 내비에 남아 있는 경유지 이름만 남긴다 (운전자가 내비에서 지웠을 때) */
    fun keepOnly(names: Set<String>) {
        val before = _list.value
        val after = before.filter { it.name in names }
        if (after.size != before.size) {
            _list.value = after
            write()
        }
    }

    fun clear() {
        _list.value = emptyList()
        write()
    }

    private fun read(): List<MyWaypoint> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            MyWaypoint(
                name = o.getString("name"),
                type = o.getString("type"),
                lat = o.getDouble("lat"),
                lng = o.getDouble("lng"),
                requestId = if (o.isNull("requestId")) null else o.getLong("requestId"),
                extId = if (o.isNull("extId")) null else o.getString("extId"),
            )
        }
    }.getOrDefault(emptyList())

    private fun write() {
        val arr = JSONArray()
        _list.value.forEach {
            arr.put(
                JSONObject()
                    .put("name", it.name)
                    .put("type", it.type)
                    .put("lat", it.lat)
                    .put("lng", it.lng)
                    .put("requestId", it.requestId ?: JSONObject.NULL)
                    .put("extId", it.extId ?: JSONObject.NULL)
            )
        }
        runCatching { file.writeText(arr.toString()) }
    }
}
