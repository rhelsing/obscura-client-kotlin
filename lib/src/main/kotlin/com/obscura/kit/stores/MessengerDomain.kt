package com.obscura.kit.stores

import com.google.protobuf.ByteString
import com.obscura.kit.crypto.SignalStore
import com.obscura.kit.crypto.UuidCodec
import com.obscura.kit.crypto.fromBase64
import com.obscura.kit.network.APIClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import obscura.v2.Client.*
import org.json.JSONArray
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import xyz.obscura.server.contracts.ObscuraProtocol.*
import java.util.*

data class DecryptedMessage(
    val clientMessage: ClientMessage,
    val sourceUserId: String,
    val senderDeviceId: String?,
    val senderRegId: Int
)

/**
 * MessengerDomain - Confined coroutines. Encrypt/decrypt/queue/flush.
 * Single-threaded dispatcher protects Signal ratchet state.
 */
class MessengerDomain internal constructor(
    private val signalStore: SignalStore,
    private val api: APIClient
) {
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    // deviceId -> { userId, registrationId }
    private val deviceMap = mutableMapOf<String, Pair<String, Int>>()

    // Pending submissions for batch sending
    private val queue = mutableListOf<SendMessageRequest.Submission>()

    fun mapDevice(deviceId: String, userId: String, registrationId: Int) {
        deviceMap[deviceId] = Pair(userId, registrationId)
    }

    fun getDeviceIdsForUser(userId: String): List<String> {
        return deviceMap.entries.filter { it.value.first == userId }.map { it.key }
    }

    suspend fun queueMessage(targetDeviceId: String, message: ClientMessage, userId: String? = null) =
        withContext(dispatcher) {
            val mapped = deviceMap[targetDeviceId]
            val encryptUserId = userId ?: mapped?.first ?: targetDeviceId
            val registrationId = mapped?.second ?: 1

            val clientMsgBytes = message.toByteArray()
            ensureSession(encryptUserId, registrationId)
            val encrypted = encrypt(encryptUserId, clientMsgBytes, registrationId)

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

            queue.add(submission)
        }

    suspend fun flushMessages(): Triple<Int, Int, List<SendMessageResponse.FailedSubmission>> =
        withContext(dispatcher) {
            if (queue.isEmpty()) return@withContext Triple(0, 0, emptyList())

            val submissions = queue.toList()
            queue.clear()

            val request = SendMessageRequest.newBuilder()
                .addAllMessages(submissions)
                .build()

            val responseBytes = api.sendMessage(request.toByteArray())
            val failedSubmissions = if (responseBytes.isNotEmpty()) {
                try {
                    SendMessageResponse.parseFrom(responseBytes).failedSubmissionsList
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            Triple(
                submissions.size - failedSubmissions.size,
                failedSubmissions.size,
                failedSubmissions
            )
        }

    suspend fun fetchPreKeyBundles(userId: String): List<PreKeyBundle> = withContext(dispatcher) {
        val bundlesJson = api.fetchPreKeyBundles(userId)
        parsePreKeyBundles(bundlesJson, userId)
    }

    suspend fun decrypt(envelope: Envelope): DecryptedMessage = withContext(dispatcher) {
        val senderId = UuidCodec.bytesToUuid(envelope.senderId.toByteArray()).toString()
        val encMsg = EncryptedMessage.parseFrom(envelope.message.toByteArray())

        val isPreKey = encMsg.type == EncryptedMessage.Type.TYPE_PREKEY_MESSAGE
        val content = encMsg.content.toByteArray()

        // Try known registrationIds + own regId + default
        val candidates = mutableSetOf(1, signalStore.getLocalRegistrationId())
        for ((_, info) in deviceMap) {
            if (info.first == senderId) candidates.add(info.second)
        }

        // For PreKey messages, try addresses WITHOUT sessions first
        val withSession = mutableListOf<Int>()
        val withoutSession = mutableListOf<Int>()
        for (regId in candidates) {
            val addr = SignalProtocolAddress(senderId, regId)
            if (signalStore.containsSession(addr)) withSession.add(regId) else withoutSession.add(regId)
        }
        val ordered = if (isPreKey) withoutSession + withSession else withSession + withoutSession

        var lastError: Exception? = null
        for (regId in ordered) {
            val address = SignalProtocolAddress(senderId, regId)
            try {
                val cipher = SessionCipher(signalStore, address)
                val decryptedBytes = if (isPreKey) {
                    cipher.decrypt(PreKeySignalMessage(content))
                } else {
                    cipher.decrypt(SignalMessage(content))
                }

                val clientMsg = ClientMessage.parseFrom(decryptedBytes)

                var senderDeviceId: String? = null
                for ((devId, info) in deviceMap) {
                    if (info.first == senderId && info.second == regId) {
                        senderDeviceId = devId
                        break
                    }
                }

                return@withContext DecryptedMessage(clientMsg, senderId, senderDeviceId, regId)
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw lastError ?: IllegalStateException("No record for $senderId")
    }

    private suspend fun ensureSession(targetUserId: String, registrationId: Int) {
        val address = SignalProtocolAddress(targetUserId, registrationId)
        if (!signalStore.containsSession(address)) {
            val bundlesJson = api.fetchPreKeyBundles(targetUserId)
            val bundles = parsePreKeyBundles(bundlesJson, targetUserId)
            val bundle = bundles.find { it.registrationId == registrationId } ?: bundles.firstOrNull()
                ?: throw IllegalStateException("No prekey bundles available for $targetUserId")
            val builder = SessionBuilder(signalStore, address)
            builder.process(bundle)
        }
    }

    private fun encrypt(targetUserId: String, plaintext: ByteArray, registrationId: Int): Pair<Int, ByteArray> {
        val address = SignalProtocolAddress(targetUserId, registrationId)
        val cipher = SessionCipher(signalStore, address)
        val ciphertext = cipher.encrypt(plaintext)
        return Pair(ciphertext.type, ciphertext.serialize())
    }

    private fun parsePreKeyBundles(bundlesJson: JSONArray, userId: String): List<PreKeyBundle> {
        return (0 until bundlesJson.length()).map { i ->
            val b = bundlesJson.getJSONObject(i)
            val deviceId = b.getString("deviceId")
            val regId = b.getInt("registrationId")
            deviceMap[deviceId] = Pair(userId, regId)

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

}
