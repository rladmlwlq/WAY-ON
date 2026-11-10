package com.wayon.restarea;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.wayon.common.ApiException;

/**
 * 휴게소 정보 (N6). 다른 기능은 이 클래스만 부른다. 휴게소는 도로공사 코드("000001")로 구분한다.
 *
 * <ul>
 * <li>3-7 휴게소 상세, A1 restarea.info: {@link #detail(String)}, {@link #info}</li>
 * <li>3-6 경로 위 휴게소(F2): 후보는 {@link #locations()}, 행의 detail은 {@link #summaries}, {@link #restaurants}</li>
 * </ul>
 */
@Service
public class RestAreaService {

	/** 장소 extId 앞머리 (app-api.md 3-6). `ra:000001` */
	public static final String EXT_ID_PREFIX = "ra:";

	private static final Pattern CODE = Pattern.compile("\\d{6}");

	static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final RestAreaMapper mapper;
	private final Clock clock;

	@Autowired
	public RestAreaService(RestAreaMapper mapper) {
		this(mapper, Clock.system(KST));
	}

	RestAreaService(RestAreaMapper mapper, Clock clock) {
		this.mapper = mapper;
		this.clock = clock;
	}

	/** 3-7. 없거나 코드 형식이 아니면 404 PLACE_NOT_FOUND */
	public RestAreaDto.Detail detail(String restAreaId) {
		if (!isCode(restAreaId)) {
			throw ApiException.placeNotFound();
		}
		RestAreaRows.RestArea ra = mapper.findByCode(restAreaId);
		if (ra == null) {
			throw ApiException.placeNotFound();
		}
		List<String> codes = List.of(restAreaId);
		List<RestAreaRows.TopItem> top = mapper.findLatestTopItems(codes);
		LocalTime now = LocalTime.now(clock.withZone(KST));

		return new RestAreaDto.Detail(ra.stdRestCd(), ra.name(), ra.routeName(), ra.directionLabel(),
				ra.lat().doubleValue(), ra.lng().doubleValue(),
				top.stream().map(RestAreaService::toTopItem).toList(),
				top.isEmpty() ? null : top.get(0).baseMonth(),
				mapper.findBrands(restAreaId).stream()
						.map(b -> new RestAreaDto.Brand(b.brdName(), b.openTime(), b.closeTime(),
								OpenHours.isOpen(b.openTime(), b.closeTime(), now)))
						.toList(),
				mapper.findFacilities(codes).stream()
						.map(f -> new RestAreaDto.Facility(f.psCode(), f.name(), f.detail(),
								f.openTime(), f.closeTime(), f.source()))
						.toList());
	}

