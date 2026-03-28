package com.obscura.kit.orm.crdt

import com.obscura.kit.orm.ModelStore
import com.obscura.kit.orm.OrmEntry

/**
 * LWWMap - Last-Writer-Wins Map CRDT
 *
 * Used for mutable state: streaks, settings, profiles, reactions.
 * On conflict, highest timestamp wins.
 */
class LWWMap(
    private val store: ModelStore,
    val modelName: String
) {
    private val entries = mutableMapOf<String, OrmEntry>()
    private var loaded = false

    suspend fun load() {
        if (loaded) return
        store.getAll(modelName).forEach { entries[it.id] = it }
        loaded = true
    }

    private suspend fun ensureLoaded() {
        if (!loaded) load()
    }

    suspend fun set(entry: OrmEntry): OrmEntry {
        ensureLoaded()
        // Prevent a malicious peer from setting a far-future timestamp that permanently wins LWW conflicts
        val maxAllowed = System.currentTimeMillis() + 60_000 // 60s clock skew tolerance
        val clamped = if (entry.timestamp > maxAllowed) entry.copy(timestamp = maxAllowed) else entry
        val existing = entries[clamped.id]
        if (existing == null || clamped.timestamp > existing.timestamp) {
            store.put(modelName, clamped)
            entries[clamped.id] = clamped
            return clamped
        }
        return existing
    }

    suspend fun add(entry: OrmEntry): OrmEntry = set(entry)

    suspend fun merge(entries: List<OrmEntry>): List<OrmEntry> {
        ensureLoaded()
        val updated = mutableListOf<OrmEntry>()
        for (entry in entries) {
            val existing = this.entries[entry.id]
            if (existing == null || entry.timestamp > existing.timestamp) {
                store.put(modelName, entry)
                this.entries[entry.id] = entry
                updated.add(entry)
            }
        }
        return updated
    }

    suspend fun get(id: String): OrmEntry? {
        ensureLoaded()
        return entries[id]
    }

    suspend fun has(id: String): Boolean {
        ensureLoaded()
        return entries.containsKey(id)
    }

    suspend fun getAll(): List<OrmEntry> {
        ensureLoaded()
        return entries.values.filter { !it.isDeleted }.toList()
    }

    suspend fun size(): Int {
        ensureLoaded()
        return entries.values.count { !it.isDeleted }
    }

    suspend fun delete(id: String, authorDeviceId: String): OrmEntry {
        ensureLoaded()
        val tombstone = OrmEntry(
            id = id,
            data = mapOf("_deleted" to true),
            timestamp = System.currentTimeMillis(),
            authorDeviceId = authorDeviceId,
            signature = ByteArray(0)
        )
        store.put(modelName, tombstone)
        entries[id] = tombstone
        return tombstone
    }

    suspend fun filter(
        predicate: (OrmEntry) -> Boolean,
        includeTombstones: Boolean = false
    ): List<OrmEntry> {
        ensureLoaded()
        var list = entries.values.toList()
        if (!includeTombstones) {
            list = list.filter { !it.isDeleted }
        }
        return list.filter(predicate)
    }

    suspend fun getAllSorted(descending: Boolean = true): List<OrmEntry> {
        ensureLoaded()
        val live = entries.values.filter { !it.isDeleted }
        return if (descending) {
            live.sortedByDescending { it.timestamp }
        } else {
            live.sortedBy { it.timestamp }
        }
    }
}
