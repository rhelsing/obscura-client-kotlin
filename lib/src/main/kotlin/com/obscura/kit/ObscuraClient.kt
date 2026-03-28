package com.obscura.kit

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.obscura.kit.crypto.ParsedSyncBlob
import com.obscura.kit.crypto.SignalStore
import com.obscura.kit.crypto.toBase64
import com.obscura.kit.db.ObscuraDatabase
import com.obscura.kit.network.APIClient
import com.obscura.kit.network.GatewayConnection
import com.obscura.kit.orm.ModelStore
import com.obscura.kit.orm.ModelSyncData
import com.obscura.kit.orm.SyncManager
import com.obscura.kit.orm.TTLManager
import com.obscura.kit.stores.*
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import obscura.v2.Client.ClientMessage
import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class ReceivedMessage(
    val type: String,
    val text: String = "",
    val username: String = "",
    val accepted: Boolean = false,
    val sourceUserId: String = "",
    val senderDeviceId: String? = null,
    val raw: ClientMessage? = null
)

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

enum class AuthState { LOGGED_OUT, AUTHENTICATED }

/**
 * Create with default JVM in-memory driver (for tests).
 * For Android production, pass an encrypted AndroidSqliteDriver:
 *
 *   val driver = AndroidSqliteDriver(
 *       ObscuraDatabase.Schema,
 *       context,
 *       "obscura.db",
 *       factory = SupportSQLiteOpenHelper.Factory(SQLCipherOpenHelperFactory(passphrase))
 *   )
 *   val client = ObscuraClient(config, driver)
 */