	/**
	 * 3-9 휴게소 지도. 카카오 장소(실제 위치)에 우리 매장 정보를 붙이고, 못 붙인 것은 건물 마커로 모은다.
	 * <ul>
	 * <li>0501 브랜드 ↔ 카카오 장소: 이름 앞 낱말로 ({@link StoreMatch}). 맞으면 영업시간·openNow</li>
	 * <li>판매순위 매장: 식당가 코너("한식", "푸드(…)" 등)는 식당가로, 그 밖은 카카오 장소 → 브랜드 → 위치 모름 순으로 붙임</li>
	 * </ul>
	 */
	public RestAreaDto.MapView map(String restAreaId) {
		if (!isCode(restAreaId)) {
			throw ApiException.placeNotFound();
		}
		RestAreaRows.RestArea ra = mapper.findByCode(restAreaId);
		if (ra == null) {
			throw ApiException.placeNotFound();
		}
		List<String> codes = List.of(restAreaId);
		LocalTime now = LocalTime.now(clock.withZone(KST));
		List<RestAreaRows.Place> places = mapper.findPlaces(restAreaId);
		List<RestAreaRows.Brand> brands = mapper.findBrands(restAreaId);
		List<RestAreaRows.TopItem> top = mapper.findLatestTopItems(codes);

		List<String> placeKeys = places.stream().map(p -> StoreMatch.placeKey(p.name())).toList();
		List<String> brandKeys = brands.stream().map(b -> StoreMatch.key(b.brdName())).toList();

		// 장소별 브랜드 (같은 브랜드는 한 장소에만)
		RestAreaRows.Brand[] brandOfPlace = new RestAreaRows.Brand[places.size()];
		boolean[] brandPlaced = new boolean[brands.size()];
		for (int i = 0; i < places.size(); i++) {
			if (!List.of("FOOD", "CAFE", "STORE").contains(places.get(i).kind())) {
				continue;
			}
			for (int j = 0; j < brands.size(); j++) {
				if (!brandPlaced[j] && StoreMatch.same(placeKeys.get(i), brandKeys.get(j))) {
					brandOfPlace[i] = brands.get(j);
					brandPlaced[j] = true;
					break;
				}
			}
		}

		// 판매순위 나누기
		java.util.Map<Integer, List<RestAreaDto.TopItem>> topOfPlace = new java.util.HashMap<>();
		java.util.Map<Integer, List<RestAreaDto.TopItem>> topOfBrand = new java.util.HashMap<>();
		java.util.Map<String, List<RestAreaDto.TopItem>> topOfUnknown = new LinkedHashMap<>();
		List<RestAreaDto.RankedItem> foodCourtTop = new java.util.ArrayList<>();
		// 매장 이름 → 지도 위 매장 ID (식당가 코너도 카카오에 따로 있으면 위치를 알려 줄 수 있음)
		java.util.function.Function<String, String> placeIdOf = store -> {
			if (store == null) {
				return null;
			}
			String k = StoreMatch.key(store);
			for (int i = 0; i < places.size(); i++) {
				if (List.of("FOOD", "CAFE", "STORE").contains(places.get(i).kind())
						&& StoreMatch.same(k, placeKeys.get(i))) {
					return "kakao:" + places.get(i).kakaoId();
				}
			}
			return null;
		};
		for (RestAreaRows.TopItem t : top) {
			RestAreaDto.TopItem item = toTopItem(t);
			if (StoreMatch.isFoodCourt(t.storeName())) {
				String store = StoreMatch.storeLabel(t.storeName());
				foodCourtTop.add(new RestAreaDto.RankedItem(t.rankNo(), t.displayName(), store, placeIdOf.apply(store),
						t.price()));
				continue;
			}
			String key = StoreMatch.storeKey(t.storeName());
			int p = StoreMatch.find(key, placeKeys);
			if (p >= 0 && List.of("FOOD", "CAFE", "STORE").contains(places.get(p).kind())) {
				topOfPlace.computeIfAbsent(p, k -> new java.util.ArrayList<>()).add(item);
				continue;
			}
			int b = StoreMatch.find(key, brandKeys);
			if (b >= 0) {
				topOfBrand.computeIfAbsent(b, k -> new java.util.ArrayList<>()).add(item);
			} else {
				topOfUnknown.computeIfAbsent(t.storeName(), k -> new java.util.ArrayList<>()).add(item);
			}
		}

		List<RestAreaDto.MapPlace> mapPlaces = new java.util.ArrayList<>();
		for (int i = 0; i < places.size(); i++) {
			RestAreaRows.Place p = places.get(i);
			RestAreaRows.Brand b = brandOfPlace[i];
			List<RestAreaDto.TopItem> items = new java.util.ArrayList<>(topOfPlace.getOrDefault(i, List.of()));
			if (b != null) {
				items.addAll(topOfBrand.getOrDefault(brands.indexOf(b), List.of()));
			}
			mapPlaces.add(new RestAreaDto.MapPlace("kakao:" + p.kakaoId(), p.name(), p.kind(),
					p.lat().doubleValue(), p.lng().doubleValue(),
					b == null ? null : b.openTime(), b == null ? null : b.closeTime(),
					b == null ? null : OpenHours.isOpen(b.openTime(), b.closeTime(), now), items));
		}

		List<RestAreaDto.Store> otherStores = new java.util.ArrayList<>();
		for (int j = 0; j < brands.size(); j++) {
			if (!brandPlaced[j]) {
				RestAreaRows.Brand b = brands.get(j);
				otherStores.add(new RestAreaDto.Store(b.brdName(), b.openTime(), b.closeTime(),
						OpenHours.isOpen(b.openTime(), b.closeTime(), now), topOfBrand.getOrDefault(j, List.of())));
			}
		}
		topOfUnknown.forEach((store, items) -> otherStores.add(new RestAreaDto.Store(store, null, null, null, items)));

		List<RestAreaDto.Menu> menus = mapper.findMenus(restAreaId).stream()
				.map(m -> {
					String[] storeAndName = StoreMatch.menuStore(m.foodName());
					return new RestAreaDto.Menu(storeAndName[1], storeAndName[0], placeIdOf.apply(storeAndName[0]),
							m.price(), m.best(), m.recommended(), m.premium());
				})
				.toList();
		List<RestAreaDto.Facility> facilities = mapper.findFacilities(codes).stream()
				.map(f -> new RestAreaDto.Facility(f.psCode(), f.name(), f.detail(), f.openTime(), f.closeTime(),
						f.source()))
				.toList();

		return new RestAreaDto.MapView(ra.stdRestCd(), ra.name(), ra.directionLabel(), ra.lat().doubleValue(),
				ra.lng().doubleValue(), mapPlaces,
				new RestAreaDto.Building(new RestAreaDto.FoodCourt(menus, foodCourtTop), otherStores, facilities),
				top.isEmpty() ? null : top.get(0).baseMonth());
	}

