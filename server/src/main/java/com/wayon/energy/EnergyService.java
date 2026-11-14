package com.wayon.energy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.wayon.trip.TripLiveService;
import com.wayon.ws.WsSessions;

/**
 * 에너지 제안 (F6, way-on.md 3.10, app-api 5-7·7장 energy.suggest).
 * 차량 앱이 "주행 가능 거리 < 목적지까지 남은 거리 + 20km"로 판단해 energy.low를 보내면, 경로 앞쪽 몇 지점에서
 * 충전소(카카오 키워드 "전기차충전소") 또는 주유소(오피넷 aroundAll)를 찾아 주행 가능 거리 안의 것만 보낸다.
 * 동승자가 있으면 폰들에게, 없으면 차량 앱에게 (음성 + 정차 중 차 화면 목록).
 * shortcut: 충전소 DB 동기화(D9) 대신 카카오로 그때그때 찾는다 (2026-11-13 결정). 사용 가능 대수는 없음
 */
@Service
public class EnergyService {

	private static final Logger log = LoggerFactory.getLogger(EnergyService.class);
	public static final String OPINET_PREFIX = "opinet:";
	/** 경로 앞쪽을 이 간격으로 나눠 찾는다 */
	static final int STEP_M = 5_000;
	static final int MAX_POINTS = 4;
	static final int KAKAO_RADIUS_M = 3_000;
	static final int OPINET_RADIUS_M = 5_000;
	static final int LIMIT = 15;
	/** 오피넷은 하루 300건이라 같은 지점은 10분 동안 다시 부르지 않는다 (api.md 4) */
	private static final Duration CACHE = Duration.ofMinutes(10);

	private final TripLiveService live;
	private final WsSessions sessions;
	private final RestClient http;
	private final String kakaoKey;
	private final String opinetKey;
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();
	/** 보낸 주유소 (opinet:ID → 장소). 동승자가 고르면 waypoint.propose가 이 값으로 다시 찾는다 */
	private final Map<String, Place> sent = new ConcurrentHashMap<>();

