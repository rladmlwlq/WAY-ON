package com.wayon.trip;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.wayon.common.ApiException;
import com.wayon.ws.WsSessions;

import jakarta.annotation.PreDestroy;

/**
 * 차 위치·기어·에너지(5-3, 5-5, 5-6) → 폰 trip.state, 목적지·경유지(5-4) → 길찾기 → 폰 route.update (7-1).
 * 위치·목적지·에너지는 trip 행에, 기어와 마지막 경로선은 메모리에 둔다 (DB 칸 없음, 늦게 붙은 폰에 다시 보내는 용도).
 * shortcut: 메모리라서 서버가 1대일 때만 맞다 (WsSessions와 같음). 서버를 늘리면 공유 저장소로 옮긴다
 */
@Service
public class TripLiveService {

	private static final Logger log = LoggerFactory.getLogger(TripLiveService.class);

	private final TripMapper mapper;
	private final KakaoDirections directions;
	private final WsSessions sessions;
	/** 길찾기는 수백 ms 걸려 WebSocket 메시지 처리와 떼어 한 줄로 차례로 */
	private final ExecutorService routing = Executors.newSingleThreadExecutor();
	private final Map<Long, Live> live = new ConcurrentHashMap<>();

	public TripLiveService(TripMapper mapper, KakaoDirections directions, WsSessions sessions) {
		this.mapper = mapper;
		this.directions = directions;
		this.sessions = sessions;
	}

	@PreDestroy
	void stop() {
		routing.shutdownNow();
	}

	/** 차량 하나의 지금 상태. 필드는 이 객체로 잠그고 읽고 쓴다 */
	private static final class Live {
		Map<String, Object> car;
		String gear;
		Map<String, Object> energy;
		/** 목적지·경유지 구성. 같으면 길찾기를 다시 부르지 않는다 */
		List<Object> routeKey;
		Stop dest;
		List<Stop> waypoints;
		/** 차 위치를 받기 전에 만든 경로라 첫 위치가 오면 다시 만든다 */
		boolean needsOrigin;
		int routeVersion;
		Map<String, Object> route;
	}

	/** 5-3. ACTIVE 세션이 없으면 버린다 (위치 공유는 세션 동안만) */
	public void save_location(long vehicleId, Map<?, ?> data) {
		double lat = coord(data.get("lat"), 90);
		double lng = coord(data.get("lng"), 180);
		Integer heading = data.get("heading") == null ? null : intIn(data.get("heading"), 0, 359);
		LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
		if (mapper.update_tripLocation(vehicleId, lat, lng, heading, now) == 0) {
			return;
		}
		Map<String, Object> car = new LinkedHashMap<>();
		car.put("lat", lat);
		car.put("lng", lng);
		car.put("heading", heading);
		car.put("at", data.get("at") instanceof String at ? at : now.toInstant(ZoneOffset.UTC).toString());
		Live l = live(vehicleId);
		boolean rebuild;
		synchronized (l) {
			l.car = car;
			rebuild = l.needsOrigin;
			l.needsOrigin = false;
		}
		sendState(vehicleId, l);
		if (rebuild) {
			scheduleRoute(vehicleId, l);
		}
	}

	/** 5-6. 서버에 저장하지 않고 폰에 trip.state로만 */
	public void save_gear(long vehicleId, Map<?, ?> data) {
		String gear = data.get("gear") instanceof String g
				&& List.of("PARK", "DRIVE", "REVERSE", "NEUTRAL", "UNKNOWN").contains(g) ? g : null;
		if (gear == null) {
			throw badRequest();
		}
		if (mapper.select_activeTrip(vehicleId) == null) {
			return;
		}
		Live l = live(vehicleId);
		synchronized (l) {
			l.gear = gear;
		}
		sendState(vehicleId, l);
	}

	/** 5-5. 값이 안 나오면 그 필드는 null */
	public void save_energy(long vehicleId, Map<?, ?> data) {
		String type = data.get("energyType") == null ? null
				: data.get("energyType") instanceof String t && (t.equals("FUEL") || t.equals("EV")) ? t : "";
		if ("".equals(type)) {
			throw badRequest();
		}
		Integer pct = data.get("energyPct") == null ? null : intIn(data.get("energyPct"), 0, 100);
		Integer range = data.get("rangeM") == null ? null : intIn(data.get("rangeM"), 0, 5_000_000);
		if (mapper.update_tripEnergy(vehicleId, type, pct, range) == 0) {
			return;
		}
		Map<String, Object> energy = new LinkedHashMap<>();
		energy.put("energyType", type);
		energy.put("energyPct", pct);
		energy.put("rangeM", range);
		Live l = live(vehicleId);
		synchronized (l) {
			l.energy = energy;
		}
		sendState(vehicleId, l);
	}

