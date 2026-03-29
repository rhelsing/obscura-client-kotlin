package scenarios

import com.obscura.kit.AuthState
import com.obscura.kit.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Scenario 9: Pix flow E2E.
 * Full lifecycle: register, befriend, upload JPEG, send as attachment, friend downloads, verify JPEG header.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PixFlowTests {

    @Test @Order(1)
    fun `9-1 - Upload JPEG, send to friend, friend downloads and verifies JPEG header`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("p9a")
        val bob = registerAndConnect("p9b")
        assertEquals(AuthState.AUTHENTICATED, alice.authState.value)
        assertEquals(AuthState.AUTHENTICATED, bob.authState.value)

        becomeFriends(alice, bob)

        // Alice uploads JPEG with proper header
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(300)
        val (attId, _) = alice.uploadAttachment(jpeg)
        assertTrue(attId.isNotEmpty(), "Attachment ID should be returned")

        // Alice sends to bob
        alice.sendAttachment(bob.username!!, attId, ByteArray(32), ByteArray(12), "image/jpeg", jpeg.size.toLong())

        // Bob receives CONTENT_REFERENCE
        val msg = bob.waitForMessage()
        assertEquals("CONTENT_REFERENCE", msg.type)
        assertEquals(alice.userId, msg.sourceUserId)

        // Bob downloads and verifies JPEG header bytes
        val downloaded = bob.downloadAttachment(msg.raw!!.contentReference.attachmentId)
        assertEquals(jpeg.size, downloaded.size, "Size must match")
        assertEquals(0xFF.toByte(), downloaded[0], "First byte should be 0xFF (JPEG SOI)")
        assertEquals(0xD8.toByte(), downloaded[1], "Second byte should be 0xD8 (JPEG SOI)")
        assertArrayEquals(jpeg, downloaded, "Full content must match")

        alice.disconnect()
        bob.disconnect()
    }

    @Test @Order(2)
    fun `9-2 - Bidirectional pix exchange with JPEG verification`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("p9ba")
        val bob = registerAndConnect("p9bb")
        becomeFriends(alice, bob)

        // Alice sends image to Bob
        val aliceJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(100) { 0xAA.toByte() }
        val (aliceAttId, _) = alice.uploadAttachment(aliceJpeg)
        alice.sendAttachment(bob.username!!, aliceAttId, ByteArray(32), ByteArray(12), "image/jpeg", aliceJpeg.size.toLong())

        val bobMsg = bob.waitForMessage()
        assertEquals("CONTENT_REFERENCE", bobMsg.type)
        assertEquals(alice.userId, bobMsg.sourceUserId)

        // Bob downloads alice's image and verifies JPEG header
        val bobDownloaded = bob.downloadAttachment(bobMsg.raw!!.contentReference.attachmentId)
        assertEquals(0xFF.toByte(), bobDownloaded[0])
        assertEquals(0xD8.toByte(), bobDownloaded[1])
        assertEquals(aliceJpeg.size, bobDownloaded.size)

        // Bob sends image back to Alice
        val bobJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(150) { 0xBB.toByte() }
        val (bobAttId, _) = bob.uploadAttachment(bobJpeg)
        bob.sendAttachment(alice.username!!, bobAttId, ByteArray(32), ByteArray(12), "image/jpeg", bobJpeg.size.toLong())

        val aliceMsg = alice.waitForMessage()
        assertEquals("CONTENT_REFERENCE", aliceMsg.type)
        assertEquals(bob.userId, aliceMsg.sourceUserId)

        // Alice downloads bob's image and verifies JPEG header
        val aliceDownloaded = alice.downloadAttachment(aliceMsg.raw!!.contentReference.attachmentId)
        assertEquals(0xFF.toByte(), aliceDownloaded[0])
        assertEquals(0xD8.toByte(), aliceDownloaded[1])
        assertEquals(bobJpeg.size, aliceDownloaded.size)

        // CONTENT_REFERENCE messages are delivered via events, not stored in conversations
        // (conversations store TEXT/IMAGE only — attachments are referenced, not inlined)
        // Verify the exchange completed by checking both downloads succeeded above

        alice.disconnect()
        bob.disconnect()
    }
}
