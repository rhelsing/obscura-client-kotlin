package com.obscura.kit.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption for attachments.
 * Matches the web client's aes.js — encrypt before upload, decrypt after download.
 *
 * Flow:
 *   1. Generate random 32-byte content key + 12-byte nonce
 *   2. Encrypt plaintext with AES-256-GCM
 *   3. Upload ciphertext to server (server never sees plaintext)
 *   4. Send contentKey + nonce + hash to recipient via CONTENT_REFERENCE (Signal-encrypted)
 *   5. Recipient downloads ciphertext, decrypts with key + nonce, verifies hash
 */
object AttachmentCrypto {

    private const val KEY_SIZE = 32        // AES-256
    private const val NONCE_SIZE = 12      // GCM standard
    private const val GCM_TAG_BITS = 128   // 16-byte auth tag
    private val csprng = SecureRandom()

    data class EncryptedAttachment(
        val ciphertext: ByteArray,
        val contentKey: ByteArray,
        val nonce: ByteArray,
        val contentHash: ByteArray,
        val sizeBytes: Long
    )

    /**
     * Encrypt content with AES-256-GCM.
     * Returns ciphertext + key material for the recipient.
     */
    fun encrypt(plaintext: ByteArray): EncryptedAttachment {
        val contentKey = ByteArray(KEY_SIZE).also { csprng.nextBytes(it) }
        val nonce = ByteArray(NONCE_SIZE).also { csprng.nextBytes(it) }
        val contentHash = MessageDigest.getInstance("SHA-256").digest(plaintext)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext)

        return EncryptedAttachment(
            ciphertext = ciphertext,
            contentKey = contentKey,
            nonce = nonce,
            contentHash = contentHash,
            sizeBytes = plaintext.size.toLong()
        )
    }

    /**
     * Decrypt content with AES-256-GCM and verify integrity.
     * Throws on wrong key, tampered ciphertext, or hash mismatch.
     */
    fun decrypt(ciphertext: ByteArray, contentKey: ByteArray, nonce: ByteArray, expectedHash: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val plaintext = cipher.doFinal(ciphertext)

        if (expectedHash != null) {
            val actualHash = MessageDigest.getInstance("SHA-256").digest(plaintext)
            if (!MessageDigest.isEqual(actualHash, expectedHash)) {
                throw SecurityException("Attachment integrity check failed: hash mismatch")
            }
        }

        return plaintext
    }
}
