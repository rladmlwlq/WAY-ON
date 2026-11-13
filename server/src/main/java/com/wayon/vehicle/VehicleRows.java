package com.wayon.vehicle;

import java.time.LocalDateTime;

/** DB 행. 시간은 UTC라 시간대 변환 없는 LocalDateTime으로 읽는다 */
public final class VehicleRows {

	private VehicleRows() {
	}

	public record ConnectCode(long id, long vehicleId, LocalDateTime expiresAt) {
	}

	public record Passenger(long id, long vehicleId, String nickname, LocalDateTime lastConnectedAt,
			LocalDateTime revokedAt, String pushEndpoint) {
	}

	/** 2-6 지난 출발 알림 1건. nicknames는 그 세션 동승자 닉네임을 쉼표로 이은 것 */
	public record Call(long id, String status, String destName, LocalDateTime calledAt, LocalDateTime closedAt,
			String nicknames) {
	}

	public record Message(long id, long callId, Long passengerId, String nickname, boolean revoked, String kind,
			String quickCode, String body, LocalDateTime sentAt) {
	}
}
