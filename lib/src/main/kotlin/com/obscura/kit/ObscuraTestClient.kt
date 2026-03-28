package com.obscura.kit

import com.google.protobuf.ByteString
import com.obscura.kit.crypto.SignalStore
import com.obscura.kit.crypto.UuidCodec
import com.obscura.kit.crypto.fromBase64
import com.obscura.kit.crypto.toBase64
import com.obscura.kit.network.APIClient
import com.obscura.kit.network.HttpException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import obscura.v2.Client.*
import obscura.v2.*
import okhttp3.*
import okio.ByteString as OkioByteString
import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import xyz.obscura.server.contracts.ObscuraProtocol.*
import java.util.*
import java.util.concurrent.TimeUnit
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.obscura.kit.db.ObscuraDatabase

/**
 * Real E2E test client. Uses actual Signal crypto, real WebSocket, real server.
 * Mirrors the JS TestClient from src/v2/test/testClient.js.
 */
class ObscuraTestClient(private val apiUrl: String) {

    val api = APIClient(apiUrl)
    val signalStore: SignalStore

    private val driver: JdbcSqliteDriver
    private val db: ObscuraDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    var userId: String? = null
        private set
    var deviceId: String? = null
        private set
    var username: String? = null
        private set
    var authRateLimitDelayMs: Long = 500L
    var refreshToken: String? = null
        private set
    var registrationId: Int = 0
        private set

    // WebSocket
    private var webSocket: WebSocket? = null
    private val receivedMessages = Channel<DecodedMessage>(Channel.UNLIMITED)
    private val okClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // Device map: deviceId -> (userId, registrationId)
    private val deviceMap = mutableMapOf<String, Pair<String, Int>>()

    // Send queue for batch sending
    private val sendQueue = mutableListOf<SendMessageRequest.Submission>()

    // Friends: username -> FriendInfo
    private val friends = mutableMapOf<String, FriendInfo>()

    data class FriendInfo(
        val username: String,
        val userId: String,
        val devices: List<DeviceRef>,
        val status: String = "accepted"
    )
    data class DeviceRef(val deviceId: String, val registrationId: Int = 1)

    data class DecodedMessage(
        val type: String,
        val text: String = "",
        val username: String = "",
        val accepted: Boolean = false,
        val sourceUserId: String = "",
        val sourceDeviceId: String? = null,
        val raw: ClientMessage? = null,
        val envelopeId: ByteString? = null
    )

    init {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ObscuraDatabase.Schema.create(driver)
        db = ObscuraDatabase(driver)
        signalStore = SignalStore(db)
    }

    // ================================================================
    // Registration & Auth
    // ================================================================

    suspend fun register(username: String, password: String) {
        this.username = username

        // 1. Generate Signal identity
        val (identityKeyPair, regId) = signalStore.generateIdentity()
        this.registrationId = regId

        // 2. Generate prekeys
        val signedPreKey = generateSignedPreKey(identityKeyPair, 1)
        val oneTimePreKeys = generateOneTimePreKeys(1, 100)

        // 3. Register user account
        val regResult = api.registerUser(username, password)
        api.token = regResult.getString("token")

        // 4. Provision device with Signal keys
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
            name = "Kotlin Test Device",
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

        // Map own device
        deviceMap[requireNotNull(deviceId) { "deviceId not set - register failed to provision device" }] =
            Pair(requireNotNull(userId) { "userId not set - register failed to resolve user" }, regId)

        Thread.sleep(authRateLimitDelayMs) // Rate limit buffer
    }

    suspend fun login(username: String? = null, password: String, deviceId: String? = null) {
        val user = username ?: this.username ?: throw IllegalStateException("No username")
        val devId = deviceId ?: this.deviceId
        val result = api.loginWithDevice(user, password, devId)
        api.token = result.getString("token")
        refreshToken = result.optString("refreshToken", null)
        userId = api.getUserId(result.getString("token"))
        this.deviceId = result.optString("deviceId", null) ?: api.getDeviceId(result.getString("token")) ?: devId
        Thread.sleep(authRateLimitDelayMs)
    }

