package com.wayon.common;

import org.springframework.http.HttpStatus;

/**
 * app-api.md 8장 오류. 응답은 HTTP 상태 + {"code", "message"}.
 * message는 폰·차 화면에 그대로 보여도 되는 문장으로 쓴다.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	public ApiException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

	public static ApiException placeNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND", "장소 정보를 찾지 못했어요");
	}
}
