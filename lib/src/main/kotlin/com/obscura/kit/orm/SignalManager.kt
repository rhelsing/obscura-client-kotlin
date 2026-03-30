package com.obscura.kit.orm

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * ECS Signal Manager — ephemeral signals attached to models.
 *
 * Signals are NOT persisted, NOT CRDT-merged. They live in memory,
 * auto-expire after 3 seconds, and emit to reactive observers.
 *
 * Used for: typing indicators, read receipts, online status.
 *
 * Wire format: MODEL_SIGNAL (type 31) in ClientMessage.
 */
class SignalManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Key: "${model}:${signal}:${contextKey}" → Set of active authors
    // contextKey is extracted from data (e.g., conversationId)
    private val activeSignals = MutableStateFlow<Map<String, Set<ActiveSignal>>>(emptyMap())

    data class ActiveSignal(
        val authorDeviceId: String,
        val senderUsername: String,
        val expiresAt: Long
    )

    // Callbacks — wired by ObscuraClient
    var sendSignal: suspend (model: String, signal: String, data: Map<String, Any?>) -> Unit = { _, _, _ -> }

    /**
     * Emit a signal. Auto-throttled: won't re-send if the same signal
     * was sent within the last 2 seconds.
     */
    private val lastSent = mutableMapOf<String, Long>()
    private val THROTTLE_MS = 2000L

    suspend fun emit(model: String, signal: String, data: Map<String, Any?>, authorDeviceId: String) {
        val throttleKey = "$model:$signal:$authorDeviceId"
        val now = System.currentTimeMillis()
        val last = lastSent[throttleKey] ?: 0L
        if (now - last < THROTTLE_MS) return

        lastSent[throttleKey] = now
        sendSignal(model, signal, data)
    }

    /**
     * Receive an incoming signal from the wire.
     * Holds it in memory for 3 seconds, then auto-expires.
     */
    fun receive(model: String, signal: String, data: Map<String, Any?>, authorDeviceId: String) {
        val contextKey = data["conversationId"] as? String ?: "global"
        val key = "$model:$signal:$contextKey"
        val senderUsername = data["senderUsername"] as? String ?: authorDeviceId
        val expiresAt = System.currentTimeMillis() + EXPIRE_MS

        val active = ActiveSignal(authorDeviceId, senderUsername, expiresAt)

        val current = activeSignals.value.toMutableMap()
        val existing = current[key]?.toMutableSet() ?: mutableSetOf()
        existing.removeAll { it.authorDeviceId == authorDeviceId }
        existing.add(active)
        current[key] = existing
        activeSignals.value = current

        // Schedule expiry — only remove if the signal hasn't been renewed
        scope.launch {
            delay(EXPIRE_MS + 100) // small buffer
            val now = System.currentTimeMillis()
            val signals = activeSignals.value[key] ?: return@launch
            val entry = signals.find { it.authorDeviceId == authorDeviceId } ?: return@launch
            if (entry.expiresAt <= now) {
                expire(key, authorDeviceId)
            }
        }
    }

    /**
     * Immediately clear a signal (e.g., stoppedTyping).
     */
    fun clear(model: String, signal: String, data: Map<String, Any?>, authorDeviceId: String) {
        val contextKey = data["conversationId"] as? String ?: "global"
        val key = "$model:$signal:$contextKey"
        expire(key, authorDeviceId)
    }

    /**
     * Observe who is actively signaling for a given model + signal + context.
     * Returns usernames of active signalers.
     */
    fun observe(model: String, signal: String, contextKey: String): Flow<List<String>> {
        val key = "$model:$signal:$contextKey"
        return activeSignals.map { signals ->
            val now = System.currentTimeMillis()
            (signals[key] ?: emptySet())
                .filter { it.expiresAt > now }
                .map { it.senderUsername }
        }
    }

    private fun expire(key: String, authorDeviceId: String) {
        val current = activeSignals.value.toMutableMap()
        val existing = current[key]?.toMutableSet() ?: return
        existing.removeAll { it.authorDeviceId == authorDeviceId }
        if (existing.isEmpty()) current.remove(key) else current[key] = existing
        activeSignals.value = current
    }

    companion object {
        const val EXPIRE_MS = 3000L
    }
}
