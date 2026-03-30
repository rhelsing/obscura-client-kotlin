package com.obscura.kit.network

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString
import xyz.obscura.server.contracts.ObscuraProtocol.*
import java.util.concurrent.TimeUnit

enum class GatewayState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

/**
 * WebSocket connection to Obscura gateway.
 *
 * Keeps the connection alive with:
 * - OkHttp ping every 30s (keeps NAT/proxy alive)
 * - Auto-reconnect with exponential backoff (1s → 30s)
 * - Token refresh before reconnect attempts
 * - Intentional disconnect (code 1000) suppresses reconnect
 */
class GatewayConnection(
    private val api: APIClient,
    private val scope: CoroutineScope
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(90, TimeUnit.SECONDS)  // if no data or pong for 90s, connection is dead
        .pingInterval(30, TimeUnit.SECONDS) // keepalive — triggers onFailure if pong missing
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var shouldReconnect = true

    private val _state = MutableStateFlow(GatewayState.DISCONNECTED)
    val state: StateFlow<GatewayState> = _state

    val envelopes = Channel<Envelope>(capacity = 1000)
    val preKeyStatus = Channel<PreKeyStatus>(capacity = 10)

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    /** Called before reconnect to ensure token is fresh. Set by ObscuraClient. */
    var ensureFreshToken: (suspend () -> Boolean) = { true }

    suspend fun connect() {
        if (_state.value == GatewayState.CONNECTED || _state.value == GatewayState.CONNECTING) return
        _state.value = GatewayState.CONNECTING
        shouldReconnect = true

        try {
            val ticket = api.fetchGatewayTicket()
            val url = api.getGatewayUrl(ticket)
            openWebSocket(url)
        } catch (e: Exception) {
            _state.value = GatewayState.DISCONNECTED
            throw e
        }
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _state.value = GatewayState.DISCONNECTED
        onDisconnected?.invoke()
    }

    fun ack(messageIds: List<com.google.protobuf.ByteString>) {
        val ackMsg = AckMessage.newBuilder()
            .addAllMessageIds(messageIds)
            .build()

        val frame = WebSocketFrame.newBuilder()
            .setAck(ackMsg)
            .build()

        webSocket?.send(ByteString.of(*frame.toByteArray()))
    }

    private fun openWebSocket(url: String) {
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = GatewayState.CONNECTED
                reconnectAttempts = 0
                onConnected?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val frame = WebSocketFrame.parseFrom(bytes.toByteArray())
                    when {
                        frame.hasPreKeyStatus() -> preKeyStatus.trySend(frame.preKeyStatus)
                        frame.hasEnvelopeBatch() -> {
                            for (envelope in frame.envelopeBatch.envelopesList) {
                                envelopes.trySend(envelope)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.value = GatewayState.DISCONNECTED
                if (shouldReconnect) scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = GatewayState.DISCONNECTED
                onDisconnected?.invoke()
                // Reconnect on unexpected close (server restart, timeout, etc.)
                // Don't reconnect on intentional close (code 1000)
                if (code != 1000 && shouldReconnect) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            _state.value = GatewayState.RECONNECTING

            // Exponential backoff: 1s, 2s, 4s, 8s, 16s, 30s max
            val delayMs = (1000L * (1L shl reconnectAttempts.coerceAtMost(5))).coerceAtMost(30_000L)
            delay(delayMs)
            reconnectAttempts++

            try {
                // Refresh token before reconnecting — stale token = failed ticket fetch
                ensureFreshToken()
                connect()
            } catch (_: Exception) {
                // connect() failed — will retry via onFailure → scheduleReconnect
                if (shouldReconnect) scheduleReconnect()
            }
        }
    }
}
