package com.wayon.ws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** /ws. 허용 Origin은 CORS와 같다 (Origin 헤더가 없는 차량 앱은 그대로 통과) */
@Configuration
@EnableWebSocket
public class WsConfig implements WebSocketConfigurer {

	private final WsHandler handler;
	private final String webUrl;

	public WsConfig(WsHandler handler, @Value("${wayon.web-url}") String webUrl) {
		this.handler = handler;
		this.webUrl = webUrl;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(handler, "/ws").setAllowedOrigins(webUrl, "http://localhost:5173");
	}
}
