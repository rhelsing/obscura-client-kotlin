package scenarios

import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class StoryAttachmentTests {

    companion object {
        private val API = "https://obscura.barrelmaker.dev"
        private var serverUp = false
        private var alice: ObscuraClient? = null
        private var bob: ObscuraClient? = null

        @BeforeAll @JvmStatic fun setup() {
            serverUp = try {
                java.net.URL("$API/openapi.yaml").openConnection().apply {
                    connectTimeout = 5000; readTimeout = 5000
                }.getInputStream().close(); true
            } catch (e: Exception) { false }

            if (serverUp) runBlocking {
                alice = ObscuraClient(ObscuraConfig(API))
                alice!!.register("kt_s10_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")
                bob = ObscuraClient(ObscuraConfig(API))
                bob!!.register("kt_s10b_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")
                alice!!.connect(); bob!!.connect()
                alice!!.befriend(bob!!.userId!!, bob!!.username!!)
                bob!!.waitForMessage()
                bob!!.acceptFriend(alice!!.userId!!, alice!!.username!!)
                alice!!.waitForMessage()
            }
        }
    }

    private fun need() = assumeTrue(serverUp && alice != null)

    @Test @Order(1)
    fun `10-1 - Story with image synced, friend downloads`() = runBlocking {
        need()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(250)
        val (attId, _) = alice!!.uploadAttachment(jpeg)

        alice!!.sendModelSync(bob!!.username!!, "story", "story_${System.currentTimeMillis()}",
            data = mapOf("content" to "My vacation", "mediaRef" to attId, "mimeType" to "image/jpeg"))

        val msg = bob!!.waitForMessage()
        assertEquals("MODEL_SYNC", msg.type)
        val storyData = org.json.JSONObject(String(msg.raw!!.modelSync.data.toByteArray()))
        assertEquals("My vacation", storyData.getString("content"))

        val downloaded = bob!!.downloadAttachment(attId)
        assertEquals(jpeg.size, downloaded.size)
        assertEquals(0xFF.toByte(), downloaded[0])
    }

    @Test @Order(2)
    fun `10-2 - Text-only story syncs`() = runBlocking {
        need()
        alice!!.sendModelSync(bob!!.username!!, "story", "story_text_${System.currentTimeMillis()}",
            data = mapOf("content" to "Just text"))

        val msg = bob!!.waitForMessage()
        assertEquals("MODEL_SYNC", msg.type)
        val d = org.json.JSONObject(String(msg.raw!!.modelSync.data.toByteArray()))
        assertEquals("Just text", d.getString("content"))
    }

    @Test @Order(3)
    fun `10-3 - Multiple stories in sequence`() = runBlocking {
        need()
        for (i in 1..3) {
            alice!!.sendModelSync(bob!!.username!!, "story", "seq_$i",
                data = mapOf("content" to "Story $i"))
        }
        val received = (1..3).map { bob!!.waitForMessage() }
        assertEquals(3, received.size)
        assertTrue(received.all { it.type == "MODEL_SYNC" })
    }

    @Test @Order(4)
    fun `10-5 - Story with text and image combined`() = runBlocking {
        need()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(500)
        val (attId, _) = alice!!.uploadAttachment(jpeg)

        alice!!.sendModelSync(bob!!.username!!, "story", "story_combo_${System.currentTimeMillis()}",
            data = mapOf("content" to "check out this sunset", "mediaRef" to attId, "contentType" to "image/jpeg"))

        val msg = bob!!.waitForMessage()
        assertEquals("MODEL_SYNC", msg.type)
        val data = org.json.JSONObject(String(msg.raw!!.modelSync.data.toByteArray()))
        assertEquals("check out this sunset", data.getString("content"))
        assertEquals(attId, data.getString("mediaRef"))
        assertEquals("image/jpeg", data.getString("contentType"))

        val downloaded = bob!!.downloadAttachment(attId)
        assertEquals(jpeg.size, downloaded.size)

        alice!!.disconnect(); bob!!.disconnect()
    }
}
