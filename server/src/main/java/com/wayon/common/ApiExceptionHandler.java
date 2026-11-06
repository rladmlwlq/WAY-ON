package com.wayon.common;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** app-api.md 0장 실패 응답 모양으로 바꾼다. */
@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<Map<String, String>> api(ApiException e) {
		return ResponseEntity.status(e.getStatus())
				.body(Map.of("code", e.getCode(), "message", e.getMessage()));
	}

	// 경로 변수 형식이 틀림 (예: /api/rest-areas/abc)
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<Map<String, String>> badRequest(MethodArgumentTypeMismatchException e) {
		return ResponseEntity.badRequest()
				.body(Map.of("code", "BAD_REQUEST", "message", "요청 형식이 맞지 않아요"));
	}
}
