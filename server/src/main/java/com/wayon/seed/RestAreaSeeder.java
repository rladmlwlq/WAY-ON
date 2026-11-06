package com.wayon.seed;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 휴게소 기준 데이터 적재 (erd.md "휴게소 데이터 적재 규칙", api.md 1·2·0501~0504).
 *
 * <ul>
 * <li>{@link #fetch()}: 도로공사 API → CSV (도로공사 키가 있는 사람이 가끔 한 번)</li>
 * <li>{@link #load()}: CSV → 로컬 DB (키 없이 누구나. CSV는 git으로 공유)</li>
 * </ul>
 * 판매순위 Top5(api.md 10)는 파일을 받으면 따로 넣는다.
 */
@Component
public class RestAreaSeeder {

	private static final Logger log = LoggerFactory.getLogger(RestAreaSeeder.class);

	static final String REST_AREA = "rest_area.csv";
	static final String OVERRIDE = "rest_area_bearing_override.csv";
	static final String FACILITY = "facility.csv";
	static final String BRAND = "rest_area_brand.csv";
	static final String MENU = "rest_area_menu.csv";
	static final String TOP_ITEM = "rest_area_top_item.csv";
	/** 1번 API에 없어 카카오로 좌표를 찾은 결과 (적재에는 안 씀, 검토용) */
	static final String GEOCODE_LOG = "rest_area_geocode.csv";

	/** Top5에서 뺄 편의점 매장 (erd.md 휴게소 적재 규칙) */
	private static final List<String> CONVENIENCE = List.of("편의점", "CU", "GS25", "이마트24", "세븐");
	/** Top5에서 뺄 상품권 */
	private static final List<String> NOT_FOOD = List.of("하나)에코100000");

	private final ExApiClient ex;
	private final KakaoPlaceClient kakao;
	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final Path dir;

	public RestAreaSeeder(ExApiClient ex, KakaoPlaceClient kakao, JdbcTemplate jdbc, TransactionTemplate tx,
			@Value("${wayon.seed.dir:src/main/resources/db/seed}") String dir) {
		this.ex = ex;
		this.kakao = kakao;
		this.jdbc = jdbc;
		this.tx = tx;
		this.dir = Path.of(dir);
	}

	// ---------------------------------------------------------------- fetch: API → CSV

	public void fetch() throws IOException {
		List<Map<String, Object>> loc = ex.fetchAll("locationinfo/locationinfoRest");
		List<Map<String, Object>> route = ex.fetchAll("business/serviceAreaRoute");
		log.info("도로공사 1번 {}건, 2번 {}건", loc.size(), route.size());

		// 1번과 2번은 serviceAreaCode로 연결 (2번에서 방향 이름을 가져옴)
		Map<String, Map<String, Object>> routeByCode = new HashMap<>();
		for (Map<String, Object> r : route) {
			String code = str(r, "serviceAreaCode");
			if (code != null) {
				routeByCode.putIfAbsent(code, r);
			}
		}

		Map<String, List<Object>> areas = new LinkedHashMap<>(); // std_rest_cd → 행
		Map<String, String> stdByNameKey = new HashMap<>();
		int shelters = 0;
		int noBearing = 0;
		for (Map<String, Object> l : loc) {
			String name = str(l, "unitName");
			String std = str(l, "stdRestCd");
			if (name == null || std == null) {
				continue;
			}
			if (RestAreaNames.isShelter(name)) {
				shelters++;
				continue;
			}
			// 방향: 이름 괄호 안을 먼저 (2번 API 방향이 이름과 반대인 휴게소가 있음, 예: 충주(양평))
			Map<String, Object> r = routeByCode.get(str(l, "serviceAreaCode"));
			String direction = RestAreaNames.directionInName(name);
			if (direction == null && r != null) {
				direction = str(r, "direction");
			}
			double lat = Double.parseDouble(str(l, "yValue"));
			double lng = Double.parseDouble(str(l, "xValue"));
			Integer bearing = TravelBearing.toward(direction, str(l, "routeName"), lat, lng);
			if (bearing == null) {
				noBearing++;
				log.info("방위각 없음 (양방향 또는 도시 좌표 없음): {} 방향={}", name, direction);
			}
			String nameKey = RestAreaNames.nameKey(name);
			areas.put(std, row(std, name, nameKey, str(l, "routeName"),
					direction == null ? "양방향" : direction + "방향", lat, lng, bearing));
			stdByNameKey.putIfAbsent(nameKey, std);
		}
		log.info("휴게소 {}곳 (쉼터 {}곳 제외, 방위각 없음 {}곳)", areas.size(), shelters, noBearing);

		List<Map<String, Object>> conv = ex.fetchAll("restinfo/restConvList"); // 0503
		List<Map<String, Object>> brandRows = ex.fetchAll("restinfo/restBrandList"); // 0501
		List<Map<String, Object>> menuRows = ex.fetchAll("restinfo/restBestfoodList"); // 0502

		// 1번에 없는 휴게소를 2번·0501~0503에서 찾아 카카오 좌표로 더한다 (확실한 것만)
		List<List<Object>> geocodeLog = addMissing(areas, stdByNameKey, route, List.of(conv, brandRows, menuRows));

		List<List<Object>> facilities = new ArrayList<>();
		for (Map<String, Object> f : conv) { // 0503
			String std = str(f, "stdRestCd");
			if (areas.containsKey(std)) {
				facilities.add(row(std, "REST_AREA", str(f, "psCode"), str(f, "psName"), str(f, "psDesc"),
						time(f, "stime"), time(f, "etime")));
			}
		}
		int oilMatched = 0;
		for (Map<String, Object> f : ex.fetchOnce("restinfo/restOilList")) { // 0504: 주유소 → 이름으로 휴게소 찾기
			String std = stdByNameKey.get(RestAreaNames.nameKey(str(f, "stdRestNm")));
			if (std != null) {
				oilMatched++;
				facilities.add(row(std, "GAS_STATION", str(f, "psCode"), str(f, "psName"), str(f, "psDesc"),
						time(f, "stime"), time(f, "etime")));
			}
		}

		Map<String, List<Object>> brands = new LinkedHashMap<>();
		for (Map<String, Object> b : brandRows) { // 0501
			String std = str(b, "stdRestCd");
			if (areas.containsKey(std)) {
				brands.putIfAbsent(std + "|" + str(b, "brdCode"), row(std, str(b, "brdCode"), str(b, "brdName"),
						str(b, "brdDesc"), time(b, "stime"), time(b, "etime")));
			}
		}

		Map<String, List<Object>> menus = new LinkedHashMap<>();
		for (Map<String, Object> m : menuRows) { // 0502
			String std = str(m, "stdRestCd");
			String food = str(m, "foodNm");
			if (areas.containsKey(std) && food != null) {
				menus.putIfAbsent(std + "|" + str(m, "seq"), row(std, str(m, "seq"), food,
						RestAreaNames.menuKey(food), price(str(m, "foodCost")), yn(m, "recommendyn"),
						yn(m, "bestfoodyn"), yn(m, "premiumyn"), str(m, "seasonMenu")));
			}
		}

		Csv.write(dir.resolve(REST_AREA), List.of("std_rest_cd", "name", "name_key", "route_name",
				"direction_label", "lat", "lng", "travel_bearing"), new ArrayList<>(areas.values()));
		Csv.write(dir.resolve(FACILITY), List.of("std_rest_cd", "source", "ps_code", "name", "detail",
				"open_time", "close_time"), facilities);
		Csv.write(dir.resolve(BRAND), List.of("std_rest_cd", "brd_code", "brd_name", "detail", "open_time",
				"close_time"), new ArrayList<>(brands.values()));
		Csv.write(dir.resolve(MENU), List.of("std_rest_cd", "seq", "food_name", "name_key", "price",
				"recommended", "best", "premium", "season"), new ArrayList<>(menus.values()));
		Csv.write(dir.resolve(GEOCODE_LOG), List.of("std_rest_cd", "name", "route_name", "ex_address", "result",
				"reason", "kakao_place", "kakao_address", "lat", "lng"), geocodeLog);
		if (!Files.exists(dir.resolve(OVERRIDE))) {
			Csv.write(dir.resolve(OVERRIDE), List.of("std_rest_cd", "travel_bearing", "memo"), List.of());
		}
		log.info("CSV 저장 {}: 휴게소 {}, 편의시설 {} (주유소 {}), 매장 {}, 메뉴 {}", dir.toAbsolutePath(),
				areas.size(), facilities.size(), oilMatched, brands.size(), menus.size());
	}

	/**
	 * 1번 API(좌표)에 없는 휴게소를 더한다. 이름·노선·주소·방향은 2번과 0501~0503에서, 좌표는 카카오 키워드 검색에서.
	 * 좌표를 확실히 못 고르면 넣지 않는다 ({@link RestAreaGeocode#pick}). 결정은 모두 rest_area_geocode.csv에 남긴다.
	 *
	 * @return rest_area_geocode.csv 행
	 */
	private List<List<Object>> addMissing(Map<String, List<Object>> areas, Map<String, String> stdByNameKey,
			List<Map<String, Object>> route, List<List<Map<String, Object>>> others) {
		// 코드 → {이름, 노선, 주소}. 0501~0503을 먼저: 편의시설·매장·메뉴가 붙는 코드가 이쪽이다.
		// 2번은 같은 휴게소를 옛 코드로 주는 곳이 있다 (논공: 2번 000399, 0503 000446. 2026-11-06 확인)
		Map<String, String[]> candidates = new LinkedHashMap<>();
		Map<String, String> codeByKey = new HashMap<>();
		for (List<Map<String, Object>> list : others) {
			for (Map<String, Object> r : list) {
				String std = str(r, "stdRestCd");
				String name = str(r, "stdRestNm");
				if (std != null && name != null) {
					candidates.putIfAbsent(std, new String[] { name, str(r, "routeNm"), str(r, "svarAddr") });
					codeByKey.putIfAbsent(RestAreaNames.nameKey(name), std);
				}
			}
		}
		List<List<Object>> logRows = new ArrayList<>();
		for (Map<String, Object> r : route) {
			String std = str(r, "serviceAreaCode2");
			String name = str(r, "serviceAreaName");
			if (std == null || name == null || areas.containsKey(std)) {
				continue;
			}
			String same = codeByKey.get(RestAreaNames.nameKey(name));
			if (same == null) {
				candidates.putIfAbsent(std, new String[] { name, str(r, "routeName"), str(r, "svarAddr") });
			} else {
				if (candidates.get(same)[2] == null) {
					candidates.get(same)[2] = str(r, "svarAddr");
				}
				if (!same.equals(std)) {
					logRows.add(row(std, name, str(r, "routeName"), str(r, "svarAddr"), "SKIP",
							"같은 휴게소의 2번 API 코드. 0501~0503 코드 " + same + "를 씀", null, null, null, null));
				}
			}
		}

		if (!kakao.hasKey()) {
			log.warn("KAKAO_REST_KEY가 없어 1번 API에 없는 휴게소를 더하지 않습니다");
			return logRows;
		}
		int added = 0;
		for (Map.Entry<String, String[]> e : candidates.entrySet()) {
			String std = e.getKey();
			if (areas.containsKey(std)) {
				continue;
			}
			String name = RestAreaNames.normalizeParen(e.getValue()[0]).trim();
			// 2번은 노선을 "논산천안선,호남선"처럼 여러 개 줄 때가 있다. 다른 행과 맞춰 첫 번째만
			String routeName = e.getValue()[1] == null ? null : e.getValue()[1].split(",")[0].trim();
			String address = e.getValue()[2];
			String nameKey = RestAreaNames.nameKey(name);
			String skip = null;
			if (!RestAreaGeocode.isRestAreaName(name)) {
				skip = "휴게소 이름이 아님 (쉼터·주차장·운영사별 이름)";
			} else if (stdByNameKey.containsKey(nameKey)) {
				skip = "같은 이름이 이미 있음 (" + stdByNameKey.get(nameKey) + ")";
			} else if (routeName == null) {
				skip = "노선명 없음";
			}
			if (skip != null) {
				logRows.add(row(std, name, routeName, address, "SKIP", skip, null, null, null, null));
				continue;
			}

			// 이름에 방향 괄호가 없으면 양방향 휴게소로 보고, 카카오에서도 양방향(또는 방향 없음)인 것만 쓴다
			String direction = RestAreaNames.directionInName(name);
			List<KakaoPlaceClient.Place> places = kakao.search(RestAreaGeocode.baseName(name) + "휴게소");
			RestAreaGeocode.Result res = RestAreaGeocode.pick(places, name, direction, address);
			if (!res.ok()) {
				logRows.add(row(std, name, routeName, address, "SKIP", res.reason(), null, null, null, null));
				continue;
			}
			KakaoPlaceClient.Place p = res.place();
			BigDecimal lat = BigDecimal.valueOf(p.lat()).setScale(6, java.math.RoundingMode.HALF_UP);
			BigDecimal lng = BigDecimal.valueOf(p.lng()).setScale(6, java.math.RoundingMode.HALF_UP);
			areas.put(std, row(std, name, nameKey, routeName, direction == null ? "양방향" : direction + "방향",
					lat, lng, TravelBearing.toward(direction, routeName, lat.doubleValue(), lng.doubleValue())));
			stdByNameKey.putIfAbsent(nameKey, std);
			added++;
			String kakaoAddr = p.roadAddressName().isEmpty() ? p.addressName() : p.roadAddressName();
			logRows.add(row(std, name, routeName, address, "ADDED", null, p.placeName(), kakaoAddr, lat, lng));
		}
		log.info("1번 API에 없던 휴게소: 후보 {}곳 중 {}곳 추가 (자세한 결과 {})", logRows.size(), added, GEOCODE_LOG);
		return logRows;
	}

	// ---------------------------------------------------------------- Top5: 판매순위 파일 → CSV

	/**
	 * 도로공사 "휴게소별 상품 월별 판매 상위 5" 파일(api.md 10)을 정리해 rest_area_top_item.csv에 넣는다.
	 * 받은 파일이 gzip이거나 CP949여도 읽는다. 같은 달 행은 바꾸고 다른 달은 남긴다.
	 * 휴게소는 이름으로 연결하므로 rest_area.csv가 먼저 있어야 한다.
	 */
	public void importTop5(Path source) throws IOException {
		Map<String, String> stdByNameKey = new HashMap<>();
		for (String[] r : Csv.read(dir.resolve(REST_AREA))) {
			stdByNameKey.putIfAbsent(r[2], r[0]);
		}

		Path utf8 = Files.createTempFile("top5", ".csv");
		try {
			Files.writeString(utf8, SourceFiles.readText(source), java.nio.charset.StandardCharsets.UTF_8);
			List<String[]> raw = Csv.read(utf8);

			// 휴게소별로 원래 순위대로 모은 뒤, 편의점·상품권을 빼고 1위부터 다시 매김
			Map<String, List<String[]>> byArea = new LinkedHashMap<>();
			int dropped = 0;
			for (String[] r : raw) {
				// 기준년월,휴게소내판매순위,휴게소코드,휴게소명,매장코드,매장명,판매상품SEQ,판매상품명
				if (r.length < 8 || r[3] == null || r[7] == null) {
					continue;
				}
				String store = r[5] == null ? "" : r[5];
				if (CONVENIENCE.stream().anyMatch(store::contains) || NOT_FOOD.stream().anyMatch(r[7]::contains)) {
					dropped++;
					continue;
				}
				String std = stdByNameKey.get(RestAreaNames.nameKey(r[3]));
				if (std != null) {
					byArea.computeIfAbsent(r[0] + "|" + std, k -> new ArrayList<>()).add(r);
				}
			}

			Map<String, List<Object>> rows = new LinkedHashMap<>(); // base_month|std|rank → 행
			java.util.Set<String> months = new java.util.HashSet<>();
			for (Map.Entry<String, List<String[]>> e : byArea.entrySet()) {
				String std = e.getKey().split("\\|")[1];
				List<String[]> items = new ArrayList<>(e.getValue());
				items.sort(java.util.Comparator.comparingInt(r -> Integer.parseInt(r[1].trim())));
				int rank = 1;
				for (String[] r : items) {
					if (rank > 5) {
						break;
					}
					months.add(r[0]);
					rows.put(r[0] + "|" + std + "|" + rank,
							row(std, r[0], rank, r[5], r[7], RestAreaNames.withoutPrefix(r[7])));
					rank++;
				}
			}

			// 기존 CSV에서 다른 달은 남김
			Path out = dir.resolve(TOP_ITEM);
			List<List<Object>> merged = new ArrayList<>();
			if (Files.exists(out)) {
				for (String[] r : Csv.read(out)) {
					if (!months.contains(r[1])) {
						merged.add(row((Object[]) r));
					}
				}
			}
			merged.addAll(rows.values());
			Csv.write(out, List.of("std_rest_cd", "base_month", "rank_no", "store_name", "item_name",
					"display_name"), merged);
			log.info("Top5 {}: 원본 {}행, 편의점·상품권 제외 {}행, 휴게소 {}곳 연결, {}행 저장", months, raw.size(),
					dropped, byArea.size(), rows.size());
		} finally {
			Files.deleteIfExists(utf8);
		}
	}

	// ---------------------------------------------------------------- load: CSV → DB

	public void load() throws IOException {
		List<String[]> areas = Csv.read(dir.resolve(REST_AREA));
		Map<String, Integer> override = new HashMap<>();
		Path overrideFile = dir.resolve(OVERRIDE);
		if (Files.exists(overrideFile)) {
			for (String[] r : Csv.read(overrideFile)) {
				if (r.length >= 2 && r[0] != null && r[1] != null) {
					override.put(r[0], Integer.valueOf(r[1].trim()));
				}
			}
		}
		List<String[]> facilities = Csv.read(dir.resolve(FACILITY));
		List<String[]> brands = Csv.read(dir.resolve(BRAND));
		List<String[]> menus = Csv.read(dir.resolve(MENU));
		Path topFile = dir.resolve(TOP_ITEM);
		List<String[]> tops = Files.exists(topFile) ? Csv.read(topFile) : List.of();
		int[] priced = { 0 };
		Timestamp now = Timestamp.from(Instant.now());

		tx.executeWithoutResult(status -> {
			// 자식 테이블(편의시설·매장·메뉴·Top5)은 ON DELETE CASCADE로 같이 지워진다
			jdbc.update("DELETE FROM rest_area");
			jdbc.batchUpdate("INSERT INTO rest_area (std_rest_cd, name, name_key, route_name, direction_label,"
					+ " lat, lng, travel_bearing, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
					areas.stream().map(r -> new Object[] { r[0], r[1], r[2], r[3], r[4], new BigDecimal(r[5]),
							new BigDecimal(r[6]), override.containsKey(r[0]) ? override.get(r[0]) : intOrNull(r[7]),
							now }).toList());

			// 휴게소 CSV에 없는 코드의 행은 넣지 않는다 (외래 키)
			java.util.Set<String> codes = new java.util.HashSet<>();
			areas.forEach(r -> codes.add(r[0]));

			jdbc.batchUpdate("INSERT INTO facility (std_rest_cd, source, ps_code, name, detail, open_time,"
					+ " close_time) VALUES (?, ?, ?, ?, ?, ?, ?)",
					facilities.stream().filter(r -> codes.contains(r[0]))
							.map(r -> new Object[] { r[0], r[1], r[2], r[3], r[4], r[5], r[6] })
							.toList());
			jdbc.batchUpdate("INSERT INTO rest_area_brand (std_rest_cd, brd_code, brd_name, detail, open_time,"
					+ " close_time) VALUES (?, ?, ?, ?, ?, ?)",
					brands.stream().filter(r -> codes.contains(r[0]))
							.map(r -> new Object[] { r[0], r[1], r[2], r[3], r[4], r[5] }).toList());
			jdbc.batchUpdate("INSERT INTO rest_area_menu (std_rest_cd, seq, food_name, name_key, price,"
					+ " recommended, best, premium, season) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
					menus.stream().filter(r -> codes.contains(r[0]))
							.map(r -> new Object[] { r[0], r[1], r[2], r[3], intOrNull(r[4]),
									Boolean.parseBoolean(r[5]), Boolean.parseBoolean(r[6]),
									Boolean.parseBoolean(r[7]), r[8] })
							.toList());

			// Top5: 같은 휴게소 메뉴 중 이름이 맞으면 menu_id로 가격 연결 (erd.md, 약 38%)
			Map<String, Long> menuIdByKey = new HashMap<>(); // std_rest_cd|name_key → menu id
			jdbc.query("SELECT id, std_rest_cd, name_key FROM rest_area_menu ORDER BY id",
					rs -> {
						menuIdByKey.putIfAbsent(rs.getString("std_rest_cd") + "|" + rs.getString("name_key"),
								rs.getLong("id"));
					});
			jdbc.batchUpdate("INSERT INTO rest_area_top_item (std_rest_cd, base_month, rank_no, store_name,"
					+ " item_name, display_name, menu_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
					tops.stream().filter(r -> codes.contains(r[0])).map(r -> {
						String areaId = r[0];
						Long menuId = menuIdByKey.get(areaId + "|" + RestAreaNames.menuKey(r[5]));
						if (menuId != null) {
							priced[0]++;
						}
						return new Object[] { areaId, r[1], Integer.valueOf(r[2]), r[3], r[4], r[5], menuId };
					}).toList());
		});
		log.info("DB 적재: 휴게소 {}, 편의시설 {}, 매장 {}, 메뉴 {}, Top5 {} (가격 연결 {}) (방위각 직접 입력 {}곳)",
				areas.size(), facilities.size(), brands.size(), menus.size(), tops.size(), priced[0],
				override.size());
	}

	// ---------------------------------------------------------------- helpers

	private static List<Object> row(Object... values) {
		List<Object> list = new ArrayList<>(values.length);
		for (Object v : values) {
			list.add(v);
		}
		return list;
	}

	static String str(Map<String, Object> m, String key) {
		Object v = m.get(key);
		if (v == null) {
			return null;
		}
		String s = v.toString().trim();
		return s.isEmpty() ? null : s;
	}

	/** HH:mm만 받는다 (CHAR(5)) */
	private static String time(Map<String, Object> m, String key) {
		String s = str(m, key);
		return s != null && s.matches("\\d{2}:\\d{2}") ? s : null;
	}

	private static boolean yn(Map<String, Object> m, String key) {
		return "Y".equalsIgnoreCase(str(m, key));
	}

	/** "9,000" 같은 값도 숫자만 뽑는다. 없으면 null */
	static Integer price(String s) {
		if (s == null) {
			return null;
		}
		String digits = s.replaceAll("[^0-9]", "");
		return digits.isEmpty() ? null : Integer.valueOf(digits);
	}

	private static Integer intOrNull(String s) {
		return s == null || s.isBlank() ? null : Integer.valueOf(s.trim());
	}
}
