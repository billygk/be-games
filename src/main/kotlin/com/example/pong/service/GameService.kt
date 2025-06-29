package com.example.pong.service

import com.example.pong.dto.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
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

    private val logger = LoggerFactory.getLogger(GameService::class.java)

    // Game Constants
    companion object {
        const val GAME_WIDTH = 800.0
        const val GAME_HEIGHT = 600.0
        const val PADDLE_WIDTH = 10.0
        const val PADDLE_HEIGHT = 100.0
        const val BALL_SIZE = 10.0
        const val PADDLE_SPEED = 15.0
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
    private var gameRunning = false

    // Player Management
    @Synchronized
    fun addPlayer(session: WebSocketSession) {
        val playerKey = when {
            !players.containsKey("player1") -> "player1"
            !players.containsKey("player2") -> "player2"
            else -> null // Game is full
        }

        if (playerKey != null) {
            logger.info("Assigning session ${session.id} to $playerKey")
            players[playerKey] = session

            // Construct the settings payload
            val settings = GameSettings(
                paddleSpeed = PADDLE_SPEED,
                gameWidth = GAME_WIDTH,
                gameHeight = GAME_HEIGHT,
                paddleHeight = PADDLE_HEIGHT,
                paddleWidth = PADDLE_WIDTH,
                ballSize = BALL_SIZE
            )
            // Send the extended assignment message
            sendMessage(session, PlayerAssignment(player = playerKey, settings = settings))

            if (players.size == 2) {
                logger.info("Both players connected. Starting game.")
                gameRunning = true
                resetGame()
            }
        } else {
            logger.warn("Game is full. Rejecting connection for session: ${session.id}")
            try {
                session.close()
            } catch (e: IOException) {
                logger.error("Error closing session ${session.id} after rejection: ${e.message}", e)
            }
        }
    }

    @Synchronized
    fun removePlayer(session: WebSocketSession) {
        val playerKey = getPlayerKey(session)
        if (playerKey != null) {
            players.remove(playerKey)
            logger.info("Player $playerKey (session ${session.id}) disconnected.")
            // Stop and reset the game if a player leaves
            gameRunning = false
            resetGame()
            logger.info("Game stopped and reset due to player disconnection.")
        } else {
            logger.warn("Attempted to remove a non-existent player for session ${session.id}")
        }
    }

    fun getPlayerKey(session: WebSocketSession): String? {
        return players.entries.find { it.value == session }?.key
    }

    @Synchronized
    fun updatePlayerPosition(playerKey: String, y: Double) {
        val paddle = if (playerKey == "player1") gameState.player1 else gameState.player2
        // Clamp paddle position to be within game bounds
        val clampedY = y.coerceIn(0.0, GAME_HEIGHT - PADDLE_HEIGHT)
        paddle.y = clampedY
        logger.debug("Updated $playerKey paddle position to y=$clampedY")
    }

    // Game Loop - runs at ~60 FPS
    @Scheduled(fixedRate = 16)
    fun gameLoop() {
        if (!gameRunning) {
            // Don't run the game loop if not enough players are connected or game is paused.
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
            logger.debug("Ball collided with top/bottom wall.")
        }

        // Paddle collision
        val ball = gameState.ball
        val p1 = gameState.player1
        val p2 = gameState.player2

        // Player 1 paddle collision
        if (ball.x <= PADDLE_WIDTH && ball.y + BALL_SIZE >= p1.y && ball.y <= p1.y + PADDLE_HEIGHT) {
            ball.x = PADDLE_WIDTH // prevent ball from getting stuck in paddle
            ballVelocityX *= -1.05 // Invert and increase speed
            logger.debug("-- Ball collided with player 1 paddle.")
        }

        // Player 2 paddle collision
        if (ball.x >= GAME_WIDTH - PADDLE_WIDTH - BALL_SIZE && ball.y + BALL_SIZE >= p2.y && ball.y <= p2.y + PADDLE_HEIGHT) {
            ball.x = GAME_WIDTH - PADDLE_WIDTH - BALL_SIZE // prevent ball from getting stuck
            ballVelocityX *= -1.05 // Invert and increase speed
            logger.debug("-- Ball collided with player 2 paddle.")
        }


        // Score detection
        if (ball.x <= 0) {
            gameState.score.player2++
            logger.info("Player 2 scored! Score is now ${gameState.score.player1} - ${gameState.score.player2}")
            resetBall()
        } else if (ball.x >= GAME_WIDTH) {
            gameState.score.player1++
            logger.info("Player 1 scored! Score is now ${gameState.score.player1} - ${gameState.score.player2}")
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
        logger.info("Ball reset to center with new velocity.")
    }

    @Synchronized
    private fun resetGame() {
        logger.info("Resetting game state. Score: 0-0.")
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
        logger.trace("Broadcasting game state: {}", jsonMessage)
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
            } else {
                logger.warn("Attempted to send message to closed session ${session.id}")
            }
        } catch (e: IOException) {
            logger.error("Error sending message to session ${session.id}: ${e.message}", e)
            // The connection is likely broken, so we should remove the player.
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