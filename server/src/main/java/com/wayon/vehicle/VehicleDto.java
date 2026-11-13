package com.wayon.vehicle;

import java.time.Instant;
import java.util.List;

/** 차량·동승자 REST 요청·응답 모양 (app-api.md 2장, 3-1~3-5) */
public final class VehicleDto {

	private VehicleDto() {
	}

	/** 2-1 */
	public record VehicleCreated(String carToken, Instant createdAt) {
	}

	/** 2-2 */
	public record ConnectCodeRequest(String kind) {
	}

	public record ConnectCodeCreated(String code, String url, Instant expiresAt) {
	}

	/** 2-3 */
	public record Passengers(List<Passenger> passengers) {
	}

	public record Passenger(long passengerId, String nickname, boolean online, boolean pushEnabled,
			Instant lastConnectedAt) {
	}

	/** 2-5 */
	public record Messages(List<Message> messages, boolean hasMore) {
	}

	public record Message(long messageId, long callId, Sender sender, String kind, String quickCode, String body,
			Instant sentAt) {
	}

	public record Sender(Long passengerId, String nickname) {
	}

	/** 2-6 */
	public record Calls(List<Call> calls, boolean hasMore) {
	}

	public record Call(long callId, String status, String destName, Instant calledAt, Instant closedAt,
			List<String> passengers) {
	}

	/** 3-1 */
	public record ConnectRequest(String code, String nickname, Boolean locationConsent) {
	}

	public record Connected(String passengerToken, long passengerId, String nickname, Instant tokenExpiresAt) {
	}

	/** 3-2 */
	public record Me(long passengerId, String nickname, boolean pushEnabled, Instant tokenExpiresAt) {
	}

	/** 3-3 */
	public record VapidKey(String publicKey) {
	}

	/** 3-4. 브라우저 PushSubscription.toJSON() 그대로 */
	public record PushSubscription(String endpoint, Keys keys) {
	}

	public record Keys(String p256dh, String auth) {
	}
}