	/** waypoint_request.place_ext_id(`ra:000001`)로 상세. 휴게소가 아니면 404 */
	public RestAreaDto.Detail detailByExtId(String extId) {
		return detail(parseExtId(extId).orElseThrow(ApiException::placeNotFound));
	}

	/**
	 * A1 restarea.info의 data (app-api.md 5-9, 7장). LEFT는 restArea 없이 닫기만.
	 * WebSocket에서 restarea.approach/arrived/left를 받으면 requestId로 찾은 place_ext_id를 넘긴다.
	 */
	public RestAreaDto.Info info(RestAreaDto.Phase phase, String placeExtId) {
		if (phase == RestAreaDto.Phase.LEFT) {
			return new RestAreaDto.Info(phase, null);
		}
		return new RestAreaDto.Info(phase, detailByExtId(placeExtId));
	}

	/** 경로 위 휴게소 후보 (199곳). 경로선과 거리·진행 방향으로 거르는 건 3-6 쪽에서 */
	public List<RestAreaDto.Location> locations() {
		return mapper.findAll().stream()
				.map(r -> new RestAreaDto.Location(r.stdRestCd(), r.name(), r.routeName(), r.directionLabel(),
						r.lat().doubleValue(), r.lng().doubleValue(), r.travelBearing()))
				.toList();
	}

