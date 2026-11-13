package com.wayon.vehicle;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** app-api.md 2장 REST: 차량 앱 */
@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

	private final VehicleService service;

	public VehicleController(VehicleService service) {
		this.service = service;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public VehicleDto.VehicleCreated save_vehicle() {
		return service.save_vehicle();
	}

	@PostMapping("/me/connect-codes")
	@ResponseStatus(HttpStatus.CREATED)
	public VehicleDto.ConnectCodeCreated save_connectCode(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@RequestBody VehicleDto.ConnectCodeRequest request) {
		return service.save_connectCode(authorization, request);
	}

	@GetMapping("/me/passengers")
	public VehicleDto.Passengers select_passengers(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
		return service.select_passengers(authorization);
	}

	@DeleteMapping("/me/passengers/{passengerId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete_passenger(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@PathVariable long passengerId) {
		service.delete_passenger(authorization, passengerId);
	}

	@GetMapping("/me/departure-messages")
	public VehicleDto.Messages select_departureMessages(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@RequestParam(required = false) Long before, @RequestParam(required = false) Integer size,
			@RequestParam(required = false) Long callId) {
		return service.select_departureMessages(authorization, before, size, callId);
	}

	/** 2-6. 지난 출발 알림 목록 (차 대화 화면의 카드 목록) */
	@GetMapping("/me/departure-calls")
	public VehicleDto.Calls select_departureCalls(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@RequestParam(required = false) Long before, @RequestParam(required = false) Integer size) {
		return service.select_departureCalls(authorization, before, size);
	}
}
