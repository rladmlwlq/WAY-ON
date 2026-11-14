package com.wayon.departure;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.wayon.common.ApiException;
import com.wayon.push.WebPushSender;
import com.wayon.trip.TripMapper;
import com.wayon.trip.TripRows;
import com.wayon.vehicle.VehicleService;
import com.wayon.ws.WsSessions;

import tools.jackson.databind.json.JsonMapper;

/**
 * 출발해요 (A2, 출발해요 기획 7·8·9장, app-api.md 5-12, 6-2, 7장).
 * 차가 [출발해요]를 누르면 받는 동승자에게 WebSocket(departure.called)과 Web Push를 보낸다.
 * 대화는 차·폰 모두에게 그대로 돌리고, 사람별 마지막 빠른 답장(departure.status)을 같이 보낸다.
 *
 * - OPEN은 차량당 1건 (차량 행 잠금)
 * - 30분이 지나면 EXPIRED. 서버 타이머(WsHandler, 30초마다)가 닫는다
 * - 출발(DEPARTED)하면 "가는 중이에요"가 아닌 사람에게 "차가 출발했어요" 푸시
 */
@Service
public class DepartureService {

	static final Duration CALL_TTL = Duration.ofMinutes(30);
	static final Duration REMIND_GAP = Duration.ofMinutes(1);
	static final Duration PREV_TRIP = Duration.ofHours(2);
	static final Duration MESSAGE_TTL = Duration.ofDays(30);
	private static final int MAX_BODY = 40;
	private static final Set<String> PASSENGER_CODES = Set.of("TOILET", "IN_LINE", "COMING");
	private static final Set<String> DRIVER_CODES = Set.of("TAKE_TIME", "HURRY");
	private static final Logger log = LoggerFactory.getLogger(DepartureService.class);

	private final DepartureMapper mapper;
	private final TripMapper trips;
	private final WsSessions sessions;
	private final WebPushSender push;
	private final JsonMapper json;
	private final TransactionTemplate tx;
	private final Clock clock = Clock.systemUTC();

	public DepartureService(DepartureMapper mapper, TripMapper trips, WsSessions sessions, WebPushSender push,
			JsonMapper json, TransactionTemplate tx) {
		this.mapper = mapper;
		this.trips = trips;
		this.sessions = sessions;
		this.push = push;
		this.json = json;
		this.tx = tx;
	}

	/**
	 * 5-12 departure.call. destName·etaS는 차량 앱이 내비 값으로 채운다 (없으면 null).
	 * 알리기는 하지 않는다: 차에 ack(callId)를 먼저 보낸 뒤 {@link #announce}를 부른다
	 */
	public DepartureDto.Called save_call(long vehicleId, String destName, Integer etaS) {
		DepartureRows.Call call = tx.execute(status -> {
			mapper.select_vehicleIdForUpdate(vehicleId);
			closeIfStale(mapper.select_openCall(vehicleId));
			if (mapper.select_openCall(vehicleId) != null) {
				throw new ApiException(HttpStatus.CONFLICT, "CALL_ALREADY_OPEN", "이미 출발 알림을 보냈어요");
			}
			TripRows.Trip trip = trips.select_activeTrip(vehicleId);
			if (trip == null) {
				throw new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_ACTIVE", "차량 앱이 켜지면 연결돼요");
			}
			Integer etaMin = etaS == null || etaS <= 0 ? null : (etaS + 59) / 60;
			mapper.insert_call(vehicleId, trip.id(), etaMin, trimName(destName), now());
			return mapper.select_openCall(vehicleId);
		});
		return toCalled(call);
	}

	/** departure.called(폰)와 Web Push, 상태 줄(차·폰) */
	public void announce(long callId) {
		DepartureRows.Call call = mapper.select_call(callId);
		if (call == null || !"OPEN".equals(call.status())) {
			return;
		}
		sessions.sendToPassengers(call.vehicleId(), "departure.called", toCalled(call));
		List<DepartureRows.Person> people = people(call);
		sendStatus(call, people);
		pushTo(people, p -> true, call, "곧 출발해요", calledBody(call));
	}

