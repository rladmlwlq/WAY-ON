package com.wayon.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;

import com.jayway.jsonpath.JsonPath;

/** 카카오는 가짜로, 토큰·차 위치·휴게소 매장 연결은 실제 DB로. 로컬 MySQL 필요 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SearchApiTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@MockitoBean
	private SearchClients clients;

	private String car;
	private String phone;

	@BeforeEach
	void setUp() throws Exception {
		when(clients.hasKakao()).thenReturn(true);
		car = "Bearer " + read(mvc.perform(post("/api/vehicles")).andReturn().getResponse().getContentAsString(),
				"$.carToken");
		String code = read(mvc.perform(post("/api/vehicles/me/connect-codes").header(HttpHeaders.AUTHORIZATION, car)
				.contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"QR\"}"))
				.andReturn().getResponse().getContentAsString(), "$.code");
		phone = "Bearer " + read(mvc.perform(post("/api/connect").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"" + code + "\",\"nickname\":\"민지\",\"locationConsent\":true}"))
				.andReturn().getResponse().getContentAsString(), "$.passengerToken");
	}

	private static String read(String json, String path) {
		return JsonPath.read(json, path);
	}

	private static SearchClients.KakaoPlace place(String id, String name) {
		return new SearchClients.KakaoPlace(id, name, "음식점 > 한식 > 국밥", "FD6", "031-611-5793", "경기 안성시 원곡면 1",
				"", 37.07, 127.13, 71, "http://place.map.kakao.com/" + id);
	}

	@Test
	void 차_위치_근처를_찾고_카카오맵_링크는_https_분류는_마지막_칸() throws Exception {
		long vehicleId = jdbc.queryForObject("SELECT MAX(id) FROM vehicle", Long.class);
		jdbc.update("INSERT INTO trip (public_id, vehicle_id, status, created_at, last_activity_at, last_lat, last_lng)"
				+ " VALUES (REPLACE(UUID(), '-', ''), ?, 'ACTIVE', NOW(3), NOW(3), 37.4127, 127.0947)", vehicleId);
		when(clients.kakaoKeyword(eq("국밥"), any(), any())).thenReturn(List.of(place("1", "곰탕집")));

		mvc.perform(get("/api/search/places").param("query", " 국밥 ").header(HttpHeaders.AUTHORIZATION, phone))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.places[0].id").value("kk:1"))
				.andExpect(jsonPath("$.places[0].category").value("국밥"))
				.andExpect(jsonPath("$.places[0].address").value("경기 안성시 원곡면 1"))
				.andExpect(jsonPath("$.places[0].distM").value(71))
				.andExpect(jsonPath("$.places[0].url").value("https://place.map.kakao.com/1"))
				.andExpect(jsonPath("$.places[0].restAreaId").doesNotExist());
		verify(clients).kakaoKeyword("국밥", 37.4127, 127.0947);
	}

	@Test
	void 휴게소_안_매장이면_restAreaId를_붙인다() throws Exception {
		List<String> ids = jdbc.queryForList("SELECT kakao_id FROM rest_area_place LIMIT 1", String.class);
		assumeTrue(!ids.isEmpty(), "rest_area_place 비어 있음 (휴게소 적재 전)");
		String code = jdbc.queryForObject("SELECT std_rest_cd FROM rest_area_place WHERE kakao_id = ?", String.class,
				ids.get(0));
		when(clients.kakaoKeyword(eq("국밥"), any(), any())).thenReturn(List.of(place(ids.get(0), "휴게소 국밥")));

		mvc.perform(get("/api/search/places").param("query", "국밥").header(HttpHeaders.AUTHORIZATION, phone))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.places[0].restAreaId").value(code));
	}

	@Test
	void 토큰_검색어_외부_실패() throws Exception {
		mvc.perform(get("/api/search/places").param("query", "국밥")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/search/places").param("query", "국밥").header(HttpHeaders.AUTHORIZATION, car))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/search/places").param("query", "  ").header(HttpHeaders.AUTHORIZATION, phone))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/search/places").param("query", "가".repeat(51)).header(HttpHeaders.AUTHORIZATION, phone))
				.andExpect(status().isBadRequest());
		when(clients.kakaoKeyword(any(), any(), any())).thenThrow(new ResourceAccessException("timeout"));
		mvc.perform(get("/api/search/places").param("query", "국밥").header(HttpHeaders.AUTHORIZATION, phone))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("EXTERNAL_UNAVAILABLE"));
	}

	@Test
	void 작은_도우미() {
		assertEquals("국밥", SearchService.lastCategory("음식점 > 한식 > 국밥"));
		assertEquals("https://x.kr", SearchService.https("http://x.kr"));
	}
}
