package com.wayon.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

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
