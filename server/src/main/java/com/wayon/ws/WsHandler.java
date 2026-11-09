package com.wayon.ws;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.wayon.common.ApiException;
import com.wayon.restarea.RestAreaDto;
import com.wayon.restarea.RestAreaService;
import com.wayon.trip.TripLiveService;
import com.wayon.trip.TripService;
import com.wayon.vehicle.VehicleService;
import com.wayon.waypoint.WaypointService;

import jakarta.annotation.PreDestroy;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * /ws (app-api.md 4장): 첫 메시지 인증, ping, 세션 시작·끝, 5분 무통신 만료, 차 위치·경로·에너지·기어(5-3~5-6),
 * 경유지 요청·수락·결과(6-1, 5-10, 5-11), 휴게소 접근·도착·떠남(5-9).
 * 세션이 끝나도 동승자 연결은 닫지 않고 trip.ended만 보낸다 (4-3의 4410은 쓰지 않음, 9장 정하지 않은 것)
 */
@Component
public class WsHandler extends TextWebSocketHandler {

	private static final Logger log = LoggerFactory.getLogger(WsHandler.class);
	private static final long AUTH_TIMEOUT_S = 5;
	private static final long EXPIRE_CHECK_S = 30;

	private final WsSessions sessions;
	private final VehicleService vehicles;
	private final TripService trips;
	private final RestAreaService restAreas;
	private final TripLiveService live;
	private final WaypointService waypoints;
	private final JsonMapper json;
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

	public WsHandler(WsSessions sessions, VehicleService vehicles, TripService trips, RestAreaService restAreas,
			TripLiveService live, WaypointService waypoints, JsonMapper json) {
		this.sessions = sessions;
		this.vehicles = vehicles;
		this.trips = trips;
		this.restAreas = restAreas;
		this.live = live;
		this.waypoints = waypoints;
		this.json = json;
		timer.scheduleWithFixedDelay(this::expireTrips, EXPIRE_CHECK_S, EXPIRE_CHECK_S, TimeUnit.SECONDS);
	}

	@PreDestroy
	void stop() {
		timer.shutdownNow();
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		sessions.open(session);
		timer.schedule(() -> {
			if (WsSessions.client(session) == null) {
				WsSessions.close(session, WsSessions.AUTH_FAILED);
			}
		}, AUTH_TIMEOUT_S, TimeUnit.SECONDS);
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		Map<?, ?> m = parse(message.getPayload());
		String type = m.get("type") instanceof String t ? t : "";
		String reqId = m.get("reqId") instanceof String r ? r : null;
		Map<?, ?> data = m.get("data") instanceof Map<?, ?> d ? d : Map.of();

		WsSessions.Client client = WsSessions.client(session);
		if (client == null) {
			auth(session, type, data);
			return;
		}
		try {
			if (client.isCar()) {
				trips.save_carActivity(client.vehicleId());
			}
			switch (type) {
				case "ping" -> sessions.send(session, "pong", null, null);
				case "trip.start" -> tripStart(session, requireCar(client), reqId, data);
				case "trip.end" -> tripEnd(session, requireCar(client), reqId);
				case "car.location" -> live.save_location(requireCar(client), data);
				case "car.route" -> live.save_route(requireCar(client), data);
				case "car.energy" -> live.save_energy(requireCar(client), data);
				case "car.gear" -> live.save_gear(requireCar(client), data);
				case "waypoint.propose" -> ack(session, reqId,
						waypoints.save_propose(client.vehicleId(), requirePassenger(client), data));
				case "waypoint.accept" -> {
					waypoints.save_accept(requireCar(client), data);
					ack(session, reqId, Map.of());
				}
				case "waypoint.reject" -> waypoints.save_reject(requireCar(client), data);
				case "waypoint.result" -> waypoints.save_result(requireCar(client), data);
				case "restarea.approach" -> restArea(session, requireCar(client), reqId, data, RestAreaDto.Phase.APPROACH);
				case "restarea.arrived" -> restArea(session, requireCar(client), reqId, data, RestAreaDto.Phase.ARRIVED);
				case "restarea.left" -> restArea(session, requireCar(client), reqId, data, RestAreaDto.Phase.LEFT);
				default -> throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "요청 형식이 맞지 않아요");
			}
		} catch (ApiException e) {
			sessions.send(session, "error", reqId, Map.of("code", e.getCode(), "message", e.getMessage()));
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		WsSessions.Client client = WsSessions.client(session);
		if (sessions.remove(session) && !client.isCar()) {
			vehicles.sendPassengersUpdate(client.vehicleId());
		}
	}

