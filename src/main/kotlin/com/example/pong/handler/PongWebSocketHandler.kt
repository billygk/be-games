package com.example.pong.handler

import com.example.pong.dto.IncomingMessage
import com.example.pong.dto.PlayerMove
import com.example.pong.service.GameService
import com.fasterxml.jackson.databind.ObjectMapper
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

    override fun afterConnectionEstablished(session: WebSocketSession) {
        println("New connection established: ${session.id}")
        gameService.addPlayer(session)
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        try {
            val incomingMessage = objectMapper.readValue(message.payload, IncomingMessage::class.java)

            when (incomingMessage.type) {
                "player-move" -> {
                    val playerMove = objectMapper.treeToValue(incomingMessage.payload, PlayerMove::class.java)
                    gameService.getPlayerKey(session)?.let { playerKey ->
                        gameService.updatePlayerPosition(playerKey, playerMove.y)
                    }
                }
                // Handle other message types if any
                else -> println("Unknown message type: ${incomingMessage.type}")
            }
        } catch (e: Exception) {
            println("Error handling message: ${e.message}")
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        println("Connection closed: ${session.id} with status: $status")
        gameService.removePlayer(session)
    }
}