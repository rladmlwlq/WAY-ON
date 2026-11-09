package com.wayon.waypoint;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import com.wayon.common.ApiException;
import com.wayon.restarea.RestAreaDto;
import com.wayon.restarea.RestAreaService;
import com.wayon.search.SearchClients;
import com.wayon.trip.TripLiveService;
import com.wayon.trip.TripMapper;
import com.wayon.trip.TripRows;
import com.wayon.ws.WsSessions;

import jakarta.annotation.PreDestroy;

/**
 * 경유지 요청 (F3, app-api.md 6-1, 5-10, 5-11, 7-2, car-app-design.md 6-4).
 * 폰 제안 → 서버가 장소를 다시 찾아 저장 → 차 수락 화면(10초) → 서버 선점 → 내비 반영 결과. 상태가 바뀔 때마다 폰들에 waypoint.status.
 * 한 세션에 PENDING은 1건: 새 제안이 오면 이전 것을 EXPIRED로 (마지막 요청 우선)
 */
@Service
public class WaypointService {

	private static final Logger log = LoggerFactory.getLogger(WaypointService.class);
	static final Duration ACCEPT_WINDOW = Duration.ofSeconds(10);
	private static final int MAX_WAYPOINTS = 2;

	private final TripMapper mapper;
	private final TripLiveService live;
	private final SearchClients search;
	private final RestAreaService restAreas;
	private final WsSessions sessions;
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

	public WaypointService(TripMapper mapper, TripLiveService live, SearchClients search, RestAreaService restAreas,
			WsSessions sessions) {
		this.mapper = mapper;
		this.live = live;
		this.search = search;
		this.restAreas = restAreas;
		this.sessions = sessions;
	}

	@PreDestroy
	void stop() {
		timer.shutdownNow();
	}

	/** 서버가 다시 찾은 장소 (폰이 보낸 좌표를 그대로 믿지 않음) */
	record Place(String type, String extId, String name, String address, double lat, double lng) {
	}

	/** 6-1. 응답 data는 {"requestId"} */
	public Map<String, Object> save_propose(long vehicleId, long passengerId, Map<?, ?> data) {
		TripRows.Trip trip = mapper.select_activeTrip(vehicleId);
		if (trip == null) {
			throw new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_ACTIVE", "차량 앱이 켜지면 연결돼요");
		}
		Place place = resolve(data.get("place") instanceof Map<?, ?> m ? m : Map.of());
		TripLiveService.RouteShape route = live.select_routeShape(vehicleId);
		String action = !route.hasDestination() || "PARKING".equals(place.type()) ? "SET_DEST" : "ADD";
		if ("ADD".equals(action) && route.waypoints() >= MAX_WAYPOINTS) {
			throw new ApiException(HttpStatus.CONFLICT, "WAYPOINT_FULL", "경유지가 가득 찼어요");
		}

		LocalDateTime now = now();
		for (long old : mapper.select_pendingRequestIds(trip.id())) {
			if (mapper.update_requestStatus(old, vehicleId, "PENDING", "EXPIRED", now, null, null) > 0) {
				sessions.sendToCar(vehicleId, "waypoint.expired", Map.of("requestId", old));
				sendStatus(old);
			}
		}
		Map<String, Object> row = new HashMap<>();
		row.put("tripId", trip.id());
		row.put("passengerId", passengerId);
		row.put("placeType", place.type());
		row.put("extId", place.extId());
		row.put("name", place.name());
		row.put("address", place.address());
		row.put("lat", place.lat());
		row.put("lng", place.lng());
		row.put("action", action);
		row.put("now", now);
		mapper.insert_waypointRequest(row);
		long requestId = ((Number) row.get("id")).longValue();

		TripRows.Request request = mapper.select_request(requestId);
		Map<String, Object> placeOut = new LinkedHashMap<>();
		placeOut.put("type", place.type());
		placeOut.put("extId", place.extId()); // 차가 myWaypoints에 저장 (휴게소면 도착 안내 카드용)
		placeOut.put("name", place.name());
		placeOut.put("address", place.address());
		placeOut.put("lat", place.lat());
		placeOut.put("lng", place.lng());
		Map<String, Object> toCar = new LinkedHashMap<>();
		toCar.put("requestId", requestId);
		toCar.put("requestedBy", request.requestedBy());
		toCar.put("place", placeOut);
		toCar.put("action", action);
		toCar.put("expiresAt", now.plus(ACCEPT_WINDOW).toInstant(ZoneOffset.UTC).toString());
		sessions.sendToCar(vehicleId, "waypoint.request", toCar);
		sendStatus(requestId);
		timer.schedule(() -> expire(vehicleId, requestId), ACCEPT_WINDOW.toMillis(), TimeUnit.MILLISECONDS);
		return Map.of("requestId", requestId);
	}

