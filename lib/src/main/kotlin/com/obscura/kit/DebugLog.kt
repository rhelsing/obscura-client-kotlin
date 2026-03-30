package com.obscura.kit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-app debug log. One-liners for every connection event.
 * Wire to a settings screen with a copy button for diagnostics.
 *
 * Usage:
 *   val log by client.debugLog.entries.collectAsState()
 *   LazyColumn { items(log) { Text(it) } }
 *   Button("Copy") { clipboard.setText(client.debugLog.dump()) }
 */
class DebugLog(private val maxEntries: Int = 200) {
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries

    fun log(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        val entry = "[$timestamp] $message"
        val current = _entries.value.toMutableList()
        current.add(0, entry)
        if (current.size > maxEntries) current.removeAt(current.lastIndex)
        _entries.value = current
    }

    fun dump(): String = _entries.value.joinToString("\n")

    fun clear() { _entries.value = emptyList() }
}
