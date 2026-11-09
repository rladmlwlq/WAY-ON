package com.wayon.trip;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 주행 세션 (SQL은 resources/mapper/TripMapper.xml) */
@Mapper
public interface TripMapper {

	TripRows.Trip select_activeTrip(@Param("vehicleId") long vehicleId);

	int insert_trip(@Param("publicId") String publicId, @Param("vehicleId") long vehicleId,
			@Param("now") LocalDateTime now);

	/** 위치·목적지·에너지를 지우고 status로 닫는다. ACTIVE가 아니면 0 */
	int update_tripClosed(@Param("tripId") long tripId, @Param("status") String status,
			@Param("now") LocalDateTime now, @Param("idleBefore") LocalDateTime idleBefore);

	int update_tripActivity(@Param("vehicleId") long vehicleId, @Param("now") LocalDateTime now);

	List<TripRows.Trip> select_idleTrips(@Param("idleBefore") LocalDateTime idleBefore);

	int insert_tripPassenger(@Param("tripId") long tripId, @Param("passengerId") long passengerId,
			@Param("now") LocalDateTime now);

	/** 이 차량 세션의 휴게소 경유지 요청이면 place_ext_id(`ra:000048`), 아니면 null */
	String select_restAreaRequestExtId(@Param("requestId") long requestId, @Param("vehicleId") long vehicleId);
}