    // ================================================================
    // WebSocket
    // ================================================================

    suspend fun connectWebSocket() {
        val ticketResult = api.fetchGatewayTicket()
        val wsBase = apiUrl.replace("https://", "wss://").replace("http://", "ws://")
        val url = "$wsBase/v1/gateway?ticket=${java.net.URLEncoder.encode(ticketResult, "UTF-8")}"

        val latch = CompletableDeferred<Unit>()

        val request = Request.Builder().url(url).build()
        webSocket = okClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // println("[TestClient $username] WebSocket OPEN")
                latch.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, bytes: OkioByteString) {
                // println("[TestClient $username] WS onMessage: ${bytes.size} bytes")
                try {
                    val frame = WebSocketFrame.parseFrom(bytes.toByteArray())
                    when {
                        frame.hasEnvelopeBatch() -> {
                            val count = frame.envelopeBatch.envelopesCount
                            // println("[TestClient $username] Got $count envelope(s)")
                            for (envelope in frame.envelopeBatch.envelopesList) {
                                handleEnvelopeSync(envelope)
                            }
                        }
                        frame.hasPreKeyStatus() -> {
                            // println("[TestClient $username] PreKeyStatus: count=${frame.preKeyStatus.oneTimePreKeyCount}")
                        }
                    }
                } catch (e: Exception) {
                    // System.err.println("[TestClient $username] WS parse error: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // System.err.println("[TestClient $username] WS FAILURE: ${t.message}")
                latch.completeExceptionally(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // println("[TestClient $username] WS CLOSED: $code $reason")
            }
        })

