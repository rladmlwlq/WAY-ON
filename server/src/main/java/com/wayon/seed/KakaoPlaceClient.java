package com.wayon.seed;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 카카오 로컬 키워드 검색 (적재 때만). 도로공사 1번 API에 없는 휴게소의 좌표를 찾는 데 쓴다.
 * 결과를 그대로 믿지 않고 {@link RestAreaGeocode#pick}으로 걸러서 확실한 것만 쓴다.
 */
@Component
public class KakaoPlaceClient {

	private static final String URL = "https://dapi.kakao.com/v2/local/search/keyword.json";

	/** 검색 결과 1개. 좌표는 x=경도, y=위도 */
	public record Place(String placeName, String categoryName, String addressName, String roadAddressName,
			double lat, double lng) {
	}

	private final RestClient http;
	private final String key;

	public KakaoPlaceClient(@Value("${KAKAO_REST_KEY:}") String key) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(5));
		factory.setReadTimeout(Duration.ofSeconds(10));
		this.http = RestClient.builder().requestFactory(factory).build();
		this.key = key;
	}

	/** 좌표 주변 검색 결과 1개 (휴게소 안 매장 위치용). groupCode는 FD6 음식점, CE7 카페, CS2 편의점 등 */
	public record Nearby(String id, String name, String categoryName, String groupCode, double lat, double lng) {
	}

	/** 카테고리 주변 검색 (반경 m, 최대 15개) */
	public List<Nearby> category(String groupCode, double lat, double lng, int radius) {
		return nearby("https://dapi.kakao.com/v2/local/search/category.json?category_group_code=" + groupCode
				+ "&x={x}&y={y}&radius={r}&sort=distance&size=15", null, lat, lng, radius);
	}

	/** 키워드 주변 검색 (반경 m, 최대 15개) */
	public List<Nearby> keyword(String query, double lat, double lng, int radius) {
		return nearby("https://dapi.kakao.com/v2/local/search/keyword.json?query={q}&x={x}&y={y}&radius={r}&size=15",
				query, lat, lng, radius);
	}

	@SuppressWarnings("unchecked")
	private List<Nearby> nearby(String url, String query, double lat, double lng, int radius) {
		if (!hasKey()) {
			throw new IllegalStateException("KAKAO_REST_KEY가 없습니다. server/.env에 넣어 주세요 (team-setup.md 4-5)");
		}
		Map<String, Object> vars = new java.util.HashMap<>(Map.of("x", lng, "y", lat, "r", radius));
		if (query != null) {
			vars.put("q", query);
		}
		Map<String, Object> body = http.get()
				.uri(url, vars)
				.header("Authorization", "KakaoAK " + key)
				.retrieve()
				.body(new ParameterizedTypeReference<Map<String, Object>>() {
				});
		Object docs = body == null ? null : body.get("documents");
		if (!(docs instanceof List<?> list)) {
			return List.of();
		}
		return list.stream().map(o -> (Map<String, Object>) o).map(d -> new Nearby(str(d.get("id")),
				str(d.get("place_name")), str(d.get("category_name")), str(d.get("category_group_code")),
				Double.parseDouble(str(d.get("y"))), Double.parseDouble(str(d.get("x"))))).toList();
	}

	public boolean hasKey() {
		return key != null && !key.isBlank();
	}

	@SuppressWarnings("unchecked")
	public List<Place> search(String query) {
		if (!hasKey()) {
			throw new IllegalStateException("KAKAO_REST_KEY가 없습니다. server/.env에 넣어 주세요 (team-setup.md 4-5)");
		}
		Map<String, Object> body = http.get()
				.uri(URL + "?query={q}&size=15", query)
				.header("Authorization", "KakaoAK " + key)
				.retrieve()
				.body(new ParameterizedTypeReference<Map<String, Object>>() {
				});
		Object docs = body == null ? null : body.get("documents");
		if (!(docs instanceof List<?> list)) {
			return List.of();
		}
		return list.stream().map(o -> (Map<String, Object>) o).map(d -> new Place(
				str(d.get("place_name")), str(d.get("category_name")), str(d.get("address_name")),
				str(d.get("road_address_name")), Double.parseDouble(str(d.get("y"))),
				Double.parseDouble(str(d.get("x"))))).toList();
	}

	private static String str(Object v) {
		return v == null ? "" : v.toString();
	}
}
