package com.wayon.vehicle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.wayon.common.ApiException;
import com.wayon.ws.WsSessions;

/** 차량 등록, QR 연결, 동승자, 푸시 구독, 지난 대화 (app-api.md 2장, 3-1~3-5). 토큰은 해시만 저장한다 */
@Service
public class VehicleService {

	private static final String CAR_PREFIX = "c_";
	private static final String PASSENGER_PREFIX = "p_";
	private static final Duration CODE_TTL = Duration.ofMinutes(3);
	private static final Duration PASSENGER_TOKEN_TTL = Duration.ofDays(90);
	private static final Duration MESSAGE_TTL = Duration.ofDays(30);
	private static final int MAX_PASSENGERS = 4;
	private static final int MAX_NICKNAME = 10;
	private static final int DEFAULT_MESSAGES = 30;
	private static final int MAX_MESSAGES = 50;

	private static final SecureRandom RANDOM = new SecureRandom();

	private final VehicleMapper mapper;
	private final WsSessions sessions;
	private final String webUrl;
	private final String vapidPublicKey;
	private final Clock clock;

	@Autowired
	public VehicleService(VehicleMapper mapper, WsSessions sessions, @Value("${wayon.web-url}") String webUrl,
			@Value("${VAPID_PUBLIC_KEY:}") String vapidPublicKey) {
		this(mapper, sessions, webUrl, vapidPublicKey, Clock.systemUTC());
	}

	VehicleService(VehicleMapper mapper, WsSessions sessions, String webUrl, String vapidPublicKey, Clock clock) {
		this.mapper = mapper;
		this.sessions = sessions;
		this.webUrl = webUrl;
		this.vapidPublicKey = vapidPublicKey;
		this.clock = clock;
	}

	/** 2-1 */
	public VehicleDto.VehicleCreated save_vehicle() {
		LocalDateTime now = now();
		String token = CAR_PREFIX + randomToken(32);
		mapper.insert_vehicle(UUID.randomUUID().toString().replace("-", ""), sha256(token), now);
		return new VehicleDto.VehicleCreated(token, toInstant(now));
	}

	/** 2-2. 새로 발급하면 그 차량의 이전 코드는 폐기한다 */
	@Transactional
	public VehicleDto.ConnectCodeCreated save_connectCode(String authorization, VehicleDto.ConnectCodeRequest request) {
		long vehicleId = requireCar(authorization);
		if (!"QR".equals(request.kind())) { // PIN은 Pleos 답을 기다리는 중 (9장)
			throw badRequest();
		}
		if (mapper.select_passengerCount(vehicleId) >= MAX_PASSENGERS) {
			throw passengerLimit();
		}
		LocalDateTime expiresAt = now().plus(CODE_TTL);
		String code = randomToken(16);
		mapper.delete_connectCodes(vehicleId);
		mapper.insert_connectCode(vehicleId, sha256(code), expiresAt);
		return new VehicleDto.ConnectCodeCreated(code, webUrl + "/join#" + code, toInstant(expiresAt));
	}

	/** 2-3 */
	public VehicleDto.Passengers select_passengers(String authorization) {
		return passengersOf(requireCar(authorization));
	}

	/** 2-4. 대화 기록은 남긴다. 이미 해제됐거나 없는 동승자도 204. 그 동승자 WebSocket은 4403으로 닫는다 */
	public void delete_passenger(String authorization, long passengerId) {
		long vehicleId = requireCar(authorization);
		if (mapper.update_passengerRevoked(vehicleId, passengerId, now()) > 0) {
			sessions.closePassenger(passengerId);
			sendPassengersUpdate(vehicleId);
		}
	}

	/** 4-1. WebSocket auth 메시지의 토큰. 동승자는 연결할 때마다 토큰 유효 기간 90일을 다시 센다 */
	public WsSessions.Client select_wsClient(String token) {
		String authorization = "Bearer " + token;
		if (isPassengerToken(authorization)) {
			VehicleRows.Passenger p = requirePassenger(authorization);
			mapper.update_passengerConnected(p.id(), now());
			return new WsSessions.Client(p.vehicleId(), p.id(), p.nickname());
		}
		long vehicleId = requireCar(authorization);
		mapper.update_vehicleSeen(vehicleId, now());
		return new WsSessions.Client(vehicleId, null, null);
	}

