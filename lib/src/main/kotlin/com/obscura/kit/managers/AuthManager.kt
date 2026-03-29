package com.obscura.kit.managers

import com.obscura.kit.AuthState
import com.obscura.kit.ObscuraConfig
import com.obscura.kit.ObscuraLogger
import com.obscura.kit.crypto.toBase64
import com.obscura.kit.managers.SignalKeyUtils.toApiJson
import com.obscura.kit.network.GatewayConnection
import com.obscura.kit.network.ProvisionDeviceRequest
import com.obscura.kit.stores.DeviceIdentityData
import kotlinx.coroutines.*

/**
 * Handles register, login, loginAndProvision, logout, session restore, and token refresh.
 */
internal class AuthManager(
    private val ctx: ClientContext,
    private val config: ObscuraConfig,
    private val gateway: GatewayConnection,
    private val scope: CoroutineScope,
    private val setAuthState: (AuthState) -> Unit,
    private val setDisconnected: () -> Unit,
    private val loggerProvider: () -> ObscuraLogger,
    private val onLogout: suspend () -> Unit
) {
    private val session get() = ctx.session
    private val api get() = ctx.api
    private val signalStore get() = ctx.signalStore
    private val messenger get() = ctx.messenger
    private val devices get() = ctx.devices
    private val refreshInProgress = java.util.concurrent.atomic.AtomicReference<Deferred<Boolean>?>(null)
    private var consecutiveRefreshFailures = 0
    private val MAX_REFRESH_FAILURES = 5
    var tokenRefreshJob: Job? = null

    suspend fun register(username: String, password: String) {
        session.username = username

        val (identityKeyPair, regId) = signalStore.generateIdentity()
        session.registrationId = regId

        val signedPreKey = SignalKeyUtils.generateSignedPreKey(signalStore, identityKeyPair, 1)
        val oneTimePreKeys = SignalKeyUtils.generateOneTimePreKeys(signalStore, 1, 100)

        val regResult = api.registerUser(username, password)
        api.token = regResult.token

        val provResult = api.provisionDevice(ProvisionDeviceRequest(
            name = config.deviceName,
            identityKey = identityKeyPair.publicKey.serialize().toBase64(),
            registrationId = regId,
            signedPreKey = signedPreKey.toApiJson(),
            oneTimePreKeys = oneTimePreKeys.toApiJson()
        ))

        val deviceToken = provResult.token
        api.token = deviceToken
        session.refreshToken = provResult.refreshToken
        session.userId = api.getUserId(deviceToken)
        session.deviceId = provResult.deviceId.ifEmpty { null } ?: api.getDeviceId(deviceToken)

        messenger.mapDevice(
            requireNotNull(session.deviceId) { "deviceId not set - register failed to provision device" },
            requireNotNull(session.userId) { "userId not set - register failed to resolve user" },
            regId
        )

        devices.storeIdentity(DeviceIdentityData(
            deviceId = requireNotNull(session.deviceId) { "deviceId not set - register failed to provision device" },
            userId = requireNotNull(session.userId) { "userId not set - register failed to resolve user" },
            username = username,
            token = deviceToken
        ))

        setAuthState(AuthState.AUTHENTICATED)
        delay(config.authRateLimitDelayMs)
    }

    suspend fun login(username: String, password: String) {
        val identity = devices.getIdentity()
        val result = api.loginWithDevice(username, password, identity?.deviceId)
        val token = result.token
        api.token = token
        session.refreshToken = result.refreshToken
        session.userId = api.getUserId(token)
        session.deviceId = result.deviceId ?: api.getDeviceId(token)
        session.username = username

        if (identity != null) {
            devices.storeIdentity(identity.copy(token = token))
        }
        if (session.deviceId != null && session.userId != null) {
            messenger.mapDevice(
                requireNotNull(session.deviceId) { "deviceId not set - call register/login first" },
                requireNotNull(session.userId) { "userId not set - call register/login first" },
                session.registrationId
            )
        }

        setAuthState(AuthState.AUTHENTICATED)
        delay(config.authRateLimitDelayMs)
    }

    suspend fun loginAndProvision(username: String, password: String, deviceName: String = "Device 2") {
        session.username = username

        val loginResult = api.loginWithDevice(username, password, null)
        api.token = loginResult.token
        session.userId = api.getUserId(loginResult.token)

        val (identityKeyPair, regId) = signalStore.generateIdentity()
        session.registrationId = regId

        val signedPreKey = SignalKeyUtils.generateSignedPreKey(signalStore, identityKeyPair, 1)
        val oneTimePreKeys = SignalKeyUtils.generateOneTimePreKeys(signalStore, 1, 100)

        val provResult = api.provisionDevice(ProvisionDeviceRequest(
            name = deviceName,
            identityKey = identityKeyPair.publicKey.serialize().toBase64(),
            registrationId = regId,
            signedPreKey = signedPreKey.toApiJson(),
            oneTimePreKeys = oneTimePreKeys.toApiJson()
        ))

        val deviceToken = provResult.token
        api.token = deviceToken
        session.refreshToken = provResult.refreshToken
        session.deviceId = provResult.deviceId.ifEmpty { null } ?: api.getDeviceId(deviceToken)

        messenger.mapDevice(
            requireNotNull(session.deviceId) { "deviceId not set - loginAndProvision failed to provision device" },
            requireNotNull(session.userId) { "userId not set - loginAndProvision failed to resolve user" },
            regId
        )

        devices.storeIdentity(DeviceIdentityData(
            deviceId = requireNotNull(session.deviceId) { "deviceId not set - loginAndProvision failed to provision device" },
            userId = requireNotNull(session.userId) { "userId not set - loginAndProvision failed to resolve user" },
            username = username,
            token = deviceToken
        ))

        setAuthState(AuthState.AUTHENTICATED)
        delay(config.authRateLimitDelayMs)
    }

    fun restoreSession(
        token: String,
        refreshToken: String?,
        userId: String,
        deviceId: String?,
        username: String?,
        registrationId: Int = 0
    ) {
        api.token = token
        session.refreshToken = refreshToken
        session.userId = userId
        session.deviceId = deviceId
        session.username = username
        session.registrationId = registrationId

        if (deviceId != null) {
            messenger.mapDevice(deviceId, userId, registrationId)
        }

        setAuthState(AuthState.AUTHENTICATED)
    }

    fun hasSession(): Boolean = api.token != null && session.userId != null

    suspend fun logout() {
        tokenRefreshJob?.cancel()
        onLogout()
        api.token = null
        session.userId = null
        session.deviceId = null
        session.username = null
        session.refreshToken = null
        setAuthState(AuthState.LOGGED_OUT)
    }

    fun startTokenRefresh() {
        tokenRefreshJob?.cancel()
        tokenRefreshJob = scope.launch {
            while (isActive) {
                val delayMs = getTokenRefreshDelay()
                delay(delayMs)
                refreshTokens()
            }
        }
    }

    suspend fun refreshTokens(): Boolean {
        refreshInProgress.get()?.let { return it.await() }

        val deferred = scope.async {
            try {
                val rt = session.refreshToken ?: return@async false
                val result = api.refreshSession(rt)
                api.token = result.token
                session.refreshToken = result.refreshToken
                consecutiveRefreshFailures = 0
                true
            } catch (e: Exception) {
                consecutiveRefreshFailures++
                loggerProvider().tokenRefreshFailed(consecutiveRefreshFailures, e.message ?: "unknown")
                if (consecutiveRefreshFailures >= MAX_REFRESH_FAILURES) {
                    setDisconnected()
                }
                false
            }
        }

        refreshInProgress.set(deferred)
        try {
            return deferred.await()
        } finally {
            refreshInProgress.set(null)
        }
    }

    suspend fun ensureFreshToken(): Boolean {
        if (!isTokenExpired(60)) return true
        return refreshTokens()
    }

    private fun isTokenExpired(bufferSeconds: Long = 0): Boolean {
        val token = api.token ?: return true
        val payload = api.decodeToken(token) ?: return true
        val exp = payload.optLong("exp", 0)
        if (exp == 0L) return true
        val now = System.currentTimeMillis() / 1000
        return (exp - now) <= bufferSeconds
    }

    private fun getTokenRefreshDelay(): Long {
        val token = api.token ?: return 30_000
        val payload = api.decodeToken(token) ?: return 30_000
        val exp = payload.optLong("exp", 0)
        if (exp == 0L) return 30_000
        val now = System.currentTimeMillis() / 1000
        val ttl = exp - now
        if (ttl <= 0) return 5_000
        return (ttl * 800).coerceAtLeast(5_000)
    }
}
