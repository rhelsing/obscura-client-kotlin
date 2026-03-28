package com.obscura.app

import android.app.Application
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.obscura.kit.AuthState
import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
import com.obscura.kit.db.ObscuraDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Application singleton — owns ObscuraClient for the process lifetime.
 * Session credentials stored in EncryptedSharedPreferences (Android Keystore-backed).
 * Signal protocol state stored in SQLite database (survives restarts).
 */
class ObscuraApp : Application() {

    lateinit var client: ObscuraClient
        private set

    private lateinit var securePrefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Encrypted storage for auth credentials
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        securePrefs = EncryptedSharedPreferences.create(
            "obscura_secure_prefs",
            masterKey,
            applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        // Load SQLCipher native library
        System.loadLibrary("sqlcipher")

        // Encrypted SQLite database (Signal's pattern: random key sealed by Keystore)
        val dbSecret = DatabaseSecretProvider.getOrCreate(applicationContext)
        val factory = SupportOpenHelperFactory(dbSecret, null, false)
        val driver = AndroidSqliteDriver(
            schema = ObscuraDatabase.Schema,
            context = applicationContext,
            name = "obscura.db",
            factory = factory,
            callback = object : AndroidSqliteDriver.Callback(ObscuraDatabase.Schema) {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    // Match Signal: KDF iter = 1 since key is already 256-bit random
                    db.query("PRAGMA cipher_default_kdf_iter = 1;").close()
                    db.query("PRAGMA cipher_default_page_size = 4096;").close()
                    super.onOpen(db)
                }
            }
        )

        client = ObscuraClient(
            config = ObscuraConfig(apiUrl = "https://obscura.barrelmaker.dev"),
            externalDriver = driver
        )

        // Restore session if saved
        val savedToken = securePrefs.getString("token", null)
        val savedUserId = securePrefs.getString("userId", null)

        if (savedToken != null && savedUserId != null) {
            client.restoreSession(
                token = savedToken,
                refreshToken = securePrefs.getString("refreshToken", null),
                userId = savedUserId,
                deviceId = securePrefs.getString("deviceId", null),
                username = securePrefs.getString("username", null),
                registrationId = securePrefs.getInt("registrationId", 0)
            )
            // Auto-reconnect WebSocket
            scope.launch {
                try { client.connect() } catch (_: Exception) {}
            }
        }

        // Auto-save session whenever auth state changes
        scope.launch {
            client.authState.collectLatest { state ->
                if (state == AuthState.AUTHENTICATED) {
                    saveSession()
                } else {
                    clearSession()
                }
            }
        }
    }

    fun saveSession() {
        securePrefs.edit()
            .putString("token", client.token)
            .putString("refreshToken", client.refreshToken)
            .putString("userId", client.userId)
            .putString("deviceId", client.deviceId)
            .putString("username", client.username)
            .putInt("registrationId", client.registrationId)
            .apply()
    }

    fun clearSession() {
        securePrefs.edit().clear().apply()
    }
}
