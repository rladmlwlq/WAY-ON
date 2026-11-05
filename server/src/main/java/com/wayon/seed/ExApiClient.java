package com.wayon.seed;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * 한국도로공사 공공데이터 API (api.md 1, 2, 0501~0504).
 * - User-Agent가 없으면 400 Request Blocked
 * - 인증 실패도 HTTP 200 + code "ERROR"로 오므로 code == "SUCCESS"로 판단
 * - 숫자도 문자열로 오므로 값은 모두 String으로 받는다
 */
@Component
public class ExApiClient {

	private static final String BASE = "https://data.ex.co.kr/openapi/";
	private static final int PAGE_SIZE = 99; // 도로공사 최대

	private final RestClient http;
	private final String key;

	public ExApiClient(@Value("${EX_API_KEY:}") String key) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(5));
		factory.setReadTimeout(Duration.ofSeconds(30));
		this.http = RestClient.builder()
				.baseUrl(BASE)
				.requestFactory(factory)
				.defaultHeader("User-Agent", "WayOn/0.1")
				.build();
		this.key = key;
	}

	/** 페이지를 끝까지 넘기며 모든 행을 받는다 */
	public List<Map<String, Object>> fetchAll(String path) {
		requireKey();
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int page = 1;; page++) {
			Map<String, Object> body = get(path, page);
			List<Map<String, Object>> list = list(body);
			rows.addAll(list);
			int pageSize = toInt(body.get("pageSize"));
			if (list.isEmpty() || page >= pageSize) {
				return rows;
			}
		}
	}

	/** 페이지 파라미터가 없는 API (0504 주유소 편의시설): 한 번에 전체가 온다 */
	public List<Map<String, Object>> fetchOnce(String path) {
		requireKey();
		return list(get(path, null));
	}

	private Map<String, Object> get(String path, Integer page) {
		Map<String, Object> body = http.get()
				.uri(b -> withParams(b.path(path), page))
				.retrieve()
				.body(new ParameterizedTypeReference<Map<String, Object>>() {
				});
		if (body == null || !"SUCCESS".equals(body.get("code"))) {
			String msg = body == null ? "빈 응답" : body.get("code") + " " + body.get("message");
			throw new IllegalStateException("도로공사 API 실패 (" + path + "): " + msg);
		}
		return body;
	}

	private java.net.URI withParams(UriBuilder b, Integer page) {
		b.queryParam("key", key).queryParam("type", "json");
		if (page != null) {
			b.queryParam("numOfRows", PAGE_SIZE).queryParam("pageNo", page);
		}
		return b.build();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Map<String, Object> body) {
		Object list = body.get("list");
		return list instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
	}

	private void requireKey() {
		if (key == null || key.isBlank()) {
			throw new IllegalStateException("EX_API_KEY가 없습니다. server/.env에 넣어 주세요 (team-setup.md 4-5)");
		}
	}

	static int toInt(Object v) {
		if (v == null) {
			return 0;
		}
		String s = v.toString().trim();
		return s.isEmpty() ? 0 : Integer.parseInt(s);
	}
}
