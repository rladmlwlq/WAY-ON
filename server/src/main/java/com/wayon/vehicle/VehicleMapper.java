package com.wayon.vehicle;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 차량·동승자·연결 코드 (SQL은 resources/mapper/VehicleMapper.xml) */
@Mapper
public interface VehicleMapper {

	int insert_vehicle(@Param("publicId") String publicId, @Param("tokenHash") String tokenHash,
			@Param("now") LocalDateTime now);

	Long select_vehicleIdByToken(@Param("tokenHash") String tokenHash);

	int update_vehicleSeen(@Param("vehicleId") long vehicleId, @Param("now") LocalDateTime now);

	int update_passengerConnected(@Param("passengerId") long passengerId, @Param("now") LocalDateTime now);

	/** 닉네임·인원 확인과 동승자 추가를 한 차량씩 차례로 처리하려고 행을 잠근다 (erd.md) */
	Long select_vehicleIdForUpdate(@Param("vehicleId") long vehicleId);

	int delete_connectCodes(@Param("vehicleId") long vehicleId);

	int insert_connectCode(@Param("vehicleId") long vehicleId, @Param("codeHash") String codeHash,
			@Param("expiresAt") LocalDateTime expiresAt);

	VehicleRows.ConnectCode select_connectCode(@Param("codeHash") String codeHash);

	int select_passengerCount(@Param("vehicleId") long vehicleId);

	int select_nicknameCount(@Param("vehicleId") long vehicleId, @Param("nickname") String nickname);

	int insert_passenger(@Param("vehicleId") long vehicleId, @Param("tokenHash") String tokenHash,
			@Param("nickname") String nickname, @Param("now") LocalDateTime now);

	VehicleRows.Passenger select_passengerByToken(@Param("tokenHash") String tokenHash);

	List<VehicleRows.Passenger> select_passengers(@Param("vehicleId") long vehicleId);

	int update_passengerRevoked(@Param("vehicleId") long vehicleId, @Param("passengerId") long passengerId,
			@Param("now") LocalDateTime now);

	int update_pushSubscription(@Param("passengerId") long passengerId, @Param("endpoint") String endpoint,
			@Param("p256dh") String p256dh, @Param("auth") String auth, @Param("now") LocalDateTime now);

	int delete_pushSubscription(@Param("passengerId") long passengerId);

	/** 최신순. before가 null이면 가장 최신부터 */
	List<VehicleRows.Message> select_departureMessages(@Param("vehicleId") long vehicleId, @Param("before") Long before,
			@Param("limit") int limit, @Param("since") LocalDateTime since, @Param("callId") Long callId);

	/** 끝난 출발 알림, 최신순 */
	List<VehicleRows.Call> select_departureCalls(@Param("vehicleId") long vehicleId, @Param("before") Long before,
			@Param("limit") int limit, @Param("since") LocalDateTime since);
}
