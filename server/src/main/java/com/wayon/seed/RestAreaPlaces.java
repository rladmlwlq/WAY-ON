package com.wayon.seed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 휴게소 안 장소(매장·주유소·충전소·화장실 등)를 카카오 검색 결과에서 골라 어느 휴게소(상행/하행) 것인지 정한다
 * (erd.md 휴게소 적재 규칙 "휴게소 지도"). 휴게소 지도 화면에 실제 위치대로 그리는 데 쓴다.
 *
 * <ol>
 * <li>이름에 휴게소 이름 앞부분(예: "매송")이 있어야 한다. 없으면 휴게소 밖 가게</li>
 * <li>이름에 "휴게소"·"주유소"·"충전소"가 없으면 150m 안에 있을 때만 (예: "CU E서울만남의광장점"은 되고 "세븐일레븐 경산대학로점"은 안 됨)</li>
 * <li>휴게소 자체(카테고리 고속도로휴게소), 주차장, 그 밖의 가게(옷·복권 등)는 뺀다</li>
 * <li>이름에 "{방향}방향"이 있으면 그 방향 휴게소, 없으면 상·하행 중 가까운 쪽 ("상행점·하행점"은 거리로 판단)</li>
 * <li>반대편 휴게소가 있는데 좌표가 없어 목록에 없으면(rest_area_geocode.csv의 SKIP), 이름에 그 방향이 있는 장소는 뺀다
 * (예: 지리산(광주)가 없으니 "…광주방향점"이 지리산(대구)에 붙지 않게)</li>
 * <li>정한 휴게소에서 350m 넘게 떨어지면 뺀다. 같은 종류가 25m 안에 겹치면 하나만 (전기차충전소 중복 등)</li>
 * </ol>
 */
public final class RestAreaPlaces {

	static final int NAME_FREE_RADIUS_M = 150;
	static final int MAX_DISTANCE_M = 350;
	static final int DUPLICATE_M = 25;

	/** 휴게소 1곳 (상행 또는 하행). direction은 "목포" 같은 방향 도시, 양방향이면 null */
	public record Area(String code, String name, String direction, double lat, double lng) {
	}

	/** 휴게소에 붙인 장소 1개 */
	public record Place(String kakaoId, String code, String name, String kind, double lat, double lng) {
	}

	private RestAreaPlaces() {
	}

	/** 지도에 그릴 종류. 그리지 않을 것이면 null */
	public static String kind(KakaoPlaceClient.Nearby p) {
		String cat = p.categoryName();
		String name = p.name();
		if (cat.endsWith("고속도로휴게소") || "PK6".equals(p.groupCode()) || name.contains("주차장")) {
			return null;
		}
		if (cat.contains("전기차") || cat.contains("수소") || name.contains("전기차충전") || name.contains("수소충전")) {
			return "EV";
		}
		if (cat.contains("화장실") || name.contains("화장실")) {
			return "TOILET";
		}
		return switch (p.groupCode()) {
			case "FD6" -> "FOOD";
			case "CE7" -> "CAFE";
			case "CS2" -> "STORE";
			case "OL7" -> "GAS";
			case "BK9" -> name.contains("365") || cat.contains("ATM") ? "ATM" : null;
			default -> null;
		};
	}

	/**
	 * 한 휴게소 쌍(같은 이름 앞부분, 1.5km 안)의 후보 장소를 각 휴게소에 나눈다.
	 *
	 * @param siblings 같은 이름 앞부분의 휴게소들 (상행·하행. 양방향이면 1개)
	 * @param candidates 각 휴게소 주변에서 받은 검색 결과를 합친 것 (중복 있어도 됨)
	 */
	public static List<Place> assign(List<Area> siblings, List<KakaoPlaceClient.Nearby> candidates) {
		return assign(siblings, java.util.Set.of(), candidates);
	}

