# BE-Games: Real-time Pong Server

This project is a backend server for a classic multiplayer Pong game. It is built with Kotlin and the Spring Boot framework, utilizing WebSockets for real-time, low-latency communication with game clients.

The server is authoritative, meaning it handles all game state management, physics calculations, and player lifecycle events. Clients connect to the server, send their paddle movement commands, and receive regular game state updates to render the game.

## ✨ Features

*   **Real-time Multiplayer:** Supports two players in a single game session.
*   **WebSocket Communication:** Uses Spring WebSockets for efficient, stateful connections.
*   **Authoritative Server Model:** All game logic, physics, and state are managed securely on the server.
*   **Dynamic Game Loop:** A scheduled game loop runs at approximately 60 FPS (`@Scheduled(fixedRate = 16)`) to update and broadcast the game state.
*   **Player Management:** Automatically assigns players as `player1` or `player2` on connection and gracefully handles disconnections by resetting the game.
*   **Simple Physics:** Includes logic for ball movement, wall bounces, and paddle collisions with a slight speed increase on each paddle hit.

## 🛠️ Tech Stack

*   **Language:** Kotlin
*   **Framework:** Spring Boot
*   **Real-time Communication:** Spring WebSockets
*   **Scheduling:** Spring Task Scheduling (`@EnableScheduling`)
*   **Serialization:** Jackson for JSON processing

## 🔌 WebSocket API Protocol

To build a frontend for this game, connect to the WebSocket endpoint and follow the message protocol outlined below.

**Endpoint:** `ws://<your-server-address>/pong`

---

### Server-to-Client Messages

The server sends messages to the client to assign a player role and to provide continuous updates on the game state.

#### 1. Player Assignment

Sent once immediately after a client successfully connects. It tells the client which player they are and provides all the initial game settings needed to draw the board and game objects.

**Type:** `player-assignment`

**Payload Example:**

## Front end client

https://github.com/billygk/fe-games