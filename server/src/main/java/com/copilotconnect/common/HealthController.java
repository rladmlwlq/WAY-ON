package com.copilotconnect.common;

import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// 서버와 DB 연결 확인용
@RestController
public class HealthController {

	private final JdbcTemplate jdbcTemplate;

	public HealthController(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@GetMapping("/health")
	public Map<String, String> health() {
		jdbcTemplate.queryForObject("SELECT 1", Integer.class);
		return Map.of("status", "UP", "db", "UP");
	}

}
