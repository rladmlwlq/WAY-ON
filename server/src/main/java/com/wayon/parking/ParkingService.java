package com.wayon.parking;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.wayon.ws.WsSessions;

/**
 * 목적지 근처 공영주차장 (way-on.md 3.6, app-api 5-8·7장 parking.suggest).
 * 차량 앱이 목적지 10km 또는 5분 안에서 parking.near-dest를 보내면, 목적지 반경 1km(없으면 2km)의 주차장을
 * 가까운 순으로 보낸다. 동승자가 있으면 폰들에게, 없으면 차량 앱에게 (음성 + 정차 중 차 화면 목록).
 * 고른 주차장은 waypoint.propose의 `pl:{id}`로 들어와 목적지를 바꾼다 (SET_DEST).
 */
@Service
public class ParkingService {

	private static final Logger log = LoggerFactory.getLogger(ParkingService.class);
	public static final String EXT_ID_PREFIX = "pl:";
	static final int RADIUS_M = 1000;
	static final int WIDE_RADIUS_M = 2000;
	static final int LIMIT = 20;
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final ParkingMapper mapper;
	private final WsSessions sessions;
	/** 목적지를 아직 못 받아 기다리는 요청: 차량 ID → 차가 말한 목적지 이름 */
	private final Map<Long, String> waiting = new ConcurrentHashMap<>();

	public ParkingService(ParkingMapper mapper, WsSessions sessions) {
		this.mapper = mapper;
		this.sessions = sessions;
	}

	/**
	 * 5-8 parking.near-dest. 차는 목적지가 바뀌는 순간 보내므로 새 목적지(car.route)보다 먼저 올 수 있다.
	 * 저장된 목적지가 차가 말한 이름과 다르거나 없으면 기다렸다가 {@link #save_routeChanged}에서 보낸다
	 */
	public void save_nearDest(long vehicleId, Map<?, ?> data) {
		String expected = data.get("destName") instanceof String s && !s.isBlank() ? s : null;
		ParkingRows.Destination dest = mapper.select_destination(vehicleId);
		if (!matches(dest, expected)) {
			log.info("parking.near-dest: 목적지 {}를 아직 몰라 기다림 (vehicle {})", expected, vehicleId);
			waiting.put(vehicleId, expected == null ? "" : expected);
			return;
		}
		waiting.remove(vehicleId);
		suggest(vehicleId, dest);
	}

	/** car.route가 저장된 뒤. 기다리던 요청의 목적지가 들어왔으면 그때 보낸다 */
	public void save_routeChanged(long vehicleId) {
		String expected = waiting.get(vehicleId);
		if (expected == null) {
			return;
		}
		ParkingRows.Destination dest = mapper.select_destination(vehicleId);
		if (matches(dest, expected.isEmpty() ? null : expected) && waiting.remove(vehicleId) != null) {
			suggest(vehicleId, dest);
		}
	}

	/** 세션이 끝나면 기다리던 요청도 버린다 */
	public void clear(long vehicleId) {
		waiting.remove(vehicleId);
	}

	private static boolean matches(ParkingRows.Destination dest, String expected) {
		return dest != null && dest.destLat() != null && dest.destLng() != null
				&& (expected == null || expected.equals(dest.destName()));
	}

	private void suggest(long vehicleId, ParkingRows.Destination dest) {
		List<Map<String, Object>> places = near(dest.destLat().doubleValue(), dest.destLng().doubleValue(),
				ZonedDateTime.now(SEOUL));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("destName", dest.destName());
		out.put("places", places);
		if (sessions.onlinePassengerIds(vehicleId).isEmpty()) {
			sessions.sendToCar(vehicleId, "parking.suggest", out);
		} else {
			sessions.sendToPassengers(vehicleId, "parking.suggest", out);
		}
		log.info("parking.suggest: {}곳 (vehicle {})", places.size(), vehicleId);
	}

