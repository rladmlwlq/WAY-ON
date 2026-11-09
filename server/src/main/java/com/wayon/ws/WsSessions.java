package com.wayon.ws;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;

import tools.jackson.databind.json.JsonMapper;

/**
 * 인증된 WebSocket 연결 목록 (app-api.md 4장). 차량 1대·동승자 1명당 연결 1개.
 * shortcut: 서버 메모리에 둔다 (way-on.md: 서버 1대). 서버를 여러 대로 늘리면 Redis pub/sub 등으로 교체
 */
@Component
public class WsSessions {

	static final CloseStatus AUTH_FAILED = new CloseStatus(4401, "auth failed");
	static final CloseStatus REVOKED = new CloseStatus(4403, "revoked");
	static final CloseStatus REPLACED = new CloseStatus(4409, "connected elsewhere");

	private static final Logger log = LoggerFactory.getLogger(WsSessions.class);
	private static final String CLIENT = "client";
	private static final String OUT = "out";
	private static final int SEND_TIME_LIMIT_MS = 5_000;
	private static final int BUFFER_LIMIT = 256 * 1024;

	/** 인증된 연결의 주인. passengerId가 null이면 차량 앱 */
	public record Client(long vehicleId, Long passengerId, String nickname) {
		public boolean isCar() {
			return passengerId == null;
		}
	}

	private final Map<Long, WebSocketSession> cars = new ConcurrentHashMap<>();
	private final Map<Long, WebSocketSession> passengers = new ConcurrentHashMap<>();
	private final JsonMapper json;

	public WsSessions(JsonMapper json) {
		this.json = json;
	}

	/** 접속 직후. 여러 스레드가 같은 연결로 보내도 되게 감싼다 */
	void open(WebSocketSession session) {
		session.getAttributes().put(OUT,
				new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_LIMIT));
	}

	/** 인증 성공. 같은 차량·동승자의 이전 연결은 4409로 닫는다 */
	void add(WebSocketSession session, Client client) {
		session.getAttributes().put(CLIENT, client);
		WebSocketSession old = client.isCar() ? cars.put(client.vehicleId(), session)
				: passengers.put(client.passengerId(), session);
		if (old != null && old != session) {
			close(old, REPLACED);
		}
	}

	/** 연결이 닫힘. 아직 목록에 있던 연결이면 true (다른 연결로 바뀌었거나 해제로 뺀 것은 false) */
	boolean remove(WebSocketSession session) {
		Client client = client(session);
		if (client == null) {
			return false;
		}
		return client.isCar() ? cars.remove(client.vehicleId(), session)
				: passengers.remove(client.passengerId(), session);
	}

	static Client client(WebSocketSession session) {
		return (Client) session.getAttributes().get(CLIENT);
	}

	public boolean isOnline(long passengerId) {
		return passengers.containsKey(passengerId);
	}

	public List<Long> onlinePassengerIds(long vehicleId) {
		return passengers.values().stream()
				.map(WsSessions::client)
				.filter(client -> client.vehicleId() == vehicleId)
				.map(Client::passengerId)
				.toList();
	}

	/** 동승자 해제 (2-4) */
	public void closePassenger(long passengerId) {
		WebSocketSession session = passengers.remove(passengerId);
		if (session != null) {
			close(session, REVOKED);
		}
	}

	public void sendToCar(long vehicleId, String type, Object data) {
		WebSocketSession session = cars.get(vehicleId);
		if (session != null) {
			send(session, type, null, data);
		}
	}

	public void sendToPassengers(long vehicleId, String type, Object data) {
		passengers.values().stream()
				.filter(session -> client(session).vehicleId() == vehicleId)
				.forEach(session -> send(session, type, null, data));
	}

	/** {"type", "reqId", "data"} (4-2). reqId·data가 null이면 뺀다 */
	void send(WebSocketSession session, String type, String reqId, Object data) {
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("type", type);
		if (reqId != null) {
			message.put("reqId", reqId);
		}
		if (data != null) {
			message.put("data", data);
		}
		try {
			((WebSocketSession) session.getAttributes().get(OUT))
					.sendMessage(new TextMessage(json.writeValueAsString(message)));
		} catch (IOException | IllegalStateException | SessionLimitExceededException e) {
			// 끊긴 연결: afterConnectionClosed에서 목록에서 빠진다
			log.debug("send failed {} {}", type, e.toString());
		}
	}

	static void close(WebSocketSession session, CloseStatus status) {
		try {
			session.close(status);
		} catch (IOException e) {
			log.debug("close failed {}", e.toString());
		}
	}
}
