package com.wayon.seed;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 휴게소·메뉴 이름 규칙 (erd.md "휴게소 데이터 적재 규칙").
 * 도로공사 API끼리, 그리고 판매순위 파일과 코드 체계가 달라서 이름으로 짝을 찾는다.
 */
public final class RestAreaNames {

	private static final Pattern DIRECTION = Pattern.compile("\\(([^)]*)\\)");
	private static final Pattern LEADING_PAREN = Pattern.compile("^\\([^)]*\\)");
	private static final Pattern SPACES = Pattern.compile("\\s+");

	private RestAreaNames() {
	}

	/** 전각 괄호를 반각으로 */
	static String normalizeParen(String s) {
		return s.replace('（', '(').replace('）', ')');
	}

	/**
	 * 휴게소·주유소 이름 연결 키: 공백 제거, 전각 괄호 → 반각, 끝의 "휴게소"·"주유소" 제거.
	 * 예: "경산(서울)휴게소", "경산(서울)주유소" → "경산(서울)"
	 */
	public static String nameKey(String name) {
		String s = SPACES.matcher(normalizeParen(name)).replaceAll("");
		if (s.endsWith("휴게소") || s.endsWith("주유소")) {
			s = s.substring(0, s.length() - 3);
		}
		return s;
	}

	/** 이름 괄호 안의 방향. 예: "안성(부산)휴게소" → "부산". 괄호가 없으면 null */
	public static String directionInName(String name) {
		Matcher m = DIRECTION.matcher(normalizeParen(name));
		return m.find() ? m.group(1).trim() : null;
	}

	/** 상품명 앞의 "(할리스)" 같은 괄호 접두어를 뗀 이름 */
	public static String withoutPrefix(String itemName) {
		return LEADING_PAREN.matcher(normalizeParen(itemName).trim()).replaceFirst("").trim();
	}

	/** 메뉴 이름 연결 키: 괄호 접두어와 공백을 지운 값. 판매순위 상품명과 비교할 때 씀 */
	public static String menuKey(String foodName) {
		return SPACES.matcher(withoutPrefix(foodName)).replaceAll("");
	}

	/** 쉼터는 휴게소가 아니라 적재에서 뺀다 (api.md 1번 "확인한 사실") */
	public static boolean isShelter(String name) {
		return name.contains("쉼터");
	}
}
