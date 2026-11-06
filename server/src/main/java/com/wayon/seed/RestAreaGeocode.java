package com.wayon.seed;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 도로공사 1번 API에 없는 휴게소의 좌표를 카카오 검색 결과에서 고른다 (erd.md 휴게소 적재 규칙).
 * 틀린 좌표를 넣는 것보다 빼는 게 낫기 때문에, 아래를 모두 만족하는 결과가 **딱 1개**일 때만 쓴다.
 *
 * <ol>
 * <li>카테고리가 "고속도로휴게소"</li>
 * <li>장소 이름이 우리 이름의 앞부분(괄호·"휴게소" 뺀 것)으로 시작</li>
 * <li>방향이 있으면 장소 이름에 "{방향}방향"이 있음. 방향이 없으면 다른 방향 이름이 없음 ("양방향"은 됨)</li>
 * <li>도로공사 주소의 시·군·구가 카카오 주소에 있음</li>
 * </ol>
 */
public final class RestAreaGeocode {

	private static final Pattern SIGUNGU = Pattern.compile("^\\S+?(시|군|구)$");
	private static final String CATEGORY = "고속도로휴게소";

	/** 고른 결과. 못 고르면 place가 null이고 reason에 이유 */
	public record Result(KakaoPlaceClient.Place place, String reason) {

		public boolean ok() {
			return place != null;
		}
	}

	private RestAreaGeocode() {
	}

	/** 적재 대상인 이름인지. 쉼터·주차장·운영사별로 쪼갠 이름("문경(양평)휴-건영식품")은 뺀다 */
	public static boolean isRestAreaName(String name) {
		String n = RestAreaNames.normalizeParen(name).trim();
		return n.endsWith("휴게소") && !RestAreaNames.isShelter(n) && !n.contains("-") && !n.contains("테스트");
	}

	/** "매송(목포)휴게소" → "매송" */
	public static String baseName(String name) {
		String key = RestAreaNames.nameKey(name);
		int paren = key.indexOf('(');
		return paren < 0 ? key : key.substring(0, paren);
	}

	/** 주소의 시·군·구 (두 번째 이후에서 처음 나오는 시·군·구 낱말). 예: "경기도 화성시 매송면…" → "화성시" */
	public static String sigungu(String address) {
		if (address == null) {
			return null;
		}
		String[] words = address.trim().split("\\s+");
		for (int i = 1; i < words.length; i++) {
			if (SIGUNGU.matcher(words[i]).matches()) {
				return words[i];
			}
		}
		return null;
	}

	public static Result pick(List<KakaoPlaceClient.Place> places, String name, String direction, String address) {
		String base = baseName(name);
		String gu = sigungu(address);
		if (gu == null) {
			return new Result(null, "도로공사 주소에서 시·군·구를 못 찾음");
		}
		List<KakaoPlaceClient.Place> hits = places.stream()
				.filter(p -> p.categoryName().endsWith(CATEGORY))
				.filter(p -> p.placeName().replace(" ", "").startsWith(base))
				.filter(p -> direction == null
						? !p.placeName().replace("양방향", "").contains("방향")
						: p.placeName().contains(direction + "방향"))
				.filter(p -> p.addressName().contains(gu) || p.roadAddressName().contains(gu))
				.toList();
		// "평택"으로 "평택휴게소"와 "평택호휴게소"가 같이 걸리면, 이름이 정확히 "{앞부분}휴게소"인 것만 남긴다
		if (hits.size() > 1) {
			List<KakaoPlaceClient.Place> exact = hits.stream()
					.filter(p -> p.placeName().replace(" ", "").startsWith(base + "휴게소"))
					.toList();
			if (!exact.isEmpty()) {
				hits = exact;
			}
		}
		if (hits.size() != 1) {
			return new Result(null, hits.isEmpty() ? "조건에 맞는 결과 없음" : "결과가 " + hits.size() + "개");
		}
		return new Result(hits.get(0), null);
	}
}
