package com.obscura.app

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.obscura.kit.ConnectionState
import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
import com.obscura.kit.db.ObscuraDatabase
import com.obscura.kit.orm.ModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

class ObscuraApp : Application() {

    var client: ObscuraClient? = null
    lateinit var securePrefs: SharedPreferences
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")

        // Reconnect when app returns from background
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val c = client ?: return
                if (c.connectionState.value != ConnectionState.CONNECTED) {
                    appScope.launch { try { c.connect() } catch (_: Exception) {} }
                }
            }
        })

        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        securePrefs = EncryptedSharedPreferences.create(
            "obscura_secure_prefs", masterKey, applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        // Restore saved session if exists
        val savedUsername = securePrefs.getString("username", null)
        if (savedUsername != null) {
            val c = createClient(savedUsername)
            val token = securePrefs.getString("token", null)
            val userId = securePrefs.getString("userId", null)
            if (token != null && userId != null) {
                c.restoreSession(
                    token = token,
                    refreshToken = securePrefs.getString("refreshToken", null),
                    userId = userId,
                    deviceId = securePrefs.getString("deviceId", null),
                    username = savedUsername,
                    registrationId = securePrefs.getInt("registrationId", 0)
                )
            }
            runBlocking { defineModels(c) }
            client = c
        }
    }

    companion object {
        suspend fun defineModels(client: ObscuraClient) {
            client.orm.define(mapOf(
                "directMessage" to ModelConfig(
                    fields = mapOf("conversationId" to "string", "content" to "string", "senderUsername" to "string"),
                    sync = "gset"
                ),
                "story" to ModelConfig(
                    fields = mapOf("content" to "string", "authorUsername" to "string", "mediaUrl" to "string?"),
                    sync = "gset",
                    ttl = "24h"
                ),
                "profile" to ModelConfig(
                    fields = mapOf("displayName" to "string", "bio" to "string?"),
                    sync = "lww"
                ),
                "settings" to ModelConfig(
                    fields = mapOf("theme" to "string", "notificationsEnabled" to "boolean"),
                    sync = "lww",
                    private = true
                )
            ))
        }
    }

    fun createClient(username: String): ObscuraClient {
        val dbSecret = DatabaseSecretProvider.getOrCreate(applicationContext, username)
        val factory = SupportOpenHelperFactory(dbSecret, null, false)
        val driver = AndroidSqliteDriver(
            schema = ObscuraDatabase.Schema,
            context = applicationContext,
            name = "obscura_$username.db",
            factory = factory
        )
        return ObscuraClient(ObscuraConfig(apiUrl = "https://obscura.barrelmaker.dev"), externalDriver = driver)
    }

    fun saveSession() {
        val c = client ?: return
        securePrefs.edit()
            .putString("username", c.username)
            .putString("token", c.token)
            .putString("refreshToken", c.refreshToken)
            .putString("userId", c.userId)
            .putString("deviceId", c.deviceId)
            .putInt("registrationId", c.registrationId)
            .apply()
    }

    fun clearSession() {
        client = null
        securePrefs.edit().clear().apply()
    }
}
