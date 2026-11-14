package com.wayon.vehicle;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** app-api.md 3-1~3-5 REST: 폰 웹 */
@RestController
@RequestMapping("/api")
public class PassengerController {

	private final VehicleService service;

	public PassengerController(VehicleService service) {
		this.service = service;
	}

	@PostMapping("/connect")
	@ResponseStatus(HttpStatus.CREATED)
	public VehicleDto.Connected save_connect(@RequestBody VehicleDto.ConnectRequest request) {
		return service.save_connect(request);
	}

	@GetMapping("/passengers/me")
	public VehicleDto.Me select_passengerMe(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
		return service.select_passengerMe(authorization);
	}

	@GetMapping("/push/vapid-public-key")
	public VehicleDto.VapidKey select_vapidPublicKey() {
		return service.select_vapidPublicKey();
	}

	@PutMapping("/passengers/me/push-subscription")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void save_pushSubscription(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@RequestBody VehicleDto.PushSubscription request) {
		service.save_pushSubscription(authorization, request);
	}

	@DeleteMapping("/passengers/me/push-subscription")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete_pushSubscription(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
		service.delete_pushSubscription(authorization);
	}
}
