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
import com.obscura.kit.orm.SignalManager
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

enum class AuthState { LOGGED_OUT, PENDING_APPROVAL, AUTHENTICATED }

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
    val orm: SchemaDomain

    private val modelStore: ModelStore
    private val syncManager: SyncManager
    private val ttlManager: TTLManager
    private val signalManager: SignalManager

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

    /** Debug log — ring buffer of last 200 events. Thread-safe. */
    val debugLog = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private fun log(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        debugLog.addFirst("[$ts] $msg")
        while (debugLog.size > 200) debugLog.removeLast()
    }

    /** Dump debug log + current status as a single string for clipboard/paste. */
    fun dumpDebugLog(): String {
        val status = buildString {
            appendLine("=== ObscuraKit Debug Dump ===")
            appendLine("user: $username ($userId)")
            appendLine("device: $deviceId")
            appendLine("auth: ${_authState.value}")
            appendLine("connection: ${_connectionState.value}")
            appendLine("friends: ${friendList.value.size} (${friendList.value.count { it.status == com.obscura.kit.stores.FriendStatus.ACCEPTED }} accepted)")
            appendLine("prekeys: ${try { signalStore.getPreKeyCount() } catch (_: Exception) { "?" }}")
            appendLine("---")
        }
        return status + debugLog.joinToString("\n")
    }

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
        signalManager = SignalManager()
        gateway = GatewayConnection(api, scope)

        orm = SchemaDomain(modelStore, syncManager, ttlManager, signalManager = signalManager)

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
                // Data stays — logout is not a wipe. Login again restores full state.
            },
            onWipeDevice = {
                envelopeJob?.cancel()
                gateway.disconnect()
                _connectionState.value = ConnectionState.DISCONNECTED
                db.friendQueries.deleteAll()
                db.messageQueries.deleteAll()
                db.deviceQueries.deleteAllDevices()
                db.deviceQueries.deleteIdentity()
                db.signalKeyQueries.deleteLocalIdentity()
                db.signalKeyQueries.deleteAllSignalData()
                db.signalKeyQueries.deleteAllPreKeys()
                db.signalKeyQueries.deleteAllSignedPreKeys()
                db.signalKeyQueries.deleteAllSessions()
                db.signalKeyQueries.deleteAllSenderKeys()
                db.modelEntryQueries.deleteAllEntries()
                db.modelEntryQueries.deleteAllAssociations()
            }
        )

        messageSender = MessageSender(messenger, authManager)
        ctx.messageSender = messageSender

        // Wire gateway reconnect token refresh
        gateway.ensureFreshToken = { authManager.ensureFreshToken() }

        // Wire ORM auto-sync: model.create() → encrypt → fan out → flush
        syncManager.getSelfSyncTargets = { devices.getSelfSyncTargets() }
        syncManager.getFriendTargets = {
            val accepted = friends.getAccepted()
            val targets = mutableListOf<String>()
            for (f in accepted) {
                var deviceIds = messenger.getDeviceIdsForUser(f.userId)
                if (deviceIds.isEmpty()) {
                    // Discover devices via prekey bundle fetch (same as MessageSender)
                    try { messenger.fetchPreKeyBundles(f.userId) } catch (_: Exception) {}
                    deviceIds = messenger.getDeviceIdsForUser(f.userId)
                }
                targets.addAll(deviceIds)
            }
            targets
        }
        syncManager.queueModelSync = { targetDeviceId, modelSync ->
            val msg = obscura.v2.Client.ClientMessage.newBuilder()
                .setType(obscura.v2.Client.ClientMessage.Type.MODEL_SYNC)
                .setTimestamp(System.currentTimeMillis())
                .setModelSync(obscura.v2.modelSync {
                    model = modelSync.model; id = modelSync.id
                    op = obscura.v2.Client.ModelSync.Op.forNumber(modelSync.op)
                        ?: obscura.v2.Client.ModelSync.Op.CREATE
                    timestamp = modelSync.timestamp
                    data = com.google.protobuf.ByteString.copyFrom(modelSync.data)
                    authorDeviceId = modelSync.authorDeviceId
                    signature = com.google.protobuf.ByteString.copyFrom(modelSync.signature)
                }).build()
            val mapped = messenger.deviceMap(targetDeviceId)
            messenger.queueMessage(targetDeviceId, msg, mapped?.first)
        }
        syncManager.getDevicesForUsername = { username ->
            val friend = friends.getAccepted().find { it.username == username }
            if (friend != null) {
                var deviceIds = messenger.getDeviceIdsForUser(friend.userId)
                if (deviceIds.isEmpty()) {
                    try { messenger.fetchPreKeyBundles(friend.userId) } catch (_: Exception) {}
                    deviceIds = messenger.getDeviceIdsForUser(friend.userId)
                }
                deviceIds
            } else emptyList()
        }
        syncManager.flushQueue = {
            authManager.ensureFreshToken()
            messenger.flushMessages()
        }

        // Wire ECS signal sending — JSON in text field (matches iOS wire format exactly)
        signalManager.sendSignal = { modelName, signalName, signalData ->
            val payload = org.json.JSONObject().apply {
                put("model", modelName)
                put("signal", signalName)
                put("data", org.json.JSONObject(signalData))
                put("authorDeviceId", session.deviceId ?: "")
                put("timestamp", System.currentTimeMillis())
            }
            val signalMsg = obscura.v2.Client.ClientMessage.newBuilder()
                .setType(obscura.v2.Client.ClientMessage.Type.MODEL_SIGNAL)
                .setTimestamp(System.currentTimeMillis())
                .setText(payload.toString())
                .build()
            authManager.ensureFreshToken()
            val accepted = friends.getAccepted()
            for (f in accepted) {
                var deviceIds = messenger.getDeviceIdsForUser(f.userId)
                if (deviceIds.isEmpty()) {
                    try { messenger.fetchPreKeyBundles(f.userId) } catch (_: Exception) {}
                    deviceIds = messenger.getDeviceIdsForUser(f.userId)
                }
                for (devId in deviceIds) {
                    messenger.queueMessage(devId, signalMsg, f.userId)
                }
            }
            messenger.flushMessages()
        }

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
            db.friendQueries.selectAll()
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

    suspend fun register(username: String, password: String) {
        authManager.register(username, password)
        orm.setUsername(username)
    }
    suspend fun login(username: String, password: String): com.obscura.kit.network.LoginResult {
        val result = authManager.login(username, password)
        orm.setUsername(username)
        return result
    }
    suspend fun loginAndProvision(username: String, password: String, deviceName: String = "Device 2") =
        authManager.loginAndProvision(username, password, deviceName)

    fun restoreSession(
        token: String,
        refreshToken: String?,
        userId: String,
        deviceId: String?,
        username: String?,
        registrationId: Int = 0
    ) {
        authManager.restoreSession(token, refreshToken, userId, deviceId, username, registrationId)
        if (username != null) orm.setUsername(username)
    }

    fun hasSession(): Boolean = authManager.hasSession()
    suspend fun logout() = authManager.logout()
    suspend fun wipeDevice() = authManager.wipeDevice()
    suspend fun ensureFreshToken(): Boolean = authManager.ensureFreshToken()

    suspend fun connect() {
        log("CONNECT start")
        _connectionState.value = ConnectionState.CONNECTING
        messenger.rebuildDeviceMap(friends.getAccepted())
        gateway.connect()
        _connectionState.value = ConnectionState.CONNECTED
        log("CONNECT ok — websocket open")
        startEnvelopeLoop()
        authManager.startTokenRefresh()
        startPreKeyStatusListener()
    }

    fun disconnect() {
        log("DISCONNECT")
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
                    log("RECV BLOCKED rate-limited sender=$senderId")
                    try { gateway.ack(listOf(envelope.id)) } catch (_: Exception) { }
                    continue
                }

                try {
                    val decrypted = messenger.decrypt(envelope)
                    val msg = decrypted.clientMessage
                    log("RECV ${msg.type.name} from=${decrypted.sourceUserId.take(8)} text=${msg.text.take(40)}")
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
                    log("RECV FAIL decrypt sender=$senderId err=${e.message?.take(60)}")
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
            ClientMessage.Type.DEVICE_LINK_APPROVAL -> handleLinkApproval(msg, sourceUserId)
            ClientMessage.Type.MODEL_SIGNAL -> handleModelSignal(msg, sourceUserId)
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

    private fun handleModelSignal(msg: ClientMessage, sourceUserId: String) {
        try {
            val modelName: String
            val signalName: String
            val authorDeviceId: String
            val data: Map<String, Any?>

            // Try JSON in text field first (cross-platform wire format)
            val json = if (msg.text.isNotBlank() && msg.text.startsWith("{")) {
                org.json.JSONObject(msg.text)
            } else null

            if (json != null && json.has("model") && json.has("signal")) {
                modelName = json.getString("model")
                signalName = json.getString("signal")
                authorDeviceId = json.optString("authorDeviceId", sourceUserId)
                val dataJson = json.optJSONObject("data") ?: org.json.JSONObject()
                val map = mutableMapOf<String, Any?>()
                for (key in dataJson.keys()) { map[key] = if (dataJson.isNull(key)) null else dataJson.get(key) }
                data = map
            } else {
                // Fallback: proto field
                val sig = msg.modelSignal ?: return
                if (sig.model.isBlank()) return
                modelName = sig.model
                signalName = sig.signal
                authorDeviceId = sig.authorDeviceId.ifBlank { sourceUserId }
                data = try {
                    val j = org.json.JSONObject(String(sig.data.toByteArray()))
                    val m = mutableMapOf<String, Any?>()
                    for (k in j.keys()) { m[k] = if (j.isNull(k)) null else j.get(k) }
                    m
                } catch (_: Exception) { emptyMap() }
            }

            if (signalName == "stoppedTyping") {
                signalManager.clear(modelName, "typing", data, authorDeviceId)
            } else {
                signalManager.receive(modelName, signalName, data, authorDeviceId)
            }
        } catch (_: Exception) {
            // Never let signal handling crash the envelope loop
        }
    }

    private suspend fun handleModelSync(msg: ClientMessage, sourceUserId: String) {
        val sync = msg.modelSync
        val syncData = ModelSyncData(
            model = sync.model, id = sync.id, op = sync.op.number,
            timestamp = sync.timestamp, data = sync.data.toByteArray(),
            authorDeviceId = sync.authorDeviceId, signature = sync.signature.toByteArray()
        )
        orm.handleSync(syncData, sourceUserId)

        // DirectMessage MODEL_SYNC → also route to conversations for chat UI
        if (sync.model == "directMessage") {
            try {
                val json = org.json.JSONObject(String(sync.data.toByteArray()))
                val content = json.optString("content", "")
                // File under the sender's userId — that's who we're chatting with
                val conversationWith = sourceUserId
                val msgData = MessageData(
                    id = sync.id, conversationId = conversationWith,
                    authorDeviceId = sync.authorDeviceId,
                    content = content, timestamp = sync.timestamp,
                    type = "text"
                )
                messagesDomain.add(conversationWith, msgData)
                refreshConversation(conversationWith)
            } catch (_: Exception) {}
        }
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

    private suspend fun handleLinkApproval(msg: ClientMessage, sourceUserId: String) {
        // Only accept approval from our own account
        if (sourceUserId != userId) return
        // Only process if we're actually waiting for approval
        if (_authState.value != AuthState.PENDING_APPROVAL) return

        val approval = msg.deviceLinkApproval

        // Verify challenge matches the one we generated in our link code
        val pendingChallenge = session.pendingLinkChallenge
        if (pendingChallenge != null && approval.challengeResponse.size() > 0) {
            val received = approval.challengeResponse.toByteArray()
            if (!com.obscura.kit.crypto.LinkCode.verifyChallenge(pendingChallenge, received)) {
                logger.decryptFailed(sourceUserId, "Link approval challenge mismatch")
                return
            }
        }

        // Import device list from approval
        val approvedDevices = approval.ownDevicesList.map { d ->
            FriendDeviceInfo(d.deviceUuid, d.deviceId, d.deviceName)
        }
        if (approvedDevices.isNotEmpty()) {
            devices.setOwnDevices(approvedDevices)
        }

        // Store identity keys from approval
        val identity = devices.getIdentity()
        if (identity != null) {
            devices.storeIdentity(identity.copy(
                p2pPublicKey = approval.p2PPublicKey?.toByteArray()?.takeIf { it.isNotEmpty() },
                recoveryPublicKey = approval.recoveryPublicKey?.toByteArray()?.takeIf { it.isNotEmpty() }
            ))
        }

        // Import friend data from approval
        if (approval.friendsExport.size() > 0) {
            try {
                friends.importAll(String(approval.friendsExport.toByteArray()))
            } catch (_: Exception) {}
        }

        session.pendingLinkChallenge = null
        _authState.value = AuthState.AUTHENTICATED
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

    /**
     * Send a text message via ORM (MODEL_SYNC). Interoperable with iOS DirectMessage.
     * Falls back to legacy TEXT if directMessage model is not defined.
     */
    suspend fun send(friendUsername: String, text: String) {
        log("SEND to=$friendUsername text=${text.take(40)}")
        val directMessage = orm.modelOrNull("directMessage")
        if (directMessage != null) {
            val friendData = friends.getAccepted().find { it.username == friendUsername }
                ?: throw IllegalStateException("Not friends with $friendUsername")
            val convId = listOf(userId ?: "", friendData.userId).sorted().joinToString("_")
            // Create via ORM — auto-syncs to friend via MODEL_SYNC
            val entry = directMessage.create(mapOf(
                "conversationId" to convId,
                "content" to text,
                "senderUsername" to (username ?: "")
            ))
            // Also persist locally to conversations (keyed by friend userId for StateFlow compat)
            messagesDomain.add(friendData.userId, MessageData(
                id = entry.id, conversationId = friendData.userId,
                authorDeviceId = deviceId ?: "self",
                content = text, timestamp = entry.timestamp, type = "text"
            ))
            refreshConversation(friendData.userId)
        } else {
            // Legacy path — sends TEXT (type 0) instead of MODEL_SYNC
            messagingManager.send(friendUsername, text)
        }
    }
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
    suspend fun revokeDevice(recoveryPhrase: String, targetDeviceId: String) {
        requireRecoveryEnabled("revokeDevice")
        deviceManager.revokeDevice(recoveryPhrase, targetDeviceId)
    }
    /**
     * Generate a link code for this device. Display as QR code or copyable text.
     * The existing device scans this and calls validateAndApproveLink().
     */
    fun generateLinkCode(): String {
        val did = deviceId ?: throw IllegalStateException("Not provisioned — call loginAndProvision first")
        val identityKey = signalStore.getIdentityKeyPair().publicKey.serialize()
        val generated = com.obscura.kit.crypto.LinkCode.generate(did, did, identityKey)
        session.pendingLinkChallenge = generated.challenge
        return generated.code
    }

    /**
     * Validate a link code and approve the new device. Called by the EXISTING device
     * after scanning QR or receiving pasted code from the new device.
     */
    suspend fun validateAndApproveLink(linkCode: String) {
        val result = com.obscura.kit.crypto.LinkCode.validate(linkCode)
        require(result.valid) { result.error ?: "Invalid link code" }
        val data = result.data!!
        deviceManager.approveLink(data.deviceId, data.challenge)
    }

    /**
     * Low-level approve — use validateAndApproveLink() instead for the full flow.
     */
    suspend fun approveLink(newDeviceId: String, challengeResponse: ByteArray) =
        deviceManager.approveLink(newDeviceId, challengeResponse)
    suspend fun takeoverDevice() = deviceManager.takeoverDevice()

    fun generateRecoveryPhrase(): String {
        requireRecoveryEnabled("generateRecoveryPhrase")
        return recoveryManager.generateRecoveryPhrase()
    }
    fun getRecoveryPhrase(): String? {
        requireRecoveryEnabled("getRecoveryPhrase")
        return recoveryManager.getRecoveryPhrase()
    }
    fun getVerifyCode(): String? = recoveryManager.getVerifyCode()
    suspend fun announceRecovery(recoveryPhrase: String, isFullRecovery: Boolean = true) {
        requireRecoveryEnabled("announceRecovery")
        recoveryManager.announceRecovery(recoveryPhrase, isFullRecovery)
    }
    suspend fun uploadBackup(): String? = recoveryManager.uploadBackup()
    suspend fun downloadBackup(recoveryPhrase: String? = null): ParsedSyncBlob? = recoveryManager.downloadBackup(recoveryPhrase)
    suspend fun checkBackup(): Triple<Boolean, String?, Long?> = recoveryManager.checkBackup()

    private fun requireRecoveryEnabled(method: String) {
        require(config.enableRecoveryPhrase) {
            "$method requires ObscuraConfig(enableRecoveryPhrase = true)"
        }
    }

    suspend fun resetSessionWith(targetUserId: String, reason: String = "manual") =
        clientSyncManager.resetSessionWith(targetUserId, reason)
    suspend fun resetAllSessions(reason: String = "manual") = clientSyncManager.resetAllSessions(reason)
    suspend fun requestSync() = clientSyncManager.requestSync()
    suspend fun pushHistoryToDevice(targetDeviceId: String) = clientSyncManager.pushHistoryToDevice(targetDeviceId)

    suspend fun waitForMessage(timeoutMs: Long = 15_000): ReceivedMessage {
        return kotlinx.coroutines.withTimeout(timeoutMs) { incomingMessages.receive() }
    }
}
