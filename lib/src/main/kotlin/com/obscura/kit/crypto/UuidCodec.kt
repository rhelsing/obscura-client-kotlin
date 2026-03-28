package com.obscura.kit.crypto

import com.google.protobuf.ByteString
import java.nio.ByteBuffer
import java.util.*

/**
 * Shared UUID ↔ ByteString codec used by MessengerDomain and ObscuraTestClient.
 */
object UuidCodec {

    fun uuidToBytes(uuid: UUID): ByteString {
        val bb = ByteBuffer.allocate(16)
        bb.putLong(uuid.mostSignificantBits)
        bb.putLong(uuid.leastSignificantBits)
        return ByteString.copyFrom(bb.array())
    }

    fun bytesToUuid(bytes: ByteArray): UUID {
        if (bytes.size < 16) return UUID(0, 0)
        val bb = ByteBuffer.wrap(bytes)
        return UUID(bb.getLong(), bb.getLong())
    }
}