	/**
	 * 5-4. 목적지·경유지 구성이 바뀔 때만 길찾기를 다시 부르고 route.update.
	 * 남은 거리·시간만 바뀐 메시지는 목적지 행만 그대로 둔다.
	 * shortcut: 경로선에서 500m·1분 넘게 벗어날 때 다시 부르기(5-3)는 아직 없음. 내비 재탐색과 어긋나면 추가
	 */
	public void save_route(long vehicleId, Map<?, ?> data) {
		Stop dest = data.get("destination") == null ? null : stop(data.get("destination"), true);
		List<Stop> waypoints = new ArrayList<>();
		if (data.get("waypoints") instanceof List<?> list) {
			for (Object o : list) {
				waypoints.add(stop(o, false));
			}
		} else if (data.get("waypoints") != null) {
			throw badRequest();
		}
		if (mapper.update_tripDestination(vehicleId, dest == null ? null : dest.name(),
				dest == null ? null : dest.lat(), dest == null ? null : dest.lng()) == 0) {
			return;
		}
		// 이름·좌표만 (남은 거리·시간은 빼고)
		List<Object> key = new ArrayList<>();
		key.add(dest == null ? null : Arrays.asList(dest.name(), dest.lat(), dest.lng()));
		waypoints.forEach(w -> key.add(Arrays.asList(w.name(), w.lat(), w.lng())));
		Live l = live(vehicleId);
		synchronized (l) {
			if (key.equals(l.routeKey)) {
				return;
			}
			l.routeKey = key;
			l.dest = dest;
			l.waypoints = waypoints;
		}
		scheduleRoute(vehicleId, l);
	}

	private void scheduleRoute(long vehicleId, Live l) {
		routing.execute(() -> {
			try {
				buildRoute(vehicleId, l);
			} catch (RuntimeException e) { // 실행기를 멈추지 않게
				log.warn("route build failed", e);
			}
		});
	}

	/** 동승자가 붙었을 때(4-1 auth.ok 직후) 보낼 메시지. type → data, 없으면 빈 맵 */
	public Map<String, Object> select_snapshot(long vehicleId) {
		Live l = live.get(vehicleId);
		Map<String, Object> out = new LinkedHashMap<>();
		if (l == null || mapper.select_activeTrip(vehicleId) == null) {
			return out;
		}
		synchronized (l) {
			if (l.route != null) {
				out.put("route.update", l.route);
			}
			if (l.car != null) {
				out.put("trip.state", state(l));
			}
		}
		return out;
	}

	/**
	 * 차가 마지막으로 보낸 경로 구성 (6-1 action·WAYPOINT_FULL 판단).
	 * known=false면 이 서버가 아직 car.route를 못 받은 것 (서버 재시작 직후 등). 부르는 쪽이 DB 목적지로 보완한다
	 */
	public record RouteShape(boolean known, boolean hasDestination, int waypoints) {
	}

	public RouteShape select_routeShape(long vehicleId) {
		Live l = live.get(vehicleId);
		if (l == null) {
			return new RouteShape(false, false, 0);
		}
		synchronized (l) {
			return new RouteShape(l.routeKey != null, l.dest != null, l.waypoints == null ? 0 : l.waypoints.size());
		}
	}

	/** 세션이 끝나면 (5-2, 만료). DB 칸은 update_tripClosed가 지운다 */
	public void clear(long vehicleId) {
		live.remove(vehicleId);
	}

