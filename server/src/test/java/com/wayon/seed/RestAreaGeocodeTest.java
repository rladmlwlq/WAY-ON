package com.wayon.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class RestAreaGeocodeTest {

	private static final String CAT = "교통,수송 > 휴게소 > 고속도로휴게소";

	private static KakaoPlaceClient.Place place(String name, String category, String addr) {
		return new KakaoPlaceClient.Place(name, category, addr, addr, 37.0, 127.0);
	}

	// 실제 카카오 검색 "매송휴게소" 결과 모양 (2026-11-06)
	private final List<KakaoPlaceClient.Place> maesong = List.of(
			place("매송휴게소 목포방향", CAT, "경기 화성시 효행구 매송면 서해안고속도로 315"),
			place("매송휴게소 서울방향", CAT, "경기 화성시 효행구 매송면 서해안고속도로 316"),
			place("매송휴게소 화물차힐링센터", "교통,수송 > 휴게소", "경기 화성시 효행구 매송면 서해안고속도로 315"),
			place("매송휴게소 서울방향 주차장", "교통,수송 > 교통시설 > 주차장", "경기 화성시 매송면"));

	@Test
	void 방향이_맞는_고속도로휴게소_하나만_고른다() {
		RestAreaGeocode.Result r = RestAreaGeocode.pick(maesong, "매송(목포)휴게소", "목포", "경기도 화성시 매송면서해안고속도로 315");
		assertTrue(r.ok());
		assertEquals("매송휴게소 목포방향", r.place().placeName());

		r = RestAreaGeocode.pick(maesong, "매송(서울)휴게소", "서울", "경기 화성시 매송면서해안고속도로 316");
		assertEquals("매송휴게소 서울방향", r.place().placeName());
	}

	@Test
	void 시군구가_다르면_쓰지_않는다() {
		RestAreaGeocode.Result r = RestAreaGeocode.pick(maesong, "매송(목포)휴게소", "목포", "전북 부안군 주산면");
		assertFalse(r.ok());
	}

	@Test
	void 방향이_없는데_결과가_방향별로_나뉘면_고르지_않는다() {
		RestAreaGeocode.Result r = RestAreaGeocode.pick(maesong, "매송휴게소", null, "경기 화성시 매송면");
		assertFalse(r.ok());
	}

	@Test
	void 양방향_휴게소는_양방향_결과를_고른다() {
		List<KakaoPlaceClient.Place> places = List.of(
				place("내린천휴게소 양방향", CAT, "강원특별자치도 인제군 상남면 서울양양고속도로 117"),
				place("내린천휴게소주유소 서울방향", "교통,수송 > 자동차 > 주유,가스 > 주유소", "강원특별자치도 인제군"));
		RestAreaGeocode.Result r = RestAreaGeocode.pick(places, "내린천휴게소", null, "강원도 인제군 상남면 서울양양고속도로 117");
		assertEquals("내린천휴게소 양방향", r.place().placeName());
	}

	@Test
	void 이름_앞부분이_다른_휴게소는_고르지_않는다() {
		List<KakaoPlaceClient.Place> places = List.of(
				place("군위영천휴게소 영천방향", CAT, "대구 군위군 산성면"),
				place("영천휴게소 대구방향", CAT, "경북 영천시 임고면 운주로 791"));
		RestAreaGeocode.Result r = RestAreaGeocode.pick(places, "영천(대구)휴게소", "대구", "경북 영천시 임고면금대리 99");
		assertEquals("영천휴게소 대구방향", r.place().placeName());
	}

	@Test
	void 앞부분이_같은_다른_휴게소가_있으면_이름이_정확한_것() {
		List<KakaoPlaceClient.Place> places = List.of(
				place("평택휴게소 양방향", CAT, "경기 평택시 청북읍 평택제천고속도로 6"),
				place("평택호휴게소", CAT, "경기 평택시 현덕면"));
		RestAreaGeocode.Result r = RestAreaGeocode.pick(places, "평택휴게소", null, "경기 평택시 청북읍평택제천고속도로 6");
		assertEquals("평택휴게소 양방향", r.place().placeName());
	}

	@Test
	void 광주원주선의_광주는_경기도_광주() {
		// 양평(광주)휴게소 근처: 경기 광주는 서쪽(약 275도), 전남 광주는 남남서쪽(약 198도)
		int gyeonggi = TravelBearing.toward("광주", "광주원주선", 37.3986, 127.7236);
		int byLatitude = TravelBearing.toward("광주", "중앙선", 37.3986, 127.7236);
		int jeonnam = TravelBearing.toward("광주", "광주대구선", 35.7674, 128.4180); // 논공(광주): 서쪽
		assertTrue(gyeonggi > 260 && gyeonggi < 290, "got " + gyeonggi);
		assertEquals(gyeonggi, byLatitude);
		assertTrue(jeonnam > 240 && jeonnam < 280, "got " + jeonnam);
	}

	@Test
	void 적재_대상_이름() {
		assertTrue(RestAreaGeocode.isRestAreaName("매송(목포)휴게소"));
		assertFalse(RestAreaGeocode.isRestAreaName("가남(창원)졸음쉼터"));
		assertFalse(RestAreaGeocode.isRestAreaName("전주(새만금)주차장"));
		assertFalse(RestAreaGeocode.isRestAreaName("문경(양평)휴-건영식품"));
		assertFalse(RestAreaGeocode.isRestAreaName("테스트휴게소"));
	}

	@Test
	void 주소의_시군구() {
		assertEquals("화성시", RestAreaGeocode.sigungu("경기도 화성시 매송면서해안고속도로 315"));
		assertEquals("달성군", RestAreaGeocode.sigungu("대구광역시 달성군 논공읍삼리리 458-5"));
		assertNull(RestAreaGeocode.sigungu(null));
		assertEquals("매송", RestAreaGeocode.baseName("매송(목포)휴게소"));
	}
}
