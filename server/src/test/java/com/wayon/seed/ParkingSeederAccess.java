package com.wayon.seed;

/** 다른 패키지 테스트에서 ParkingSeeder의 계산을 부르기 위한 통로 */
public final class ParkingSeederAccess {

	private ParkingSeederAccess() {
	}

	public static Integer fee1h(String feeType, Integer basicMin, Integer basicFee, Integer addMin, Integer addFee,
			Integer dayMax) {
		return ParkingSeeder.fee1h(feeType, basicMin, basicFee, addMin, addFee, dayMax);
	}
}
