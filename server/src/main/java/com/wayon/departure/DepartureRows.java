package com.wayon.departure;

import java.time.LocalDateTime;

/** DB 행. 시간은 UTC */
public final class DepartureRows {

	private DepartureRows() {
	}

	public record Call(long id, long vehicleId, long tripId, String status, Integer etaMin, String destName,
			LocalDateTime calledAt, LocalDateTime lastRemindedAt) {
	}

	/**
	 * 출발 알림을 받는 동승자 1명 (기획 8장: 지금 세션 + 끝난 지 2시간 안의 직전 세션, 해제한 사람 제외).
	 * lastQuickCode는 이 알림에서 마지막으로 고른 빠른 답장, messages는 이 알림에서 보낸 메시지 수
	 */
	public record Person(long passengerId, String nickname, String pushEndpoint, String pushP256dh, String pushAuth,
			String lastQuickCode, int messages) {
	}
}
