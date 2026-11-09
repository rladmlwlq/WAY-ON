package com.wayon.trip;

import java.time.LocalDateTime;

/** DB 행. 시간은 UTC */
public final class TripRows {

	private TripRows() {
	}

	public record Trip(long id, String publicId, long vehicleId, LocalDateTime createdAt) {
	}

	/** 경유지 요청 1건 (waypoint_request + 요청한 동승자 닉네임) */
	public record Request(long id, long vehicleId, String placeType, String placeExtId, String poiName,
			String address, java.math.BigDecimal lat, java.math.BigDecimal lng, String action, String status,
			LocalDateTime requestedAt, String failReason, String requestedBy) {
	}

	/** ACTIVE 세션의 마지막 차 위치 (5-3) */
	public record CarPosition(java.math.BigDecimal lat, java.math.BigDecimal lng) {
	}
}