	/** 5-12 departure.remind. 아직 아무것도 안 보낸 사람에게만 다시 푸시 */
	public void save_remind(long vehicleId, long callId) {
		DepartureRows.Call call = requireOpen(vehicleId, callId);
		LocalDateTime last = call.lastRemindedAt() != null ? call.lastRemindedAt() : call.calledAt();
		if (last.plus(REMIND_GAP).isAfter(now())) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "REMIND_TOO_SOON", "잠시 뒤에 다시 알릴 수 있어요");
		}
		mapper.update_callReminded(callId, now());
		pushTo(people(call), p -> p.messages() == 0, call, "곧 출발해요 (다시 알림)", calledBody(call));
	}

	/** 5-12 departure.close. 이미 닫혔으면 아무것도 안 한다 (기어 D와 만료가 겹칠 수 있음) */
	public void save_close(long vehicleId, long callId, String reason) {
		if (!"DEPARTED".equals(reason) && !"CANCELLED".equals(reason)) {
			throw badRequest();
		}
		DepartureRows.Call call = mapper.select_call(callId);
		if (call == null || call.vehicleId() != vehicleId) {
			throw callClosed();
		}
		if (mapper.update_callClosed(callId, reason, now()) == 0) {
			return;
		}
		sendClosed(call, reason);
		if ("DEPARTED".equals(reason)) {
			// 사람을 두고 가지 않게: "가는 중이에요"를 고르지 않은 사람에게 알린다 (기획 7장)
			pushTo(people(call), p -> !"COMING".equals(p.lastQuickCode()), call, "차가 출발했어요",
					"차에 타셨나요? 아니면 운전자에게 연락해 주세요");
		}
	}

	/**
	 * 5-12·6-2 departure.message. passengerId가 null이면 운전자.
	 * 빠른 답장은 보낸 쪽 목록만, 텍스트는 1~40자. 동승자는 1초에 1건까지
	 */
	public void save_message(long vehicleId, Long passengerId, long callId, String kind, String quickCode,
			String body) {
		DepartureRows.Call call = requireOpen(vehicleId, callId);
		String text = null;
		if ("QUICK".equals(kind)) {
			if (quickCode == null || !(passengerId == null ? DRIVER_CODES : PASSENGER_CODES).contains(quickCode)) {
				throw messageInvalid();
			}
		} else if ("TEXT".equals(kind)) {
			text = body == null ? "" : body.strip();
			int length = text.codePointCount(0, text.length());
			if (length < 1 || length > MAX_BODY) {
				throw messageInvalid();
			}
			quickCode = null;
		} else {
			throw messageInvalid();
		}
		if (passengerId != null
				&& mapper.select_recentMessageCount(callId, passengerId, now().minusSeconds(1)) > 0) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "MESSAGE_TOO_FAST", "조금 천천히 보내 주세요");
		}
		mapper.insert_message(callId, passengerId, kind, quickCode, text, now());
		Object message = VehicleService.toMessage(mapper.select_lastMessage(callId));
		sessions.sendToCar(vehicleId, "departure.message", message);
		sessions.sendToPassengers(vehicleId, "departure.message", message);
		if (passengerId != null && "QUICK".equals(kind)) {
			sendStatus(call, people(call));
		}
	}

	/** auth.ok의 openCall. 열린 알림이 없으면 null */
	public DepartureDto.OpenCall select_openCall(long vehicleId) {
		DepartureRows.Call call = mapper.select_openCall(vehicleId);
		if (call == null || isStale(call)) {
			return null;
		}
		DepartureDto.Called c = toCalled(call);
		return new DepartureDto.OpenCall(c.callId(), c.calledAt(), c.etaS(), c.destName(),
				people(call).stream().map(DepartureService::toPerson).toList());
	}

	/** 서버 타이머: 30분 지난 OPEN을 EXPIRED로 닫고 차·폰에 알린다 */
	public void save_expiredCalls() {
		for (DepartureRows.Call call : mapper.select_staleOpenCalls(now().minus(CALL_TTL))) {
			closeIfStale(call);
		}
	}

	/** 서버 타이머: 30일 지난 메시지 삭제 (기획 11-3) */
	public int delete_oldMessages() {
		return mapper.delete_oldMessages(now().minus(MESSAGE_TTL));
	}

	// ----

	private DepartureRows.Call requireOpen(long vehicleId, long callId) {
		DepartureRows.Call call = mapper.select_call(callId);
		if (call == null || call.vehicleId() != vehicleId || !"OPEN".equals(call.status())) {
			throw callClosed();
		}
		if (closeIfStale(call)) {
			throw callClosed();
		}
		return call;
	}

	private boolean isStale(DepartureRows.Call call) {
		return call.calledAt().plus(CALL_TTL).isBefore(now());
	}

	/** 30분 지났으면 닫고 알린다. 닫았으면 true */
	private boolean closeIfStale(DepartureRows.Call call) {
		if (call == null || !isStale(call)) {
			return false;
		}
		if (mapper.update_callClosed(call.id(), "EXPIRED", now()) > 0) {
			sendClosed(call, "EXPIRED");
		}
		return true;
	}

	private List<DepartureRows.Person> people(DepartureRows.Call call) {
		return mapper.select_people(call.id(), call.vehicleId(), now().minus(PREV_TRIP));
	}

	private void sendStatus(DepartureRows.Call call, List<DepartureRows.Person> people) {
		DepartureDto.Status status = new DepartureDto.Status(call.id(),
				people.stream().map(DepartureService::toPerson).toList());
		sessions.sendToCar(call.vehicleId(), "departure.status", status);
		sessions.sendToPassengers(call.vehicleId(), "departure.status", status);
	}

	private void sendClosed(DepartureRows.Call call, String reason) {
		DepartureDto.Closed closed = new DepartureDto.Closed(call.id(), reason);
		sessions.sendToCar(call.vehicleId(), "departure.closed", closed);
		sessions.sendToPassengers(call.vehicleId(), "departure.closed", closed);
	}

	/** 7-3. 같은 tag라 다시 알리기·출발 알림이 이전 알림을 바꿔 띄운다. 끝난 구독(404·410)은 지운다 */
	private void pushTo(List<DepartureRows.Person> people, Predicate<DepartureRows.Person> who,
			DepartureRows.Call call, String title, String body) {
		if (!push.enabled()) {
			return;
		}
		String payload = json.writeValueAsString(new DepartureDto.Push("DEPARTURE_CALLED", call.id(), title, body,
				"/?call=" + call.id(), "departure-" + call.id()));
		for (DepartureRows.Person p : people) {
			if (p.pushEndpoint() == null || !who.test(p)) {
				continue;
			}
			push.send(new WebPushSender.Subscription(p.pushEndpoint(), p.pushP256dh(), p.pushAuth()), payload)
					.thenAccept(result -> {
						if (result == WebPushSender.Result.GONE) {
							log.info("push subscription gone, passenger {}", p.passengerId());
							mapper.update_pushRemoved(p.passengerId());
						}
					});
		}
	}

	/** "부산역까지 약 3시간 30분 · 답장해 주세요". 목적지를 모르면 "차로 와 주세요 · 답장해 주세요" */
	static String calledBody(DepartureRows.Call call) {
		String head = call.destName() == null ? "차로 와 주세요"
				: call.etaMin() == null ? call.destName() + "(으)로 출발해요"
						: call.destName() + "까지 약 " + durationText(call.etaMin());
		return head + " · 답장해 주세요";
	}

	static String durationText(int minutes) {
		int h = minutes / 60;
		int m = minutes % 60;
		return h == 0 ? m + "분" : m == 0 ? h + "시간" : h + "시간 " + m + "분";
	}

	private static DepartureDto.Called toCalled(DepartureRows.Call call) {
		return new DepartureDto.Called(call.id(), call.calledAt().toInstant(ZoneOffset.UTC),
				call.etaMin() == null ? null : call.etaMin() * 60, call.destName(), null);
	}

	private static DepartureDto.Person toPerson(DepartureRows.Person p) {
		return new DepartureDto.Person(p.passengerId(), p.nickname(), p.lastQuickCode(), p.pushEndpoint() != null);
	}

	private static String trimName(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		String s = name.strip();
		return s.length() > 100 ? s.substring(0, 100) : s;
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
	}

	private static ApiException callClosed() {
		return new ApiException(HttpStatus.CONFLICT, "CALL_CLOSED", "출발 알림이 끝났어요");
	}

	private static ApiException messageInvalid() {
		return new ApiException(HttpStatus.BAD_REQUEST, "MESSAGE_INVALID", "메시지를 보낼 수 없어요");
	}

	private static ApiException badRequest() {
		return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "요청 형식이 맞지 않아요");
	}
}