	/** 목적지에서 가까운 순, 1km 안이 없으면 2km까지 */
	List<Map<String, Object>> near(double lat, double lng, ZonedDateTime now) {
		List<Map<String, Object>> found = within(lat, lng, RADIUS_M, now);
		return found.isEmpty() ? within(lat, lng, WIDE_RADIUS_M, now) : found;
	}

	private List<Map<String, Object>> within(double lat, double lng, int radiusM, ZonedDateTime now) {
		double dLat = radiusM / 111_000d;
		double dLng = radiusM / (111_000d * Math.cos(Math.toRadians(lat)));
		return mapper.select_inBox(dec(lat - dLat), dec(lat + dLat), dec(lng - dLng), dec(lng + dLng)).stream()
				.map(l -> Map.entry(l, meters(lat, lng, l.lat().doubleValue(), l.lng().doubleValue())))
				.filter(e -> e.getValue() <= radiusM)
				.sorted(Comparator.comparingDouble(Map.Entry::getValue))
				.limit(LIMIT)
				.map(e -> toPlace(e.getKey(), (int) Math.round(e.getValue()), now))
				.toList();
	}

	private static Map<String, Object> toPlace(ParkingRows.Lot l, Integer distM, ZonedDateTime now) {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("extId", EXT_ID_PREFIX + l.id());
		p.put("name", l.name());
		p.put("address", l.addr());
		p.put("lat", l.lat().doubleValue());
		p.put("lng", l.lng().doubleValue());
		p.put("distM", distM);
		p.put("fee1h", l.fee1h());
		p.put("feeType", l.feeType());
		p.put("basicMin", l.basicMin());
		p.put("basicFee", l.basicFee());
		p.put("totalSpaces", l.totalSpaces());
		p.put("nowParked", l.nowParked());
		p.put("openNow", openNow(l, now));
		p.put("phone", l.phone());
		return p;
	}

	/** waypoint.propose의 pl:{id} → 주차장. 없으면 empty */
	public Optional<ParkingRows.Lot> lot(String extId) {
		if (extId == null || !extId.startsWith(EXT_ID_PREFIX)) {
			return Optional.empty();
		}
		try {
			return Optional.ofNullable(mapper.select_lot(Long.parseLong(extId.substring(EXT_ID_PREFIX.length()))));
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	/**
	 * 지금 운영 중인지 (한국 시간). 평일·토요일·일요일(공휴일 칸) 시간으로 보고, 공휴일 달력은 보지 않는다.
	 * 종료가 시작보다 이르면 다음 날까지. 시간이 없으면 null (모름)
	 */
	static Boolean openNow(ParkingRows.Lot l, ZonedDateTime now) {
		DayOfWeek day = now.getDayOfWeek();
		String open = day == DayOfWeek.SATURDAY ? l.satOpen() : day == DayOfWeek.SUNDAY ? l.holidayOpen() : l.weekdayOpen();
		String close = day == DayOfWeek.SATURDAY ? l.satClose() : day == DayOfWeek.SUNDAY ? l.holidayClose() : l.weekdayClose();
		Integer o = minutes(open);
		Integer c = minutes(close);
		if (o == null || c == null) {
			return null;
		}
		if (o.equals(c)) { // 0000~0000은 휴무로 적힌 곳과 24시간이 섞여 있어 판단하지 않음
			return o == 0 ? null : false;
		}
		LocalTime t = now.toLocalTime();
		int m = t.getHour() * 60 + t.getMinute();
		return o < c ? m >= o && m < c : m >= o || m < c;
	}

	private static Integer minutes(String hhmm) {
		if (hhmm == null || hhmm.length() != 4) {
			return null;
		}
		return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(2));
	}

	static double meters(double lat1, double lng1, double lat2, double lng2) {
		double r = 6_371_000;
		double p1 = Math.toRadians(lat1);
		double p2 = Math.toRadians(lat2);
		double dp = Math.toRadians(lat2 - lat1);
		double dl = Math.toRadians(lng2 - lng1);
		double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
		return 2 * r * Math.asin(Math.sqrt(a));
	}

	private static BigDecimal dec(double v) {
		return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
	}
}
