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

	/**
	 * 휴게소 지도 (app-api.md 3-9). 지도 중심은 휴게소 건물(lat, lng).
	 * places는 카카오에서 찾은 실제 위치의 장소, building은 건물 마커에 모은 정보 (식당가, 위치를 모르는 매장, 편의시설).
	 */
	public record MapView(String restAreaId, String name, String direction, double lat, double lng,
			List<MapPlace> places, Building building, String topMonth) {
	}

	/**
	 * 지도 위 장소 1개. kind: FOOD 음식점 / CAFE 카페 / STORE 편의점 / GAS 주유소 / EV 충전소 / TOILET 화장실 / ATM.
	 * 우리 매장 데이터와 이름이 맞으면 영업시간·openNow·잘 팔리는 메뉴가 붙는다 (아니면 null·빈 목록).
	 */
	public record MapPlace(String placeId, String name, String kind, double lat, double lng,
			String open, String close, Boolean openNow, List<TopItem> topItems) {
	}

	public record Building(FoodCourt foodCourt, List<Store> otherStores, List<Facility> facilities) {
	}

	/** 식당가: 메뉴(0502, 베스트·추천 먼저)와 식당가 코너의 판매 순위 */
	public record FoodCourt(List<Menu> menus, List<RankedItem> topItems) {
	}

	/**
	 * 식당가 메뉴. store는 파는 곳 (메뉴 이름 앞 괄호, 예: "(유가네)곰탕" → 유가네, 이름은 "곰탕"). 없으면 null (식당가 공용).
	 * placeId는 그 매장이 지도에 있으면 그 장소 ID
	 */
	public record Menu(String name, String store, String placeId, Integer price, boolean best, boolean recommended,
			boolean premium) {
	}

	/** 판매 순위 1칸 + 파는 곳. store는 "푸드(양갑조국밥)" → "양갑조국밥"처럼 읽기 좋게 */
	public record RankedItem(int rank, String name, String store, String placeId, Integer price) {
	}

	/** 지도에서 위치를 못 찾은 매장 (0501 브랜드 또는 판매순위 매장) */
	public record Store(String name, String open, String close, Boolean openNow, List<TopItem> topItems) {
	}

	public enum Phase { APPROACH, ARRIVED, LEFT }

	/** 7장 restarea.info의 data. LEFT면 restArea 없이 닫기만 */
	public record Info(Phase phase, Detail restArea) {
	}
}