class ObscuraClient(
    val config: ObscuraConfig,
    externalDriver: app.cash.sqldelight.db.SqlDriver? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ================================================================
    // Observable state — what Compose views collect
    // ================================================================

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _authState = MutableStateFlow(AuthState.LOGGED_OUT)
    val authState: StateFlow<AuthState> = _authState

    private val _friendList = MutableStateFlow<List<FriendData>>(emptyList())
    val friendList: StateFlow<List<FriendData>> = _friendList

    private val _pendingRequests = MutableStateFlow<List<FriendData>>(emptyList())
    val pendingRequests: StateFlow<List<FriendData>> = _pendingRequests

    // Conversations are still manually managed since they're keyed by conversationId
    // and SQLDelight reactive queries need a fixed query (not dynamic per-conversation)
    private val _conversations = MutableStateFlow<Map<String, List<MessageData>>>(emptyMap())
    val conversations: StateFlow<Map<String, List<MessageData>>> = _conversations

    private val _events = MutableSharedFlow<ReceivedMessage>(extraBufferCapacity = 64)
    val events: SharedFlow<ReceivedMessage> = _events

    // ================================================================
    // Internal infrastructure
    // ================================================================

    private val driver = externalDriver ?: if (config.databasePath != null) {
        JdbcSqliteDriver("jdbc:sqlite:${config.databasePath}")
    } else {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    }
    internal val db: ObscuraDatabase

    internal val signalStore: SignalStore
    internal val api = APIClient(config.apiUrl)
    internal val gateway: GatewayConnection

    private val friends: FriendDomain
    private val messages: MessageDomain
    internal val devices: DeviceDomain
    internal val messenger: MessengerDomain
    internal val orm: SchemaDomain

    private val modelStore: ModelStore
    private val syncManager: SyncManager
    private val ttlManager: TTLManager

    // Identity
    var userId: String? = null; private set
    var deviceId: String? = null; private set
    var username: String? = null; private set
    var refreshToken: String? = null; private set
    var registrationId: Int = 0; private set
    val token: String? get() = api.token

    private var recoveryPhrase: String? = null
    private var recoveryPublicKey: ByteArray? = null

    /** Structured logger for security events. Set to a custom implementation for production. */
    var logger: ObscuraLogger = NoOpLogger

    // Test convenience — single-consumer channel
    val incomingMessages = Channel<ReceivedMessage>(capacity = 1000)

    private var envelopeJob: Job? = null
    private var tokenRefreshJob: Job? = null
    private var backupEtag: String? = null

    // M13: Decrypt rate limiting per sender
    private val decryptFailures = mutableMapOf<String, Pair<Int, Long>>() // senderId -> (count, windowStart)
    private val MAX_DECRYPT_FAILURES = 10
    private val DECRYPT_FAILURE_WINDOW_MS = 60_000L

    init {
        if (externalDriver == null) {
            ObscuraDatabase.Schema.create(driver)
            // Secure delete: overwrite freed pages so deleted data isn't recoverable
            // Only for JVM driver — Android driver handles this via SQLiteOpenHelper config
            try { driver.execute(null, "PRAGMA secure_delete = ON", 0) } catch (_: Exception) {}
        }
        db = ObscuraDatabase(driver)

        signalStore = SignalStore(db)
        signalStore.onIdentityChanged = { address, _, _ ->
            logger.identityChanged(address)
        }
        friends = FriendDomain(db)
        messages = MessageDomain(db)
        devices = DeviceDomain(db)
        messenger = MessengerDomain(signalStore, api)

        modelStore = ModelStore(db)
        syncManager = SyncManager(modelStore)
        ttlManager = TTLManager(modelStore)
        gateway = GatewayConnection(api, scope)

        orm = SchemaDomain(modelStore, syncManager, ttlManager)

        // Reactive observation — auto-updates StateFlows when DB changes
        startDatabaseObservation()
    }

    private fun startDatabaseObservation() {
        // Friends: accepted
        scope.launch {
            db.friendQueries.selectByStatus(FriendStatus.ACCEPTED.value)
                .asFlow()
                .mapToList(Dispatchers.IO)
                .map { rows -> rows.map { it.toFriendData() } }
                .collect { _friendList.value = it }
        }

        // Friends: pending
        scope.launch {
            db.friendQueries.selectByStatus(FriendStatus.PENDING_RECEIVED.value)
                .asFlow()
                .mapToList(Dispatchers.IO)
                .map { rows -> rows.map { it.toFriendData() } }
                .collect { _pendingRequests.value = it }
        }
    }

    private fun com.obscura.kit.Friend.toFriendData(): FriendData {
        return FriendData(
            userId = user_id,
            username = username,
            status = FriendStatus.entries.find { it.value == status } ?: FriendStatus.PENDING_SENT,
            devices = try {
                val arr = org.json.JSONArray(devices)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    FriendDeviceInfo(
                        deviceUuid = obj.optString("deviceUuid", ""),
                        deviceId = obj.optString("deviceId", ""),
                        deviceName = obj.optString("deviceName", "")
                    )
                }
            } catch (e: Exception) { emptyList() }
        )
    }

    // ================================================================
    // Auth
    // ================================================================

    suspend fun register(username: String, password: String) {
        this.username = username

        val (identityKeyPair, regId) = signalStore.generateIdentity()
        this.registrationId = regId

        val signedPreKey = generateSignedPreKey(identityKeyPair, 1)
        val oneTimePreKeys = generateOneTimePreKeys(1, 100)

        val regResult = api.registerUser(username, password)
        api.token = regResult.getString("token")

        val identityKeyB64 = identityKeyPair.publicKey.serialize().toBase64()
        val spkJson = JSONObject().apply {
            put("keyId", signedPreKey.id)
            put("publicKey", signedPreKey.keyPair.publicKey.serialize().toBase64())
            put("signature", signedPreKey.signature.toBase64())
        }
        val otpJsonArr = JSONArray(oneTimePreKeys.map { pk ->
            JSONObject().apply {
                put("keyId", pk.id)
                put("publicKey", pk.keyPair.publicKey.serialize().toBase64())
            }
        })

        val provResult = api.provisionDevice(
            name = config.deviceName,
            identityKey = identityKeyB64,
            registrationId = regId,
            signedPreKey = spkJson,
            oneTimePreKeys = otpJsonArr
        )

        val deviceToken = provResult.getString("token")
        api.token = deviceToken
        refreshToken = provResult.optString("refreshToken", null)
        userId = api.getUserId(deviceToken)
        deviceId = provResult.optString("deviceId", null) ?: api.getDeviceId(deviceToken)

        messenger.mapDevice(
            requireNotNull(deviceId) { "deviceId not set - register failed to provision device" },
            requireNotNull(userId) { "userId not set - register failed to resolve user" },
            regId
        )

        devices.storeIdentity(DeviceIdentityData(
            deviceId = requireNotNull(deviceId) { "deviceId not set - register failed to provision device" },
            userId = requireNotNull(userId) { "userId not set - register failed to resolve user" },
            username = username,
            token = deviceToken
        ))

        _authState.value = AuthState.AUTHENTICATED
        delay(config.authRateLimitDelayMs)
    }

    suspend fun login(username: String, password: String) {
        val identity = devices.getIdentity()
        val result = api.loginWithDevice(username, password, identity?.deviceId)
        val token = result.getString("token")
        api.token = token
        refreshToken = result.optString("refreshToken", null)
        userId = api.getUserId(token)
        deviceId = result.optString("deviceId", null) ?: api.getDeviceId(token)
        this.username = username

        if (identity != null) {
            devices.storeIdentity(identity.copy(token = token))
        }
        if (deviceId != null && userId != null) {
            messenger.mapDevice(
                requireNotNull(deviceId) { "deviceId not set - call register/login first" },
                requireNotNull(userId) { "userId not set - call register/login first" },
                registrationId
            )
        }

        _authState.value = AuthState.AUTHENTICATED
        delay(config.authRateLimitDelayMs)
    }

    /**
     * Login as existing user and provision a NEW device (multi-device).
     * Creates fresh Signal keys for the new device.
     */
    suspend fun loginAndProvision(username: String, password: String, deviceName: String = "Device 2") {
        this.username = username

        // Login without deviceId → user-scoped token
        val loginResult = api.loginWithDevice(username, password, null)
        api.token = loginResult.getString("token")
        userId = api.getUserId(loginResult.getString("token"))

        // Generate fresh Signal identity for this new device
        val (identityKeyPair, regId) = signalStore.generateIdentity()
        this.registrationId = regId

        val signedPreKey = generateSignedPreKey(identityKeyPair, 1)
        val oneTimePreKeys = generateOneTimePreKeys(1, 100)

        val identityKeyB64 = identityKeyPair.publicKey.serialize().toBase64()
        val spkJson = JSONObject().apply {
            put("keyId", signedPreKey.id)
            put("publicKey", signedPreKey.keyPair.publicKey.serialize().toBase64())
            put("signature", signedPreKey.signature.toBase64())
        }
        val otpJsonArr = JSONArray(oneTimePreKeys.map { pk ->
            JSONObject().apply {
                put("keyId", pk.id)
                put("publicKey", pk.keyPair.publicKey.serialize().toBase64())
            }
        })

        val provResult = api.provisionDevice(
            name = deviceName,
            identityKey = identityKeyB64,
            registrationId = regId,
            signedPreKey = spkJson,
            oneTimePreKeys = otpJsonArr
        )

        val deviceToken = provResult.getString("token")
        api.token = deviceToken
        refreshToken = provResult.optString("refreshToken", null)
        deviceId = provResult.optString("deviceId", null) ?: api.getDeviceId(deviceToken)

        messenger.mapDevice(
            requireNotNull(deviceId) { "deviceId not set - loginAndProvision failed to provision device" },
            requireNotNull(userId) { "userId not set - loginAndProvision failed to resolve user" },
            regId
        )

        devices.storeIdentity(DeviceIdentityData(
            deviceId = requireNotNull(deviceId) { "deviceId not set - loginAndProvision failed to provision device" },
            userId = requireNotNull(userId) { "userId not set - loginAndProvision failed to resolve user" },
            username = username,
            token = deviceToken
        ))

        _authState.value = AuthState.AUTHENTICATED
        delay(config.authRateLimitDelayMs)
    }

    /**
     * Restore a previously saved session without contacting the server.
     * Signal keys and sessions are already in the database (persistent driver).
     * This just sets the in-memory auth state so the client is ready to connect.
     */
    fun restoreSession(
        token: String,
        refreshToken: String?,
        userId: String,
        deviceId: String?,
        username: String?,
        registrationId: Int = 0
    ) {
        api.token = token
        this.refreshToken = refreshToken
        this.userId = userId
        this.deviceId = deviceId
        this.username = username
        this.registrationId = registrationId

        if (deviceId != null) {
            messenger.mapDevice(deviceId, userId, registrationId)
        }

        _authState.value = AuthState.AUTHENTICATED
    }

    /**
     * Check if this client has a restorable session (token + userId set).
     */
    fun hasSession(): Boolean = api.token != null && userId != null

    suspend fun logout() {
        tokenRefreshJob?.cancel()
        envelopeJob?.cancel()
        gateway.disconnect()
        api.token = null
        userId = null
        deviceId = null
        username = null
        refreshToken = null
        _authState.value = AuthState.LOGGED_OUT
        _connectionState.value = ConnectionState.DISCONNECTED
        // Clear all local data so a subsequent login starts fresh and doesn't leak state across accounts
        db.friendQueries.deleteAll()
        db.messageQueries.deleteAll()
        db.deviceQueries.deleteAllDevices()
        db.deviceQueries.deleteIdentity()
        db.signalKeyQueries.deleteAllSignalData()
        db.modelEntryQueries.deleteAllEntries()
        db.modelEntryQueries.deleteAllAssociations()
    }

    // ================================================================
    // Connection
    // ================================================================

    suspend fun connect() {
        _connectionState.value = ConnectionState.CONNECTING
        gateway.connect()
        _connectionState.value = ConnectionState.CONNECTED
        startEnvelopeLoop()
        startTokenRefresh()
        startPreKeyStatusListener()
    }

    fun disconnect() {
        tokenRefreshJob?.cancel()
        envelopeJob?.cancel()
        gateway.disconnect()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    // ================================================================
    // Token Refresh — dedup, 80% TTL schedule, failure tracking
    // ================================================================

    private val refreshInProgress = java.util.concurrent.atomic.AtomicReference<Deferred<Boolean>?>(null)
    private var consecutiveRefreshFailures = 0
    private val MAX_REFRESH_FAILURES = 5

    private fun startTokenRefresh() {
        tokenRefreshJob?.cancel()
        tokenRefreshJob = scope.launch {
            while (isActive) {
                val delayMs = getTokenRefreshDelay()
                delay(delayMs)
                refreshTokens()
            }
        }
    }

    /**
     * Dedup-safe token refresh. Concurrent calls share the same in-flight request.
     */
    private suspend fun refreshTokens(): Boolean {
        // Dedup: if refresh is already in-flight, piggyback on it
        refreshInProgress.get()?.let { return it.await() }

        val deferred = scope.async {
            try {
                val rt = refreshToken ?: return@async false
                val result = api.refreshSession(rt)
                api.token = result.getString("token")
                refreshToken = result.optString("refreshToken", null)
                consecutiveRefreshFailures = 0
                // Reschedule based on new token TTL
                true
            } catch (e: Exception) {
                consecutiveRefreshFailures++
                logger.tokenRefreshFailed(consecutiveRefreshFailures, e.message ?: "unknown")
                if (consecutiveRefreshFailures >= MAX_REFRESH_FAILURES) {
                    disconnect()
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

    /**
     * Ensure token is fresh before critical API calls. 60s lookahead.
     */
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
        if (ttl <= 0) return 5_000 // Already expired, refresh ASAP
        return (ttl * 800).coerceAtLeast(5_000) // 80% of TTL, min 5s
    }

    // ================================================================
    // Prekey Replenishment — check after decrypt, handle PreKeyStatus
    // ================================================================

    private val PREKEY_MIN_COUNT = 20L
    private val PREKEY_REPLENISH_COUNT = 50

    private fun startPreKeyStatusListener() {
        scope.launch {
            for (status in gateway.preKeyStatus) {
                if (status.oneTimePreKeyCount < status.minThreshold) {
                    replenishPreKeys()
                }
            }
        }
    }

    private fun checkAndReplenishPreKeys() {
        // Fire-and-forget, non-blocking
        scope.launch {
            try {
                if (signalStore.getPreKeyCount() < PREKEY_MIN_COUNT) {
                    replenishPreKeys()
                }
            } catch (e: Exception) { /* non-fatal */ }
        }
    }

    private suspend fun replenishPreKeys() {
        try {
            val highestId = signalStore.getHighestPreKeyId().toInt()
            val newPreKeys = generateOneTimePreKeys(highestId + 1, PREKEY_REPLENISH_COUNT)

            val identityKeyB64 = signalStore.getIdentityKeyPair().publicKey.serialize().toBase64()

            // Get current signed prekey for upload
            val spkRecord = signalStore.loadSignedPreKey(1)
            val spkJson = JSONObject().apply {
                put("keyId", spkRecord.id)
                put("publicKey", spkRecord.keyPair.publicKey.serialize().toBase64())
                put("signature", spkRecord.signature.toBase64())
            }

            val otpJsonArr = JSONArray(newPreKeys.map { pk ->
                JSONObject().apply {
                    put("keyId", pk.id)
                    put("publicKey", pk.keyPair.publicKey.serialize().toBase64())
                }
            })

            api.uploadDeviceKeys(identityKeyB64, signalStore.getLocalRegistrationId(), spkJson, otpJsonArr)
        } catch (e: Exception) {
            logger.preKeyReplenishFailed(e.message ?: "unknown")
        }
    }

    // ================================================================
    // Envelope Processing
    // ================================================================

    private fun startEnvelopeLoop() {
        envelopeJob = scope.launch {
            for (envelope in gateway.envelopes) {
                // M13: rate limit check before decrypt attempt
                val senderId = try {
                    val bb = java.nio.ByteBuffer.wrap(envelope.senderId.toByteArray())
                    java.util.UUID(bb.getLong(), bb.getLong()).toString()
                } catch (_: Exception) { "unknown" }

                if (isDecryptRateLimited(senderId)) {
                    try { gateway.ack(listOf(envelope.id)) } catch (_: Exception) { }
                    continue
                }

                try {
                    val decrypted = messenger.decrypt(envelope)
                    val msg = decrypted.clientMessage
                    routeMessage(msg, decrypted.sourceUserId, decrypted.senderDeviceId)

                    // Clear failure count on success
                    decryptFailures.remove(decrypted.sourceUserId)

                    val received = ReceivedMessage(
                        type = msg.type.name,
                        text = msg.text,
                        username = msg.username,
                        accepted = msg.accepted,
                        sourceUserId = decrypted.sourceUserId,
                        senderDeviceId = decrypted.senderDeviceId,
                        raw = msg
                    )

                    incomingMessages.trySend(received)
                    _events.tryEmit(received)

                    checkAndReplenishPreKeys()
                } catch (e: Exception) {
                    trackDecryptFailure(senderId)
                    logger.decryptFailed(senderId, e.message ?: "unknown")
                }

                try { gateway.ack(listOf(envelope.id)) } catch (e: Exception) { }
            }
        }
    }

    private fun isDecryptRateLimited(senderId: String): Boolean {
        val (count, windowStart) = decryptFailures[senderId] ?: return false
        val now = System.currentTimeMillis()
        if (now - windowStart > DECRYPT_FAILURE_WINDOW_MS) {
            decryptFailures.remove(senderId) // window expired, reset
            return false
        }
        return count >= MAX_DECRYPT_FAILURES
    }

    private fun trackDecryptFailure(senderId: String) {
        val now = System.currentTimeMillis()
        val (count, windowStart) = decryptFailures[senderId] ?: Pair(0, now)
        if (now - windowStart > DECRYPT_FAILURE_WINDOW_MS) {
            decryptFailures[senderId] = Pair(1, now) // new window
        } else {
            decryptFailures[senderId] = Pair(count + 1, windowStart)
        }
    }

    private suspend fun routeMessage(msg: ClientMessage, sourceUserId: String, senderDeviceId: String?) {
        when (msg.type) {
            ClientMessage.Type.FRIEND_REQUEST -> {
                friends.add(sourceUserId, msg.username, FriendStatus.PENDING_RECEIVED)

            }
            ClientMessage.Type.FRIEND_RESPONSE -> {
                if (msg.accepted) friends.add(sourceUserId, msg.username, FriendStatus.ACCEPTED)

            }
            ClientMessage.Type.TEXT, ClientMessage.Type.IMAGE -> {
                val msgId = UUID.randomUUID().toString()
                val msgData = MessageData(
                    id = msgId, conversationId = sourceUserId,
                    authorDeviceId = senderDeviceId ?: "unknown",
                    content = msg.text, timestamp = msg.timestamp,
                    type = msg.type.name.lowercase()
                )
                messages.add(sourceUserId, msgData)
                refreshConversation(sourceUserId)
            }
            ClientMessage.Type.DEVICE_ANNOUNCE -> {
                val announce = msg.deviceAnnounce
                // Device announcements must be signed by the recovery key to prevent forged device lists
                if (announce.signature.size() > 0 && announce.recoveryPublicKey.size() > 0) {
                    val payload = com.obscura.kit.crypto.RecoveryKeys.serializeAnnounceForSigning(
                        announce.devicesList.map { it.deviceId }, announce.timestamp, announce.isRevocation
                    )
                    try {
                        val pubKey = Curve.decodePoint(announce.recoveryPublicKey.toByteArray(), 0)
                        if (!Curve.verifySignature(pubKey, payload, announce.signature.toByteArray())) {
                            logger.decryptFailed(sourceUserId, "device announce signature invalid")
                            return
                        }
                    } catch (e: Exception) {
                        logger.decryptFailed(sourceUserId, "device announce signature verify error: ${e.message}")
                        return
                    }
                }
                friends.updateDevices(sourceUserId, announce.devicesList.map { d ->
                    FriendDeviceInfo(d.deviceUuid, d.deviceId, d.deviceName)
                })
            }
            ClientMessage.Type.MODEL_SYNC -> {
                val sync = msg.modelSync
                orm.handleSync(ModelSyncData(
                    model = sync.model, id = sync.id, op = sync.op.number,
                    timestamp = sync.timestamp, data = sync.data.toByteArray(),
                    authorDeviceId = sync.authorDeviceId, signature = sync.signature.toByteArray()
                ), sourceUserId)
            }
            ClientMessage.Type.SYNC_BLOB -> {
                if (sourceUserId != userId) return // Ignore sync blobs from other users — only our own devices should send these
                processSyncBlob(msg)
            }
            ClientMessage.Type.SENT_SYNC -> {
                if (sourceUserId != userId) return // only own devices can inject sent messages
                val ss = msg.sentSync
                messages.add(ss.conversationId, MessageData(
                    id = ss.messageId, conversationId = ss.conversationId,
                    authorDeviceId = deviceId ?: "self", content = String(ss.content.toByteArray()),
                    timestamp = ss.timestamp, type = "text"
                ))
                refreshConversation(ss.conversationId)
            }
            ClientMessage.Type.SESSION_RESET -> {
                signalStore.deleteAllSessions(sourceUserId)
            }
            ClientMessage.Type.FRIEND_SYNC -> {
                if (sourceUserId != userId) return // A friend sync from someone else's device would corrupt our friend list
                val fs = msg.friendSync
                val status = if (fs.status == FriendStatus.ACCEPTED.value) FriendStatus.ACCEPTED else FriendStatus.PENDING_RECEIVED
                if (fs.action == FriendSyncAction.ADD.value) {
                    friends.add(sourceUserId, fs.username, status,
                        fs.devicesList.map { FriendDeviceInfo(it.deviceUuid, it.deviceId, it.deviceName) })
                } else if (fs.action == FriendSyncAction.REMOVE.value) {
                    friends.remove(sourceUserId)
                }
            }
            else -> { }
        }
    }

    // ================================================================
    // Conversation refresh (messages are per-conversationId, not globally reactive)
    // ================================================================

    private suspend fun refreshConversation(conversationId: String) {
        val msgs = messages.getMessages(conversationId)
        val current = _conversations.value.toMutableMap()
        current[conversationId] = msgs
        _conversations.value = current
    }

    /**
     * Get messages for a conversation (for initial load).
     */
    suspend fun getMessages(conversationId: String, limit: Int = 50): List<MessageData> {
        val msgs = messages.getMessages(conversationId, limit)
        val current = _conversations.value.toMutableMap()
        current[conversationId] = msgs
        _conversations.value = current
        return msgs
    }

    // ================================================================
    // Messaging
    // ================================================================

    suspend fun send(friendUsername: String, text: String) {
        val friendData = friends.getAccepted().find { it.username == friendUsername }
            ?: throw IllegalStateException("Not friends with $friendUsername")

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.TEXT).setText(text)
            .setTimestamp(System.currentTimeMillis()).build()

        sendToAllDevices(friendData.userId, msg)

        // Self-sync: SENT_SYNC to own devices
        val selfTargets = devices.getSelfSyncTargets().filter { it != deviceId }
        if (selfTargets.isNotEmpty()) {
            val msgId = UUID.randomUUID().toString()
            val sentSync = ClientMessage.newBuilder()
                .setType(ClientMessage.Type.SENT_SYNC)
                .setTimestamp(System.currentTimeMillis())
                .setSentSync(obscura.v2.sentSync {
                    conversationId = friendUsername
                    messageId = msgId
                    timestamp = System.currentTimeMillis()
                    content = com.google.protobuf.ByteString.copyFrom(text.toByteArray())
                })
                .build()
            for (devId in selfTargets) {
                messenger.queueMessage(devId, sentSync, userId)
            }
            messenger.flushMessages()
        }
    }

    suspend fun befriend(targetUserId: String, targetUsername: String) {
        messenger.fetchPreKeyBundles(targetUserId)

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.FRIEND_REQUEST)
            .setUsername(username ?: "").setTimestamp(System.currentTimeMillis()).build()

        sendToAllDevices(targetUserId, msg)
        friends.add(targetUserId, targetUsername, FriendStatus.PENDING_SENT)

        syncFriendToOwnDevices(targetUsername, FriendSyncAction.ADD.value, FriendStatus.PENDING_SENT.value)
    }

    suspend fun acceptFriend(targetUserId: String, targetUsername: String) {
        messenger.fetchPreKeyBundles(targetUserId)

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.FRIEND_RESPONSE)
            .setUsername(username ?: "").setAccepted(true)
            .setTimestamp(System.currentTimeMillis()).build()

        sendToAllDevices(targetUserId, msg)
        friends.add(targetUserId, targetUsername, FriendStatus.ACCEPTED)

        // Sync to own devices
        syncFriendToOwnDevices(targetUsername, FriendSyncAction.ADD.value, FriendStatus.ACCEPTED.value)
    }

    suspend fun sendAttachment(friendUsername: String, attachmentId: String, contentKey: ByteArray, nonce: ByteArray, mimeType: String, sizeBytes: Long) {
        val friendData = friends.getAccepted().find { it.username == friendUsername }
            ?: throw IllegalStateException("Not friends with $friendUsername")

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.CONTENT_REFERENCE)
            .setTimestamp(System.currentTimeMillis())
            .setContentReference(obscura.v2.contentReference {
                this.attachmentId = attachmentId
                this.contentKey = com.google.protobuf.ByteString.copyFrom(contentKey)
                this.nonce = com.google.protobuf.ByteString.copyFrom(nonce)
                this.contentType = mimeType
                this.sizeBytes = sizeBytes
            }).build()

        sendToAllDevices(friendData.userId, msg)
    }

    suspend fun sendModelSync(friendUsername: String, model: String, entryId: String, op: String = "CREATE", data: Map<String, Any?>) {
        val friendData = friends.getAccepted().find { it.username == friendUsername }
            ?: throw IllegalStateException("Not friends with $friendUsername")

        val opEnum = when (op.uppercase()) {
            "UPDATE" -> obscura.v2.Client.ModelSync.Op.UPDATE
            "DELETE" -> obscura.v2.Client.ModelSync.Op.DELETE
            else -> obscura.v2.Client.ModelSync.Op.CREATE
        }

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.MODEL_SYNC)
            .setTimestamp(System.currentTimeMillis())
            .setModelSync(obscura.v2.modelSync {
                this.model = model; this.id = entryId; this.op = opEnum
                timestamp = System.currentTimeMillis()
                this.data = com.google.protobuf.ByteString.copyFrom(JSONObject(data).toString().toByteArray())
                authorDeviceId = this@ObscuraClient.deviceId ?: ""
            }).build()

        sendToAllDevices(friendData.userId, msg)
    }

    // ================================================================
    // Device Announce (TRIVIAL)
    // ================================================================

    suspend fun announceDevices() {
        val ownDevices = devices.getOwnDevices()
        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.DEVICE_ANNOUNCE)
            .setTimestamp(System.currentTimeMillis())
            .setDeviceAnnounce(obscura.v2.deviceAnnounce {
                for (d in ownDevices) {
                    devices.add(obscura.v2.deviceInfo {
                        deviceUuid = d.deviceId; deviceId = d.deviceId; deviceName = d.deviceName
                    })
                }
                timestamp = System.currentTimeMillis()
                isRevocation = false
            }).build()

        for (friend in friends.getAccepted()) {
            sendToAllDevices(friend.userId, msg)
        }
    }

    suspend fun announceDeviceRevocation(friendUsername: String, remainingDeviceIds: List<String>) {
        val friendData = friends.getAccepted().find { it.username == friendUsername }
            ?: throw IllegalStateException("Not friends with $friendUsername")

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.DEVICE_ANNOUNCE)
            .setTimestamp(System.currentTimeMillis())
            .setDeviceAnnounce(obscura.v2.deviceAnnounce {
                for (devId in remainingDeviceIds) {
                    devices.add(obscura.v2.deviceInfo {
                        deviceUuid = devId; deviceId = devId; deviceName = "Device"
                    })
                }
                timestamp = System.currentTimeMillis()
                isRevocation = true
                signature = com.google.protobuf.ByteString.copyFrom(ByteArray(64))
            }).build()

        sendToAllDevices(friendData.userId, msg)
    }

    // ================================================================
    // Device Revocation with Recovery Phrase (HARD)
    // ================================================================

    /**
     * Revoke a device. Signs revocation with recovery phrase, deletes from server,
     * purges messages from revoked device, broadcasts to all friends.
     */
    suspend fun revokeDevice(recoveryPhrase: String, targetDeviceId: String) {
        // Delete from server
        api.deleteDevice(targetDeviceId)

        // Purge messages from revoked device
        messages.deleteByAuthorDevice(targetDeviceId)

        // H4 fix: clean Signal state for revoked device
        signalStore.deleteAllSessions(targetDeviceId)

        // Build signed revocation announcement
        val remainingDeviceIds = devices.getOwnDevices()
            .map { it.deviceId }
            .filter { it != targetDeviceId }

        val announceData = com.obscura.kit.crypto.RecoveryKeys.serializeAnnounceForSigning(
            remainingDeviceIds, System.currentTimeMillis(), true
        )
        val signature = com.obscura.kit.crypto.RecoveryKeys.signWithPhrase(recoveryPhrase, announceData)
        val recoveryPubKey = com.obscura.kit.crypto.RecoveryKeys.getPublicKey(recoveryPhrase)

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.DEVICE_ANNOUNCE)
            .setTimestamp(System.currentTimeMillis())
            .setDeviceAnnounce(obscura.v2.deviceAnnounce {
                for (devId in remainingDeviceIds) {
                    devices.add(obscura.v2.deviceInfo {
                        deviceUuid = devId; deviceId = devId; deviceName = "Device"
                    })
                }
                timestamp = System.currentTimeMillis()
                isRevocation = true
                this.signature = com.google.protobuf.ByteString.copyFrom(signature)
                this.recoveryPublicKey = com.google.protobuf.ByteString.copyFrom(recoveryPubKey.serialize())
            }).build()

        for (friend in friends.getAccepted()) {
            sendToAllDevices(friend.userId, msg)
        }
    }

    /**
     * Announce account recovery to all friends. Signed with recovery phrase.
     */
    suspend fun announceRecovery(recoveryPhrase: String, isFullRecovery: Boolean = true) {
        val recoveryPubKey = com.obscura.kit.crypto.RecoveryKeys.getPublicKey(recoveryPhrase)

        val announceData = com.obscura.kit.crypto.RecoveryKeys.serializeAnnounceForSigning(
            listOf(deviceId ?: ""), System.currentTimeMillis(), false
        )
        val signature = com.obscura.kit.crypto.RecoveryKeys.signWithPhrase(recoveryPhrase, announceData)

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.DEVICE_RECOVERY_ANNOUNCE)
            .setTimestamp(System.currentTimeMillis())
            .setDeviceRecoveryAnnounce(obscura.v2.deviceRecoveryAnnounce {
                newDevices.add(obscura.v2.deviceInfo {
                    deviceUuid = this@ObscuraClient.deviceId ?: ""
                    deviceId = this@ObscuraClient.deviceId ?: ""
                    deviceName = config.deviceName
                })
                timestamp = System.currentTimeMillis()
                this.isFullRecovery = isFullRecovery
                this.signature = com.google.protobuf.ByteString.copyFrom(signature)
                this.recoveryPublicKey = com.google.protobuf.ByteString.copyFrom(recoveryPubKey.serialize())
            }).build()

        for (friend in friends.getAccepted()) {
            sendToAllDevices(friend.userId, msg)
        }
    }

    /**
     * Approve a new device linking. Sends P2P keys + state to new device.
     */
    suspend fun approveLink(newDeviceId: String, challengeResponse: ByteArray) {
        val identity = devices.getIdentity()
        val ownDeviceList = devices.getOwnDevices()
        val friendsExportStr = friends.exportAll()

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.DEVICE_LINK_APPROVAL)
            .setTimestamp(System.currentTimeMillis())
            .setDeviceLinkApproval(obscura.v2.deviceLinkApproval {
                if (identity?.p2pPublicKey != null) {
                    p2PPublicKey = com.google.protobuf.ByteString.copyFrom(identity.p2pPublicKey)
                }
                if (identity?.p2pPrivateKey != null) {
                    p2PPrivateKey = com.google.protobuf.ByteString.copyFrom(identity.p2pPrivateKey)
                }
                if (identity?.recoveryPublicKey != null) {
                    recoveryPublicKey = com.google.protobuf.ByteString.copyFrom(identity.recoveryPublicKey)
                }
                this.challengeResponse = com.google.protobuf.ByteString.copyFrom(challengeResponse)

                for (d in ownDeviceList) {
                    this.ownDevices.add(obscura.v2.deviceInfo {
                        deviceUuid = d.deviceId; deviceId = d.deviceId; deviceName = d.deviceName
                    })
                }

                friendsExport = com.google.protobuf.ByteString.copyFrom(friendsExportStr.toByteArray())
            }).build()

        messenger.queueMessage(newDeviceId, msg, userId)
        messenger.flushMessages()

        // Also push a sync blob with full state
        pushHistoryToDevice(newDeviceId)

        // Announce updated device list to all friends
        announceDevices()
    }

    /**
     * Generate a recovery phrase and store the keypair.
     */
    fun generateRecoveryPhrase(): String {
        val phrase = com.obscura.kit.crypto.RecoveryKeys.generatePhrase()
        recoveryPhrase = phrase
        recoveryPublicKey = com.obscura.kit.crypto.RecoveryKeys.getPublicKey(phrase).serialize()
        return phrase
    }

    // ================================================================
    // Session Reset (EASY)
    // ================================================================

    suspend fun resetSessionWith(targetUserId: String, reason: String = "manual") {
        signalStore.deleteAllSessions(targetUserId)

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.SESSION_RESET)
            .setResetReason(reason)
            .setTimestamp(System.currentTimeMillis()).build()

        sendToAllDevices(targetUserId, msg)
    }

    suspend fun resetAllSessions(reason: String = "manual") {
        val allFriends = friends.getAccepted()
        for (friend in allFriends) {
            try { resetSessionWith(friend.userId, reason) } catch (e: Exception) { }
        }
    }

    // ================================================================
    // Sync (TRIVIAL + MEDIUM)
    // ================================================================

    suspend fun requestSync() {
        val selfTargets = devices.getSelfSyncTargets().filter { it != deviceId }
        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.SYNC_REQUEST)
            .setTimestamp(System.currentTimeMillis()).build()

        for (devId in selfTargets) {
            messenger.queueMessage(devId, msg, userId)
        }
        messenger.flushMessages()
    }

    suspend fun pushHistoryToDevice(targetDeviceId: String) {
        val friendList = friends.getAccepted()
        val compressed = com.obscura.kit.crypto.SyncBlob.export(friendList)

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.SYNC_BLOB)
            .setTimestamp(System.currentTimeMillis())
            .setSyncBlob(obscura.v2.syncBlob {
                compressedData = com.google.protobuf.ByteString.copyFrom(compressed)
            }).build()

        messenger.queueMessage(targetDeviceId, msg, userId)
        messenger.flushMessages()
    }

    private suspend fun processSyncBlob(msg: ClientMessage) {
        val compressed = msg.syncBlob.compressedData.toByteArray()
        val parsed = com.obscura.kit.crypto.SyncBlob.parse(compressed) ?: return

        // Import friends
        if (parsed.friends.isNotEmpty()) {
            val friendsJson = JSONArray(parsed.friends.map { JSONObject(it) }).toString()
            friends.importAll(friendsJson)
        }

        // Import messages
        for (m in parsed.messages) {
            val convId = m["conversationId"] as? String ?: continue
            messages.add(convId, MessageData(
                id = m["messageId"] as? String ?: UUID.randomUUID().toString(),
                conversationId = convId,
                authorDeviceId = m["authorDeviceId"] as? String ?: "synced",
                content = m["content"] as? String ?: "",
                timestamp = (m["timestamp"] as? Long) ?: System.currentTimeMillis(),
                type = m["type"] as? String ?: "text"
            ))
        }
    }

    private suspend fun syncFriendToOwnDevices(friendUsername: String, action: String, status: String) {
        val selfTargets = devices.getSelfSyncTargets().filter { it != deviceId }
        if (selfTargets.isEmpty()) return

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.FRIEND_SYNC)
            .setTimestamp(System.currentTimeMillis())
            .setFriendSync(obscura.v2.friendSync {
                username = friendUsername
                this.action = action
                this.status = status
                timestamp = System.currentTimeMillis()
            }).build()

        for (devId in selfTargets) {
            messenger.queueMessage(devId, msg, userId)
        }
        messenger.flushMessages()
    }

    // ================================================================
    // Backup (MEDIUM)
    // ================================================================

    /**
     * Upload encrypted backup. Uses ECDH + AES-256-GCM with recovery public key.
     * No recovery phrase needed for upload — only the stored public key.
     */
    suspend fun uploadBackup(): String? {
        val friendList = friends.getAccepted()
        val compressed = com.obscura.kit.crypto.SyncBlob.export(friendList)

        val pubKey = recoveryPublicKey
        val payload = if (pubKey != null) {
            val ecPubKey = Curve.decodePoint(pubKey, 0)
            com.obscura.kit.crypto.BackupCrypto.encrypt(compressed, ecPubKey)
        } else {
            compressed // fallback: unencrypted if no recovery key set
        }

        val newEtag = api.uploadBackup(payload, backupEtag)
        backupEtag = newEtag
        return newEtag
    }

    /**
     * Download and decrypt backup using recovery phrase.
     * Returns null if no backup exists.
     */
    suspend fun downloadBackup(recoveryPhrase: String? = null): ParsedSyncBlob? {
        val result = api.downloadBackup(backupEtag) ?: return null
        val (data, etag) = result
        backupEtag = etag

        val decrypted = if (recoveryPhrase != null) {
            com.obscura.kit.crypto.BackupCrypto.decrypt(data, recoveryPhrase)
        } else {
            data // fallback: assume unencrypted
        }

        return com.obscura.kit.crypto.SyncBlob.parse(decrypted)
    }

    suspend fun checkBackup(): Triple<Boolean, String?, Long?> {
        return api.checkBackup()
    }

    /**
     * Replace identity key on current device (device takeover).
     * Generates fresh Signal keys, uploads via /v1/devices/keys.
     */
    suspend fun takeoverDevice() {
        val (identityKeyPair, regId) = signalStore.generateIdentity()
        this.registrationId = regId

        val signedPreKey = generateSignedPreKey(identityKeyPair, 1)
        val oneTimePreKeys = generateOneTimePreKeys(1, 100)

        val identityKeyB64 = identityKeyPair.publicKey.serialize().toBase64()
        val spkJson = JSONObject().apply {
            put("keyId", signedPreKey.id)
            put("publicKey", signedPreKey.keyPair.publicKey.serialize().toBase64())
            put("signature", signedPreKey.signature.toBase64())
        }
        val otpJsonArr = JSONArray(oneTimePreKeys.map { pk ->
            JSONObject().apply {
                put("keyId", pk.id)
                put("publicKey", pk.keyPair.publicKey.serialize().toBase64())
            }
        })

        api.uploadDeviceKeys(identityKeyB64, regId, spkJson, otpJsonArr)
        messenger.mapDevice(
            requireNotNull(deviceId) { "deviceId not set - call register/login first" },
            requireNotNull(userId) { "userId not set - call register/login first" },
            regId
        )
    }

    // ================================================================
    // Recovery (EASY — phrase storage + verify code)
    // ================================================================

    fun getRecoveryPhrase(): String? {
        val phrase = recoveryPhrase
        recoveryPhrase = null // one-time read
        return phrase
    }

    fun getVerifyCode(): String? {
        val key = recoveryPublicKey ?: return null
        return com.obscura.kit.crypto.VerificationCode.fromRecoveryKey(key)
    }

    // ================================================================
    // Attachments
    // ================================================================

    /**
     * Upload raw bytes (no client-side encryption). Use uploadEncryptedAttachment for E2E.
     */
    suspend fun uploadAttachment(data: ByteArray): Pair<String, Long> {
        val result = api.uploadAttachment(data)
        return Pair(result.getString("id"), result.optLong("expiresAt", 0))
    }

    /**
     * Upload encrypted attachment and send CONTENT_REFERENCE to a friend in one call.
     * Encrypts with AES-256-GCM, uploads ciphertext, sends key material via Signal.
     */
    suspend fun sendEncryptedAttachment(friendUsername: String, plaintext: ByteArray, mimeType: String = "application/octet-stream") {
        val encrypted = com.obscura.kit.crypto.AttachmentCrypto.encrypt(plaintext)
        val result = api.uploadAttachment(encrypted.ciphertext)
        val attachmentId = result.getString("id")
        sendAttachment(friendUsername, attachmentId, encrypted.contentKey, encrypted.nonce, mimeType, encrypted.sizeBytes)
    }

    suspend fun downloadAttachment(id: String): ByteArray = api.fetchAttachment(id)

    /**
     * Download and decrypt an attachment using the key material from CONTENT_REFERENCE.
     */
    suspend fun downloadDecryptedAttachment(id: String, contentKey: ByteArray, nonce: ByteArray, expectedHash: ByteArray? = null): ByteArray {
        val ciphertext = api.fetchAttachment(id)
        return com.obscura.kit.crypto.AttachmentCrypto.decrypt(ciphertext, contentKey, nonce, expectedHash)
    }

    // ================================================================
    // Raw + Wait (escape hatches)
    // ================================================================

    suspend fun sendRaw(targetUserId: String, msg: ClientMessage) = sendToAllDevices(targetUserId, msg)

    suspend fun waitForMessage(timeoutMs: Long = 15_000): ReceivedMessage {
        return kotlinx.coroutines.withTimeout(timeoutMs) { incomingMessages.receive() }
    }

    // ================================================================
    // Internal
    // ================================================================

    private suspend fun sendToAllDevices(targetUserId: String, msg: ClientMessage) {
        ensureFreshToken() // refresh if within 60s of expiry
        var deviceIds = messenger.getDeviceIdsForUser(targetUserId)
        if (deviceIds.isEmpty()) {
            messenger.fetchPreKeyBundles(targetUserId)
            deviceIds = messenger.getDeviceIdsForUser(targetUserId)
        }
        for (devId in deviceIds) {
            messenger.queueMessage(devId, msg, targetUserId)
        }
        messenger.flushMessages()
    }

    private fun generateSignedPreKey(identityKeyPair: IdentityKeyPair, id: Int): SignedPreKeyRecord {
        val keyPair = Curve.generateKeyPair()
        val signature = Curve.calculateSignature(identityKeyPair.privateKey, keyPair.publicKey.serialize())
        val record = SignedPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature)
        signalStore.storeSignedPreKey(id, record)
        return record
    }

    private fun generateOneTimePreKeys(startId: Int, count: Int): List<PreKeyRecord> {
        return (startId until startId + count).map { id ->
            val keyPair = Curve.generateKeyPair()
            val record = PreKeyRecord(id, keyPair)
            signalStore.storePreKey(id, record)
            record
        }
    }

}
