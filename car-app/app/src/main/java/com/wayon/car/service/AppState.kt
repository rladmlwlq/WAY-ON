package com.wayon.car.service

import com.wayon.car.Config
import com.wayon.car.car.CarLocation
import com.wayon.car.car.DemoState
import com.wayon.car.car.DriveInfo
import com.wayon.car.car.Energy
import com.wayon.car.car.Gear
import com.wayon.car.car.UxRestrictions
import com.wayon.car.server.DepartureMessage
import com.wayon.car.server.DeparturePerson
import com.wayon.car.server.Passenger
import com.wayon.car.server.Trip
import com.wayon.car.server.WaypointRequest

/**
 * 화면이 읽는 상태 한 덩어리 (car-app-design.md 2-2, 3장). 서비스 안에서만 바뀐다.
 * 지금 있는 것만 넣었다. pendingRequest, call 등은 기능을 붙일 때 더한다.
 */
data class AppState(
    val gear: Gear = Gear.UNKNOWN,
    val energy: Energy = Energy(),
    /** 차 위치 (NaviHelper, 5초마다). 휴게소 지도에 현재 위치로 찍는다 */
    val location: CarLocation? = null,
    val drive: DriveInfo? = null,
    val myWaypoints: List<MyWaypoint> = emptyList(),
    val restArea: RestAreaUi = RestAreaUi(),
    /** 플랫폼 주행 규제 (CarUxRestrictions). null이면 못 받은 것 */
    val uxRestricted: Boolean? = null,
    val server: ServerUi = ServerUi(),
    /** 시연 주행 (9장) */
    val demo: DemoState = DemoState(),
) {
    /** 기어 P이고 주행 규제가 없을 때만 정차. 기어를 못 읽으면 주행 중으로 본다 (5장, 안전한 쪽) */
    val parked: Boolean get() = UxRestrictions.isParked(gear, uxRestricted)
}

/** 서버 연결 쪽 상태 (car-app-design.md 3장, 7-2) */
data class ServerUi(
    /** WebSocket이 auth.ok까지 됨 */
    val online: Boolean = false,
    /** 지금 세션, 없으면 null ("연결 준비 중") */
    val trip: Trip? = null,
    /** 등록 동승자 (passengers.update) */
    val passengers: List<Passenger> = emptyList(),
    /** 차량 쪽 위치 공유 동의. 없으면 세션을 열 수 없다 (7-3) */
    val locationConsent: Boolean = false,
    /** QR 화면이 열려 있으면 그 상태, 닫혀 있으면 null (4-2) */
    val qr: QrUi? = null,
    /** 홈에 띄우는 한 줄 안내 (7-4 등) */
    val notice: String? = null,
    /** 열린 출발 알림 (출발해요 대화, 4-5). 없으면 null */
    val departure: DepartureUi? = null,
    /** 경유지 요청 수락 화면 (4-3). 없으면 null. 어느 화면이든 그 위에, 주행 중에도 뜬다 */
    val request: RequestUi? = null,
) {
    val onlinePassengers: List<Passenger> get() = passengers.filter { it.online }
    val passengersFull: Boolean get() = passengers.size >= Config.MAX_PASSENGERS

    /** [출발해요]를 받을 사람: 지금 폰이 붙어 있거나 알림을 켠 동승자 (기획 7장) */
    val departureReceivers: List<Passenger> get() = passengers.filter { it.online || it.pushEnabled }
}

/**
 * 출발해요 대화 (car-app-design.md 4-5, 출발해요 기획 6-2).
 * callId가 null이면 [출발해요]를 눌러 서버 답(ack)을 기다리는 중.
 */
data class DepartureUi(
    val callId: Long? = null,
    /** 기기 시각(ms). "3분 지남"에 쓴다 */
    val calledAtMs: Long = System.currentTimeMillis(),
    val etaS: Int? = null,
    val destName: String? = null,
    /** 사람별 마지막 빠른 답장 (departure.status) */
    val people: List<DeparturePerson> = emptyList(),
    /** 지난 대화 + 이번 대화 (최신이 마지막) */
    val messages: List<DepartureMessage> = emptyList(),
    /** [다시 알리기]를 이 시각(기기 ms)까지 잠근다 (1분에 1번) */
    val remindLockedUntilMs: Long = 0,
    /** 보내기 실패 문구 (3초 뒤 지움) */
    val error: String? = null,
)
/** 수락 화면 1건 (car-app-design.md 6-4 `pendingRequest`) */
data class RequestUi(
    val request: WaypointRequest,
    /** 기기 시각(ms) 기준 수락 마감 */
    val expiresAtMs: Long,
    val phase: Phase = Phase.SHOWING,
) {
    enum class Phase {
        /** 버튼을 누를 수 있음 */
        SHOWING,
        /** [추가] 뒤 서버 선점(ack) 기다림. 버튼 잠금 */
        CLAIMING,
        /** 내비에 넣는 중 (성공 콜백 기다림, 최대 60초) */
        APPLYING,
        /** "추가하지 못했어요" 3초 */
        FAILED,
    }
}

/** QR 화면 (4-2). url이 null이면 받는 중이거나 실패([error]) */
data class QrUi(
    val url: String? = null,
    /** 기기 시각(ms) 기준 만료 */
    val expiresAtMs: Long = 0,
    val error: String? = null,
)
