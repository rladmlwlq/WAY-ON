package com.wayon.parking;

import java.math.BigDecimal;

/** parking_lot 행 (resources/mapper/ParkingMapper.xml) */
public final class ParkingRows {

	private ParkingRows() {
	}

	public record Lot(long id, String name, String kind, String addr, BigDecimal lat, BigDecimal lng,
			Integer totalSpaces, String feeType, Integer basicMin, Integer basicFee, Integer fee1h,
			String weekdayOpen, String weekdayClose, String satOpen, String satClose, String holidayOpen,
			String holidayClose, String phone, Integer nowParked) {
	}

	/** 지금 세션의 목적지. 목적지가 없으면 이름·좌표가 null */
	public record Destination(String destName, BigDecimal destLat, BigDecimal destLng) {
	}
}