	/** 4-1 */
	private void auth(WebSocketSession session, String type, Map<?, ?> data) {
		WsSessions.Client client;
		try {
			if (!"auth".equals(type) || !(data.get("token") instanceof String token)) {
				throw new IllegalArgumentException();
			}
			client = vehicles.select_wsClient(token);
		} catch (ApiException | IllegalArgumentException e) {
			WsSessions.close(session, WsSessions.AUTH_FAILED);
			return;
		}
		sessions.add(session, client);
		if (!session.isOpen()) { // 인증 중에 5초가 지나 닫힘
			sessions.remove(session);
			return;
		}

		Map<String, Object> ok = new LinkedHashMap<>();
		ok.put("role", client.isCar() ? "CAR" : "PASSENGER");
		if (!client.isCar()) {
			ok.put("me", Map.of("passengerId", client.passengerId(), "nickname", client.nickname()));
		}
		ok.put("trip", trips.select_activeTrip(client.vehicleId()));
		ok.put("openCall", null); // shortcut: 출발해요(A2) 구현 때 열린 출발 알림을 넣는다
		sessions.send(session, "auth.ok", null, ok);

		if (client.isCar()) {
			trips.save_carActivity(client.vehicleId());
		} else {
			trips.save_tripPassenger(client.vehicleId(), client.passengerId());
			vehicles.sendPassengersUpdate(client.vehicleId());
			// 4-1: 지도를 바로 그리게 마지막 경로·차 위치를 한 번
			live.select_snapshot(client.vehicleId()).forEach((t, d) -> sessions.send(session, t, null, d));
		}
	}

	/** 5-1 */
	private void tripStart(WebSocketSession session, long vehicleId, String reqId, Map<?, ?> data) {
		if (!Boolean.TRUE.equals(data.get("locationConsent"))) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "CONSENT_REQUIRED", "위치 공유에 동의해야 연결돼요");
		}
		TripService.TripInfo trip = trips.save_tripStart(vehicleId);
		for (long passengerId : sessions.onlinePassengerIds(vehicleId)) {
			trips.save_tripPassenger(vehicleId, passengerId);
		}
		ack(session, reqId, Map.of("tripId", trip.tripId()));
		sessions.sendToPassengers(vehicleId, "trip.started", trip);
	}

	/** 5-2 */
	private void tripEnd(WebSocketSession session, long vehicleId, String reqId) {
		if (trips.save_tripEnd(vehicleId)) {
			live.clear(vehicleId);
			sessions.sendToPassengers(vehicleId, "trip.ended", Map.of("reason", "ENDED"));
		}
		ack(session, reqId, Map.of());
	}

	/**
	 * 5-9 → 7장 restarea.info. 경유지로 넣은 휴게소만 (requestId가 이 차량의 휴게소 요청).
	 * 받는 사람은 지금 붙어 있는 동승자. 폰은 접근·도착이면 카드를 띄우고 LEFT면 닫는다
	 */
	private void restArea(WebSocketSession session, long vehicleId, String reqId, Map<?, ?> data,
			RestAreaDto.Phase phase) {
		if (!(data.get("requestId") instanceof Number requestId)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "요청 형식이 맞지 않아요");
		}
		String extId = trips.select_restAreaExtId(vehicleId, requestId.longValue());
		sessions.sendToPassengers(vehicleId, "restarea.info", restAreas.info(phase, extId));
		ack(session, reqId, Map.of());
	}

	private void expireTrips() {
		try {
			for (long vehicleId : trips.save_expiredTrips()) {
				live.clear(vehicleId);
				sessions.sendToPassengers(vehicleId, "trip.ended", Map.of("reason", "EXPIRED"));
			}
		} catch (RuntimeException e) { // 던지면 다음 확인이 멈춘다
			log.warn("trip expire failed", e);
		}
	}

	private void ack(WebSocketSession session, String reqId, Object data) {
		if (reqId != null) {
			sessions.send(session, "ack", reqId, data);
		}
	}

	private static long requirePassenger(WsSessions.Client client) {
		if (client.isCar()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE", "이 기능을 쓸 수 없어요");
		}
		return client.passengerId();
	}

	private static long requireCar(WsSessions.Client client) {
		if (!client.isCar()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE", "이 기능을 쓸 수 없어요");
		}
		return client.vehicleId();
	}

	private Map<?, ?> parse(String payload) {
		try {
			return json.readValue(payload, Map.class) instanceof Map<?, ?> m ? m : Map.of();
		} catch (JacksonException e) {
			return Map.of();
		}
	}
}
