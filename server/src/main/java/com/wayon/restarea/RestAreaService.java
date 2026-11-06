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
