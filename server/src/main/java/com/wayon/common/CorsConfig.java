package com.wayon.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 폰 웹(Vercel)과 서버 주소가 달라서 웹 주소만 허용한다 (app-api.md 0장) */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

	private final String webUrl;

	public CorsConfig(@Value("${wayon.web-url}") String webUrl) {
		this.webUrl = webUrl;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
				.allowedOrigins(webUrl, "http://localhost:5173")
				.allowedMethods("GET", "POST", "PUT", "DELETE")
				.allowedHeaders("Authorization", "Content-Type");
	}
}
