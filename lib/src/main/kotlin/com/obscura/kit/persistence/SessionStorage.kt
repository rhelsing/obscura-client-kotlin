package com.obscura.kit.persistence

/**
 * Kit-owned session persistence. Bridge provides platform-specific implementation.
 * Default: no-op (for JVM tests). Android bridge provides SharedPreferences impl.
 */
interface SessionStorage {
    fun save(data: Map<String, Any?>)
    fun load(): Map<String, Any?>?
    fun clear()
}

/** No-op implementation for JVM tests */
object NoOpSessionStorage : SessionStorage {
    override fun save(data: Map<String, Any?>) {}
    override fun load(): Map<String, Any?>? = null
    override fun clear() {}
}
