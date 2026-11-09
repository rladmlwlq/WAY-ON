package com.wayon.vehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

/** 실제 SQL까지 확인한다. 로컬 MySQL 필요, 데이터는 테스트마다 되돌린다 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class VehicleApiTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	private String text(ResultActions result, String path) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
	}

	private String newCar() throws Exception {
		return "Bearer " + text(mvc.perform(post("/api/vehicles")).andExpect(status().isCreated()), "$.carToken");
	}

	private String newCode(String car) throws Exception {
		ResultActions result = mvc.perform(post("/api/vehicles/me/connect-codes")
				.header(HttpHeaders.AUTHORIZATION, car)
				.contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"QR\"}"))
				.andExpect(status().isCreated());
		String code = text(result, "$.code");
		assertTrue(text(result, "$.url").endsWith("/join#" + code));
		return code;
	}

	private ResultActions connect(String code, String nickname) throws Exception {
		return mvc.perform(post("/api/connect").contentType(MediaType.APPLICATION_JSON).content(
				"{\"code\":\"%s\",\"nickname\":\"%s\",\"locationConsent\":true}".formatted(code, nickname)));
	}

	private String newPassenger(String car, String nickname) throws Exception {
		return "Bearer " + text(connect(newCode(car), nickname).andExpect(status().isCreated()), "$.passengerToken");
	}

	@Test
	void QR로_연결하면_목록과_내_정보에_보인다() throws Exception {
		String car = newCar();
		String phone = newPassenger(car, " 민지 ");

		mvc.perform(get("/api/passengers/me").header(HttpHeaders.AUTHORIZATION, phone))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nickname").value("민지"))
				.andExpect(jsonPath("$.pushEnabled").value(false));
		mvc.perform(get("/api/vehicles/me/passengers").header(HttpHeaders.AUTHORIZATION, car))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.passengers[0].nickname").value("민지"))
				.andExpect(jsonPath("$.passengers[0].online").value(false));
	}

	@Test
	void 토큰은_해시만_저장한다() throws Exception {
		String car = newCar();
		String phone = newPassenger(car, "민지");
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM vehicle WHERE car_token_hash = ?", Integer.class,
				car.substring("Bearer ".length())));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM passenger WHERE reconnect_token_hash = ?",
				Integer.class, phone.substring("Bearer ".length())));
	}

	@Test
	void QR_하나로_4명까지_연결하고_새로_발급하면_이전_코드는_폐기된다() throws Exception {
		String car = newCar();
		String code = newCode(car);
		for (String nickname : new String[] {"민지", "준호", "서연", "도윤"}) {
			connect(code, nickname).andExpect(status().isCreated());
		}
		connect(code, "하준").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PASSENGER_LIMIT"));

		String car2 = newCar();
		String old = newCode(car2);
		newCode(car2);
		connect(old, "준호").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CODE_INVALID"));
	}

	@Test
	void 닉네임이_겹치면_거절하고_코드는_살아_있다() throws Exception {
		String car = newCar();
		newPassenger(car, "민지");
		String code = newCode(car);
		connect(code, "민지").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NICKNAME_TAKEN"));
		connect(code, "01234567890").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("NICKNAME_INVALID"));
		connect(code, "민지2").andExpect(status().isCreated());
	}

	@Test
	void 만료된_코드는_410() throws Exception {
		String code = newCode(newCar());
		jdbc.update("UPDATE connect_code SET expires_at = '2020-01-01'");
		connect(code, "민지").andExpect(status().isGone()).andExpect(jsonPath("$.code").value("CODE_EXPIRED"));
	}

	@Test
	void 위치_동의가_없으면_400() throws Exception {
		String code = newCode(newCar());
		mvc.perform(post("/api/connect").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"%s\",\"nickname\":\"민지\",\"locationConsent\":false}".formatted(code)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
	}

	@Test
	void 동승자는_4명까지() throws Exception {
		String car = newCar();
		String lastCode = null;
		for (String name : new String[] { "a", "b", "c" }) {
			newPassenger(car, name);
		}
		lastCode = newCode(car);
		connect(lastCode, "d").andExpect(status().isCreated());
		mvc.perform(post("/api/vehicles/me/connect-codes").header(HttpHeaders.AUTHORIZATION, car)
				.contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"QR\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PASSENGER_LIMIT"));
	}

	@Test
	void 토큰_역할이_다르거나_없으면_거절한다() throws Exception {
		String car = newCar();
		String phone = newPassenger(car, "민지");
		mvc.perform(get("/api/vehicles/me/passengers").header(HttpHeaders.AUTHORIZATION, phone))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/passengers/me").header(HttpHeaders.AUTHORIZATION, car))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/passengers/me")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/vehicles/me/passengers").header(HttpHeaders.AUTHORIZATION, "Bearer c_없는토큰"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void 해제하면_토큰이_막히고_닉네임을_다시_쓸_수_있다() throws Exception {
		String car = newCar();
		String phone = newPassenger(car, "민지");
		long id = jdbc.queryForObject("SELECT MAX(id) FROM passenger", Long.class);

		mvc.perform(delete("/api/vehicles/me/passengers/" + id).header(HttpHeaders.AUTHORIZATION, newCar()))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/passengers/me").header(HttpHeaders.AUTHORIZATION, phone)).andExpect(status().isOk());

		mvc.perform(delete("/api/vehicles/me/passengers/" + id).header(HttpHeaders.AUTHORIZATION, car))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/passengers/me").header(HttpHeaders.AUTHORIZATION, phone))
				.andExpect(status().isUnauthorized());
		newPassenger(car, "민지");
	}

	@Test
	void 푸시_구독을_저장_덮어쓰기_삭제한다() throws Exception {
		String phone = newPassenger(newCar(), "민지");
		String body = "{\"endpoint\":\"https://push.example/%s\",\"keys\":{\"p256dh\":\"k\",\"auth\":\"a\"}}";

		mvc.perform(put("/api/passengers/me/push-subscription").header(HttpHeaders.AUTHORIZATION, phone)
				.contentType(MediaType.APPLICATION_JSON).content(body.formatted("1")))
				.andExpect(status().isNoContent());
		mvc.perform(put("/api/passengers/me/push-subscription").header(HttpHeaders.AUTHORIZATION, phone)
				.contentType(MediaType.APPLICATION_JSON).content(body.formatted("2")))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/passengers/me").header(HttpHeaders.AUTHORIZATION, phone))
				.andExpect(jsonPath("$.pushEnabled").value(true));

		mvc.perform(delete("/api/passengers/me/push-subscription").header(HttpHeaders.AUTHORIZATION, phone))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/passengers/me").header(HttpHeaders.AUTHORIZATION, phone))
				.andExpect(jsonPath("$.pushEnabled").value(false));

		mvc.perform(put("/api/passengers/me/push-subscription").header(HttpHeaders.AUTHORIZATION, phone)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"endpoint\":\"http://inner/\",\"keys\":{\"p256dh\":\"k\",\"auth\":\"a\"}}"))
				.andExpect(status().isBadRequest());
		mvc.perform(put("/api/passengers/me/push-subscription").header(HttpHeaders.AUTHORIZATION, phone)
				.contentType(MediaType.APPLICATION_JSON).content("{깨진"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
	}

	@Test
	void 지난_대화는_최신이_마지막이고_30일_지난_것은_없다() throws Exception {
		String car = newCar();
		String phone = newPassenger(car, "민지");
		long passengerId = jdbc.queryForObject("SELECT MAX(id) FROM passenger", Long.class);
		long vehicleId = jdbc.queryForObject("SELECT vehicle_id FROM passenger WHERE id = ?", Long.class, passengerId);
		jdbc.update("INSERT INTO trip (public_id, vehicle_id, status, created_at, last_activity_at) "
				+ "VALUES (?, ?, 'ACTIVE', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))", "t" + vehicleId, vehicleId);
		long tripId = jdbc.queryForObject("SELECT MAX(id) FROM trip", Long.class);
		jdbc.update("INSERT INTO departure_call (vehicle_id, trip_id, status, called_at) "
				+ "VALUES (?, ?, 'OPEN', UTC_TIMESTAMP(3))", vehicleId, tripId);
		long callId = jdbc.queryForObject("SELECT MAX(id) FROM departure_call", Long.class);
		String insert = "INSERT INTO departure_message (call_id, passenger_id, kind, quick_code, body, sent_at) "
				+ "VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(3) - INTERVAL ? DAY)";
		jdbc.update(insert, callId, passengerId, "QUICK", "TOILET", null, 31);
		jdbc.update(insert, callId, passengerId, "QUICK", "TOILET", null, 0);
		jdbc.update(insert, callId, null, "TEXT", null, "주차장 B동", 0);
		jdbc.update(insert, callId, passengerId, "TEXT", null, "가요", 0);

		String page = mvc.perform(get("/api/vehicles/me/departure-messages?size=2")
				.header(HttpHeaders.AUTHORIZATION, car))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.hasMore").value(true))
				.andExpect(jsonPath("$.messages.length()").value(2))
				.andExpect(jsonPath("$.messages[0].sender.nickname").value("운전자"))
				.andExpect(jsonPath("$.messages[1].body").value("가요"))
				.andExpect(jsonPath("$.messages[1].sender.nickname").value("민지"))
				.andReturn().getResponse().getContentAsString();

		Number oldest = JsonPath.read(page, "$.messages[0].messageId");
		String rest = mvc.perform(get("/api/vehicles/me/departure-messages?before=" + oldest)
				.header(HttpHeaders.AUTHORIZATION, phone))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.messages.length()").value(1))
				.andExpect(jsonPath("$.messages[0].quickCode").value("TOILET"))
				.andReturn().getResponse().getContentAsString();
		assertFalse((Boolean) JsonPath.read(rest, "$.hasMore"));

		mvc.perform(delete("/api/vehicles/me/passengers/" + passengerId).header(HttpHeaders.AUTHORIZATION, car));
		mvc.perform(get("/api/vehicles/me/departure-messages?size=1").header(HttpHeaders.AUTHORIZATION, car))
				.andExpect(jsonPath("$.messages[0].sender.nickname").value("(나간 동승자)"));
	}

	@Test
	void 폰_웹_주소만_CORS를_허용한다() throws Exception {
		mvc.perform(options("/api/passengers/me").header(HttpHeaders.ORIGIN, "http://localhost:5173")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
		mvc.perform(options("/api/passengers/me").header(HttpHeaders.ORIGIN, "https://evil.example")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
				.andExpect(status().isForbidden());
	}
}
