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

	/** 5-3. ACTIVE 세션이 없으면 0 */
	int update_tripLocation(@Param("vehicleId") long vehicleId, @Param("lat") double lat, @Param("lng") double lng,
			@Param("heading") Integer heading, @Param("now") LocalDateTime now);

	/** 5-5. ACTIVE 세션이 없으면 0 */
	int update_tripEnergy(@Param("vehicleId") long vehicleId, @Param("energyType") String energyType,
			@Param("energyPct") Integer energyPct, @Param("rangeM") Integer rangeM);

	/** 5-4. 목적지가 없으면 셋 다 null. ACTIVE 세션이 없으면 0 */
	int update_tripDestination(@Param("vehicleId") long vehicleId, @Param("name") String name,
			@Param("lat") Double lat, @Param("lng") Double lng);

	/** row에 tripId, passengerId, placeType, extId, name, address, lat, lng, action, now. 넣은 뒤 row.id */
	int insert_waypointRequest(java.util.Map<String, Object> row);

	List<Long> select_pendingRequestIds(@Param("tripId") long tripId);

	/** 이 차량 요청이고 상태가 from일 때만. 바뀐 행 수 */
	int update_requestStatus(@Param("requestId") long requestId, @Param("vehicleId") long vehicleId,
			@Param("from") String from, @Param("to") String to, @Param("now") LocalDateTime now,
			@Param("failReason") String failReason, @Param("requestedSince") LocalDateTime requestedSince);

	TripRows.Request select_request(@Param("requestId") long requestId);

	/** ACTIVE 세션이 없거나 위치를 아직 못 받았으면 null */
	TripRows.CarPosition select_carPosition(@Param("vehicleId") long vehicleId);

	/** 이 차량 세션의 휴게소 경유지 요청이면 place_ext_id(`ra:000048`), 아니면 null */
	String select_restAreaRequestExtId(@Param("requestId") long requestId, @Param("vehicleId") long vehicleId);
}
