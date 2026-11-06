package com.wayon.restarea;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 3-7 휴게소 상세 (N6, A1). restAreaId는 도로공사 휴게소 코드 (/api/rest-areas/000001).
 * TODO 동승자 토큰 인증: 인증 공통부가 생기면 붙인다 (app-api.md 0장). 지금은 기준 데이터라 열어 둠.
 */
@RestController
@RequestMapping("/api/rest-areas")
public class RestAreaController {

	private final RestAreaService service;

	public RestAreaController(RestAreaService service) {
		this.service = service;
	}

	@GetMapping("/{restAreaId}")
	public RestAreaDto.Detail detail(@PathVariable String restAreaId) {
		return service.detail(restAreaId);
	}
}
