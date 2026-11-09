package com.wayon.car

/** 기준값 (car-app-design.md 11장, app-api.md 9장). 값을 바꿀 때는 이 파일만 고친다. */
object Config {
    val SERVER_URL: String = BuildConfig.SERVER_URL
    val WEB_URL: String = BuildConfig.WEB_URL

    const val LOCATION_INTERVAL_MS = 5_000L          // 위치 전송
    const val ROUTE_REMAIN_INTERVAL_MS = 30_000L     // 남은 거리·시간만 바뀔 때
    const val ENERGY_MIN_INTERVAL_MS = 60_000L       // 에너지 전송 최소 간격

    const val ENERGY_MARGIN_M = 20_000               // 에너지 여유 20km
    const val REST_AREA_APPROACH_M = 2_000           // 휴게소 접근
    const val PARKING_NEAR_DIST_M = 10_000           // 주차장: 목적지 10km
    const val PARKING_NEAR_TIME_S = 300              //   또는 5분

    const val ACCEPT_TIMEOUT_MS = 10_000L            // 수락 대기
    const val REMIND_INTERVAL_MS = 60_000L           // 출발해요 다시 알리기
    const val MESSAGE_MAX_LEN = 40                   // 출발해요 메시지
    const val MAX_PASSENGERS = 4
    const val MAX_WAYPOINTS = 2                      // SDK로 확인 전 (car-app-design.md 13장)

    const val PING_INTERVAL_MS = 20_000L
    const val RECONNECT_MAX_MS = 30_000L             // 1·2·4 … 최대 30초
    const val FAIL_TOAST_MS = 3_000L                 // "추가하지 못했어요"
    const val VOICE_LEAD_SILENCE_MS = 200L           // 음성 앞 묵음 (Pleos 권장 150~200ms)
    const val VOICE_MAX_MS = 15_000L                 // 끝 콜백이 안 와도 이만큼 지나면 포커스를 놓음
}
