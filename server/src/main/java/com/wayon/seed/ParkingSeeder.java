package com.wayon.seed;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * 공영주차장 적재 (api.md 7·8·9, erd.md parking_lot 적재 규칙).
 * fetch: 전국주차장정보표준데이터(공영만) + 서울시 공영주차장 → 좌표 없는 곳은 카카오 주소 검색 → parking_lot.csv.
 * load: CSV → DB (지우고 다시 넣음). 한 달에 1번이면 충분하다 (원본이 반기 갱신).
 */
@Component
public class ParkingSeeder {

	private static final Logger log = LoggerFactory.getLogger(ParkingSeeder.class);
	static final String FILE = "parking_lot.csv";
	private static final List<String> HEADER = List.of("source", "source_key", "name", "kind", "addr", "lat", "lng",
			"coord_source", "total_spaces", "fee_type", "basic_min", "basic_fee", "add_min", "add_fee",
			"day_max_fee", "fee_1h", "weekday_open", "weekday_close", "sat_open", "sat_close", "holiday_open",
			"holiday_close", "phone", "ref_date");
	private static final int PAGE = 1000;

	private final RestClient http;
	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final Path dir;
	private final String dataGoKrKey;
	private final String seoulKey;
	private final String kakaoKey;

	public ParkingSeeder(JdbcTemplate jdbc, TransactionTemplate tx,
			@Value("${wayon.seed.dir:src/main/resources/db/seed}") String dir,
			@Value("${DATA_GO_KR_KEY:}") String dataGoKrKey, @Value("${SEOUL_DATA_KEY:}") String seoulKey,
			@Value("${KAKAO_REST_KEY:}") String kakaoKey) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(5));
		factory.setReadTimeout(Duration.ofSeconds(30));
		// 공공데이터는 curl 기본 User-Agent를 막는다 (api.md 0장)
		this.http = RestClient.builder().requestFactory(factory).defaultHeader("User-Agent", "WayOn/0.1").build();
		this.jdbc = jdbc;
		this.tx = tx;
		this.dir = Path.of(dir);
		this.dataGoKrKey = dataGoKrKey;
		this.seoulKey = seoulKey;
		this.kakaoKey = kakaoKey;
	}

	/** 한 행 = CSV 한 줄 (HEADER 순서) */
	record Lot(String source, String sourceKey, String name, String kind, String addr, double lat, double lng,
			String coordSource, Integer totalSpaces, String feeType, Integer basicMin, Integer basicFee,
			Integer addMin, Integer addFee, Integer dayMaxFee, Integer fee1h, String weekdayOpen,
			String weekdayClose, String satOpen, String satClose, String holidayOpen, String holidayClose,
			String phone, String refDate) {

		List<Object> row() {
			return new ArrayList<>(java.util.Arrays.asList(source, sourceKey, name, kind, addr, lat, lng,
					coordSource, totalSpaces, feeType, basicMin, basicFee, addMin, addFee, dayMaxFee, fee1h,
					weekdayOpen, weekdayClose, satOpen, satClose, holidayOpen, holidayClose, phone, refDate));
		}
	}

	// ---------------------------------------------------------------- fetch: API → CSV

	public void fetch() throws IOException {
		require(dataGoKrKey, "DATA_GO_KR_KEY");
		require(seoulKey, "SEOUL_DATA_KEY");
		require(kakaoKey, "KAKAO_REST_KEY");
		List<Lot> seoul = fetchSeoul();
		List<Lot> national = fetchNational();
		// 서울은 서울시 데이터를 기준으로 하고, 전국 데이터의 서울 행은 이름이 같고 100m 안이면 버린다 (erd.md)
		Map<String, List<Lot>> seoulByName = new HashMap<>();
		seoul.forEach(l -> seoulByName.computeIfAbsent(nameKey(l.name()), k -> new ArrayList<>()).add(l));
		int dup = 0;
		List<Lot> all = new ArrayList<>(seoul);
		for (Lot n : national) {
			List<Lot> same = seoulByName.getOrDefault(nameKey(n.name()), List.of());
			if (same.stream().anyMatch(s -> RestAreaPlaces.meters(s.lat(), s.lng(), n.lat(), n.lng()) <= 100)) {
				dup++;
				continue;
			}
			all.add(n);
		}
		Csv.write(dir.resolve(FILE), HEADER, all.stream().map(Lot::row).toList());
		log.info("주차장 CSV: 서울 {}, 전국 {} (서울과 겹쳐 뺀 것 {}), 합계 {}", seoul.size(), national.size() - dup, dup,
				all.size());
	}

	/** 7번 전국 표준데이터. 공영만, 좌표가 없거나 국내 범위 밖이면 뺀다 */
	@SuppressWarnings("unchecked")
	private List<Lot> fetchNational() {
		Map<String, Lot> out = new LinkedHashMap<>(); // 같은 기관·번호가 두 번 오는 행이 있어 처음 것만
		int skipped = 0;
		for (int page = 1;; page++) {
			Map<String, Object> res = http.get()
					.uri("https://api.data.go.kr/openapi/tn_pubr_prkplce_info_api?serviceKey={k}&type=json"
							+ "&pageNo={p}&numOfRows={n}", dataGoKrKey, page, PAGE)
					.retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {
					});
			Map<String, Object> root = res != null && res.get("response") instanceof Map<?, ?> r
					? (Map<String, Object>) r : res;
			Map<String, Object> header = root == null ? null : (Map<String, Object>) root.get("header");
			String code = header == null ? null : str(header.get("resultCode"));
			if ("03".equals(code)) { // NODATA: 페이지 끝
				break;
			}
			if (!"00".equals(code)) {
				throw new IllegalStateException("전국 주차장 API 실패: " + header);
			}
			Map<String, Object> body = (Map<String, Object>) root.get("body");
			Object items = body == null ? null : ((Map<String, Object>) body.get("items")).get("item");
			List<Map<String, Object>> list = items instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
			for (Map<String, Object> it : list) {
				Lot lot = national(it);
				if (lot == null) {
					skipped++;
				} else if (out.putIfAbsent(lot.sourceKey(), lot) != null) {
					skipped++;
				}
			}
			int total = Integer.parseInt(str(body.get("totalCount")));
			if (page * PAGE >= total || list.isEmpty()) {
				break;
			}
		}
		log.info("전국 주차장: 공영 {}곳 (민영·좌표 없음·중복 등 뺀 것 {})", out.size(), skipped);
		return new ArrayList<>(out.values());
	}

	private static Lot national(Map<String, Object> it) {
		if (!"공영".equals(str(it.get("prkplceSe")))) {
			return null;
		}
		Double lat = dbl(it.get("latitude"));
		Double lng = dbl(it.get("longitude"));
		if (lat == null || lng == null || !inKorea(lat, lng)) {
			return null;
		}
		String name = blank(str(it.get("prkplceNm")));
		String addr = blank(str(it.get("rdnmadr")));
		if (addr == null) {
			addr = blank(str(it.get("lnmadr")));
		}
		if (name == null || addr == null) {
			return null;
		}
		String feeType = switch (str(it.get("parkingchrgeInfo"))) {
			case "무료" -> "FREE";
			case "혼합" -> "MIXED";
			default -> "PAID";
		};
		Integer basicMin = num(it.get("basicTime"));
		Integer basicFee = num(it.get("basicCharge"));
		Integer addMin = num(it.get("addUnitTime"));
		Integer addFee = num(it.get("addUnitCharge"));
		Integer dayMax = positive(num(it.get("dayCmmtkt")));
		String kind = switch (str(it.get("prkplceType"))) {
			case "노상" -> "ON_STREET";
			case "부설" -> "ATTACHED";
			default -> "OFF_STREET";
		};
		return new Lot("NATIONAL", str(it.get("insttCode")) + ":" + str(it.get("prkplceNo")), cut(name, 100), kind,
				cut(addr, 200), round6(lat), round6(lng), "API", positive(num(it.get("prkcmprt"))), feeType, basicMin,
				basicFee, addMin, addFee, dayMax, fee1h(feeType, basicMin, basicFee, addMin, addFee, dayMax),
				hhmm(it.get("weekdayOperOpenHhmm")), hhmm(it.get("weekdayOperColseHhmm")),
				hhmm(it.get("satOperOperOpenHhmm")), hhmm(it.get("satOperCloseHhmm")),
				hhmm(it.get("holidayOperOpenHhmm")), hhmm(it.get("holidayCloseOpenHhmm")),
				cut(blank(str(it.get("phoneNumber"))), 30), blank(str(it.get("referenceDate"))));
	}

	/** 8-1 서울시 공영주차장. 한 코드에 여러 행(노상 구간)이면 첫 행만, 좌표가 없으면 카카오 주소 검색 */
	@SuppressWarnings("unchecked")
	private List<Lot> fetchSeoul() {
		Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
		for (int start = 1;; start += PAGE) {
			Map<String, Object> res = http.get()
					.uri("http://openapi.seoul.go.kr:8088/{k}/json/GetParkInfo/{s}/{e}/", seoulKey, start,
							start + PAGE - 1)
					.retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {
					});
			Map<String, Object> svc = res == null ? null : (Map<String, Object>) res.get("GetParkInfo");
			if (svc == null) { // INFO-200 데이터 없음 = 끝
				break;
			}
			List<Map<String, Object>> rows = (List<Map<String, Object>>) svc.get("row");
			rows.forEach(r -> byCode.putIfAbsent(str(r.get("PKLT_CD")), r));
			if (start + PAGE - 1 >= ((Number) svc.get("list_total_count")).intValue()) {
				break;
			}
		}
		List<Lot> out = new ArrayList<>();
		int geocoded = 0;
		int skipped = 0;
		for (Map<String, Object> r : byCode.values()) {
			String oper = str(r.get("OPER_SE_NM"));
			if (oper.contains("버스") || oper.contains("거주자")) {
				skipped++;
				continue;
			}
			String name = blank(str(r.get("PKLT_NM")));
			String addr = blank(str(r.get("ADDR")));
			if (name == null || addr == null) {
				skipped++;
				continue;
			}
			addr = "서울 " + addr;
			Double lat = dbl(r.get("LAT"));
			Double lng = dbl(r.get("LOT"));
			String coordSource = "API";
			if (lat == null || lng == null || !inKorea(lat, lng)) {
				double[] p = geocode(addr);
				if (p == null) {
					skipped++;
					continue;
				}
				lat = p[0];
				lng = p[1];
				coordSource = "GEOCODE";
				geocoded++;
			}
			String feeType = "Y".equals(str(r.get("CHGD_FREE_SE"))) ? "PAID" : "FREE";
			Integer basicMin = positive(num(r.get("PRK_HM")));
			Integer basicFee = num(r.get("PRK_CRG"));
			Integer addMin = positive(num(r.get("ADD_UNIT_TM_MNT")));
			Integer addFee = num(r.get("ADD_CRG"));
			Integer dayMax = positive(num(r.get("DLY_MAX_CRG")));
			Integer total = num(r.get("TPKCT"));
			out.add(new Lot("SEOUL", str(r.get("PKLT_CD")), cut(name, 100),
					"NS".equals(str(r.get("PKLT_KND"))) ? "ON_STREET" : "OFF_STREET", cut(addr, 200), round6(lat),
					round6(lng), coordSource, total != null && total > 1 ? total : null, feeType, basicMin, basicFee,
					addMin, addFee, dayMax, fee1h(feeType, basicMin, basicFee, addMin, addFee, dayMax),
					hhmm(r.get("WD_OPER_BGNG_TM")), hhmm(r.get("WD_OPER_END_TM")), hhmm(r.get("WE_OPER_BGNG_TM")),
					hhmm(r.get("WE_OPER_END_TM")), hhmm(r.get("LHLDY_BGNG")), hhmm(r.get("LHLDY")),
					cut(blank(str(r.get("TELNO"))), 30), null));
		}
		log.info("서울 주차장: {}곳 (좌표를 주소로 찾은 곳 {}, 뺀 곳 {})", out.size(), geocoded, skipped);
		return out;
	}

	/** 9번 카카오 주소 검색. 못 찾으면 null */
	@SuppressWarnings("unchecked")
	private double[] geocode(String address) {
		try {
			Map<String, Object> res = http.get()
					.uri("https://dapi.kakao.com/v2/local/search/address.json?query={q}", address)
					.header("Authorization", "KakaoAK " + kakaoKey)
					.retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() {
					});
			Object docs = res == null ? null : res.get("documents");
			if (docs instanceof List<?> l && !l.isEmpty()) {
				Map<String, Object> d = (Map<String, Object>) l.get(0);
				return new double[] { Double.parseDouble(str(d.get("y"))), Double.parseDouble(str(d.get("x"))) };
			}
		} catch (RuntimeException e) {
			log.warn("주소 검색 실패 {}: {}", address, e.getMessage());
		}
		return null;
	}

	// ---------------------------------------------------------------- load: CSV → DB

	public void load() throws IOException {
		List<String[]> rows = Csv.read(dir.resolve(FILE));
		Timestamp now = Timestamp.from(Instant.now());
		tx.executeWithoutResult(status -> {
			jdbc.update("DELETE FROM parking_lot");
			jdbc.batchUpdate("INSERT INTO parking_lot (source, source_key, name, kind, addr, lat, lng, coord_source,"
					+ " total_spaces, fee_type, basic_min, basic_fee, add_min, add_fee, day_max_fee, fee_1h,"
					+ " weekday_open, weekday_close, sat_open, sat_close, holiday_open, holiday_close, phone, ref_date,"
					+ " synced_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
					rows.stream().map(r -> new Object[] { r[0], r[1], r[2], r[3], r[4], new BigDecimal(r[5]),
							new BigDecimal(r[6]), r[7], intOrNull(r[8]), r[9], intOrNull(r[10]), intOrNull(r[11]),
							intOrNull(r[12]), intOrNull(r[13]), intOrNull(r[14]), intOrNull(r[15]), r[16], r[17],
							r[18], r[19], r[20], r[21], r[22], r[23] == null ? null : Date.valueOf(r[23]), now })
							.toList());
		});
		log.info("DB 적재: 주차장 {}곳", rows.size());
	}

	// ---------------------------------------------------------------- 계산

	/**
	 * 정렬용 1시간 요금 (erd.md): 무료면 0. 유료면 기본 요금 + 남은 시간을 추가 단위로 올림해 추가 요금,
	 * 일 최대 요금이 있으면 그 이하. 추가 요금이 비었거나 0이면 기본 단위가 반복된다고 본다
	 * (서울 "5분 50원"처럼 기본만 적은 곳이 유료의 약 20%). 기본도 없으면 null ("싼 순"에서 맨 뒤)
	 */
	static Integer fee1h(String feeType, Integer basicMin, Integer basicFee, Integer addMin, Integer addFee,
			Integer dayMax) {
		if ("FREE".equals(feeType)) {
			return 0;
		}
		if (basicMin == null || basicMin <= 0 || basicFee == null) {
			return null;
		}
		if (addMin == null || addMin <= 0 || addFee == null || addFee <= 0) {
			addMin = basicMin;
			addFee = basicFee;
		}
		int fee = basicFee;
		int left = 60 - basicMin;
		if (left > 0) {
			fee += (left + addMin - 1) / addMin * addFee;
		}
		if (dayMax != null && dayMax > 0) {
			fee = Math.min(fee, dayMax);
		}
		return fee;
	}

	/** "08:00", "0800", 800.0 → "0800". 24:00까지 */
	static String hhmm(Object v) {
		String s = str(v).replace(":", "").trim();
		if (s.contains(".")) {
			s = s.substring(0, s.indexOf('.'));
		}
		if (!s.matches("\\d{3,4}")) {
			return null;
		}
		s = s.length() == 3 ? "0" + s : s;
		int h = Integer.parseInt(s.substring(0, 2));
		int m = Integer.parseInt(s.substring(2));
		return h > 24 || m > 59 ? null : s;
	}

	static String nameKey(String name) {
		return name.replaceAll("\\s|\\(.*?\\)", "");
	}

	private static boolean inKorea(double lat, double lng) {
		return lat > 33 && lat < 39 && lng > 124 && lng < 132;
	}

	private static double round6(double v) {
		return Math.round(v * 1_000_000d) / 1_000_000d;
	}

	private static String str(Object v) {
		return v == null ? "" : v.toString();
	}

	private static String blank(String s) {
		return s == null || s.isBlank() ? null : s.strip();
	}

	private static String cut(String s, int max) {
		return s == null || s.length() <= max ? s : s.substring(0, max);
	}

	private static Double dbl(Object v) {
		try {
			double d = Double.parseDouble(str(v).trim());
			return d == 0 ? null : d;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Integer num(Object v) {
		try {
			return (int) Math.round(Double.parseDouble(str(v).replace(",", "").trim()));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Integer positive(Integer v) {
		return v == null || v <= 0 ? null : v;
	}

	private static Integer intOrNull(String s) {
		return s == null ? null : Integer.valueOf(s);
	}

	private static void require(String key, String name) {
		if (key == null || key.isBlank()) {
			throw new IllegalStateException(name + "가 없습니다. server/.env에 넣어 주세요 (team-setup.md 4-5)");
		}
	}
}
