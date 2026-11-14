package com.wayon.restarea;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 카카오 장소 이름과 우리 매장 데이터(0501 브랜드, 판매순위 매장 이름)를 잇는 규칙.
 * 카카오 이름은 "할리스 매송휴게소목포방향점"처럼 앞 낱말이 브랜드, 우리 데이터는 "할리스커피", "푸드(양갑조국밥)" 모양이다.
 */
final class StoreMatch {

	private static final Pattern PAREN = Pattern.compile("\\(.*?\\)");
	private static final Pattern INNER = Pattern.compile("\\(([^)]*)\\)");
	private static final Pattern LEADING = Pattern.compile("^\\(([^)]*)\\)");
	private static final Pattern LEADING_NUMBER = Pattern.compile("^\\d+\\s*[._\\-)]\\s*");
	/** 가게 이름이 아니라 식당가 코너 종류인 것 */
	private static final Pattern GENERIC_CORNER = Pattern.compile(
			"(한식|양식|중식|일식|분식|일반|라면|우동|국수|스낵|면류|식사|간식|한식당|한식코너|한식전문점|우동전문점|자율식당|식당)(코너)?");
	private static final Pattern NOISE = Pattern.compile("커피|전문점|카페|\\s");
	/** 판매순위 매장 이름 중 식당가 코너 (위치가 따로 없고 식당가 메뉴에 속함) */
	private static final Pattern FOOD_COURT = Pattern.compile(
			"식당|한식|양식|중식|일식|분식|라면|우동|국수|코너|키오스크|kiosk|자율|스낵|푸드|덮밥|국밥|김밥|돈가스|돈까스|자판기");

	private StoreMatch() {
	}

	/** "할리스커피" → "할리스", "드롭탑(커피전문점)" → "드롭탑" */
	static String key(String name) {
		String s = PAREN.matcher(name).replaceAll("");
		return NOISE.matcher(s).replaceAll("").toLowerCase(Locale.ROOT);
	}

	/** 카카오 이름의 앞 낱말 키. "CU E매송휴게소목포점" → "cu" */
	static String placeKey(String kakaoName) {
		String first = kakaoName.trim().split("\\s+")[0];
		return key(first);
	}

	/** 판매순위 매장 이름의 키. "푸드(양갑조국밥)" → "양갑조국밥", "카페드롭탑" → "드롭탑" */
	static String storeKey(String storeName) {
		var m = INNER.matcher(storeName);
		return key(m.find() ? m.group(1) : storeName);
	}

	/** 판매순위 매장이 식당가 코너인지 ("한식", "식당(양식)", "식당가KIOSK", "푸드(양갑조국밥)" 등) */
	static boolean isFoodCourt(String storeName) {
		return FOOD_COURT.matcher(storeName.toLowerCase(Locale.ROOT)).find();
	}

	/** 메뉴 이름 앞 괄호의 매장. "☆(명품-그때그집)순대국밥☆" → {"명품-그때그집", "순대국밥"}. 괄호가 없으면 {null, 이름} */
	static String[] menuStore(String foodName) {
		String s = foodName.replace("☆", "").replace("★", "").trim();
		var m = LEADING.matcher(s);
		if (m.find() && m.end() < s.length() && !m.group(1).isBlank()) {
			return new String[] { cleanStore(m.group(1)), s.substring(m.end()).trim() };
		}
		return new String[] { null, s };
	}

	/**
	 * 판매순위 매장 이름을 읽기 좋게.
	 * "푸드(양갑조국밥)" → "양갑조국밥", "식당(일반)"·"일반" → "식당가 일반 코너", "한식" → "식당가 한식 코너",
	 * "식당가KIOSK" → "식당가 키오스크"
	 */
	static String storeLabel(String storeName) {
		var m = INNER.matcher(storeName);
		String s = m.find() && !m.group(1).isBlank() ? m.group(1).trim() : storeName.trim();
		if (s.toLowerCase(Locale.ROOT).contains("kiosk") || s.contains("키오스크")) {
			return "식당가 키오스크";
		}
		if (GENERIC_CORNER.matcher(s).matches()) {
			return "식당가 " + s.replace("코너", "").replace("전문점", "").replace("식당", "").trim() + " 코너";
		}
		return cleanStore(s);
	}

	/** 앞의 번호를 뗀다. "13.101번지남산돈까스" → "101번지남산돈까스", "308_101번지남산돈까스" → 같음 */
	private static String cleanStore(String s) {
		return LEADING_NUMBER.matcher(s.trim()).replaceFirst("").trim();
	}

	/** 두 키가 같은 매장인지. 짧은 쪽이 2글자 이상이고, 한쪽이 다른 쪽으로 시작하거나 포함 */
	static boolean same(String a, String b) {
		if (a.length() < 2 || b.length() < 2) {
			return false;
		}
		return a.startsWith(b) || b.startsWith(a) || a.contains(b) || b.contains(a);
	}

	/** 키 목록에서 처음 맞는 것의 위치, 없으면 -1 */
	static int find(String key, List<String> keys) {
		for (int i = 0; i < keys.size(); i++) {
			if (same(key, keys.get(i))) {
				return i;
			}
		}
		return -1;
	}
}
