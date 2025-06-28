package com.example.pong.config

import com.example.pong.handler.PongWebSocketHandler
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

@Configuration
@EnableWebSocket
class WebSocketConfig(private val pongWebSocketHandler: PongWebSocketHandler) : WebSocketConfigurer {

    private val logger = LoggerFactory.getLogger(WebSocketConfig::class.java)

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        logger.info("Registering PongWebSocketHandler for endpoint '/pong'")
        registry.addHandler(pongWebSocketHandler, "/pong")
            .setAllowedOrigins("*") // For development purposes, allow all origins.
    }
}