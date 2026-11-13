package com.wayon.search;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 카카오 로컬 키워드 검색 (api.md 5). 키는 서버에만 둔다 (폰에 노출하지 않음).
 * 실패는 RestClientException 그대로 던져 서비스가 503으로 바꾼다
 */
@Component
public class SearchClients {

	/**
	 * 카카오 키워드 검색 결과 1개. 좌표는 x=경도, y=위도. distance는 중심을 줬을 때만.
	 * groupCode는 FD6 음식점, CE7 카페, PK6 주차장, OL7 주유소·충전소 등 (없으면 빈 글자)
	 */
	public record KakaoPlace(String id, String placeName, String categoryName, String groupCode, String phone,
			String addressName, String roadAddressName, double lat, double lng, Integer distance, String placeUrl) {
	}

	private final RestClient http;
	private final String kakaoKey;

	public SearchClients(@Value("${KAKAO_REST_KEY:}") String kakaoKey) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(3));
		factory.setReadTimeout(Duration.ofSeconds(5));
		this.http = RestClient.builder().requestFactory(factory).build();
		this.kakaoKey = kakaoKey;
	}

	public boolean hasKakao() {
		return !kakaoKey.isBlank();
	}

	/** 중심이 있으면 반경 20km 안에서, 없으면 전국에서 정확도순 15개 */
	@SuppressWarnings("unchecked")
	public List<KakaoPlace> kakaoKeyword(String query, Double lat, Double lng) {
		String url = "https://dapi.kakao.com/v2/local/search/keyword.json?query={q}&size=15&sort=accuracy"
				+ (lat == null ? "" : "&x={x}&y={y}&radius=20000");
		Map<String, Object> vars = lat == null ? Map.of("q", query) : Map.of("q", query, "x", lng, "y", lat);
		Map<String, Object> body = http.get().uri(url, vars)
				.header("Authorization", "KakaoAK " + kakaoKey)
				.retrieve()
				.body(new ParameterizedTypeReference<Map<String, Object>>() {
				});
		if (body == null || !(body.get("documents") instanceof List<?> docs)) {
			return List.of();
		}
		return docs.stream().map(o -> (Map<String, Object>) o).map(d -> new KakaoPlace(str(d.get("id")),
				str(d.get("place_name")), str(d.get("category_name")), str(d.get("category_group_code")),
				str(d.get("phone")),
				str(d.get("address_name")), str(d.get("road_address_name")), Double.parseDouble(str(d.get("y"))),
				Double.parseDouble(str(d.get("x"))),
				str(d.get("distance")).isEmpty() ? null : Integer.valueOf(str(d.get("distance"))),
				str(d.get("place_url")))).toList();
	}

	private static String str(Object v) {
		return v == null ? "" : v.toString();
	}
}
