package com.obscura.kit.orm

import com.obscura.kit.orm.crdt.GSet
import com.obscura.kit.orm.crdt.LWWMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Generic base class for all ORM models.
 * Works for any model name: "story", "streak", "settings", etc.
 */
class Model(
    val name: String,
    val config: ModelConfig,
    private val gset: GSet? = null,
    private val lwwMap: LWWMap? = null,
    internal val syncManager: SyncManager? = null,
    private val ttlManager: TTLManager? = null,
    private val deviceId: String = "",
    internal val store: ModelStore? = null,
    internal var signalManager: SignalManager? = null
) {

    suspend fun create(data: Map<String, Any?>): OrmEntry {
        validate(data)

        val id = "${name}_${System.currentTimeMillis()}_${randomId()}"
        val entry = OrmEntry(
            id = id,
            data = data,
            timestamp = System.currentTimeMillis(),
            authorDeviceId = deviceId,
            signature = sign(name, id, data)
        )

        if (config.sync == "lww") {
            lwwMap!!.add(entry)
        } else {
            gset!!.add(entry)
        }

        if (config.ttl != null && ttlManager != null) {
            ttlManager.schedule(name, id, config.ttl!!)
        }

        // Auto-register association if this model belongs_to a parent
        if (config.belongsTo.isNotEmpty() && store != null) {
            for (parentModel in config.belongsTo) {
                val foreignKey = "${parentModel}Id"
                val parentId = data[foreignKey] as? String
                if (parentId != null) {
                    store.addAssociation(parentModel, parentId, name, id)
                }
            }
        }

        syncManager?.broadcast(this, entry)

        return entry
    }

    suspend fun upsert(id: String, data: Map<String, Any?>): OrmEntry {
        validate(data)
        val entry = OrmEntry(
            id = id,
            data = data,
            timestamp = System.currentTimeMillis(),
            authorDeviceId = deviceId,
            signature = sign(name, id, data)
        )

        val result = lwwMap?.set(entry) ?: gset?.add(entry) ?: entry

        if (result === entry) {
            syncManager?.broadcast(this, entry)
        }

        return result
    }

    suspend fun find(id: String): OrmEntry? {
        return if (config.sync == "lww") lwwMap?.get(id) else gset?.get(id)
    }

    fun where(conditions: Map<String, Any?>): QueryBuilder {
        return QueryBuilder(this).where(conditions)
    }

    /**
     * DSL query:
     *   story.where { "author" eq "alice"; "likes" atLeast 5 }.exec()
     */
    fun where(block: WhereBuilder.() -> Unit): QueryBuilder {
        val builder = WhereBuilder()
        builder.block()
        return QueryBuilder(this).where(builder.conditions)
    }

    suspend fun all(): List<OrmEntry> {
        return if (config.sync == "lww") lwwMap?.getAll() ?: emptyList() else gset?.getAll() ?: emptyList()
    }

    suspend fun allSorted(descending: Boolean = true): List<OrmEntry> {
        return if (config.sync == "lww") lwwMap?.getAllSorted(descending) ?: emptyList()
        else gset?.getAllSorted(descending) ?: emptyList()
    }

    /**
     * Observe all entries as a reactive Flow. Compose-ready:
     *   val stories = model.observe().collectAsState(emptyList())
     */
    fun observe(): Flow<List<OrmEntry>> {
        val db = store?.db ?: throw IllegalStateException("observe() requires a store-backed model")
        val now = System.currentTimeMillis()
        return db.modelEntryQueries.selectByModel(name)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows ->
                rows.filter { row -> row.ttl_expires_at == null || row.ttl_expires_at > System.currentTimeMillis() }
                    .map { row ->
                        OrmEntry(
                            id = row.entry_id,
                            data = parseJsonMap(row.data_),
                            timestamp = row.timestamp,
                            authorDeviceId = row.author_device_id,
                            signature = row.signature ?: ByteArray(0)
                        )
                    }
            }
    }

    private fun parseJsonMap(json: String): Map<String, Any?> {
        val obj = JSONObject(json)
        val map = mutableMapOf<String, Any?>()
        for (key in obj.keys()) { map[key] = if (obj.isNull(key)) null else obj.get(key) }
        return map
    }

    suspend fun delete(id: String) {
        if (config.sync != "lww") throw IllegalStateException("Delete only supported for LWW models")
        lwwMap?.delete(id, deviceId)
    }

    // ─── ECS Signals (ephemeral, not persisted) ─────────────────

    /** Username of the local user — set by ObscuraClient when wiring. */
    internal var localUsername: String = ""

    /**
     * Send a typing indicator for a conversation.
     * Auto-throttled: sends at most once per 2 seconds.
     */
    suspend fun typing(conversationId: String) {
        signalManager?.emit(name, "typing", mapOf("conversationId" to conversationId, "senderUsername" to localUsername), deviceId)
    }

    /** Explicitly stop typing. */
    suspend fun stopTyping(conversationId: String) {
        signalManager?.emit(name, "stoppedTyping", mapOf("conversationId" to conversationId, "senderUsername" to localUsername), deviceId)
    }

    /** Send a read receipt. */
    suspend fun read(conversationId: String) {
        signalManager?.emit(name, "read", mapOf("conversationId" to conversationId, "senderUsername" to localUsername), deviceId)
    }

    /**
     * Observe who is typing in a conversation.
     * Returns Flow<List<String>> — list of usernames currently typing.
     * Auto-expires after 3 seconds of no signal.
     */
    fun observeTyping(conversationId: String): Flow<List<String>> {
        return signalManager?.observe(name, "typing", conversationId)
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }

    /** Observe read receipts for a conversation. */
    fun observeRead(conversationId: String): Flow<List<String>> {
        return signalManager?.observe(name, "read", conversationId)
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }

    suspend fun handleSync(modelSync: ModelSyncData): OrmEntry? {
        val entry = OrmEntry(
            id = modelSync.id,
            data = decodeData(modelSync.data),
            timestamp = modelSync.timestamp,
            authorDeviceId = modelSync.authorDeviceId,
            signature = modelSync.signature
        )

        val merged = if (config.sync == "lww") {
            lwwMap?.merge(listOf(entry)) ?: emptyList()
        } else {
            gset?.merge(listOf(entry)) ?: emptyList()
        }

        return merged.firstOrNull()
    }

    fun validate(data: Map<String, Any?>) {
        for ((field, type) in config.fields) {
            val isOptional = type.endsWith("?")
            val baseType = type.removeSuffix("?")
            val value = data[field]

            if (value == null) {
                if (!isOptional) throw ValidationException("$field is required")
                continue
            }

            when (baseType) {
                "string" -> if (value !is String) throw ValidationException("$field must be string")
                "number" -> if (value !is Number) throw ValidationException("$field must be number")
                "boolean" -> if (value !is Boolean) throw ValidationException("$field must be boolean")
                "timestamp" -> if (value !is Number || value.toLong() < 0) throw ValidationException("$field must be positive timestamp")
            }
        }
    }

    fun getTargetingAssociation(): Pair<String, String>? {
        if (config.belongsTo.isEmpty()) return null
        val parent = config.belongsTo[0]
        return Pair(parent, "${parent}Id")
    }

    private fun sign(model: String, id: String, data: Map<String, Any?>): ByteArray {
        val toSign = JSONObject(mapOf(
            "model" to model,
            "id" to id,
            "data" to data
        )).toString()
        return MessageDigest.getInstance("SHA-256").digest(toSign.toByteArray())
    }

    private fun decodeData(data: ByteArray): Map<String, Any?> {
        val json = String(data)
        val obj = JSONObject(json)
        val map = mutableMapOf<String, Any?>()
        for (key in obj.keys()) {
            map[key] = if (obj.isNull(key)) null else obj.get(key)
        }
        return map
    }

    companion object {
        private fun randomId(length: Int = 8): String {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            return (1..length).map { chars.random() }.joinToString("")
        }
    }
}

data class ModelSyncData(
    val model: String,
    val id: String,
    val op: Int = 0,
    val timestamp: Long,
    val data: ByteArray,
    val authorDeviceId: String,
    val signature: ByteArray = ByteArray(0)
)

class ValidationException(message: String) : IllegalArgumentException(message)
