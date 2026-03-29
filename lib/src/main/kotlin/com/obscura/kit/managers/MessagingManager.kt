package com.obscura.kit.managers

import obscura.v2.Client.ClientMessage
import org.json.JSONObject

/**
 * Send text, attachments, model sync, raw messages. Upload/download attachments.
 */
internal class MessagingManager(
    private val ctx: ClientContext
) {
    private val session get() = ctx.session
    private val api get() = ctx.api
    private val messenger get() = ctx.messenger
    private val friends get() = ctx.friends
    private val devices get() = ctx.devices
    private val messageSender get() = ctx.messageSender
    suspend fun send(friendUsername: String, text: String) {
        val friendData = friends.getAccepted().find { it.username == friendUsername }
            ?: throw IllegalStateException("Not friends with $friendUsername")

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.TEXT).setText(text)
            .setTimestamp(System.currentTimeMillis()).build()

        messageSender.sendToAllDevices(friendData.userId, msg)

        // Self-sync: SENT_SYNC to own devices
        val selfTargets = devices.getSelfSyncTargets().filter { it != session.deviceId }
        if (selfTargets.isNotEmpty()) {
            val msgId = java.util.UUID.randomUUID().toString()
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
                messenger.queueMessage(devId, sentSync, session.userId)
            }
            messenger.flushMessages()
        }
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

        messageSender.sendToAllDevices(friendData.userId, msg)
    }

    suspend fun sendEncryptedAttachment(friendUsername: String, plaintext: ByteArray, mimeType: String = "application/octet-stream") {
        val encrypted = com.obscura.kit.crypto.AttachmentCrypto.encrypt(plaintext)
        val result = api.uploadAttachment(encrypted.ciphertext)
        sendAttachment(friendUsername, result.id, encrypted.contentKey, encrypted.nonce, mimeType, encrypted.sizeBytes)
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
                authorDeviceId = session.deviceId ?: ""
            }).build()

        messageSender.sendToAllDevices(friendData.userId, msg)
    }

    suspend fun sendRaw(targetUserId: String, msg: ClientMessage) = messageSender.sendToAllDevices(targetUserId, msg)

    suspend fun uploadAttachment(data: ByteArray): Pair<String, Long> {
        val result = api.uploadAttachment(data)
        return Pair(result.id, result.expiresAt)
    }

    suspend fun downloadAttachment(id: String): ByteArray = api.fetchAttachment(id)

    suspend fun downloadDecryptedAttachment(id: String, contentKey: ByteArray, nonce: ByteArray, expectedHash: ByteArray? = null): ByteArray {
        val ciphertext = api.fetchAttachment(id)
        return com.obscura.kit.crypto.AttachmentCrypto.decrypt(ciphertext, contentKey, nonce, expectedHash)
    }
}
