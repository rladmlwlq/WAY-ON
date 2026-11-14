package com.wayon.departure;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.wayon.vehicle.VehicleRows;

/** 출발 알림·대화 (SQL은 resources/mapper/DepartureMapper.xml) */
@Mapper
public interface DepartureMapper {

	/** OPEN 1건만 만들려고 차량 행을 잠근다 (erd.md 출발 알림 규칙) */
	Long select_vehicleIdForUpdate(@Param("vehicleId") long vehicleId);

	DepartureRows.Call select_openCall(@Param("vehicleId") long vehicleId);

	DepartureRows.Call select_call(@Param("callId") long callId);

	int insert_call(@Param("vehicleId") long vehicleId, @Param("tripId") long tripId, @Param("etaMin") Integer etaMin,
			@Param("destName") String destName, @Param("now") LocalDateTime now);

	/** OPEN일 때만 닫는다. 닫았으면 1 */
	int update_callClosed(@Param("callId") long callId, @Param("status") String status,
			@Param("now") LocalDateTime now);

	int update_callReminded(@Param("callId") long callId, @Param("now") LocalDateTime now);

	List<DepartureRows.Call> select_staleOpenCalls(@Param("calledBefore") LocalDateTime calledBefore);

	List<DepartureRows.Person> select_people(@Param("callId") long callId, @Param("vehicleId") long vehicleId,
			@Param("prevEndedAfter") LocalDateTime prevEndedAfter);

	int insert_message(@Param("callId") long callId, @Param("passengerId") Long passengerId,
			@Param("kind") String kind, @Param("quickCode") String quickCode, @Param("body") String body,
			@Param("now") LocalDateTime now);

	VehicleRows.Message select_lastMessage(@Param("callId") long callId);

	/** 1초에 1건 제한 (기획 7장) */
	int select_recentMessageCount(@Param("callId") long callId, @Param("passengerId") long passengerId,
			@Param("since") LocalDateTime since);

	int update_pushRemoved(@Param("passengerId") long passengerId);

	/** 30일 지난 메시지 (하루 1번) */
	int delete_oldMessages(@Param("before") LocalDateTime before);
}
