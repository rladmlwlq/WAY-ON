package com.wayon.restarea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class StoreMatchTest {

	@Test
	void 카카오_이름과_브랜드() {
		assertTrue(StoreMatch.same(StoreMatch.placeKey("할리스 매송휴게소목포방향점"), StoreMatch.key("할리스커피")));
		assertTrue(StoreMatch.same(StoreMatch.placeKey("던킨 경산휴게소점"), StoreMatch.key("던킨도너츠")));
		assertTrue(StoreMatch.same(StoreMatch.placeKey("크리스피크림도넛 매송휴게소목포방향점"), StoreMatch.key("크리스피크림")));
		assertTrue(StoreMatch.same(StoreMatch.placeKey("CU E매송휴게소목포점"), StoreMatch.key("CU")));
		assertFalse(StoreMatch.same(StoreMatch.placeKey("브알라 매송휴게소점"), StoreMatch.key("드롭탑(커피전문점)")));
	}

	@Test
	void 판매순위_매장_이름() {
		assertEquals("드롭탑", StoreMatch.storeKey("카페드롭탑"));
		assertEquals("호두과자", StoreMatch.storeKey("외부매장(호두과자)"));
		assertTrue(StoreMatch.isFoodCourt("푸드(양갑조국밥)"));
		assertTrue(StoreMatch.isFoodCourt("식당가KIOSK"));
		assertTrue(StoreMatch.isFoodCourt("한식"));
		assertFalse(StoreMatch.isFoodCourt("외부매장(호두과자)"));
		assertFalse(StoreMatch.isFoodCourt("엔제리너스"));
	}

	@Test
	void 메뉴_이름_앞_괄호가_파는_곳() {
		assertEquals(List.of("유가네", "곰탕"), List.of(StoreMatch.menuStore("(유가네)곰탕")));
		assertEquals(List.of("명품-그때그집", "순대국밥"), List.of(StoreMatch.menuStore("☆(명품-그때그집)순대국밥☆")));
		String[] none = StoreMatch.menuStore("양갑조한우국밥");
		assertEquals(null, none[0]);
		assertEquals("양갑조한우국밥", none[1]);
		assertEquals("양갑조국밥", StoreMatch.storeLabel("푸드(양갑조국밥)"));
		assertEquals("식당가 키오스크", StoreMatch.storeLabel("식당가KIOSK"));
		assertEquals("식당가 일반 코너", StoreMatch.storeLabel("식당(일반)"));
		assertEquals("식당가 한식 코너", StoreMatch.storeLabel("한식"));
		assertEquals("식당가 양식 코너", StoreMatch.storeLabel("식당(양식)"));
		assertEquals("101번지남산돈까스", StoreMatch.menuStore("(13.101번지남산돈까스)돈까스")[0]);
		assertEquals("101번지남산돈까스", StoreMatch.menuStore("(308_101번지남산돈까스)돈까스")[0]);
	}

	@Test
	void 한_글자로는_맞추지_않는다() {
		assertFalse(StoreMatch.same("a", "abc"));
		assertEquals(-1, StoreMatch.find("x", List.of("xyz")));
		assertEquals(1, StoreMatch.find("드롭탑", List.of("할리스", "드롭탑")));
	}
}
