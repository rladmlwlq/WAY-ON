package com.wayon.trip;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.wayon.common.ApiException;

/** 주행 세션 시작·끝·만료 (app-api.md 4-3, 5-1, 5-2). 차량 앱 메시지가 5분 없으면 EXPIRED */
@Service
public class TripService {

	static final Duration IDLE_LIMIT = Duration.ofMinutes(5);

	private final TripMapper mapper;
	private final Clock clock = Clock.systemUTC();

	public TripService(TripMapper mapper) {
		this.mapper = mapper;
	}

	/** auth.ok, trip.started의 trip */
	public record TripInfo(String tripId, Instant startedAt) {
	}

	/** ACTIVE 세션, 없으면 null */
	public TripInfo select_activeTrip(long vehicleId) {
		return toInfo(mapper.select_activeTrip(vehicleId));
	}

	/**
	 * 5-1. 이미 ACTIVE면 그것을 돌려준다 (앱 재시작).
	 * shortcut: 차량 연결은 1개(4409)이고 한 연결의 메시지는 차례로 처리돼 잠금 없이 둔다. 서버를 여러 대로 늘리면 차량 행 잠금 추가
	 */
	public TripInfo save_tripStart(long vehicleId) {
		TripRows.Trip trip = mapper.select_activeTrip(vehicleId);
		if (trip == null) {
			mapper.insert_trip(UUID.randomUUID().toString().replace("-", ""), vehicleId, now());
			trip = mapper.select_activeTrip(vehicleId);
		}
		return toInfo(trip);
	}

	/** 5-2. 닫은 세션이 있으면 true */
	public boolean save_tripEnd(long vehicleId) {
		TripRows.Trip trip = mapper.select_activeTrip(vehicleId);
		return trip != null && mapper.update_tripClosed(trip.id(), "ENDED", now(), null) > 0;
	}

	/** 차량 앱 메시지(ping 포함)마다 */
	public void save_carActivity(long vehicleId) {
		mapper.update_tripActivity(vehicleId, now());
	}

	/** 5분 무통신 세션을 EXPIRED로. 닫은 세션의 차량 ID */
	public List<Long> save_expiredTrips() {
		LocalDateTime now = now();
		LocalDateTime idleBefore = now.minus(IDLE_LIMIT);
		List<Long> vehicleIds = new ArrayList<>();
		for (TripRows.Trip trip : mapper.select_idleTrips(idleBefore)) {
			// 조회와 닫기 사이에 차량 앱 메시지가 오면 닫지 않는다
			if (mapper.update_tripClosed(trip.id(), "EXPIRED", now, idleBefore) > 0) {
				vehicleIds.add(trip.vehicleId());
			}
		}
		return vehicleIds;
	}

	/** 그 세션에 붙은 동승자로 기록 (출발해요 받는 사람). 위치 동의는 연결(3-1) 때 받아서 지금 시각으로 적는다 */
	public void save_tripPassenger(long vehicleId, long passengerId) {
		TripRows.Trip trip = mapper.select_activeTrip(vehicleId);
		if (trip != null) {
			mapper.insert_tripPassenger(trip.id(), passengerId, now());
		}
	}

	/**
	 * 5-9. 차가 보낸 requestId가 이 차량의 휴게소 경유지 요청인지 보고 place_ext_id를 돌려준다.
	 * 지금 세션이 아니어도 된다 (휴게소에서 시동을 껐다 켜면 세션이 바뀌어도 떠남은 보내야 함)
	 */
	public String select_restAreaExtId(long vehicleId, long requestId) {
		String extId = mapper.select_restAreaRequestExtId(requestId, vehicleId);
		if (extId == null) {
			throw ApiException.placeNotFound();
		}
		return extId;
	}

	/** 검색 중심점. ACTIVE 세션의 마지막 차 위치, 없으면 null */
	public TripRows.CarPosition select_carPosition(long vehicleId) {
		return mapper.select_carPosition(vehicleId);
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
	}

	private static TripInfo toInfo(TripRows.Trip trip) {
		return trip == null ? null : new TripInfo(trip.publicId(), trip.createdAt().toInstant(ZoneOffset.UTC));
	}
}
