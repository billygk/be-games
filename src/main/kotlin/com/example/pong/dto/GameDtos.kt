package com.example.pong.dto

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.fasterxml.jackson.databind.JsonNode

// Incoming Message Models

/**
 * A generic wrapper for all incoming messages from the client.
 * The 'type' field is used to determine how to deserialize the 'payload'.
 */
data class IncomingMessage(val type: String, val payload: JsonNode)

/**
 * DTO for player movement updates.
 */
data class PlayerMove(val y: Double)


// Outgoing Message Models

/**
 * A sealed interface for all messages sent from the server to the client.
 * This allows for exhaustive 'when' statements and clear modeling of server responses.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
sealed interface ServerMessage


/**
 * Informs a client which player they are (player1 or player2) and provides initial game settings.
 * Serializes to: {"type": "player-assignment", "player": "player1", "settings": {...}}
 */
@JsonTypeName("player-assignment")
data class PlayerAssignment(
    val player: String,
    val settings: GameSettings
) : ServerMessage

/**
 * Represents a full snapshot of the game state, broadcast to all clients on every tick.
 * Serializes to: {"type": "game-state", "ball": {...}, "player1": {...}, ...}
 */
@JsonTypeName("game-state")
data class GameStateUpdate(
    val ball: Ball,
    val player1: Paddle,
    val player2: Paddle,
    val score: Score
) : ServerMessage


// Component Models for GameStateUpdate

/**
 * Contains initial game constants for the client, controlled by the server.
 */
data class GameSettings(
    val paddleSpeed: Double,
    val gameWidth: Double,
    val gameHeight: Double,
    val paddleHeight: Double,
    val paddleWidth: Double,
    val ballSize: Double,
    val ballInitialSpeed: Double,
    val ballSpeedIncreaseFactor: Double,
    val initialPlayer1: Paddle,
    val initialPlayer2: Paddle,
    val initialBall: Ball
)

data class Ball(var x: Double, var y: Double)
data class Paddle(var x: Double, var y: Double)
data class Score(var player1: Int, var player2: Int)
