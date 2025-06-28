package com.example.pong.service

import com.example.pong.dto.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Service
class GameService(private val objectMapper: ObjectMapper) {

    // Game Constants
    companion object {
        const val GAME_WIDTH = 800.0
        const val GAME_HEIGHT = 600.0
        const val PADDLE_WIDTH = 10.0
        const val PADDLE_HEIGHT = 100.0
        const val BALL_SIZE = 10.0
        const val PADDLE_SPEED = 6.0 // Not used on backend, but good to have
    }

    // Thread-safe state management
    private val players = ConcurrentHashMap<String, WebSocketSession>()
    private val gameState = GameState(
        ball = Ball(GAME_WIDTH / 2, GAME_HEIGHT / 2),
        player1 = Paddle(GAME_HEIGHT / 2 - PADDLE_HEIGHT / 2),
        player2 = Paddle(GAME_HEIGHT / 2 - PADDLE_HEIGHT / 2),
        score = Score(0, 0)
    )
    private var ballVelocityX = 5.0
    private var ballVelocityY = 5.0

    // Player Management
    @Synchronized
    fun addPlayer(session: WebSocketSession) {
        val playerKey = when {
            !players.containsKey("player1") -> "player1"
            !players.containsKey("player2") -> "player2"
            else -> null // Game is full
        }

        if (playerKey != null) {
            players[playerKey] = session
            sendMessage(session, PlayerAssignment(player = playerKey))
            if (players.size == 2) {
                resetGame()
            }
        } else {
            // Optionally send a "game full" message and close connection
            println("Game is full. Connection rejected for session: ${session.id}")
            try {
                session.close()
            } catch (e: IOException) {
                println("Error closing session: ${e.message}")
            }
        }
    }

    @Synchronized
    fun removePlayer(session: WebSocketSession) {
        val playerKey = getPlayerKey(session)
        if (playerKey != null) {
            players.remove(playerKey)
            println("Player $playerKey disconnected.")
            // Reset the game if a player leaves
            resetGame()
        }
    }

    fun getPlayerKey(session: WebSocketSession): String? {
        return players.entries.find { it.value == session }?.key
    }

    @Synchronized
    fun updatePlayerPosition(playerKey: String, y: Double) {
        val paddle = if (playerKey == "player1") gameState.player1 else gameState.player2
        // Clamp paddle position to be within game bounds
        paddle.y = y.coerceIn(0.0, GAME_HEIGHT - PADDLE_HEIGHT)
    }

    // Game Loop - runs at ~60 FPS
    @Scheduled(fixedRate = 16)
    fun gameLoop() {
        if (players.size < 2) {
            // Don't run the game loop if not enough players are connected.
            return
        }

        updateBallPosition()
        broadcastGameState()
    }

    private fun updateBallPosition() {
        // Move ball
        gameState.ball.x += ballVelocityX
        gameState.ball.y += ballVelocityY

        // Wall collision (top/bottom)
        if (gameState.ball.y <= 0 || gameState.ball.y >= GAME_HEIGHT - BALL_SIZE) {
            ballVelocityY *= -1
        }

        // Paddle collision
        val ball = gameState.ball
        val p1 = gameState.player1
        val p2 = gameState.player2

        // Player 1 paddle collision
        if (ball.x <= PADDLE_WIDTH && ball.y + BALL_SIZE >= p1.y && ball.y <= p1.y + PADDLE_HEIGHT) {
            ball.x = PADDLE_WIDTH // prevent ball from getting stuck in paddle
            ballVelocityX *= -1.05 // Invert and increase speed
        }

        // Player 2 paddle collision
        if (ball.x >= GAME_WIDTH - PADDLE_WIDTH - BALL_SIZE && ball.y + BALL_SIZE >= p2.y && ball.y <= p2.y + PADDLE_HEIGHT) {
            ball.x = GAME_WIDTH - PADDLE_WIDTH - BALL_SIZE // prevent ball from getting stuck
            ballVelocityX *= -1.05 // Invert and increase speed
        }


        // Score detection
        if (ball.x <= 0) {
            gameState.score.player2++
            resetBall()
        } else if (ball.x >= GAME_WIDTH) {
            gameState.score.player1++
            resetBall()
        }
    }

    private fun resetBall() {
        gameState.ball.x = GAME_WIDTH / 2
        gameState.ball.y = GAME_HEIGHT / 2

        // Give the ball a new random direction
        var angle = Random.nextDouble(-Math.PI / 4, Math.PI / 4)
        // Send it towards the other player
        if (Random.nextBoolean()) {
            angle += Math.PI
        }
        ballVelocityX = 5.0 * cos(angle)
        ballVelocityY = 5.0 * sin(angle)
    }

    @Synchronized
    private fun resetGame() {
        println("Resetting game state.")
        gameState.score.player1 = 0
        gameState.score.player2 = 0
        gameState.player1.y = GAME_HEIGHT / 2 - PADDLE_HEIGHT / 2
        gameState.player2.y = GAME_HEIGHT / 2 - PADDLE_HEIGHT / 2
        resetBall()
    }

    // Broadcasting Logic
    private fun broadcastGameState() {
        val message = GameStateUpdate(
            ball = gameState.ball,
            player1 = gameState.player1,
            player2 = gameState.player2,
            score = gameState.score
        )
        broadcast(message)
    }

    private fun broadcast(message: ServerMessage) {
        val jsonMessage = objectMapper.writeValueAsString(message)
        players.values.forEach { session ->
            sendMessage(session, jsonMessage)
        }
    }

    private fun sendMessage(session: WebSocketSession, message: ServerMessage) {
        sendMessage(session, objectMapper.writeValueAsString(message))
    }

    private fun sendMessage(session: WebSocketSession, message: String) {
        try {
            if (session.isOpen) {
                session.sendMessage(TextMessage(message))
            }
        } catch (e: IOException) {
            println("Error sending message to session ${session.id}: ${e.message}")
            removePlayer(session)
        }
    }

    // Private data class to hold the mutable state internally
    private data class GameState(
        val ball: Ball,
        val player1: Paddle,
        val player2: Paddle,
        val score: Score
    )
}