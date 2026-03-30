package com.obscura.kit.orm

import com.obscura.kit.orm.crdt.GSet
import com.obscura.kit.orm.crdt.LWWMap

/**
 * Schema - Defines and wires together models from config.
 */
class Schema(
    private val store: ModelStore,
    private val syncManager: SyncManager,
    private val ttlManager: TTLManager,
    private val deviceId: String = "",
    private val signalManager: SignalManager? = null,
    var username: String = ""
) {
    private val models = mutableMapOf<String, Model>()

    fun define(definitions: Map<String, ModelConfig>) {
        for ((name, config) in definitions) {
            val model = if (config.sync == "lww") {
                Model(
                    name = name, config = config,
                    lwwMap = LWWMap(store, name),
                    syncManager = syncManager, ttlManager = ttlManager,
                    deviceId = deviceId, store = store,
                    signalManager = signalManager
                )
            } else {
                Model(
                    name = name, config = config,
                    gset = GSet(store, name),
                    syncManager = syncManager, ttlManager = ttlManager,
                    deviceId = deviceId, store = store,
                    signalManager = signalManager
                )
            }
            model.localUsername = username
            models[name] = model
            syncManager.register(name, model)
        }
    }

    fun model(name: String): Model = models[name]
        ?: throw IllegalArgumentException("Unknown model '$name'. Did you define it? Available: ${models.keys.joinToString()}")

    fun modelOrNull(name: String): Model? = models[name]

    fun allModels(): Map<String, Model> = models.toMap()
}
