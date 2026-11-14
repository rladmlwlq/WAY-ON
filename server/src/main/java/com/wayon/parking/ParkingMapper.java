package com.wayon.parking;

import java.math.BigDecimal;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 공영주차장 (SQL은 resources/mapper/ParkingMapper.xml) */
@Mapper
public interface ParkingMapper {

	/** 사각형 안의 주차장. 실제 거리는 서비스에서 계산해 자른다 (erd.md 근처 찾기 쿼리) */
	List<ParkingRows.Lot> select_inBox(@Param("minLat") BigDecimal minLat, @Param("maxLat") BigDecimal maxLat,
			@Param("minLng") BigDecimal minLng, @Param("maxLng") BigDecimal maxLng);

	ParkingRows.Lot select_lot(@Param("id") long id);

	/** 그 차량 ACTIVE 세션의 목적지 (car.route로 저장됨). 세션이 없으면 null */
	ParkingRows.Destination select_destination(@Param("vehicleId") long vehicleId);
}
