package com.obscura.kit.crypto

import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Backup encryption using ECDH + HKDF + AES-256-GCM.
 * Matches the web client's backup.js.
 *
 * All crypto is delegated to libsignal (ECDH) and JCE (HKDF, AES-GCM).
 * No hand-rolled crypto.
 *
 * Export (no recovery phrase needed):
 *   1. Generate ephemeral Curve25519 keypair (libsignal)
 *   2. ECDH: ephemeralPrivate + recoveryPublicKey → sharedSecret (libsignal)
 *   3. HKDF: sharedSecret → AES key (JCE HMAC-SHA256)
 *   4. AES-256-GCM encrypt backup data (JCE)
 *   5. Package: version(1) + ephemeralPub(32) + salt(32) + iv(12) + ciphertext
 *
 * Import (recovery phrase required):
 *   1. Derive recovery keypair from phrase (libsignal + BIP39)
 *   2. ECDH: recoveryPrivate + ephemeralPublicKey → same sharedSecret
 *   3. Derive same AES key, decrypt
 */
object BackupCrypto {

    private const val BACKUP_VERSION: Byte = 1
    private const val SALT_SIZE = 32
    private const val IV_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private val HKDF_INFO = "obscura-backup-v1".toByteArray()
    private val csprng = SecureRandom()

    /**
     * Encrypt backup data using the recovery public key.
     * No recovery phrase needed — only the stored public key.
     */
    fun encrypt(plaintext: ByteArray, recoveryPublicKey: ECPublicKey): ByteArray {
        // 1. Ephemeral keypair
        val ephemeral = Curve.generateKeyPair()

        // 2. ECDH shared secret (libsignal)
        val sharedSecret = Curve.calculateAgreement(recoveryPublicKey, ephemeral.privateKey)

        // 3. Random salt + IV
        val salt = ByteArray(SALT_SIZE).also { csprng.nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { csprng.nextBytes(it) }

        // 4. HKDF → AES key (JCE)
        val aesKey = hkdfSha256(sharedSecret, salt, HKDF_INFO, 32)

        // 5. AES-256-GCM encrypt (JCE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)

        // 6. Package: version(1) + ephemeralPub(32) + salt(32) + iv(12) + ciphertext
        val ephemeralPubBytes = ephemeral.publicKey.serialize() // 33 bytes with 0x05 prefix
        val pubKeyRaw = if (ephemeralPubBytes.size == 33 && ephemeralPubBytes[0] == 0x05.toByte())
            ephemeralPubBytes.copyOfRange(1, 33) else ephemeralPubBytes

        val blob = ByteBuffer.allocate(1 + 32 + SALT_SIZE + IV_SIZE + ciphertext.size)
        blob.put(BACKUP_VERSION)
        blob.put(pubKeyRaw)
        blob.put(salt)
        blob.put(iv)
        blob.put(ciphertext)

        // Zero sensitive material
        sharedSecret.fill(0)
        aesKey.fill(0)

        return blob.array()
    }

    /**
     * Decrypt backup using the 12-word recovery phrase.
     */
    fun decrypt(encryptedBlob: ByteArray, recoveryPhrase: String): ByteArray {
        val keypair = RecoveryKeys.deriveKeypair(recoveryPhrase)
        return decryptWithPrivateKey(encryptedBlob, keypair.privateKey)
    }

    /**
     * Decrypt backup with a raw private key (for internal use / testing).
     */
    fun decryptWithPrivateKey(encryptedBlob: ByteArray, recoveryPrivateKey: ECPrivateKey): ByteArray {
        require(encryptedBlob.size > 1 + 32 + SALT_SIZE + IV_SIZE) { "Backup blob too small" }

        val buf = ByteBuffer.wrap(encryptedBlob)
        val version = buf.get()
        require(version == BACKUP_VERSION) { "Unsupported backup version: $version" }

        val ephemeralPubRaw = ByteArray(32).also { buf.get(it) }
        val salt = ByteArray(SALT_SIZE).also { buf.get(it) }
        val iv = ByteArray(IV_SIZE).also { buf.get(it) }
        val ciphertext = ByteArray(buf.remaining()).also { buf.get(it) }

        // Reconstruct public key with 0x05 prefix (libsignal Curve25519 format)
        val prefixed = ByteArray(33)
        prefixed[0] = 0x05
        System.arraycopy(ephemeralPubRaw, 0, prefixed, 1, 32)
        val ephemeralPublicKey = Curve.decodePoint(prefixed, 0)

        // ECDH (libsignal)
        val sharedSecret = Curve.calculateAgreement(ephemeralPublicKey, recoveryPrivateKey)

        // HKDF → AES key (JCE)
        val aesKey = hkdfSha256(sharedSecret, salt, HKDF_INFO, 32)

        // AES-256-GCM decrypt (JCE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)

        // Zero sensitive material
        sharedSecret.fill(0)
        aesKey.fill(0)

        return plaintext
    }

    /**
     * HKDF-SHA256 (RFC 5869). Extract + Expand.
     * Uses JCE HMAC-SHA256 — no hand-rolled crypto.
     */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        // Extract
        val prk = hmacSha256(salt, ikm)

        // Expand
        var t = ByteArray(0)
        val okm = ByteArray(length)
        var offset = 0
        var i: Byte = 1
        while (offset < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(i))
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, okm, offset, copyLen)
            offset += copyLen
            i++
        }
        return okm
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
