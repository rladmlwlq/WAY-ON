package com.wayon.restarea;

import java.math.BigDecimal;

/** DB에서 읽은 행. 밖으로는 RestAreaDto의 모양으로 바꿔서 내보낸다. 휴게소는 도로공사 코드(std_rest_cd)로 구분 */
public final class RestAreaRows {

	private RestAreaRows() {
	}

	public record RestArea(String stdRestCd, String name, String routeName, String directionLabel,
			BigDecimal lat, BigDecimal lng, Integer travelBearing) {
	}

	public record TopItem(String stdRestCd, String baseMonth, int rankNo, String displayName,
			String storeName, Integer price) {
	}

	public record Brand(String brdName, String openTime, String closeTime) {
	}

	public record Place(String kakaoId, String name, String kind, BigDecimal lat, BigDecimal lng) {
	}

	public record Menu(String foodName, Integer price, boolean recommended, boolean best, boolean premium) {
	}

	public record Facility(String stdRestCd, String source, String psCode, String name, String detail,
			String openTime, String closeTime) {
	}
}