	/** 5-10 [추가]. 10초가 지났거나 밀린 요청이면 REQUEST_EXPIRED (차량 앱은 내비를 부르지 않음) */
	public void save_accept(long vehicleId, Map<?, ?> data) {
		long id = requestId(data);
		LocalDateTime now = now();
		if (mapper.update_requestStatus(id, vehicleId, "PENDING", "ACCEPTED", now, null, now.minus(ACCEPT_WINDOW)) == 0) {
			throw new ApiException(HttpStatus.CONFLICT, "REQUEST_EXPIRED", "요청 시간이 지났어요");
		}
		sendStatus(id);
	}

	/** 5-10 [거절]. 이미 끝난 요청이면 조용히 무시 */
	public void save_reject(long vehicleId, Map<?, ?> data) {
		long id = requestId(data);
		if (mapper.update_requestStatus(id, vehicleId, "PENDING", "REJECTED", now(), null, null) > 0) {
			sendStatus(id);
		}
	}

	/** 5-11 내비 반영 결과. ACCEPTED인 요청만 */
	public void save_result(long vehicleId, Map<?, ?> data) {
		long id = requestId(data);
		boolean ok = Boolean.TRUE.equals(data.get("ok"));
		String reason = ok ? null
				: data.get("message") instanceof String m && !m.isBlank() ? m.substring(0, Math.min(200, m.length()))
						: "내비에 넣지 못했어요";
		if (mapper.update_requestStatus(id, vehicleId, "ACCEPTED", ok ? "APPLIED" : "FAILED", now(), reason,
				null) > 0) {
			sendStatus(id);
		}
	}

	private void expire(long vehicleId, long requestId) {
		try {
			if (mapper.update_requestStatus(requestId, vehicleId, "PENDING", "EXPIRED", now(), null, null) > 0) {
				sessions.sendToCar(vehicleId, "waypoint.expired", Map.of("requestId", requestId));
				sendStatus(requestId);
			}
		} catch (RuntimeException e) { // 던지면 타이머가 멈춘다
			log.warn("waypoint expire failed", e);
		}
	}

	/** 7장 waypoint.status: 그 차량의 모든 동승자에게 */
	private void sendStatus(long requestId) {
		TripRows.Request r = mapper.select_request(requestId);
		Map<String, Object> status = new LinkedHashMap<>();
		status.put("requestId", r.id());
		status.put("status", r.status());
		status.put("failReason", r.failReason());
		status.put("requestedBy", r.requestedBy());
		status.put("placeName", r.poiName());
		status.put("action", r.action());
		sessions.sendToPassengers(r.vehicleId(), "waypoint.status", status);
	}

	/**
	 * extId로 장소를 다시 찾는다.
	 * - `ra:000048`: 우리 휴게소
	 * - `kk:{카카오 ID}`: 폰이 준 이름·좌표 근처를 카카오로 다시 검색해 같은 ID를 찾는다 (카카오에 ID 조회 API가 없음).
	 *   휴게소 안 매장이면 그 휴게소로 바꿔서 넣는다 (도착 안내 A1이 휴게소 요청으로 동작하게)
	 */
	Place resolve(Map<?, ?> place) {
		String extId = place.get("extId") instanceof String s ? s : "";
		if (extId.startsWith(RestAreaService.EXT_ID_PREFIX)) {
			return restArea(RestAreaService.parseExtId(extId).orElseThrow(ApiException::placeNotFound));
		}
		if (!extId.startsWith("kk:") || !(place.get("name") instanceof String name) || name.isBlank()
				|| !(place.get("lat") instanceof Number lat) || !(place.get("lng") instanceof Number lng)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "요청 형식이 맞지 않아요");
		}
		String kakaoId = extId.substring(3);
		SearchClients.KakaoPlace found;
		try {
			found = search.kakaoKeyword(name.strip(), lat.doubleValue(), lng.doubleValue()).stream()
					.filter(p -> p.id().equals(kakaoId)).findFirst().orElseThrow(ApiException::placeNotFound);
		} catch (RestClientException e) {
			log.warn("경유지 장소 확인 실패: {}", e.getMessage());
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EXTERNAL_UNAVAILABLE", "잠시 뒤에 다시 시도해 주세요");
		}
		String restAreaCode = restAreas.restAreaCodesByKakaoId(java.util.List.of(kakaoId)).get(kakaoId);
		if (restAreaCode != null) {
			return restArea(restAreaCode);
		}
		String type = switch (found.groupCode()) {
			case "PK6" -> "PARKING";
			case "OL7" -> "GAS_STATION";
			default -> "RESTAURANT";
		};
		String address = found.roadAddressName().isBlank() ? found.addressName() : found.roadAddressName();
		return new Place(type, extId, found.placeName(), address.isBlank() ? null : address, found.lat(),
				found.lng());
	}

	private Place restArea(String code) {
		RestAreaDto.Location ra = restAreas.location(code);
		if (ra == null) {
			throw ApiException.placeNotFound();
		}
		return new Place("REST_AREA", RestAreaService.EXT_ID_PREFIX + code, ra.name(),
				ra.routeName() + " " + ra.direction(), ra.lat(), ra.lng());
	}

	private static long requestId(Map<?, ?> data) {
		if (!(data.get("requestId") instanceof Number n)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "요청 형식이 맞지 않아요");
		}
		return n.longValue();
	}

	private static LocalDateTime now() {
		return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
	}
}
