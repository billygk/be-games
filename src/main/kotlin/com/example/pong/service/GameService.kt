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
        ballInitialSpeed = 2.5,
        ballSpeedIncreaseFactor = 1.15,
        initialPlayer1 = Paddle( 30.0,  300.0),
        initialPlayer2 = Paddle(755.0, 300.0), // Corrected position: 800 (width) - 15 (paddle) - 30 (margin) = 755
        initialBall = Ball(400.0, 300.0)
    )
    // Thread-safe state management
    private val players = ConcurrentHashMap<String, WebSocketSession>()
    private val gameState = GameState(
        ball = Ball(settings.initialBall.x, settings.initialBall.y),
        player1 = Paddle(settings.initialPlayer1.x, settings.initialPlayer1.y),
        player2 = Paddle(settings.initialPlayer2.x, settings.initialPlayer2.y),
        score = Score(0, 0)
    )
    private var ballVelocityX = settings.ballInitialSpeed
    private var ballVelocityY = settings.ballInitialSpeed
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

        val ball = gameState.ball
        val ballLeft = ball.x
        val ballRight = ball.x + settings.ballSize
        val ballTop = ball.y
        val ballBottom = ball.y + settings.ballSize

        // Determine which paddle to check for collision based on ball's direction
        val paddleToCheck = if (ballVelocityX < 0) gameState.player1 else gameState.player2

        val paddleLeft = paddleToCheck.x
        val paddleRight = paddleToCheck.x + settings.paddleWidth
        val paddleTop = paddleToCheck.y
        val paddleBottom = paddleToCheck.y + settings.paddleHeight

        // AABB intersection test
        if (ballRight > paddleLeft && ballLeft < paddleRight && ballBottom > paddleTop && ballTop < paddleBottom) {

            // *** FIXED: Correct snapping logic ***
            if (ballVelocityX < 0) { // Hitting left paddle
                ball.x = paddleRight
                logger.debug("-- Ball collided with player 1 paddle.")
            } else { // Hitting right paddle
                ball.x = paddleLeft - settings.ballSize
                logger.debug("-- Ball collided with player 2 paddle.")
            }

            ballVelocityX *= -settings.ballSpeedIncreaseFactor // Reverse direction and increase speed
        }


        // Score detection
        if (ball.x < 0) {
            gameState.score.player2++
            logger.info("Player 2 scored! Score is now ${gameState.score.player1} - ${gameState.score.player2}")
            resetBall()
        } else if (ball.x + settings.ballSize > settings.gameWidth) {
            gameState.score.player1++
            logger.info("Player 1 scored! Score is now ${gameState.score.player1} - ${gameState.score.player2}")
            resetBall()
        }
    }

    private fun resetBall() {
        gameState.ball.x = settings.initialBall.x
        gameState.ball.y = settings.initialBall.y

        var angle = Random.nextDouble(-Math.PI / 4, Math.PI / 4)
        if (Random.nextBoolean()) {
            angle += Math.PI
        }
        ballVelocityX = settings.ballInitialSpeed * cos(angle)
        ballVelocityY = settings.ballInitialSpeed * sin(angle)
        logger.info("Ball reset to center with new velocity.")
    }

    @Synchronized
    private fun resetGame() {
        logger.info("Resetting game state. Score: 0-0.")
        gameState.score.player1 = 0
        gameState.score.player2 = 0

        // *** FIXED: Reset paddles to initial positions from settings ***
        val p1 = gameState.player1
        p1.x = settings.initialPlayer1.x
        p1.y = settings.initialPlayer1.y

        val p2 = gameState.player2
        p2.x = settings.initialPlayer2.x
        p2.y = settings.initialPlayer2.y

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
            } else {
                logger.warn("Attempted to send message to closed session ${session.id}")
            }
        } catch (e: IOException) {
            logger.error("Error sending message to session ${session.id}: ${e.message}", e)
            removePlayer(session)
        }
    }

    private data class GameState(
        val ball: Ball,
        val player1: Paddle,
        val player2: Paddle,
        val score: Score
    )
}
