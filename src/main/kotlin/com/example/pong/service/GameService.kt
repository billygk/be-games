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

    // Construct the settings payload
    val settings = GameSettings(
        paddleSpeed = 5.0,
        gameWidth = 800.0,
        gameHeight = 600.0,
        paddleHeight = 100.0,
        paddleWidth = 15.0,
        ballSize = 10.0,
        ballSpeed = 1.05
    )
    // Thread-safe state management
    private val players = ConcurrentHashMap<String, WebSocketSession>()
    private val gameState = GameState(
        ball = Ball(settings.gameWidth / 2, settings.gameHeight / 2),
        player1 = Paddle(settings.gameHeight / 2 - settings.paddleHeight / 2),
        player2 = Paddle(settings.gameHeight / 2 - settings.paddleHeight / 2),
        score = Score(0, 0)
    )
    private var ballVelocityX = 2.0
    private var ballVelocityY = 2.0
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
        val clampedY = y.coerceIn(0.0, settings.gameHeight - settings.paddleHeight)
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
        if (gameState.ball.y <= 0 || gameState.ball.y >= settings.gameHeight - settings.ballSize) {
            ballVelocityY *= -1
            logger.debug("Ball collided with top/bottom wall.")
        }

        // Paddle collision
        val ball = gameState.ball
        val p1 = gameState.player1
        val p2 = gameState.player2

        // Check for collision with Player 1 (left paddle)
        // A collision occurs if the ball is moving left, its left edge has crossed
        // the paddle's right edge, and it vertically aligns with the paddle.
        if (ballVelocityX < 0 &&
            ball.x <= settings.paddleWidth &&
            ball.y + settings.ballSize >= p1.y && ball.y <= p1.y + settings.paddleHeight
        ) {
            ball.x = settings.paddleWidth // Snap to paddle face to prevent getting stuck
            ballVelocityX *= -settings.ballSpeed // Reverse direction and increase speed
            logger.debug("-- Ball collided with player 1 paddle.")
        }
        // Check for collision with Player 2 (right paddle)
        // A collision occurs if the ball is moving right, its right edge has crossed
        // the paddle's left edge, and it vertically aligns with the paddle.
        else if (ballVelocityX > 0 &&
            ball.x + settings.ballSize >= settings.gameWidth - settings.paddleWidth &&
            ball.y + settings.ballSize >= p2.y && ball.y <= p2.y + settings.paddleHeight
        ) {
            ball.x = settings.gameWidth - settings.paddleWidth - settings.ballSize // Snap to paddle face
            ballVelocityX *= -settings.ballSpeed // Reverse direction and increase speed
            logger.debug("-- Ball collided with player 2 paddle.")
            logger.debug("   paddle size: ${settings.paddleHeight}")
            logger.debug("   p2.y: ${p2.y}")
            logger.debug("   p2.y + settings.paddleHeight: ${p2.y + settings.paddleHeight}")
            logger.debug("   ball size: ${settings.ballSize}")
            logger.debug("   ball.y: ${ball.y}")
            logger.debug("   ball.y + settings.ballSize: ${ball.y + settings.ballSize}")
        }


        // Score detection
        if (ball.x <= 0) {
            gameState.score.player2++
            logger.info("Player 2 scored! Score is now ${gameState.score.player1} - ${gameState.score.player2}")
            resetBall()
        } else if (ball.x + settings.ballSize >= settings.gameWidth) {
            gameState.score.player1++
            logger.info("Player 1 scored! Score is now ${gameState.score.player1} - ${gameState.score.player2}")
            resetBall()
        }
    }

    private fun resetBall() {
        gameState.ball.x = settings.gameWidth / 2
        gameState.ball.y = settings.gameHeight / 2

        // Give the ball a new random direction
        var angle = Random.nextDouble(-Math.PI / 4, Math.PI / 4)
        // Send it towards a random player
        if (Random.nextBoolean()) {
            angle += Math.PI
        }
        ballVelocityX = 2.0 * cos(angle)
        ballVelocityY = 2.0 * sin(angle)
        logger.info("Ball reset to center with new velocity.")
    }

    @Synchronized
    private fun resetGame() {
        logger.info("Resetting game state. Score: 0-0.")
        gameState.score.player1 = 0
        gameState.score.player2 = 0
        gameState.player1.y = settings.gameHeight / 2 - settings.paddleHeight / 2
        gameState.player2.y = settings.gameHeight / 2 - settings.paddleHeight / 2
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