package com.obscura.kit.orm

import com.obscura.kit.db.ObscuraDatabase
import org.json.JSONObject

/**
 * SQLDelight-backed persistence for ORM entries.
 */
class ModelStore(internal val db: ObscuraDatabase) {

    fun put(modelName: String, entry: OrmEntry) {
        val jsonData = JSONObject(entry.data).toString()
        db.modelEntryQueries.insertEntry(
            model_name = modelName,
            entry_id = entry.id,
            data_ = jsonData,
            timestamp = entry.timestamp,
            author_device_id = entry.authorDeviceId,
            signature = entry.signature.takeIf { it.isNotEmpty() },
            deleted = if (entry.isDeleted) 1L else 0L,
            ttl_expires_at = null
        )
    }

    fun getAll(modelName: String): List<OrmEntry> {
        val now = System.currentTimeMillis()
        return db.modelEntryQueries.selectByModel(modelName).executeAsList()
            .filter { row -> row.ttl_expires_at == null || row.ttl_expires_at > now } // Exclude expired entries so TTL-limited models (e.g. stories) disappear on time
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

    fun find(modelName: String, id: String): OrmEntry? {
        val row = db.modelEntryQueries.selectByModelAndId(modelName, id).executeAsOneOrNull()
            ?: return null
        // Single-entry reads must also respect TTL, not just list queries
        if (row.ttl_expires_at != null && row.ttl_expires_at <= System.currentTimeMillis()) return null
        return OrmEntry(
            id = row.entry_id,
            data = parseJsonMap(row.data_),
            timestamp = row.timestamp,
            authorDeviceId = row.author_device_id,
            signature = row.signature ?: ByteArray(0)
        )
    }

    fun markDeleted(modelName: String, id: String) {
        db.modelEntryQueries.markDeleted(
            timestamp = System.currentTimeMillis(),
            model_name = modelName,
            entry_id = id
        )
    }

    fun setTTL(modelName: String, id: String, expiresAt: Long) {
        val existing = db.modelEntryQueries.selectByModelAndId(modelName, id).executeAsOneOrNull()
        if (existing != null) {
            db.modelEntryQueries.insertEntry(
                model_name = modelName,
                entry_id = id,
                data_ = existing.data_,
                timestamp = existing.timestamp,
                author_device_id = existing.author_device_id,
                signature = existing.signature,
                deleted = existing.deleted,
                ttl_expires_at = expiresAt
            )
        }
    }

    fun getExpired(): List<Pair<String, String>> {
        val now = System.currentTimeMillis()
        return db.modelEntryQueries.selectExpired(now).executeAsList().map {
            Pair(it.model_name, it.entry_id)
        }
    }

    fun deleteExpired() {
        db.modelEntryQueries.deleteExpired(System.currentTimeMillis())
    }

    fun addAssociation(belongsToModel: String, belongsToId: String, modelName: String, entryId: String) {
        db.modelEntryQueries.insertAssociation(
            model_name = modelName,
            entry_id = entryId,
            belongs_to_model = belongsToModel,
            belongs_to_id = belongsToId
        )
    }

    fun getAssociated(belongsToModel: String, belongsToId: String): List<OrmEntry> {
        return db.modelEntryQueries.selectAssociations(belongsToModel, belongsToId).executeAsList().map { row ->
            OrmEntry(
                id = row.entry_id,
                data = parseJsonMap(row.data_),
                timestamp = row.timestamp,
                authorDeviceId = row.author_device_id,
                signature = row.signature ?: ByteArray(0)
            )
        }
    }

    private fun parseJsonMap(json: String): Map<String, Any?> {
        val obj = JSONObject(json)
        val map = mutableMapOf<String, Any?>()
        for (key in obj.keys()) {
            map[key] = when {
                obj.isNull(key) -> null
                else -> obj.get(key)
            }
        }
        return map
    }
}
