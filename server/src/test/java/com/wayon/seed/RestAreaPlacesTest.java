package com.wayon.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class RestAreaPlacesTest {

	// 실제 좌표 (2026-11-06 적재). 매송 상·하행은 약 265m 떨어져 있다
	private final RestAreaPlaces.Area mokpo = new RestAreaPlaces.Area("000555", "매송(목포)휴게소", "목포", 37.265122, 126.888717);
	private final RestAreaPlaces.Area seoul = new RestAreaPlaces.Area("000554", "매송(서울)휴게소", "서울", 37.264703, 126.891678);

	private static KakaoPlaceClient.Nearby p(String id, String name, String group, String cat, double lat, double lng) {
		return new KakaoPlaceClient.Nearby(id, name, cat, group, lat, lng);
	}

	private Map<String, String> ownerByName(List<RestAreaPlaces.Place> places) {
		return places.stream().collect(Collectors.toMap(RestAreaPlaces.Place::name, RestAreaPlaces.Place::code));
	}

	@Test
	void 방향이_없는_매장은_가까운_쪽_휴게소로() {
		List<RestAreaPlaces.Place> out = RestAreaPlaces.assign(List.of(mokpo, seoul), List.of(
				p("1", "브알라 매송휴게소점", "FD6", "음식점", 37.265103, 126.888732),
				p("2", "인생닭강정 매송휴게소상행선점", "FD6", "음식점", 37.264900, 126.891200)));
		assertEquals(Map.of("브알라 매송휴게소점", "000555", "인생닭강정 매송휴게소상행선점", "000554"), ownerByName(out));
	}

	@Test
	void 이름에_방향이_있으면_그_방향_휴게소로() {
		// 일부러 서울방향 휴게소 쪽에 가깝게 둔 "목포방향점"
		List<RestAreaPlaces.Place> out = RestAreaPlaces.assign(List.of(mokpo, seoul), List.of(
				p("1", "할리스 매송휴게소목포방향점", "CE7", "카페", 37.264900, 126.890500)));
		assertEquals("000555", out.get(0).code());
	}

	@Test
	void 휴게소_밖_가게와_휴게소_자체_주차장은_뺀다() {
		RestAreaPlaces.Area gyeongsan = new RestAreaPlaces.Area("000048", "경산(서울)휴게소", "서울", 35.878589, 128.809738);
		List<RestAreaPlaces.Place> out = RestAreaPlaces.assign(List.of(gyeongsan), List.of(
				p("1", "세븐일레븐 경산대학로점", "CS2", "편의점", 35.8810, 128.8115),
				p("2", "근처 국밥집", "FD6", "음식점", 35.8786, 128.8098),
				p("3", "경산휴게소 서울방향", "", "교통,수송 > 휴게소 > 고속도로휴게소", 35.8786, 128.8097),
				p("4", "경산휴게소 서울방향 주차장", "PK6", "주차장", 35.8786, 128.8097),
				p("5", "던킨 경산휴게소점", "FD6", "음식점", 35.879226, 128.810403)));
		assertEquals(List.of("던킨 경산휴게소점"), out.stream().map(RestAreaPlaces.Place::name).toList());
	}

	@Test
	void 주유소는_이름에_휴게소가_없어도_멀리_있어도_된다() {
		List<RestAreaPlaces.Place> out = RestAreaPlaces.assign(List.of(mokpo, seoul), List.of(
				p("1", "매송주유소 목포방향", "OL7", "주유소", 37.263586, 126.888491)));
		assertEquals("000555", out.get(0).code());
		assertEquals("GAS", out.get(0).kind());
	}

	@Test
	void 반대편_휴게소가_목록에_없으면_그_방향_매장은_뺀다() {
		RestAreaPlaces.Area daegu = new RestAreaPlaces.Area("000087", "지리산(대구)휴게소", "대구", 35.481380, 127.566756);
		List<RestAreaPlaces.Place> out = RestAreaPlaces.assign(List.of(daegu), java.util.Set.of("광주"), List.of(
				p("1", "지리산휴게소주유소 광주방향", "OL7", "주유소", 35.4800, 127.5660),
				p("2", "지리산휴게소주유소 대구방향", "OL7", "주유소", 35.4813, 127.5668),
				p("3", "서울도너츠 지리산휴게소 담양방향점", "FD6", "음식점", 35.4810, 127.5665),
				p("4", "할리스 (상)지리산휴게소점", "CE7", "카페", 35.4811, 127.5666),
				p("5", "지리산휴게소(광주) 전기차충전소", "", "교통,수송 > 자동차 > 전기차 충전소", 35.4805, 127.5662),
				p("6", "지리산휴게소(대구) 전기차충전소", "", "교통,수송 > 자동차 > 전기차 충전소", 35.4815, 127.5670)));
		assertEquals(List.of("지리산휴게소주유소 대구방향", "지리산휴게소(대구) 전기차충전소"), out.stream().map(RestAreaPlaces.Place::name).toList());
	}

	@Test
	void 같은_충전소가_겹치면_하나만() {
		List<RestAreaPlaces.Place> out = RestAreaPlaces.assign(List.of(mokpo), List.of(
				p("1", "매송휴게소(목포방향) 전기차충전소", "", "교통,수송 > 자동차 > 전기차 충전소", 37.26500, 126.88860),
				p("2", "매송(목포) 휴게소 전기차충전소", "", "교통,수송 > 자동차 > 전기차 충전소", 37.26501, 126.88861)));
		assertEquals(1, out.size());
		assertEquals("EV", out.get(0).kind());
	}

	@Test
	void 종류() {
		assertEquals("FOOD", RestAreaPlaces.kind(p("1", "a", "FD6", "음식점", 0, 0)));
		assertEquals("TOILET", RestAreaPlaces.kind(p("1", "매송휴게소 공중화장실", "", "가정,생활 > 화장실", 0, 0)));
		assertEquals("ATM", RestAreaPlaces.kind(p("1", "IBK기업은행365 경산휴게소", "BK9", "금융 > 은행 > ATM", 0, 0)));
		assertNull(RestAreaPlaces.kind(p("1", "까스텔바작 기흥휴게소점", "", "가정,생활 > 패션", 0, 0)));
	}
}
