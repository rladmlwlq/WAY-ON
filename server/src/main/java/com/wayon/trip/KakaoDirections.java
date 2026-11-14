package com.wayon.trip;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.wayon.seed.RestAreaPlaces;

/**
 * 카카오모빌리티 길찾기 (api.md 11). Pleos가 경로선을 주지 않아 같은 출발지·경유지·목적지로 받는다.
 * 실패하면 null (부르는 쪽이 직선 근사 APPROX로 바꾼다)
 */
@Component
public class KakaoDirections {

	private static final Logger log = LoggerFactory.getLogger(KakaoDirections.class);
	private static final String URL = "https://apis-navi.kakaomobility.com/v1/directions";
	private static final int MAX_WAYPOINTS = 5;
	/** 경로선 점 간격 (app-api.md 7-1: 약 20m로 줄여서 보냄) */
	static final double STEP_M = 20;

	public record Point(double lat, double lng) {
	}

	/** path는 [lng, lat] 순서 (7-1, 카카오 vertexes와 같음) */
	public record Route(List<double[]> path, int distanceM, int durationS) {
	}

	private final RestClient http;
	private final String key;

	public KakaoDirections(@Value("${KAKAO_REST_KEY:}") String key) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(5));
		factory.setReadTimeout(Duration.ofSeconds(10));
		this.http = RestClient.builder().requestFactory(factory).build();
		this.key = key;
	}

	/** heading이 있으면 진행 방향을 넣는다. 경유지는 앞에서 5개까지 (카카오 한도) */
	@SuppressWarnings("unchecked")
	public Route route(Point origin, Integer heading, List<Point> waypoints, Point destination) {
		if (key == null || key.isBlank()) {
			log.warn("KAKAO_REST_KEY 없음: 경로선을 직선으로 대신함");
			return null;
		}
		StringBuilder uri = new StringBuilder(URL).append("?origin=").append(lngLat(origin));
		if (heading != null) {
			uri.append(",angle=").append(heading);
		}
		uri.append("&destination=").append(lngLat(destination));
		List<Point> wps = waypoints.subList(0, Math.min(MAX_WAYPOINTS, waypoints.size()));
		if (!wps.isEmpty()) {
			uri.append("&waypoints=").append(String.join("%7C", wps.stream().map(KakaoDirections::lngLat).toList()));
		}
		try {
			Map<String, Object> body = http.get()
					.uri(java.net.URI.create(uri.toString()))
					.header("Authorization", "KakaoAK " + key)
					.retrieve()
					.body(new ParameterizedTypeReference<Map<String, Object>>() {
					});
			List<Map<String, Object>> routes = body == null ? null : (List<Map<String, Object>>) body.get("routes");
			if (routes == null || routes.isEmpty()) {
				return null;
			}
			Map<String, Object> r = routes.get(0);
			if (!(r.get("result_code") instanceof Number code) || code.intValue() != 0) {
				log.info("길찾기 실패: {} {}", r.get("result_code"), r.get("result_msg"));
				return null;
			}
			Map<String, Object> summary = (Map<String, Object>) r.get("summary");
			List<double[]> points = new ArrayList<>();
			for (Map<String, Object> section : (List<Map<String, Object>>) r.get("sections")) {
				for (Map<String, Object> road : (List<Map<String, Object>>) section.get("roads")) {
					List<Number> v = (List<Number>) road.get("vertexes");
					for (int i = 0; i + 1 < v.size(); i += 2) {
						points.add(new double[] { v.get(i).doubleValue(), v.get(i + 1).doubleValue() });
					}
				}
			}
			return new Route(thin(points), ((Number) summary.get("distance")).intValue(),
					((Number) summary.get("duration")).intValue());
		} catch (RestClientException | ClassCastException | NullPointerException e) {
			log.warn("길찾기 호출 실패: {}", e.getMessage());
			return null;
		}
	}

	/** 앞 점에서 STEP_M 안쪽인 점은 버린다. 처음과 끝 점은 남긴다 */
	static List<double[]> thin(List<double[]> points) {
		List<double[]> out = new ArrayList<>();
		for (int i = 0; i < points.size(); i++) {
			double[] p = points.get(i);
			boolean last = i == points.size() - 1;
			if (out.isEmpty() || last || metersBetween(out.get(out.size() - 1), p) >= STEP_M) {
				out.add(p);
			}
		}
		return out;
	}

	private static double metersBetween(double[] a, double[] b) {
		return RestAreaPlaces.meters(a[1], a[0], b[1], b[0]);
	}

	private static String lngLat(Point p) {
		return p.lng() + "," + p.lat();
	}
}