	private void buildRoute(long vehicleId, Live l) {
		List<Object> key;
		Stop dest;
		List<Stop> waypoints;
		Map<String, Object> car;
		synchronized (l) {
			key = l.routeKey;
			dest = l.dest;
			waypoints = l.waypoints;
			car = l.car;
			l.needsOrigin = dest != null && car == null;
		}
		Map<String, Object> route = new LinkedHashMap<>();
		if (dest == null) {
			route.put("source", "KAKAO");
			route.put("path", List.of());
			route.put("destination", null);
			route.put("waypoints", List.of());
			route.put("distanceM", null);
			route.put("durationS", null);
		} else {
			List<Stop> known = waypoints.stream().filter(w -> w.lat() != null).toList();
			KakaoDirections.Point origin = car == null ? null
					: new KakaoDirections.Point((double) car.get("lat"), (double) car.get("lng"));
			KakaoDirections.Route found = origin == null ? null
					: directions.route(origin, (Integer) car.get("heading"),
							known.stream().map(w -> new KakaoDirections.Point(w.lat(), w.lng())).toList(),
							new KakaoDirections.Point(dest.lat(), dest.lng()));
			if (found != null) {
				route.put("source", "KAKAO");
				route.put("path", found.path());
				route.put("distanceM", found.distanceM());
				route.put("durationS", found.durationS());
			} else {
				// way-on.md 3.4 7번: 길찾기 실패면 차 → 경유지 → 목적지 직선. 폰은 점선으로 그린다
				List<double[]> path = new ArrayList<>();
				if (origin != null) {
					path.add(new double[] { origin.lng(), origin.lat() });
				}
				known.forEach(w -> path.add(new double[] { w.lng(), w.lat() }));
				path.add(new double[] { dest.lng(), dest.lat() });
				route.put("source", "APPROX");
				route.put("path", path);
				route.put("distanceM", dest.remainDistM());
				route.put("durationS", dest.remainTimeS());
			}
			route.put("destination", place(dest));
			route.put("waypoints", known.stream().map(TripLiveService::place).toList());
		}
		synchronized (l) {
			if (!key.equals(l.routeKey) || live.get(vehicleId) != l) {
				return; // 그사이 경로가 또 바뀌었거나 세션이 끝남
			}
			l.routeVersion++;
			Map<String, Object> versioned = new LinkedHashMap<>();
			versioned.put("routeVersion", l.routeVersion);
			versioned.putAll(route);
			l.route = versioned;
		}
		sessions.sendToPassengers(vehicleId, "route.update", l.route);
		// 차도 같은 경로선을 받아 시연 주행에 쓴다 (car-app-design.md 9장)
		sessions.sendToCar(vehicleId, "route.update", l.route);
	}

	private void sendState(long vehicleId, Live l) {
		Map<String, Object> state;
		synchronized (l) {
			if (l.car == null) {
				return; // 폰은 위치가 있어야 지도에 그린다
			}
			state = state(l);
		}
		sessions.sendToPassengers(vehicleId, "trip.state", state);
	}

	/** 7장 trip.state */
	private static Map<String, Object> state(Live l) {
		Map<String, Object> state = new LinkedHashMap<>();
		state.put("car", l.car);
		state.put("gear", l.gear);
		state.put("energy", l.energy);
		return state;
	}

	private Live live(long vehicleId) {
		return live.computeIfAbsent(vehicleId, id -> new Live());
	}

	/** 5-4의 목적지·경유지 하나. 목적지는 좌표 필수, 경유지는 좌표를 모르면 null (운전자가 내비에서 직접 넣음) */
	private record Stop(String name, Double lat, Double lng, Integer remainDistM, Integer remainTimeS) {
	}

	private static Stop stop(Object o, boolean coordRequired) {
		if (!(o instanceof Map<?, ?> m)) {
			throw badRequest();
		}
		String name = m.get("name") instanceof String s && s.length() <= 100 ? s : null;
		boolean hasCoord = m.get("lat") != null || m.get("lng") != null;
		if (name == null || (coordRequired && !hasCoord)) {
			throw badRequest();
		}
		Double lat = hasCoord ? coord(m.get("lat"), 90) : null;
		Double lng = hasCoord ? coord(m.get("lng"), 180) : null;
		Integer dist = m.get("remainDistM") == null ? null : intIn(m.get("remainDistM"), 0, Integer.MAX_VALUE);
		Integer time = m.get("remainTimeS") == null ? null : intIn(m.get("remainTimeS"), 0, Integer.MAX_VALUE);
		return new Stop(name, lat, lng, dist, time);
	}

	private static Map<String, Object> place(Stop s) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", s.name());
		m.put("lat", s.lat());
		m.put("lng", s.lng());
		return m;
	}

	private static double coord(Object v, double limit) {
		if (!(v instanceof Number n) || !Double.isFinite(n.doubleValue()) || Math.abs(n.doubleValue()) > limit) {
			throw badRequest();
		}
		return n.doubleValue();
	}

	private static int intIn(Object v, int min, int max) {
		if (!(v instanceof Number n) || n.doubleValue() < min || n.doubleValue() > max) {
			throw badRequest();
		}
		return n.intValue();
	}

	private static ApiException badRequest() {
		return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "요청 형식이 맞지 않아요");
	}
}