	/** @param missingDirections 반대편이 있지만 목록에 없는 휴게소의 방향 도시 (예: "광주") */
	public static List<Place> assign(List<Area> siblings, java.util.Set<String> missingDirections,
			List<KakaoPlaceClient.Nearby> candidates) {
		String base = RestAreaGeocode.baseName(siblings.get(0).name());
		List<Place> out = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (KakaoPlaceClient.Nearby p : candidates) {
			if (!seen.add(p.id())) {
				continue;
			}
			String name = p.name().replace(" ", "");
			String kind = kind(p);
			if (kind == null || !name.contains(base)) {
				continue;
			}
			if (missingDirections.stream().anyMatch(d -> name.contains(d + "방향"))) {
				continue;
			}
			// 반대편이 빠진 한쪽 휴게소: 방향을 다른 말로 쓴 반대편 매장("담양방향" = 광주 쪽)도 섞일 수 있어 우리 방향이 아닌 방향 이름은 뺀다
			if (!missingDirections.isEmpty() && siblings.size() == 1 && hasOtherDirection(p.name(), siblings.get(0).direction())) {
				continue;
			}
			Area nearest = siblings.stream()
					.min(Comparator.comparingDouble(a -> meters(a.lat(), a.lng(), p.lat(), p.lng())))
					.orElseThrow();
			double nearestM = meters(nearest.lat(), nearest.lng(), p.lat(), p.lng());
			if (!(name.contains("휴게소") || name.contains("주유소") || name.contains("충전소")) && nearestM > NAME_FREE_RADIUS_M) {
				continue;
			}
			Area owner = siblings.stream()
					.filter(a -> a.direction() != null && name.contains(a.direction() + "방향"))
					.findFirst()
					.orElse(nearest);
			if (meters(owner.lat(), owner.lng(), p.lat(), p.lng()) > MAX_DISTANCE_M) {
				continue;
			}
			boolean duplicate = out.stream().anyMatch(o -> o.code().equals(owner.code()) && o.kind().equals(kind)
					&& meters(o.lat(), o.lng(), p.lat(), p.lng()) < DUPLICATE_M
					&& ("EV".equals(kind) || "TOILET".equals(kind) || "GAS".equals(kind)));
			if (!duplicate) {
				out.add(new Place(p.id(), owner.code(), p.name(), kind, p.lat(), p.lng()));
			}
		}
		return out;
	}

	private static final java.util.regex.Pattern DIRECTION_WORD = java.util.regex.Pattern.compile("([가-힣]+)방향");
	private static final java.util.regex.Pattern PAREN_WORD = java.util.regex.Pattern.compile("\\(([가-힣]+)\\)");

	/**
	 * 이름에 우리 방향이 아닌 방향 표시가 있으면 true. "{X}방향"(X가 우리 방향이 아님, "양방향"은 아님),
	 * "(X)"(X가 우리 방향이 아닌 말), "(상)"·"(하)"·"상행"·"하행"(어느 쪽인지 모름)
	 */
	static boolean hasOtherDirection(String name, String ours) {
		java.util.regex.Matcher m = DIRECTION_WORD.matcher(name);
		while (m.find()) {
			String w = m.group(1);
			if (!w.equals("양") && (ours == null || !w.endsWith(ours))) {
				return true;
			}
		}
		java.util.regex.Matcher paren = PAREN_WORD.matcher(name);
		while (paren.find()) {
			if (ours == null || !paren.group(1).equals(ours)) {
				return true;
			}
		}
		return name.contains("상행") || name.contains("하행");
	}

	/** 두 좌표 사이 거리 (m) */
	public static double meters(double lat1, double lng1, double lat2, double lng2) {
		double r = 6_371_000;
		double dLat = Math.toRadians(lat2 - lat1);
		double dLng = Math.toRadians(lng2 - lng1);
		double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
				* Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
		return 2 * r * Math.asin(Math.sqrt(a));
	}
}