	/** passengers.update (7장): 동승자 연결·끊김·해제 때 차량 앱에 */
	public void sendPassengersUpdate(long vehicleId) {
		sessions.sendToCar(vehicleId, "passengers.update", passengersOf(vehicleId));
	}

	private VehicleDto.Passengers passengersOf(long vehicleId) {
		return new VehicleDto.Passengers(mapper.select_passengers(vehicleId).stream()
				.map(p -> new VehicleDto.Passenger(p.id(), p.nickname(), sessions.isOnline(p.id()),
						p.pushEndpoint() != null, toInstant(p.lastConnectedAt())))
				.toList());
	}

	/** 2-5. 차량·동승자 토큰 공용. 최신이 마지막 */
	public VehicleDto.Messages select_departureMessages(String authorization, Long before, Integer size) {
		long vehicleId = isPassengerToken(authorization) ? requirePassenger(authorization).vehicleId()
				: requireCar(authorization);
		int limit = size == null ? DEFAULT_MESSAGES : Math.clamp(size, 1, MAX_MESSAGES);
		// 하루 1번 삭제 배치가 돌기 전에도 30일 지난 메시지는 주지 않는다
		List<VehicleRows.Message> rows = new ArrayList<>(
				mapper.select_departureMessages(vehicleId, before, limit + 1, now().minus(MESSAGE_TTL)));
		boolean hasMore = rows.size() > limit;
		if (hasMore) {
			rows.remove(limit);
		}
		Collections.reverse(rows);
		return new VehicleDto.Messages(rows.stream().map(VehicleService::toMessage).toList(), hasMore);
	}

	/**
	 * 3-1. QR 하나로 만료(3분) 전까지 여러 명이 연결한다. 인원 4명·닉네임 중복은 차량 행을 잠그고 확인한다.
	 * READ_COMMITTED: 기본값(REPEATABLE READ)이면 차량 행을 잠근 뒤의 인원·닉네임 확인이 잠금 전 스냅샷을 본다
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public VehicleDto.Connected save_connect(VehicleDto.ConnectRequest request) {
		if (!Boolean.TRUE.equals(request.locationConsent())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "CONSENT_REQUIRED", "위치 공유에 동의해야 연결돼요");
		}
		String nickname = request.nickname() == null ? "" : request.nickname().strip();
		int length = nickname.codePointCount(0, nickname.length());
		if (length < 1 || length > MAX_NICKNAME) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "NICKNAME_INVALID", "닉네임은 1~10자로 정해 주세요");
		}
		VehicleRows.ConnectCode code = request.code() == null ? null : mapper.select_connectCode(sha256(request.code()));
		if (code == null) {
			throw codeInvalid();
		}
		LocalDateTime now = now();
		if (code.expiresAt().isBefore(now)) {
			throw new ApiException(HttpStatus.GONE, "CODE_EXPIRED", "QR이 만료됐어요");
		}
		long vehicleId = mapper.select_vehicleIdForUpdate(code.vehicleId());
		if (mapper.select_passengerCount(vehicleId) >= MAX_PASSENGERS) {
			throw passengerLimit();
		}
		if (mapper.select_nicknameCount(vehicleId, nickname) > 0) {
			throw new ApiException(HttpStatus.CONFLICT, "NICKNAME_TAKEN", "이미 쓰는 닉네임이에요");
		}
		String token = PASSENGER_PREFIX + randomToken(32);
		String tokenHash = sha256(token);
		mapper.insert_passenger(vehicleId, tokenHash, nickname, now);
		VehicleRows.Passenger passenger = mapper.select_passengerByToken(tokenHash);
		return new VehicleDto.Connected(token, passenger.id(), nickname, toInstant(now.plus(PASSENGER_TOKEN_TTL)));
	}

	/** 3-2 */
	/** 동승자 토큰으로 그 차량 ID (검색처럼 차 위치가 필요한 폰 API용) */
	public long select_passengerVehicleId(String authorization) {
		return requirePassenger(authorization).vehicleId();
	}