	public EnergyService(TripLiveService live, WsSessions sessions, @Value("${KAKAO_REST_KEY:}") String kakaoKey,
			@Value("${OPINET_KEY:}") String opinetKey) {
		this.live = live;
		this.sessions = sessions;
		this.kakaoKey = kakaoKey;
		this.opinetKey = opinetKey;
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(5));
		factory.setReadTimeout(Duration.ofSeconds(10));
		this.http = RestClient.builder().requestFactory(factory).defaultHeader("User-Agent", "WayOn/0.1").build();
	}

	/** 폰·차에 보내는 장소 1곳 */
	public record Place(String type, String extId, String name, String address, double lat, double lng,
			Map<String, Object> detail) {
	}

	private record Cached(Instant at, List<Place> places) {
	}

	/** 5-7 energy.low */
	public void save_low(long vehicleId, Map<?, ?> data) {
		boolean ev = "EV".equals(data.get("energyType")) || "ELECTRIC".equals(data.get("energyType"));
		int rangeM = data.get("rangeM") instanceof Number n ? n.intValue() : 0;
		Integer remainDistM = data.get("remainDistM") instanceof Number n ? n.intValue() : null;
		TripLiveService.Ahead ahead = live.select_ahead(vehicleId);
		if (ahead == null) {
			log.info("energy.low: 차 위치를 몰라 건너뜀 (vehicle {})", vehicleId);
			return;
		}
		List<Map<String, Object>> places = find(ev, ahead, rangeM);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("energyType", ev ? "EV" : "FUEL");
		out.put("rangeM", rangeM);
		out.put("remainDistM", remainDistM);
		out.put("places", places);
		if (sessions.onlinePassengerIds(vehicleId).isEmpty()) {
			sessions.sendToCar(vehicleId, "energy.suggest", out);
		} else {
			sessions.sendToPassengers(vehicleId, "energy.suggest", out);
		}
		log.info("energy.suggest: {} {}곳 (vehicle {})", ev ? "충전소" : "주유소", places.size(), vehicleId);
	}

	/** 경로 앞쪽 지점마다 찾아 합치고, 차에서 주행 가능 거리 안만 가까운 순으로 */
	List<Map<String, Object>> find(boolean ev, TripLiveService.Ahead ahead, int rangeM) {
		Map<String, Place> byId = new LinkedHashMap<>();
		for (double[] p : points(ahead, rangeM)) {
			for (Place place : ev ? chargers(p[0], p[1]) : gasStations(p[0], p[1])) {
				byId.putIfAbsent(place.extId(), place);
			}
		}
		return byId.values().stream()
				.map(pl -> Map.entry(pl, (int) Math.round(meters(ahead.lat(), ahead.lng(), pl.lat(), pl.lng()))))
				.filter(e -> rangeM <= 0 || e.getValue() <= rangeM)
				.sorted(Comparator.comparingInt(Map.Entry::getValue))
				.limit(LIMIT)
				.map(e -> {
					Place pl = e.getKey();
					if (pl.extId().startsWith(OPINET_PREFIX)) {
						sent.put(pl.extId(), pl);
					}
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("type", pl.type());
					m.put("extId", pl.extId());
					m.put("name", pl.name());
					m.put("address", pl.address());
					m.put("lat", pl.lat());
					m.put("lng", pl.lng());
					m.put("distM", e.getValue());
					m.put("detail", pl.detail());
					return m;
				})
				.toList();
	}

	/**
	 * 찾을 지점 (위도, 경도): 차 위치, 그리고 경로선에서 차와 가장 가까운 점부터 앞으로 STEP_M마다.
	 * 주행 가능 거리를 넘는 곳은 넣지 않는다. 경로선이 없으면 차 위치만
	 */
	static List<double[]> points(TripLiveService.Ahead ahead, int rangeM) {
		List<double[]> out = new ArrayList<>();
		out.add(new double[] { ahead.lat(), ahead.lng() });
		List<double[]> path = ahead.path();
		if (path.size() < 2) {
			return out;
		}
		int start = 0;
		double best = Double.MAX_VALUE;
		for (int i = 0; i < path.size(); i++) {
			double d = meters(ahead.lat(), ahead.lng(), path.get(i)[1], path.get(i)[0]);
			if (d < best) {
				best = d;
				start = i;
			}
		}
		double limit = rangeM > 0 ? rangeM : Double.MAX_VALUE;
		double along = 0;
		double next = STEP_M;
		for (int i = start + 1; i < path.size() && out.size() < MAX_POINTS && along < limit; i++) {
			double[] a = path.get(i - 1);
			double[] b = path.get(i);
			along += meters(a[1], a[0], b[1], b[0]);
			if (along >= next && along < limit) {
				out.add(new double[] { b[1], b[0] });
				next += STEP_M;
			}
		}
		return out;
	}

	/** 카카오 키워드 "전기차충전소", 지점 반경 3km, 가까운 순 15곳 */
	@SuppressWarnings("unchecked")
	private List<Place> chargers(double lat, double lng) {
		if (kakaoKey.isBlank()) {
			return List.of();
		}
		String key = String.format("ev:%.2f,%.2f", lat, lng);
		Cached c = cache.get(key);
		if (c != null && c.at().plus(CACHE).isAfter(Instant.now())) {
			return c.places();
		}
		try {
			Map<String, Object> body = http.get()
					.uri("https://dapi.kakao.com/v2/local/search/keyword.json?query={q}&x={x}&y={y}&radius={r}"
							+ "&sort=distance&size=15", "전기차충전소", lng, lat, KAKAO_RADIUS_M)
					.header("Authorization", "KakaoAK " + kakaoKey)
					.retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {
					});
			List<Place> places = new ArrayList<>();
			if (body != null && body.get("documents") instanceof List<?> docs) {
				for (Object o : docs) {
					Map<String, Object> d = (Map<String, Object>) o;
					String category = str(d.get("category_name"));
					if (!category.contains("충전")) {
						continue; // 키워드에 걸린 다른 장소(주차장 등) 제외
					}
					String road = str(d.get("road_address_name"));
					Map<String, Object> detail = new HashMap<>();
					detail.put("category", lastCategory(category));
					detail.put("phone", blank(str(d.get("phone"))));
					places.add(new Place("EV_CHARGER", "kk:" + str(d.get("id")), str(d.get("place_name")),
							road.isBlank() ? blank(str(d.get("address_name"))) : road,
							Double.parseDouble(str(d.get("y"))), Double.parseDouble(str(d.get("x"))), detail));
				}
			}
			cache.put(key, new Cached(Instant.now(), places));
			return places;
		} catch (RestClientException | NumberFormatException e) {
			log.warn("충전소 검색 실패: {}", e.getMessage());
			return List.of();
		}
	}

	/** 오피넷 aroundAll, 지점 반경 5km, 보통휘발유 가격, 거리순 (api.md 4) */
	@SuppressWarnings("unchecked")
	private List<Place> gasStations(double lat, double lng) {
		if (opinetKey.isBlank()) {
			return List.of();
		}
		String key = String.format("op:%.2f,%.2f", lat, lng);
		Cached c = cache.get(key);
		if (c != null && c.at().plus(CACHE).isAfter(Instant.now())) {
			return c.places();
		}
		double[] xy = Katec.fromWgs84(lat, lng);
		try {
			Map<String, Object> body = http.get()
					.uri("https://www.opinet.co.kr/api/aroundAll.do?code={k}&x={x}&y={y}&radius={r}&prodcd=B027"
							+ "&sort=2&out=json", opinetKey, xy[0], xy[1], OPINET_RADIUS_M)
					.retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {
					});
			List<Place> places = new ArrayList<>();
			Object result = body == null ? null : body.get("RESULT");
			if (result instanceof Map<?, ?> r && r.get("OIL") instanceof List<?> oil) {
				for (Object o : oil) {
					Map<String, Object> s = (Map<String, Object>) o;
					double[] ll = Katec.toWgs84(num(s.get("GIS_X_COOR")), num(s.get("GIS_Y_COOR")));
					Map<String, Object> detail = new HashMap<>();
					detail.put("brand", str(s.get("POLL_DIV_CD")));
					detail.put("gasoline", (int) Math.round(num(s.get("PRICE"))));
					places.add(new Place("GAS_STATION", OPINET_PREFIX + str(s.get("UNI_ID")), str(s.get("OS_NM")), null,
							round6(ll[0]), round6(ll[1]), detail));
				}
			}
			cache.put(key, new Cached(Instant.now(), places));
			return places;
		} catch (RestClientException | NumberFormatException e) {
			log.warn("주유소 검색 실패: {}", e.getMessage());
			return List.of();
		}
	}

	/** waypoint.propose의 opinet:ID → 보냈던 주유소. 없으면 empty (서버 재시작 등) */
	public Optional<Place> sentPlace(String extId) {
		return Optional.ofNullable(sent.get(extId));
	}

	static double meters(double lat1, double lng1, double lat2, double lng2) {
		double r = 6_371_000;
		double p1 = Math.toRadians(lat1);
		double p2 = Math.toRadians(lat2);
		double dp = Math.toRadians(lat2 - lat1);
		double dl = Math.toRadians(lng2 - lng1);
		double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
		return 2 * r * Math.asin(Math.sqrt(a));
	}

	private static String lastCategory(String category) {
		String[] parts = category.split(">");
		return parts[parts.length - 1].strip();
	}

	private static String str(Object v) {
		return v == null ? "" : v.toString().strip();
	}

	private static String blank(String s) {
		return s == null || s.isBlank() ? null : s;
	}

	private static double num(Object v) {
		return v instanceof Number n ? n.doubleValue() : Double.parseDouble(str(v));
	}

	private static double round6(double v) {
		return Math.round(v * 1_000_000d) / 1_000_000d;
	}
}
