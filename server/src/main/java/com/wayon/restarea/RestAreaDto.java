package com.wayon.restarea;

import java.util.List;

/**
 * 휴게소 응답 모양 (app-api.md 3-6, 3-7, 7장 restarea.info).
 * restAreaId는 도로공사 휴게소 코드 문자열("000001"). 다시 적재해도 바뀌지 않는다.
 */
public final class RestAreaDto {

	private RestAreaDto() {
	}

	/** 3-7 휴게소 상세. restarea.info의 restArea도 같은 모양 */
	public record Detail(String restAreaId, String name, String routeName, String direction,
			double lat, double lng, List<TopItem> topItems, String topMonth,
			List<Brand> brands, List<Facility> facilities) {
	}

	public record TopItem(int rank, String name, String store, Integer price) {
	}

	public record Brand(String name, String open, String close, Boolean openNow) {
	}

	public record Facility(String code, String name, String detail, String open, String close, String source) {
	}

	/** 3-6 경로 위 장소에서 REST_AREA 행의 detail */
	public record Summary(String routeName, String direction, String top1, List<String> facilities,
			boolean hasGasStation) {
	}

	/** 3-6 RESTAURANT(고속도로) 행의 detail */
	public record Restaurant(String restAreaName, List<TopItem> topItems) {
	}

	/**
	 * 경로 위 휴게소를 고를 때 쓰는 위치 (3-6, F2).
	 * travelBearing은 진입 차량의 진행 방향 0~359, null이면 양방향 공용 (erd.md 휴게소 적재 규칙).
	 */
	public record Location(String restAreaId, String name, String routeName, String direction,
			double lat, double lng, Integer travelBearing) {

		public String extId() {
			return RestAreaService.EXT_ID_PREFIX + restAreaId;
		}
	}

	public enum Phase { APPROACH, ARRIVED, LEFT }

	/** 7장 restarea.info의 data. LEFT면 restArea 없이 닫기만 */
	public record Info(Phase phase, Detail restArea) {
	}
}
