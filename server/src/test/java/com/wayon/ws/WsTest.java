package com.wayon.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.wayon.search.SearchClients;
import com.wayon.trip.KakaoDirections;
import com.wayon.trip.TripService;
import com.wayon.vehicle.VehicleDto;
import com.wayon.vehicle.VehicleService;

import tools.jackson.databind.json.JsonMapper;

/** 실제 포트로 /ws에 붙는다. 서버 스레드가 커밋하므로 만든 데이터는 끝날 때 지운다. 로컬 MySQL 필요 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WsTest {

	@LocalServerPort
	private int port;

	@Autowired
	private VehicleService vehicles;

	@Autowired
	private TripService trips;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	/** 카카오 길찾기는 네트워크라 가짜로. 기본은 null(실패 → APPROX) */
	@MockitoBean
	private KakaoDirections directions;

	/** 경유지 장소 다시 찾기(카카오 검색)도 가짜로 */
	@MockitoBean
	private SearchClients search;

	private final List<Long> vehicleIds = new ArrayList<>();
	private final List<Client> clients = new ArrayList<>();

	/** 받은 메시지와 닫힌 코드를 모은다 */
	private class Client extends TextWebSocketHandler {
		final BlockingQueue<Map<?, ?>> received = new LinkedBlockingQueue<>();
		final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
		WebSocketSession session;

		@Override
		protected void handleTextMessage(WebSocketSession session, TextMessage message) {
			received.add(json.readValue(message.getPayload(), Map.class));
		}

		@Override
		public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
			closed.complete(status);
		}

		void send(String text) throws Exception {
			session.sendMessage(new TextMessage(text));
		}

		/** 다음 메시지 type이 맞는지 보고 data를 돌려준다 */
		Map<?, ?> next(String type) throws Exception {
			Map<?, ?> m = received.poll(5, TimeUnit.SECONDS);
			assertNotNull(m, "no message, wanted " + type);
			assertEquals(type, m.get("type"), m.toString());
			return (Map<?, ?>) m.get("data");
		}

		int closeCode() throws Exception {
			return closed.get(5, TimeUnit.SECONDS).getCode();
		}
	}

	private Client open() throws Exception {
		Client client = new Client();
		client.session = new StandardWebSocketClient().execute(client, "ws://localhost:" + port + "/ws")
				.get(5, TimeUnit.SECONDS);
		clients.add(client);
		return client;
	}

	private Client auth(String token) throws Exception {
		Client client = open();
		client.send("{\"type\":\"auth\",\"data\":{\"token\":\"" + token + "\"}}");
		return client;
	}

	private String newCar() {
		String token = vehicles.save_vehicle().carToken();
		vehicleIds.add(jdbc.queryForObject("SELECT MAX(id) FROM vehicle", Long.class));
		return token;
	}

	private VehicleDto.Connected newPassenger(String car, String nickname) {
		String code = vehicles.save_connectCode("Bearer " + car, new VehicleDto.ConnectCodeRequest("QR")).code();
		return vehicles.save_connect(new VehicleDto.ConnectRequest(code, nickname, true));
	}

	@AfterEach
	void cleanUp() throws Exception {
		for (Client client : clients) {
			if (client.session.isOpen()) {
				client.session.close();
			}
		}
		for (long id : vehicleIds) {
			jdbc.update("DELETE FROM waypoint_request WHERE trip_id IN (SELECT id FROM trip WHERE vehicle_id = ?)", id);
			jdbc.update("DELETE FROM trip_passenger WHERE trip_id IN (SELECT id FROM trip WHERE vehicle_id = ?)", id);
			jdbc.update("DELETE FROM trip WHERE vehicle_id = ?", id);
			jdbc.update("DELETE FROM passenger WHERE vehicle_id = ?", id);
			jdbc.update("DELETE FROM connect_code WHERE vehicle_id = ?", id);
			jdbc.update("DELETE FROM vehicle WHERE id = ?", id);
		}
	}

	@Test
	void 차량이_세션을_열면_동승자가_붙고_목록에_online으로_보인다() throws Exception {
		String car = newCar();
		Client carWs = auth(car);
		Map<?, ?> ok = carWs.next("auth.ok");
		assertEquals("CAR", ok.get("role"));
		assertNull(ok.get("trip"));

		carWs.send("{\"type\":\"trip.start\",\"reqId\":\"1\",\"data\":{\"locationConsent\":true}}");
		String tripId = (String) carWs.next("ack").get("tripId");

		VehicleDto.Connected p = newPassenger(car, "민지");
		Client phone = auth(p.passengerToken());
		ok = phone.next("auth.ok");
		assertEquals("PASSENGER", ok.get("role"));
		assertEquals(tripId, ((Map<?, ?>) ok.get("trip")).get("tripId"));
		assertEquals("민지", ((Map<?, ?>) ok.get("me")).get("nickname"));

		Map<?, ?> update = carWs.next("passengers.update");
		assertEquals(true, ((Map<?, ?>) ((List<?>) update.get("passengers")).get(0)).get("online"));
		assertEquals(true, vehicles.select_passengers("Bearer " + car).passengers().get(0).online());
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM trip_passenger WHERE passenger_id = ?",
				Integer.class, p.passengerId()));

		phone.send("{\"type\":\"ping\"}");
		phone.next("pong");

		carWs.send("{\"type\":\"trip.end\",\"data\":{}}");
		assertEquals("ENDED", phone.next("trip.ended").get("reason"));

		phone.session.close();
		update = carWs.next("passengers.update");
		assertEquals(false, ((Map<?, ?>) ((List<?>) update.get("passengers")).get(0)).get("online"));
	}

	@Test
	void 틀린_토큰이나_auth가_아닌_첫_메시지는_4401로_닫는다() throws Exception {
		assertEquals(4401, auth("c_wrong").closeCode());
		Client client = open();
		client.send("{\"type\":\"ping\"}");
		assertEquals(4401, client.closeCode());
	}

	@Test
	void 같은_동승자가_다시_접속하면_이전_연결을_4409로_닫는다() throws Exception {
		String car = newCar();
		String token = newPassenger(car, "민지").passengerToken();
		Client first = auth(token);
		first.next("auth.ok");
		Client second = auth(token);
		second.next("auth.ok");
		assertEquals(4409, first.closeCode());
		assertEquals(true, vehicles.select_passengers("Bearer " + car).passengers().get(0).online());
	}

	@Test
	void 해제한_동승자는_4403으로_닫히고_차에_목록이_간다() throws Exception {
		String car = newCar();
		Client carWs = auth(car);
		carWs.next("auth.ok");
		VehicleDto.Connected p = newPassenger(car, "민지");
		Client phone = auth(p.passengerToken());
		phone.next("auth.ok");
		carWs.next("passengers.update");

		vehicles.delete_passenger("Bearer " + car, p.passengerId());
		assertEquals(4403, phone.closeCode());
		assertEquals(List.of(), carWs.next("passengers.update").get("passengers"));
	}

	@Test
	void 동승자는_세션을_열_수_없고_동의_없이는_못_연다() throws Exception {
		String car = newCar();
		Client phone = auth(newPassenger(car, "민지").passengerToken());
		phone.next("auth.ok");
		phone.send("{\"type\":\"trip.start\",\"reqId\":\"a\",\"data\":{\"locationConsent\":true}}");
		assertEquals("FORBIDDEN_ROLE", phone.next("error").get("code"));

		Client carWs = auth(car);
		carWs.next("auth.ok");
		carWs.send("{\"type\":\"trip.start\",\"reqId\":\"b\",\"data\":{}}");
		assertEquals("CONSENT_REQUIRED", carWs.next("error").get("code"));
	}

	@Test
	void 차량_앱_메시지가_5분_없으면_세션이_만료되고_동승자에게_알린다() throws Exception {
		String car = newCar();
		Client carWs = auth(car);
		carWs.next("auth.ok");
		carWs.send("{\"type\":\"trip.start\",\"reqId\":\"1\",\"data\":{\"locationConsent\":true}}");
		carWs.next("ack");
		long vehicleId = vehicleIds.get(0);

		jdbc.update("UPDATE trip SET last_activity_at = last_activity_at - INTERVAL 6 MINUTE WHERE vehicle_id = ?",
				vehicleId);
		assertEquals(List.of(vehicleId), trips.save_expiredTrips());
		assertEquals("EXPIRED", jdbc.queryForObject("SELECT status FROM trip WHERE vehicle_id = ?", String.class,
				vehicleId));
		assertNull(trips.select_activeTrip(vehicleId));
	}

	@Test
	void 휴게소_접근_도착_떠남을_동승자에게_restarea_info로_보낸다() throws Exception {
		// 휴게소 데이터가 적재된 로컬 DB에서만 (team-setup.md 3-4)
		List<Map<String, Object>> restAreas = jdbc.queryForList("SELECT std_rest_cd, name FROM rest_area LIMIT 1");
		assumeTrue(!restAreas.isEmpty(), "rest_area 비어 있음");
		String code = (String) restAreas.get(0).get("std_rest_cd");
		String name = (String) restAreas.get(0).get("name");

		String car = newCar();
		Client carWs = auth(car);
		carWs.next("auth.ok");
		carWs.send("{\"type\":\"trip.start\",\"reqId\":\"1\",\"data\":{\"locationConsent\":true}}");
		carWs.next("ack");
		VehicleDto.Connected p = newPassenger(car, "민지");
		Client phone = auth(p.passengerToken());
		phone.next("auth.ok");
		carWs.next("passengers.update");

		// 경유지 반영(3번)이 만들 요청 행을 직접 넣는다
		long tripId = jdbc.queryForObject("SELECT id FROM trip WHERE vehicle_id = ?", Long.class, vehicleIds.get(0));
		jdbc.update("INSERT INTO waypoint_request (trip_id, passenger_id, place_type, place_ext_id, poi_name, lat, lng,"
				+ " action, status, requested_at) VALUES (?, ?, 'REST_AREA', ?, ?, 36.0, 128.0, 'ADD', 'APPLIED', NOW(3))",
				tripId, p.passengerId(), "ra:" + code, name);
		long requestId = jdbc.queryForObject("SELECT MAX(id) FROM waypoint_request WHERE trip_id = ?", Long.class, tripId);

		carWs.send("{\"type\":\"restarea.approach\",\"reqId\":\"2\",\"data\":{\"requestId\":" + requestId + "}}");
		carWs.next("ack");
		Map<?, ?> info = phone.next("restarea.info");
		assertEquals("APPROACH", info.get("phase"));
		assertEquals(name, ((Map<?, ?>) info.get("restArea")).get("name"));

		carWs.send("{\"type\":\"restarea.arrived\",\"data\":{\"requestId\":" + requestId + "}}");
		assertEquals("ARRIVED", phone.next("restarea.info").get("phase"));

		carWs.send("{\"type\":\"restarea.left\",\"data\":{\"requestId\":" + requestId + "}}");
		info = phone.next("restarea.info");
		assertEquals("LEFT", info.get("phase"));
		assertNull(info.get("restArea"));
	}

	/** carAndPhone이 만든 차량 토큰 (같은 차량에 동승자를 더 붙일 때) */
	private String carToken;

	/** 세션을 연 차와, 그 뒤에 붙은 동승자 폰 */
	private Client[] carAndPhone() throws Exception {
		String car = newCar();
		carToken = car;
		Client carWs = auth(car);
		carWs.next("auth.ok");
		carWs.send("{\"type\":\"trip.start\",\"reqId\":\"1\",\"data\":{\"locationConsent\":true}}");
		carWs.next("ack");
		Client phone = auth(newPassenger(car, "민지").passengerToken());
		phone.next("auth.ok");
		carWs.next("passengers.update");
		return new Client[] { carWs, phone };
	}

	@Test
	void 차_위치_기어_에너지는_trip_state로_폰에_가고_trip에_저장된다() throws Exception {
		Client[] c = carAndPhone();
		c[0].send("{\"type\":\"car.gear\",\"data\":{\"gear\":\"DRIVE\"}}");
		c[0].send("{\"type\":\"car.location\",\"data\":{\"lat\":36.62,\"lng\":127.43,\"heading\":182}}");
		Map<?, ?> state = c[1].next("trip.state");
		assertEquals(36.62, ((Map<?, ?>) state.get("car")).get("lat"));
		assertEquals(182, ((Map<?, ?>) state.get("car")).get("heading"));
		assertEquals("DRIVE", state.get("gear"));

		c[0].send("{\"type\":\"car.energy\",\"data\":{\"energyType\":\"EV\",\"energyPct\":18,\"rangeM\":61000}}");
		assertEquals(61000, ((Map<?, ?>) c[1].next("trip.state").get("energy")).get("rangeM"));
		Map<String, Object> row = jdbc.queryForMap(
				"SELECT last_lat, last_heading, energy_type, range_m FROM trip WHERE vehicle_id = ?", vehicleIds.get(0));
		assertEquals(0, new java.math.BigDecimal("36.620000").compareTo((java.math.BigDecimal) row.get("last_lat")));
		assertEquals("EV", row.get("energy_type"));

		c[0].send("{\"type\":\"car.location\",\"reqId\":\"x\",\"data\":{\"lat\":91,\"lng\":127}}");
		assertEquals("BAD_REQUEST", c[0].next("error").get("code"));
	}

	@Test
	void 목적지가_바뀌면_길찾기_경로를_route_update로_보내고_늦게_붙은_폰도_받는다() throws Exception {
		when(directions.route(any(), any(), any(), any())).thenReturn(new KakaoDirections.Route(
				List.of(new double[] { 127.43, 36.62 }, new double[] { 129.041, 35.115 }), 312000, 12600));
		Client[] c = carAndPhone();
		c[0].send("{\"type\":\"car.location\",\"data\":{\"lat\":36.62,\"lng\":127.43,\"heading\":182}}");
		c[1].next("trip.state");
		String route = "{\"type\":\"car.route\",\"data\":{\"destination\":{\"name\":\"부산역\",\"lat\":35.115,"
				+ "\"lng\":129.041,\"remainDistM\":312000,\"remainTimeS\":12600},\"waypoints\":["
				+ "{\"index\":\"FIRST\",\"name\":\"내비 직접\",\"lat\":null,\"lng\":null}]}}";
		c[0].send(route);
		Map<?, ?> update = c[1].next("route.update");
		assertEquals(1, update.get("routeVersion"));
		assertEquals("KAKAO", update.get("source"));
		assertEquals(2, ((List<?>) update.get("path")).size());
		assertEquals("부산역", ((Map<?, ?>) update.get("destination")).get("name"));
		assertEquals(List.of(), update.get("waypoints")); // 좌표 모르는 경유지는 뺀다
		assertEquals("부산역", jdbc.queryForObject("SELECT dest_name FROM trip WHERE vehicle_id = ?", String.class,
				vehicleIds.get(0)));

		// 남은 거리만 바뀌면 다시 보내지 않는다
		c[0].send(route.replace("312000", "311000"));
		c[0].send("{\"type\":\"ping\"}");
		c[0].next("pong");
		Thread.sleep(300);
		assertNull(c[1].received.poll());

		Client late = auth(newPassenger(carToken, "준호").passengerToken());
		late.next("auth.ok");
		assertEquals(1, late.next("route.update").get("routeVersion"));
		late.next("trip.state");
	}

	@Test
	void 길찾기가_실패하면_직선_APPROX_목적지를_지우면_빈_경로() throws Exception {
		Client[] c = carAndPhone();
		c[0].send("{\"type\":\"car.location\",\"data\":{\"lat\":36.62,\"lng\":127.43}}");
		c[1].next("trip.state");
		c[0].send("{\"type\":\"car.route\",\"data\":{\"destination\":{\"name\":\"부산역\",\"lat\":35.115,"
				+ "\"lng\":129.041,\"remainDistM\":312000,\"remainTimeS\":12600},\"waypoints\":[]}}");
		Map<?, ?> update = c[1].next("route.update");
		assertEquals("APPROX", update.get("source"));
		assertEquals(List.of(List.of(127.43, 36.62), List.of(129.041, 35.115)), update.get("path"));
		assertEquals(312000, update.get("distanceM"));

		c[0].send("{\"type\":\"car.route\",\"data\":{\"destination\":null,\"waypoints\":[]}}");
		update = c[1].next("route.update");
		assertEquals(2, update.get("routeVersion"));
		assertNull(update.get("destination"));
		assertEquals(List.of(), update.get("path"));
	}

	private static final String PROPOSE = "{\"type\":\"waypoint.propose\",\"reqId\":\"p\",\"data\":{\"place\":"
			+ "{\"extId\":\"kk:123\",\"name\":\"곰탕집\",\"lat\":37.4,\"lng\":127.1}}}";

	private void kakaoFinds() {
		when(search.kakaoKeyword(eq("곰탕집"), any(), any())).thenReturn(List.of(new SearchClients.KakaoPlace("123",
				"곰탕집", "음식점 > 한식 > 국밥", "FD6", "", "경기 성남시 1", "", 37.41, 127.11, 10, "http://p/123")));
	}

	@Test
	void 경유지_제안_수락_반영까지_차와_폰에_간다() throws Exception {
		kakaoFinds();
		Client[] c = carAndPhone();
		c[1].send(PROPOSE);
		assertEquals("PENDING", c[1].next("waypoint.status").get("status"));
		long requestId = ((Number) c[1].next("ack").get("requestId")).longValue();

		Map<?, ?> request = c[0].next("waypoint.request");
		assertEquals("민지", request.get("requestedBy"));
		assertEquals("SET_DEST", request.get("action")); // 목적지가 없으면 목적지로
		assertEquals(37.41, ((Map<?, ?>) request.get("place")).get("lat")); // 폰 좌표가 아니라 다시 찾은 좌표
		assertEquals("RESTAURANT", ((Map<?, ?>) request.get("place")).get("type"));

		c[0].send("{\"type\":\"waypoint.accept\",\"reqId\":\"a\",\"data\":{\"requestId\":" + requestId + "}}");
		c[0].next("ack");
		assertEquals("ACCEPTED", c[1].next("waypoint.status").get("status"));
		c[0].send("{\"type\":\"waypoint.result\",\"data\":{\"requestId\":" + requestId + ",\"ok\":true}}");
		Map<?, ?> status = c[1].next("waypoint.status");
		assertEquals("APPLIED", status.get("status"));
		assertEquals("곰탕집", status.get("placeName"));
	}

	@Test
	void 서버가_경로를_아직_못_받았어도_DB에_목적지가_있으면_경유지로_넣는다() throws Exception {
		kakaoFinds();
		Client[] c = carAndPhone();
		// 서버를 다시 켠 직후: 메모리에는 경로가 없고 trip 행에만 목적지가 남아 있음
		jdbc.update("UPDATE trip SET dest_name = '부산역', dest_lat = 35.1, dest_lng = 129.0 WHERE vehicle_id = ?",
				vehicleIds.get(0));
		c[1].send(PROPOSE);
		assertEquals("ADD", c[0].next("waypoint.request").get("action"));
	}

	@Test
	void 새_제안이_오면_이전_것은_만료되고_늦은_수락은_REQUEST_EXPIRED() throws Exception {
		kakaoFinds();
		Client[] c = carAndPhone();
		c[1].send(PROPOSE);
		c[1].next("waypoint.status");
		long first = ((Number) c[1].next("ack").get("requestId")).longValue();
		c[0].next("waypoint.request");

		c[1].send(PROPOSE);
		assertEquals(first, ((Number) c[0].next("waypoint.expired").get("requestId")).longValue());
		assertEquals("EXPIRED", c[1].next("waypoint.status").get("status"));
		c[1].next("waypoint.status"); // 새 요청 PENDING
		long second = ((Number) c[1].next("ack").get("requestId")).longValue();
		c[0].next("waypoint.request");

		c[0].send("{\"type\":\"waypoint.accept\",\"reqId\":\"a\",\"data\":{\"requestId\":" + first + "}}");
		assertEquals("REQUEST_EXPIRED", c[0].next("error").get("code"));

		// 마감(자동 수락 10초 + 여유 5초)이 지난 요청도 받지 않는다
		jdbc.update("UPDATE waypoint_request SET requested_at = requested_at - INTERVAL 16 SECOND WHERE id = ?", second);
		c[0].send("{\"type\":\"waypoint.accept\",\"reqId\":\"b\",\"data\":{\"requestId\":" + second + "}}");
		assertEquals("REQUEST_EXPIRED", c[0].next("error").get("code"));

		c[0].send("{\"type\":\"waypoint.reject\",\"data\":{\"requestId\":" + second + "}}");
		assertEquals("REJECTED", c[1].next("waypoint.status").get("status"));
	}

	@Test
	void 경유지가_2개면_WAYPOINT_FULL_못_찾으면_PLACE_NOT_FOUND_차는_제안_못함() throws Exception {
		kakaoFinds();
		Client[] c = carAndPhone();
		c[0].send("{\"type\":\"car.route\",\"data\":{\"destination\":{\"name\":\"부산역\",\"lat\":35.1,\"lng\":129.0},"
				+ "\"waypoints\":[{\"name\":\"a\"},{\"name\":\"b\"}]}}");
		c[1].next("route.update");
		c[1].send(PROPOSE);
		assertEquals("WAYPOINT_FULL", c[1].next("error").get("code"));

		c[1].send(PROPOSE.replace("kk:123", "kk:999"));
		assertEquals("PLACE_NOT_FOUND", c[1].next("error").get("code"));
		c[0].send(PROPOSE);
		assertEquals("FORBIDDEN_ROLE", c[0].next("error").get("code"));
	}

	@Test
	void 세션이_없으면_위치를_저장하지도_보내지도_않는다() throws Exception {
		String car = newCar();
		Client carWs = auth(car);
		carWs.next("auth.ok");
		Client phone = auth(newPassenger(car, "민지").passengerToken());
		phone.next("auth.ok");
		carWs.next("passengers.update");
		carWs.send("{\"type\":\"car.location\",\"data\":{\"lat\":36.62,\"lng\":127.43}}");
		carWs.send("{\"type\":\"ping\"}");
		carWs.next("pong");
		Thread.sleep(300);
		assertNull(phone.received.poll());
	}

	@Test
	void 이_차량의_휴게소_요청이_아니면_PLACE_NOT_FOUND() throws Exception {
		Client carWs = auth(newCar());
		carWs.next("auth.ok");
		carWs.send("{\"type\":\"restarea.approach\",\"reqId\":\"1\",\"data\":{\"requestId\":999999999}}");
		assertEquals("PLACE_NOT_FOUND", carWs.next("error").get("code"));
		carWs.send("{\"type\":\"restarea.approach\",\"reqId\":\"2\",\"data\":{}}");
		assertEquals("BAD_REQUEST", carWs.next("error").get("code"));
	}
}
