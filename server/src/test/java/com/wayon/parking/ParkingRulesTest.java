package com.wayon.parking;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import com.wayon.seed.ParkingSeederAccess;

/** 1시간 요금(정렬용)과 지금 운영 중 판단 */
class ParkingRulesTest {

	private static ParkingRows.Lot lot(String wdOpen, String wdClose, String satOpen, String satClose) {
		return new ParkingRows.Lot(1, "테스트", "OFF_STREET", "서울", BigDecimal.ONE, BigDecimal.ONE, null, "PAID",
				null, null, null, wdOpen, wdClose, satOpen, satClose, null, null, null, null);
	}

	private static ZonedDateTime at(int day, int hour, int minute) {
		// 2026-11-13은 금요일, 14일 토요일, 15일 일요일
		return ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, ZoneId.of("Asia/Seoul"));
	}

	@Test
	void openNow() {
		assertThat(ParkingService.openNow(lot("0900", "1800", null, null), at(13, 10, 0))).isTrue();
		assertThat(ParkingService.openNow(lot("0900", "1800", null, null), at(13, 18, 0))).isFalse();
		assertThat(ParkingService.openNow(lot("0000", "2400", null, null), at(13, 23, 59))).isTrue();
		// 밤새 운영
		assertThat(ParkingService.openNow(lot("2000", "0600", null, null), at(13, 2, 0))).isTrue();
		// 토요일은 토요일 시간, 없으면 모름
		assertThat(ParkingService.openNow(lot("0900", "1800", null, null), at(14, 10, 0))).isNull();
		assertThat(ParkingService.openNow(lot("0900", "1800", "1000", "1200"), at(14, 13, 0))).isFalse();
	}

	@Test
	void fee1h() {
		assertThat(ParkingSeederAccess.fee1h("FREE", null, null, null, null, null)).isZero();
		// 기본 30분 600원 + 10분마다 300원 → 600 + 3×300
		assertThat(ParkingSeederAccess.fee1h("PAID", 30, 600, 10, 300, null)).isEqualTo(1500);
		// 추가 요금이 비면 기본 단위 반복 (서울 5분 50원)
		assertThat(ParkingSeederAccess.fee1h("PAID", 5, 50, null, 0, null)).isEqualTo(600);
		// 일 최대 요금 이하
		assertThat(ParkingSeederAccess.fee1h("PAID", 30, 600, 10, 300, 1000)).isEqualTo(1000);
		assertThat(ParkingSeederAccess.fee1h("PAID", null, null, null, null, null)).isNull();
	}
}
