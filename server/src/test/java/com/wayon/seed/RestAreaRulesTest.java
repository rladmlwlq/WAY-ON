package com.wayon.seed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestAreaRulesTest {

	@Test
	void 이름키는_공백_전각괄호_끝말을_정리한다() {
		assertEquals("경산(서울)", RestAreaNames.nameKey("경산(서울)휴게소"));
		assertEquals("경산(서울)", RestAreaNames.nameKey("경산(서울)주유소"));
		assertEquals("경산(서울)", RestAreaNames.nameKey("경산 （서울） 휴게소"));
		assertEquals("기흥복합", RestAreaNames.nameKey("기흥복합휴게소"));
	}

	@Test
	void 방향은_이름_괄호_안에서_꺼낸다() {
		assertEquals("부산", RestAreaNames.directionInName("안성(부산)휴게소"));
		assertNull(RestAreaNames.directionInName("기흥복합휴게소"));
	}

	@Test
	void 메뉴키는_괄호_접두어와_공백을_지운다() {
		assertEquals("아메리카노", RestAreaNames.withoutPrefix("(할리스) 아메리카노"));
		assertEquals("돼지김치찌게", RestAreaNames.menuKey("돼지 김치찌게"));
		assertEquals("아이스아메리카노", RestAreaNames.menuKey("(할리스)아이스 아메리카노"));
	}

	@Test
	void 쉼터는_뺀다() {
		assertTrue(RestAreaNames.isShelter("서울하이패스센터쉼터"));
	}

	@Test
	void 방위각은_방향_도시_쪽이다() {
		// 칠곡(서울)휴게소 근처에서 서울은 북서쪽 (상·하행 짝 계산은 83도로 틀렸던 곳)
		int toSeoul = TravelBearing.toward("서울", 35.98, 128.40);
		assertTrue(toSeoul > 300 && toSeoul < 340, "북서쪽이어야 함: " + toSeoul);
		int toBusan = TravelBearing.toward("부산", 35.98, 128.40);
		assertTrue(toBusan > 120 && toBusan < 160, "남동쪽이어야 함: " + toBusan);
		assertNull(TravelBearing.toward(null, 37.0, 127.0));
		assertNull(TravelBearing.toward("모르는도시", 37.0, 127.0));
	}

	@Test
	void 가격은_숫자만_뽑는다() {
		assertEquals(9000, RestAreaSeeder.price("9,000"));
		assertEquals(4500, RestAreaSeeder.price("4500"));
		assertNull(RestAreaSeeder.price(""));
		assertNull(RestAreaSeeder.price(null));
	}

	@Test
	void CSV는_쉼표와_따옴표가_있어도_그대로_돌아온다(@TempDir Path tmp) throws Exception {
		Path f = tmp.resolve("t.csv");
		Csv.write(f, List.of("a", "b", "c"), List.of(
				Arrays.asList("06:00~21:00 유인운영, 21:00~06:00 무인운영", null, "말 \"따옴표\""),
				Arrays.asList("줄\n바꿈", "", "x")));
		List<String[]> rows = Csv.read(f);
		assertEquals(2, rows.size());
		assertArrayEquals(new String[] { "06:00~21:00 유인운영, 21:00~06:00 무인운영", null, "말 \"따옴표\"" },
				rows.get(0));
		assertArrayEquals(new String[] { "줄\n바꿈", "", "x" }, rows.get(1));
		assertTrue(Files.readString(f).startsWith("a,b,c\n"));
	}
}