	public VehicleDto.Me select_passengerMe(String authorization) {
		VehicleRows.Passenger p = requirePassenger(authorization);
		return new VehicleDto.Me(p.id(), p.nickname(), p.pushEndpoint() != null,
				toInstant(p.lastConnectedAt().plus(PASSENGER_TOKEN_TTL)));
	}

	/** 3-3 */
	public VehicleDto.VapidKey select_vapidPublicKey() {
		if (vapidPublicKey.isBlank()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EXTERNAL_UNAVAILABLE", "잠시 뒤에 다시 시도해 주세요");
		}
		return new VehicleDto.VapidKey(vapidPublicKey);
	}

	/** 3-4. 같은 폰이 다시 보내면 덮어쓴다. 구독은 passenger 행의 칸이라 INSERT가 없다 */
	public void save_pushSubscription(String authorization, VehicleDto.PushSubscription request) {
		long passengerId = requirePassenger(authorization).id();
		VehicleDto.Keys keys = request.keys();
		// 서버가 나중에 이 주소로 요청을 보내므로 https만 받는다
		if (!isValid(request.endpoint(), 500) || !request.endpoint().startsWith("https://") || keys == null
				|| !isValid(keys.p256dh(), 100) || !isValid(keys.auth(), 50)) {
			throw badRequest();
		}
		mapper.update_pushSubscription(passengerId, request.endpoint(), keys.p256dh(), keys.auth(), now());
	}

	/** 3-5 */
	public void delete_pushSubscription(String authorization) {
		mapper.delete_pushSubscription(requirePassenger(authorization).id());
	}

	private long requireCar(String authorization) {
		String token = bearer(authorization);
		if (token.startsWith(PASSENGER_PREFIX)) {
			throw forbiddenRole();
		}
		Long vehicleId = mapper.select_vehicleIdByToken(sha256(token));
		if (vehicleId == null) {
			throw tokenInvalid();
		}
		return vehicleId;
	}

	private VehicleRows.Passenger requirePassenger(String authorization) {
		String token = bearer(authorization);
		if (token.startsWith(CAR_PREFIX)) {
			throw forbiddenRole();
		}
		VehicleRows.Passenger p = mapper.select_passengerByToken(sha256(token));
		if (p == null || p.revokedAt() != null || p.lastConnectedAt().plus(PASSENGER_TOKEN_TTL).isBefore(now())) {
			throw tokenInvalid();
		}
		return p;
	}

	private static boolean isPassengerToken(String authorization) {
		return bearer(authorization).startsWith(PASSENGER_PREFIX);
	}

	private static String bearer(String authorization) {
		if (authorization == null || !authorization.startsWith("Bearer ")) {
			throw tokenInvalid();
		}
		return authorization.substring("Bearer ".length());
	}

	/** 2-5 메시지 1개. 출발해요 실시간 메시지(departure.message)도 같은 모양 */
	public static VehicleDto.Message toMessage(VehicleRows.Message m) {
		String nickname = m.passengerId() == null ? "운전자" : m.revoked() ? "(나간 동승자)" : m.nickname();
		return new VehicleDto.Message(m.id(), m.callId(), new VehicleDto.Sender(m.passengerId(), nickname), m.kind(),
				m.quickCode(), m.body(), toInstant(m.sentAt()));
	}

	private static boolean isValid(String value, int maxLength) {
		return value != null && !value.isBlank() && value.length() <= maxLength;
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
	}

	private static Instant toInstant(LocalDateTime utc) {
		return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
	}

	private static String randomToken(int bytes) {
		byte[] value = new byte[bytes];
		RANDOM.nextBytes(value);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static ApiException tokenInvalid() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "TOKEN_INVALID", "다시 연결해 주세요");
	}

	private static ApiException forbiddenRole() {
		return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE", "이 기능을 쓸 수 없어요");
	}

	private static ApiException codeInvalid() {
		return new ApiException(HttpStatus.NOT_FOUND, "CODE_INVALID", "QR을 다시 띄워 주세요");
	}

	private static ApiException passengerLimit() {
		return new ApiException(HttpStatus.CONFLICT, "PASSENGER_LIMIT", "동승자는 4명까지 연결할 수 있어요");
	}

	private static ApiException badRequest() {
		return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "요청 형식이 맞지 않아요");
	}
}
