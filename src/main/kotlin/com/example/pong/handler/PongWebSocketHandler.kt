package com.example.pong.handler

import com.example.pong.dto.IncomingMessage
import com.example.pong.dto.PlayerMove
import com.example.pong.service.GameService
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler

@Component
class PongWebSocketHandler(
    private val gameService: GameService,
    private val objectMapper: ObjectMapper
) : TextWebSocketHandler() {

    private val logger = LoggerFactory.getLogger(PongWebSocketHandler::class.java)

    override fun afterConnectionEstablished(session: WebSocketSession) {
        logger.info("New connection established: id=${session.id}, uri=${session.uri}")
        gameService.addPlayer(session)
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        try {
            logger.debug("Received message from session ${session.id}: ${message.payload}")
            val incomingMessage = objectMapper.readValue(message.payload, IncomingMessage::class.java)

            when (incomingMessage.type) {
                "player-move" -> {
                    val playerMove = objectMapper.treeToValue(incomingMessage.payload, PlayerMove::class.java)
                    gameService.getPlayerKey(session)?.let { playerKey ->
                        gameService.updatePlayerPosition(playerKey, playerMove.y)
                    } ?: logger.warn("Received 'player-move' from an unknown session: ${session.id}")
                }
                // Handle other message types if any
                else -> logger.warn("Received unknown message type '${incomingMessage.type}' from session ${session.id}")
            }
        } catch (e: Exception) {
            logger.error("Error handling message from session ${session.id}: ${e.message}", e)
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        logger.info("Connection closed: id=${session.id}, status=$status")
        gameService.removePlayer(session)
    }
}