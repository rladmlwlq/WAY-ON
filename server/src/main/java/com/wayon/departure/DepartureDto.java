package com.wayon.departure;

import java.time.Instant;
import java.util.List;

/** 출발해요 WebSocket 메시지 모양 (app-api.md 5-12, 6-2, 7장) */
public final class DepartureDto {

	private DepartureDto() {
	}

	/** departure.called. nextRestAreaM은 경로 위 휴게소 계산(2번)이 생기면 채운다 */
	public record Called(long callId, Instant calledAt, Integer etaS, String destName, Integer nextRestAreaM) {
	}

	/** departure.status의 사람 1명. lastQuickCode가 null이면 응답 없음 */
	public record Person(long passengerId, String nickname, String lastQuickCode, boolean pushEnabled) {
	}

	public record Status(long callId, List<Person> people) {
	}

	public record Closed(long callId, String reason) {
	}

	/** auth.ok의 openCall. 다시 접속한 차·폰이 대화 화면을 되살린다 */
	public record OpenCall(long callId, Instant calledAt, Integer etaS, String destName, List<Person> people) {
	}

	/** 7-3 Web Push 본문 (Service Worker가 받음) */
	public record Push(String kind, long callId, String title, String body, String url, String tag) {
	}
}
