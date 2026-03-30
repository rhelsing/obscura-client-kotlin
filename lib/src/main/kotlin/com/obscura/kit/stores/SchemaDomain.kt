package com.obscura.kit.stores

import com.obscura.kit.orm.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SchemaDomain - Confined coroutines. ORM model definitions and sync handling.
 */
class SchemaDomain internal constructor(
    private val store: ModelStore,
    private val syncManager: SyncManager,
    private val ttlManager: TTLManager,
    private val deviceId: String = "",
    private val signalManager: SignalManager? = null
) {
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val schema = Schema(store, syncManager, ttlManager, deviceId, signalManager)

    /** Set the local username — used for signal senderUsername. */
    fun setUsername(username: String) { schema.username = username }

    suspend fun define(definitions: Map<String, ModelConfig>) = withContext(dispatcher) {
        schema.define(definitions)
    }

    fun model(name: String): Model = schema.model(name)

    fun modelOrNull(name: String): Model? = schema.modelOrNull(name)

    suspend fun handleSync(modelSync: ModelSyncData, from: String): OrmEntry? = withContext(dispatcher) {
        syncManager.handleIncoming(modelSync, from)
    }
}
