package com.wayon.restarea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class OpenHoursTest {

	private static LocalTime at(String hhmm) {
		return LocalTime.parse(hhmm);
	}

	@Test
	void 시작부터_끝_전까지_영업() {
		assertTrue(OpenHours.isOpen("08:00", "20:00", at("08:00")));
		assertTrue(OpenHours.isOpen("08:00", "20:00", at("19:59")));
		assertFalse(OpenHours.isOpen("08:00", "20:00", at("20:00")));
		assertFalse(OpenHours.isOpen("08:00", "20:00", at("07:59")));
	}

	@Test
	void 끝이_24시면_자정_전까지() {
		assertTrue(OpenHours.isOpen("00:00", "24:00", at("00:00")));
		assertTrue(OpenHours.isOpen("00:00", "24:00", at("23:59")));
		assertTrue(OpenHours.isOpen("06:00", "24:00", at("23:30")));
		assertFalse(OpenHours.isOpen("06:00", "24:00", at("05:00")));
	}

	@Test
	void 끝이_시작보다_이르면_다음_날까지() {
		assertTrue(OpenHours.isOpen("22:00", "02:00", at("23:00")));
		assertTrue(OpenHours.isOpen("22:00", "02:00", at("01:59")));
		assertFalse(OpenHours.isOpen("22:00", "02:00", at("02:00")));
		assertFalse(OpenHours.isOpen("22:00", "02:00", at("12:00")));
	}

	@Test
	void 시작과_끝이_같으면_하루_종일() {
		assertTrue(OpenHours.isOpen("07:00", "07:00", at("03:00")));
	}

	@Test
	void 값이_없거나_틀리면_모름() {
		assertNull(OpenHours.isOpen(null, "20:00", at("10:00")));
		assertNull(OpenHours.isOpen("8:00", "20:00", at("10:00")));
		assertNull(OpenHours.isOpen("08:00", "25:00", at("10:00")));
	}

	@Test
	void extId는_ra_뒤의_휴게소_코드_6자리() {
		assertEquals(Optional.of("000001"), RestAreaService.parseExtId("ra:000001"));
		assertEquals(Optional.empty(), RestAreaService.parseExtId("ev:ME000001"));
		assertEquals(Optional.empty(), RestAreaService.parseExtId("ra:abc"));
		assertEquals(Optional.empty(), RestAreaService.parseExtId("ra:31"));
		assertEquals(Optional.empty(), RestAreaService.parseExtId(null));
	}
}
