package com.wayon.search;

import java.util.List;

/** app-api.md 3-10 응답 */
public final class SearchDto {

	private SearchDto() {
	}

	/**
	 * 장소 1개. url은 카카오맵 모바일 웹 (앱이 없어도 열림, https).
	 * restAreaId가 있으면 휴게소 안 매장이라 3-7로 메뉴·영업시간을 볼 수 있다
	 */
	public record Place(String id, String name, String category, String phone, String address, double lat,
			double lng, Integer distM, String url, String restAreaId) {
	}

	public record Places(List<Place> places) {
	}
}
