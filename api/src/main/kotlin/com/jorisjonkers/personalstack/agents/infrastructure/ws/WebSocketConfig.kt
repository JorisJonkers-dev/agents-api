package com.jorisjonkers.personalstack.agents.infrastructure.ws

import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val sessionAttachHandler: SessionAttachHandler,
) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry
            .addHandler(sessionAttachHandler, "/api/v1/ws/sessions/*/attach")
            .setAllowedOriginPatterns("*")
    }
}