	/** 3-6 REST_AREA 행의 detail. 넘긴 코드 순서대로, 없는 코드는 빠짐 */
	public Map<String, RestAreaDto.Summary> summaries(Collection<String> restAreaIds) {
		if (restAreaIds.isEmpty()) {
			return Map.of();
		}
		Map<String, RestAreaRows.RestArea> areas = mapper.findByCodes(restAreaIds).stream()
				.collect(Collectors.toMap(RestAreaRows.RestArea::stdRestCd, r -> r));
		Map<String, String> top1 = mapper.findLatestTopItems(restAreaIds).stream()
				.filter(t -> t.rankNo() == 1)
				.collect(Collectors.toMap(RestAreaRows.TopItem::stdRestCd, RestAreaRows.TopItem::displayName));
		Map<String, List<RestAreaRows.Facility>> facilities = mapper.findFacilities(restAreaIds).stream()
				.collect(Collectors.groupingBy(RestAreaRows.Facility::stdRestCd));

		Map<String, RestAreaDto.Summary> result = new LinkedHashMap<>();
		for (String id : restAreaIds) {
			RestAreaRows.RestArea ra = areas.get(id);
			if (ra == null) {
				continue;
			}
			List<RestAreaRows.Facility> fs = facilities.getOrDefault(id, List.of());
			result.put(id, new RestAreaDto.Summary(ra.routeName(), ra.directionLabel(), top1.get(id),
					// 아이콘용 코드. 휴게소·주유소에 같은 시설이 있으면 한 번만
					fs.stream().map(RestAreaRows.Facility::psCode).distinct().sorted().toList(),
					// 0504(휴게소 주유소) 행이 있으면 주유소가 있는 휴게소
					fs.stream().anyMatch(f -> "GAS_STATION".equals(f.source()))));
		}
		return result;
	}

	/** 3-6 RESTAURANT(고속도로) 행의 detail. Top5가 없는 휴게소는 빠짐 */
	public Map<String, RestAreaDto.Restaurant> restaurants(Collection<String> restAreaIds) {
		if (restAreaIds.isEmpty()) {
			return Map.of();
		}
		Map<String, String> names = mapper.findByCodes(restAreaIds).stream()
				.collect(Collectors.toMap(RestAreaRows.RestArea::stdRestCd, RestAreaRows.RestArea::name));
		Map<String, List<RestAreaRows.TopItem>> top = mapper.findLatestTopItems(restAreaIds).stream()
				.collect(Collectors.groupingBy(RestAreaRows.TopItem::stdRestCd));

		Map<String, RestAreaDto.Restaurant> result = new LinkedHashMap<>();
		for (String id : restAreaIds) {
			List<RestAreaRows.TopItem> items = top.get(id);
			if (names.containsKey(id) && items != null) {
				result.put(id, new RestAreaDto.Restaurant(names.get(id),
						items.stream().map(RestAreaService::toTopItem).toList()));
			}
		}
		return result;
	}

	/** 휴게소 하나의 이름·좌표 (경유지 요청 6-1). 없는 코드면 null */
	public RestAreaDto.Location location(String code) {
		RestAreaRows.RestArea r = isCode(code) ? mapper.findByCode(code) : null;
		return r == null ? null
				: new RestAreaDto.Location(r.stdRestCd(), r.name(), r.routeName(), r.directionLabel(),
						r.lat().doubleValue(), r.lng().doubleValue(), r.travelBearing());
	}

	/** 카카오 장소 ID → 그 매장이 있는 휴게소 코드. 휴게소 안 매장이 아니면 빠짐 */
	public Map<String, String> restAreaCodesByKakaoId(Collection<String> kakaoIds) {
		if (kakaoIds.isEmpty()) {
			return Map.of();
		}
		return mapper.findByKakaoIds(kakaoIds).stream()
				.collect(Collectors.toMap(RestAreaRows.KakaoMatch::kakaoId, RestAreaRows.KakaoMatch::stdRestCd,
						(a, b) -> a));
	}

	/** `ra:000001` → "000001". 형식이 다르면 비어 있음 */
	public static Optional<String> parseExtId(String extId) {
		if (extId == null || !extId.startsWith(EXT_ID_PREFIX)) {
			return Optional.empty();
		}
		String code = extId.substring(EXT_ID_PREFIX.length());
		return isCode(code) ? Optional.of(code) : Optional.empty();
	}

	static boolean isCode(String s) {
		return s != null && CODE.matcher(s).matches();
	}

	private static RestAreaDto.TopItem toTopItem(RestAreaRows.TopItem t) {
		return new RestAreaDto.TopItem(t.rankNo(), t.displayName(), t.storeName(), t.price());
	}
}
