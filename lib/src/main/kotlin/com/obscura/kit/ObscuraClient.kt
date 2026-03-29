package com.obscura.kit

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.obscura.kit.crypto.ParsedSyncBlob
import com.obscura.kit.crypto.SignalStore
import com.obscura.kit.crypto.toBase64
import com.obscura.kit.db.ObscuraDatabase
import com.obscura.kit.managers.*
import com.obscura.kit.managers.SignalKeyUtils.toApiJson
import com.obscura.kit.network.APIClient
import com.obscura.kit.network.GatewayConnection
import com.obscura.kit.network.UploadDeviceKeysRequest
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
import org.signal.libsignal.protocol.ecc.Curve
import java.util.*

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

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _authState = MutableStateFlow(AuthState.LOGGED_OUT)
    val authState: StateFlow<AuthState> = _authState

    private val _friendList = MutableStateFlow<List<FriendData>>(emptyList())
    val friendList: StateFlow<List<FriendData>> = _friendList

    private val _pendingRequests = MutableStateFlow<List<FriendData>>(emptyList())
    val pendingRequests: StateFlow<List<FriendData>> = _pendingRequests

    private val _conversations = MutableStateFlow<Map<String, List<MessageData>>>(emptyMap())
    val conversations: StateFlow<Map<String, List<MessageData>>> = _conversations

    private val _events = MutableSharedFlow<ReceivedMessage>(extraBufferCapacity = 64)
    val events: SharedFlow<ReceivedMessage> = _events

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
    private val messagesDomain: MessageDomain
    internal val devices: DeviceDomain
    internal val messenger: MessengerDomain
    internal val orm: SchemaDomain

    private val modelStore: ModelStore
    private val syncManager: SyncManager
    private val ttlManager: TTLManager

    // Session — shared mutable state
    private val session = ClientSession()

    // Managers
    private val authManager: AuthManager
    private val messageSender: MessageSender
    private val recoveryManager: RecoveryManager
    private val friendshipManager: FriendshipManager
    private val messagingManager: MessagingManager
    private val deviceManager: DeviceManager
    private val clientSyncManager: ClientSyncManager

    // Identity — delegate to session
    var userId: String?
        get() = session.userId
        private set(value) { session.userId = value }
    var deviceId: String?
        get() = session.deviceId
        private set(value) { session.deviceId = value }
    var username: String?
        get() = session.username
        private set(value) { session.username = value }
    var refreshToken: String?
        get() = session.refreshToken
        private set(value) { session.refreshToken = value }
    var registrationId: Int
        get() = session.registrationId
        private set(value) { session.registrationId = value }
    val token: String? get() = api.token

    // recoveryPhrase and recoveryPublicKey are managed via session + RecoveryManager

    /** Structured logger for security events. Set to a custom implementation for production. */
    var logger: ObscuraLogger = NoOpLogger

    // Test convenience — single-consumer channel
    val incomingMessages = Channel<ReceivedMessage>(capacity = 1000)

    private var envelopeJob: Job? = null

    // M13: Decrypt rate limiting per sender
    private val decryptFailures = mutableMapOf<String, Pair<Int, Long>>() // senderId -> (count, windowStart)
    private val MAX_DECRYPT_FAILURES = 10
    private val DECRYPT_FAILURE_WINDOW_MS = 60_000L

    // Prekey replenishment
    private val PREKEY_MIN_COUNT = 20L
    private val PREKEY_REPLENISH_COUNT = 50

    init {
        if (externalDriver == null) {
            ObscuraDatabase.Schema.create(driver)
            try { driver.execute(null, "PRAGMA secure_delete = ON", 0) } catch (_: Exception) {}
        }
        db = ObscuraDatabase(driver)

        signalStore = SignalStore(db)
        signalStore.onIdentityChanged = { address, _, _ ->
            logger.identityChanged(address)
        }
        friends = FriendDomain(db)
        messagesDomain = MessageDomain(db)
        devices = DeviceDomain(db)
        messenger = MessengerDomain(signalStore, api)

        modelStore = ModelStore(db)
        syncManager = SyncManager(modelStore)
        ttlManager = TTLManager(modelStore)
        gateway = GatewayConnection(api, scope)

        orm = SchemaDomain(modelStore, syncManager, ttlManager)

        // Create ClientContext — shared dependencies for all managers
        val ctx = ClientContext(
            session = session,
            api = api,
            signalStore = signalStore,
            messenger = messenger,
            friends = friends,
            devices = devices,
            messages = messagesDomain
        )

        // Create managers — order matters: AuthManager before MessageSender
        authManager = AuthManager(
            ctx = ctx,
            config = config,
            gateway = gateway,
            scope = scope,
            setAuthState = { _authState.value = it },
            setDisconnected = { disconnect() },
            loggerProvider = { logger },
            onLogout = {
                envelopeJob?.cancel()
                gateway.disconnect()
                _connectionState.value = ConnectionState.DISCONNECTED
                db.friendQueries.deleteAll()
                db.messageQueries.deleteAll()
                db.deviceQueries.deleteAllDevices()
                db.deviceQueries.deleteIdentity()
                db.signalKeyQueries.deleteAllSignalData()
                db.modelEntryQueries.deleteAllEntries()
                db.modelEntryQueries.deleteAllAssociations()
            }
        )

        messageSender = MessageSender(messenger, authManager)
        ctx.messageSender = messageSender

        recoveryManager = RecoveryManager(ctx = ctx, config = config)

        friendshipManager = FriendshipManager(ctx = ctx)

        messagingManager = MessagingManager(ctx = ctx)

        clientSyncManager = ClientSyncManager(ctx = ctx)

        deviceManager = DeviceManager(
            ctx = ctx,
            clientSyncManager = { clientSyncManager },
            announceDevicesCallback = { announceDevices() }
        )

        // Reactive observation — auto-updates StateFlows when DB changes
        startDatabaseObservation()
    }

    private fun startDatabaseObservation() {
        scope.launch {
            db.friendQueries.selectByStatus(FriendStatus.ACCEPTED.value)
                .asFlow()
                .mapToList(Dispatchers.IO)
                .map { rows -> rows.map { it.toFriendData() } }
                .collect { _friendList.value = it }
        }

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

    suspend fun register(username: String, password: String) = authManager.register(username, password)
    suspend fun login(username: String, password: String) = authManager.login(username, password)
    suspend fun loginAndProvision(username: String, password: String, deviceName: String = "Device 2") =
        authManager.loginAndProvision(username, password, deviceName)

    fun restoreSession(
        token: String,
        refreshToken: String?,
        userId: String,
        deviceId: String?,
        username: String?,
        registrationId: Int = 0
    ) = authManager.restoreSession(token, refreshToken, userId, deviceId, username, registrationId)

    fun hasSession(): Boolean = authManager.hasSession()
    suspend fun logout() = authManager.logout()
    suspend fun ensureFreshToken(): Boolean = authManager.ensureFreshToken()

    suspend fun connect() {
        _connectionState.value = ConnectionState.CONNECTING
        gateway.connect()
        _connectionState.value = ConnectionState.CONNECTED
        startEnvelopeLoop()
        authManager.startTokenRefresh()
        startPreKeyStatusListener()
    }

    fun disconnect() {
        authManager.tokenRefreshJob?.cancel()
        envelopeJob?.cancel()
        gateway.disconnect()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

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
            val newPreKeys = SignalKeyUtils.generateOneTimePreKeys(signalStore, highestId + 1, PREKEY_REPLENISH_COUNT)

            val spkRecord = signalStore.loadSignedPreKey(1)

            api.uploadDeviceKeys(UploadDeviceKeysRequest(
                identityKey = signalStore.getIdentityKeyPair().publicKey.serialize().toBase64(),
                registrationId = signalStore.getLocalRegistrationId(),
                signedPreKey = spkRecord.toApiJson(),
                oneTimePreKeys = newPreKeys.toApiJson()
            ))
        } catch (e: Exception) {
            logger.preKeyReplenishFailed(e.message ?: "unknown")
        }
    }

    private fun startEnvelopeLoop() {
        envelopeJob = scope.launch {
            for (envelope in gateway.envelopes) {
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
            decryptFailures.remove(senderId)
            return false
        }
        return count >= MAX_DECRYPT_FAILURES
    }

    private fun trackDecryptFailure(senderId: String) {
        val now = System.currentTimeMillis()
        val (count, windowStart) = decryptFailures[senderId] ?: Pair(0, now)
        if (now - windowStart > DECRYPT_FAILURE_WINDOW_MS) {
            decryptFailures[senderId] = Pair(1, now)
        } else {
            decryptFailures[senderId] = Pair(count + 1, windowStart)
        }
    }

    private suspend fun routeMessage(msg: ClientMessage, sourceUserId: String, senderDeviceId: String?) {
        when (msg.type) {
            ClientMessage.Type.FRIEND_REQUEST -> handleFriendRequest(msg, sourceUserId)
            ClientMessage.Type.FRIEND_RESPONSE -> handleFriendResponse(msg, sourceUserId)
            ClientMessage.Type.TEXT, ClientMessage.Type.IMAGE -> handleTextMessage(msg, sourceUserId, senderDeviceId)
            ClientMessage.Type.DEVICE_ANNOUNCE -> handleDeviceAnnounce(msg, sourceUserId)
            ClientMessage.Type.MODEL_SYNC -> handleModelSync(msg, sourceUserId)
            ClientMessage.Type.SYNC_BLOB -> handleSyncBlob(msg, sourceUserId)
            ClientMessage.Type.SENT_SYNC -> handleSentSync(msg)
            ClientMessage.Type.SESSION_RESET -> signalStore.deleteAllSessions(sourceUserId)
            ClientMessage.Type.FRIEND_SYNC -> handleFriendSync(msg, sourceUserId)
            else -> { }
        }
    }

    private suspend fun handleFriendRequest(msg: ClientMessage, sourceUserId: String) {
        friends.add(sourceUserId, msg.username, FriendStatus.PENDING_RECEIVED)
    }

    private suspend fun handleFriendResponse(msg: ClientMessage, sourceUserId: String) {
        if (msg.accepted) friends.add(sourceUserId, msg.username, FriendStatus.ACCEPTED)
    }

    private suspend fun handleTextMessage(msg: ClientMessage, sourceUserId: String, senderDeviceId: String?) {
        val msgId = UUID.randomUUID().toString()
        val msgData = MessageData(
            id = msgId, conversationId = sourceUserId,
            authorDeviceId = senderDeviceId ?: "unknown",
            content = msg.text, timestamp = msg.timestamp,
            type = msg.type.name.lowercase()
        )
        messagesDomain.add(sourceUserId, msgData)
        refreshConversation(sourceUserId)
    }

    private suspend fun handleDeviceAnnounce(msg: ClientMessage, sourceUserId: String) {
        val announce = msg.deviceAnnounce
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

    private suspend fun handleModelSync(msg: ClientMessage, sourceUserId: String) {
        val sync = msg.modelSync
        orm.handleSync(ModelSyncData(
            model = sync.model, id = sync.id, op = sync.op.number,
            timestamp = sync.timestamp, data = sync.data.toByteArray(),
            authorDeviceId = sync.authorDeviceId, signature = sync.signature.toByteArray()
        ), sourceUserId)
    }

    private suspend fun handleSyncBlob(msg: ClientMessage, sourceUserId: String) {
        if (sourceUserId != userId) return
        clientSyncManager.processSyncBlob(msg)
    }

    private suspend fun handleSentSync(msg: ClientMessage) {
        val ss = msg.sentSync
        messagesDomain.add(ss.conversationId, MessageData(
            id = ss.messageId, conversationId = ss.conversationId,
            authorDeviceId = deviceId ?: "self", content = String(ss.content.toByteArray()),
            timestamp = ss.timestamp, type = "text"
        ))
        refreshConversation(ss.conversationId)
    }

    private suspend fun handleFriendSync(msg: ClientMessage, sourceUserId: String) {
        if (sourceUserId != userId) return
        val fs = msg.friendSync
        val status = if (fs.status == FriendStatus.ACCEPTED.value) FriendStatus.ACCEPTED else FriendStatus.PENDING_RECEIVED
        if (fs.action == FriendSyncAction.ADD.value) {
            friends.add(sourceUserId, fs.username, status,
                fs.devicesList.map { FriendDeviceInfo(it.deviceUuid, it.deviceId, it.deviceName) })
        } else if (fs.action == FriendSyncAction.REMOVE.value) {
            friends.remove(sourceUserId)
        }
    }

    private suspend fun refreshConversation(conversationId: String) {
        val msgs = messagesDomain.getMessages(conversationId)
        val current = _conversations.value.toMutableMap()
        current[conversationId] = msgs
        _conversations.value = current
    }

    suspend fun getMessages(conversationId: String, limit: Int = 50): List<MessageData> {
        val msgs = messagesDomain.getMessages(conversationId, limit)
        val current = _conversations.value.toMutableMap()
        current[conversationId] = msgs
        _conversations.value = current
        return msgs
    }

    suspend fun send(friendUsername: String, text: String) = messagingManager.send(friendUsername, text)
    suspend fun sendAttachment(friendUsername: String, attachmentId: String, contentKey: ByteArray, nonce: ByteArray, mimeType: String, sizeBytes: Long) =
        messagingManager.sendAttachment(friendUsername, attachmentId, contentKey, nonce, mimeType, sizeBytes)
    suspend fun sendEncryptedAttachment(friendUsername: String, plaintext: ByteArray, mimeType: String = "application/octet-stream") =
        messagingManager.sendEncryptedAttachment(friendUsername, plaintext, mimeType)
    suspend fun sendModelSync(friendUsername: String, model: String, entryId: String, op: String = "CREATE", data: Map<String, Any?>) =
        messagingManager.sendModelSync(friendUsername, model, entryId, op, data)
    suspend fun sendRaw(targetUserId: String, msg: ClientMessage) = messagingManager.sendRaw(targetUserId, msg)
    suspend fun uploadAttachment(data: ByteArray): Pair<String, Long> = messagingManager.uploadAttachment(data)
    suspend fun downloadAttachment(id: String): ByteArray = messagingManager.downloadAttachment(id)
    suspend fun downloadDecryptedAttachment(id: String, contentKey: ByteArray, nonce: ByteArray, expectedHash: ByteArray? = null): ByteArray =
        messagingManager.downloadDecryptedAttachment(id, contentKey, nonce, expectedHash)

    suspend fun befriend(targetUserId: String, targetUsername: String) = friendshipManager.befriend(targetUserId, targetUsername)
    suspend fun acceptFriend(targetUserId: String, targetUsername: String) = friendshipManager.acceptFriend(targetUserId, targetUsername)

    suspend fun announceDevices() = deviceManager.announceDevices()
    suspend fun announceDeviceRevocation(friendUsername: String, remainingDeviceIds: List<String>) =
        deviceManager.announceDeviceRevocation(friendUsername, remainingDeviceIds)
    suspend fun revokeDevice(recoveryPhrase: String, targetDeviceId: String) =
        deviceManager.revokeDevice(recoveryPhrase, targetDeviceId)
    suspend fun approveLink(newDeviceId: String, challengeResponse: ByteArray) =
        deviceManager.approveLink(newDeviceId, challengeResponse)
    suspend fun takeoverDevice() = deviceManager.takeoverDevice()

    fun generateRecoveryPhrase(): String = recoveryManager.generateRecoveryPhrase()
    fun getRecoveryPhrase(): String? = recoveryManager.getRecoveryPhrase()
    fun getVerifyCode(): String? = recoveryManager.getVerifyCode()
    suspend fun announceRecovery(recoveryPhrase: String, isFullRecovery: Boolean = true) =
        recoveryManager.announceRecovery(recoveryPhrase, isFullRecovery)
    suspend fun uploadBackup(): String? = recoveryManager.uploadBackup()
    suspend fun downloadBackup(recoveryPhrase: String? = null): ParsedSyncBlob? = recoveryManager.downloadBackup(recoveryPhrase)
    suspend fun checkBackup(): Triple<Boolean, String?, Long?> = recoveryManager.checkBackup()

    suspend fun resetSessionWith(targetUserId: String, reason: String = "manual") =
        clientSyncManager.resetSessionWith(targetUserId, reason)
    suspend fun resetAllSessions(reason: String = "manual") = clientSyncManager.resetAllSessions(reason)
    suspend fun requestSync() = clientSyncManager.requestSync()
    suspend fun pushHistoryToDevice(targetDeviceId: String) = clientSyncManager.pushHistoryToDevice(targetDeviceId)

    suspend fun waitForMessage(timeoutMs: Long = 15_000): ReceivedMessage {
        return kotlinx.coroutines.withTimeout(timeoutMs) { incomingMessages.receive() }
    }
}