        withTimeout(10_000) { latch.await() }
    }

    fun disconnectWebSocket() {
        webSocket?.close(1000, "Test done")
        webSocket = null
    }

    private fun handleEnvelopeSync(envelope: Envelope) {
        val senderId = UuidCodec.bytesToUuid(envelope.senderId.toByteArray()).toString()
        val envelopeId = envelope.id

        try {
            val encMsg = EncryptedMessage.parseFrom(envelope.message.toByteArray())
            val isPreKey = encMsg.type == EncryptedMessage.Type.TYPE_PREKEY_MESSAGE
            val content = encMsg.content.toByteArray()

            // Try all known registrationIds for sender, plus own regId
            val candidates = mutableSetOf(1, registrationId)
            for ((_, info) in deviceMap) {
                if (info.first == senderId) candidates.add(info.second)
            }

            var decrypted: ByteArray? = null
            var senderRegId = 1
            var lastError: Exception? = null

            // For PreKey messages, try addresses WITHOUT sessions first
            val withSession = mutableListOf<Int>()
            val withoutSession = mutableListOf<Int>()
            for (regId in candidates) {
                val addr = SignalProtocolAddress(senderId, regId)
                if (signalStore.containsSession(addr)) withSession.add(regId)
                else withoutSession.add(regId)
            }
            val ordered = if (isPreKey) withoutSession + withSession else withSession + withoutSession

            for (regId in ordered) {
                val address = SignalProtocolAddress(senderId, regId)
                try {
                    val cipher = SessionCipher(signalStore, address)
                    decrypted = if (isPreKey) {
                        cipher.decrypt(PreKeySignalMessage(content))
                    } else {
                        cipher.decrypt(SignalMessage(content))
                    }
                    senderRegId = regId
                    break
                } catch (e: Exception) {
                    lastError = e
                }
            }

            if (decrypted == null) {
                // System.err.println("[TestClient] Decrypt FAILED for sender=$senderId candidates=$candidates isPreKey=$isPreKey err=${lastError?.message}")
                acknowledge(listOf(envelopeId))
                return
            }

            val clientMsg = ClientMessage.parseFrom(decrypted)
            val typeName = clientMsg.type.name

            // Look up sender deviceId
            var senderDeviceId: String? = null
            for ((devId, info) in deviceMap) {
                if (info.first == senderId && info.second == senderRegId) {
                    senderDeviceId = devId
                    break
                }
            }

            receivedMessages.trySend(DecodedMessage(
                type = typeName,
                text = clientMsg.text,
                username = clientMsg.username,
                accepted = clientMsg.accepted,
                sourceUserId = senderId,
                sourceDeviceId = senderDeviceId,
                raw = clientMsg,
                envelopeId = envelopeId
            ))
        } catch (e: Exception) {
            // error swallowed — handleEnvelope failure
        }

        // ACK
        acknowledge(listOf(envelopeId))
    }

    fun acknowledge(messageIds: List<ByteString>) {
        val ack = AckMessage.newBuilder().addAllMessageIds(messageIds).build()
        val frame = WebSocketFrame.newBuilder().setAck(ack).build()
        webSocket?.send(OkioByteString.of(*frame.toByteArray()))
    }

    suspend fun waitForMessage(timeoutMs: Long = 10_000): DecodedMessage {
        return withTimeout(timeoutMs) {
            receivedMessages.receive()
        }
    }

    // ================================================================
    // Signal Encryption
    // ================================================================

    suspend fun fetchPreKeyBundles(targetUserId: String): List<PreKeyBundle> {
        val bundlesJson = api.fetchPreKeyBundles(targetUserId)
        return (0 until bundlesJson.length()).map { i ->
            val b = bundlesJson.getJSONObject(i)
            val devId = b.getString("deviceId")
            val regId = b.getInt("registrationId")

            deviceMap[devId] = Pair(targetUserId, regId)

            val spk = b.getJSONObject("signedPreKey")
            val otp = if (b.has("oneTimePreKey") && !b.isNull("oneTimePreKey")) b.getJSONObject("oneTimePreKey") else null

            PreKeyBundle(
                regId, 1,
                otp?.getInt("keyId") ?: 0,
                otp?.let { Curve.decodePoint(it.getString("publicKey").fromBase64(), 0) },
                spk.getInt("keyId"),
                Curve.decodePoint(spk.getString("publicKey").fromBase64(), 0),
                spk.getString("signature").fromBase64(),
                IdentityKey(b.getString("identityKey").fromBase64())
            )
        }
    }

    private fun encrypt(targetUserId: String, plaintext: ByteArray, registrationId: Int): Pair<Int, ByteArray> {
        val address = SignalProtocolAddress(targetUserId, registrationId)

        // Build session if needed
        if (!signalStore.containsSession(address)) {
            // Need prekey bundle — should have been fetched already
        }

        val cipher = SessionCipher(signalStore, address)
        val ciphertext = cipher.encrypt(plaintext)
        return Pair(ciphertext.type, ciphertext.serialize())
    }

    // ================================================================
    // Message Sending
    // ================================================================

    fun encodeClientMessage(type: ClientMessage.Type, block: ClientMessage.Builder.() -> Unit = {}): ByteArray {
        val builder = ClientMessage.newBuilder()
            .setType(type)
            .setTimestamp(System.currentTimeMillis())
        builder.block()
        return builder.build().toByteArray()
    }

    suspend fun queueMessage(targetDeviceId: String, clientMsgBytes: ByteArray, targetUserId: String? = null) {
        val mapped = deviceMap[targetDeviceId]
        val encryptUserId = targetUserId ?: mapped?.first ?: throw IllegalStateException("No userId for device $targetDeviceId")
        val regId = mapped?.second ?: 1

        // Ensure session exists
        val address = SignalProtocolAddress(encryptUserId, regId)
        if (!signalStore.containsSession(address)) {
            // Build session from prekey bundle
            val bundles = fetchPreKeyBundles(encryptUserId)
            val bundle = bundles.find { it.registrationId == regId } ?: bundles[0]
            val builder = SessionBuilder(signalStore, address)
            builder.process(bundle)
        }

        val encrypted = encrypt(encryptUserId, clientMsgBytes, regId)

        val encMsg = EncryptedMessage.newBuilder()
            .setType(
                if (encrypted.first == 3) EncryptedMessage.Type.TYPE_PREKEY_MESSAGE
                else EncryptedMessage.Type.TYPE_ENCRYPTED_MESSAGE
            )
            .setContent(ByteString.copyFrom(encrypted.second))
            .build()

        val submission = SendMessageRequest.Submission.newBuilder()
            .setSubmissionId(UuidCodec.uuidToBytes(UUID.randomUUID()))
            .setDeviceId(UuidCodec.uuidToBytes(UUID.fromString(targetDeviceId)))
            .setMessage(ByteString.copyFrom(encMsg.toByteArray()))
            .build()

        sendQueue.add(submission)
    }

    suspend fun flushMessages(): Int {
        if (sendQueue.isEmpty()) return 0

        val submissions = sendQueue.toList()
        sendQueue.clear()

        val request = SendMessageRequest.newBuilder()
            .addAllMessages(submissions)
            .build()

        // println("[TestClient $username] Flushing ${submissions.size} message(s)")
        val responseBytes = api.sendMessage(request.toByteArray())

        if (responseBytes.isNotEmpty()) {
            try {
                val resp = SendMessageResponse.parseFrom(responseBytes)
                if (resp.failedSubmissionsCount > 0) {
                    for (f in resp.failedSubmissionsList) {
                        // System.err.println("[TestClient $username] Submission FAILED: code=${f.errorCode} ${f.errorMessage}")
                    }
                }
            } catch (e: Exception) { /* empty response = all success */ }
        }
        // println("[TestClient $username] Flush OK, sent=${submissions.size}")
        return submissions.size
    }

    suspend fun sendMessage(targetDeviceId: String, type: ClientMessage.Type, targetUserId: String, block: ClientMessage.Builder.() -> Unit = {}) {
        val msgBytes = encodeClientMessage(type, block)
        queueMessage(targetDeviceId, msgBytes, targetUserId)
        flushMessages()
    }

    /**
     * Send to all devices of a friend (fan-out).
     */
    suspend fun sendToFriend(friendUsername: String, type: ClientMessage.Type, block: ClientMessage.Builder.() -> Unit = {}) {
        val friend = friends[friendUsername] ?: throw IllegalStateException("Not friends with $friendUsername")
        val msgBytes = encodeClientMessage(type, block)
        for (device in friend.devices) {
            queueMessage(device.deviceId, msgBytes, friend.userId)
        }
        flushMessages()
    }

    // ================================================================
    // Friend Management
    // ================================================================

    suspend fun sendFriendRequest(targetDeviceId: String, targetUsername: String, targetUserId: String) {
        sendMessage(targetDeviceId, ClientMessage.Type.FRIEND_REQUEST, targetUserId) {
            username = this@ObscuraTestClient.username ?: ""
        }
    }

    suspend fun sendFriendResponse(targetDeviceId: String, targetUsername: String, accept: Boolean, targetUserId: String) {
        sendMessage(targetDeviceId, ClientMessage.Type.FRIEND_RESPONSE, targetUserId) {
            username = this@ObscuraTestClient.username ?: ""
            accepted = accept
        }
    }

    fun storeFriend(username: String, userId: String, devices: List<DeviceRef>) {
        friends[username] = FriendInfo(username, userId, devices)
    }

    fun isFriendsWith(username: String): Boolean = friends[username]?.status == "accepted"

    fun mapDevice(deviceId: String, userId: String, registrationId: Int) {
        deviceMap[deviceId] = Pair(userId, registrationId)
    }

    fun findDeviceId(userId: String, registrationId: Int): String? {
        return deviceMap.entries.find { it.value.first == userId && it.value.second == registrationId }?.key
    }

    fun getDeviceIdsForUser(userId: String): List<String> {
        return deviceMap.entries.filter { it.value.first == userId }.map { it.key }
    }

    // ================================================================
    // Helpers
    // ================================================================

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
