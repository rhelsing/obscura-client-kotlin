package scenarios

import com.obscura.kit.ConnectionState
import com.obscura.kit.orm.ModelConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Connection resilience tests — reconnect, message survival, ORM survival.
 * All against live server. Matches iOS ReconnectTests.
 */
class ReconnectResilienceTests {

    @Test
    fun `Messages flow after disconnect and reconnect`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("rcn_a")
        val bob = registerAndConnect("rcn_b")
        becomeFriends(alice, bob)

        // Messages work before
        sendAndVerify(alice, bob, "before disconnect")

        // Bob disconnects and reconnects
        bob.disconnect()
        delay(500)
        bob.connect()
        delay(1000)

        // Messages work after
        sendAndVerify(alice, bob, "after reconnect")

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `Queued messages arrive after reconnect`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("rcn_qa")
        val bob = registerAndConnect("rcn_qb")
        becomeFriends(alice, bob)

        // Bob goes offline
        bob.disconnect()
        delay(500)

        // Alice sends while Bob is down
        alice.send(bob.username!!, "while you were gone 1")
        delay(200)
        alice.send(bob.username!!, "while you were gone 2")
        delay(500)

        // Bob comes back
        bob.connect()

        val received = mutableListOf<String>()
        repeat(2) {
            val msg = bob.waitForMessage(15_000)
            val text = if (msg.type == "MODEL_SYNC") {
                JSONObject(String(msg.raw!!.modelSync.data.toByteArray())).optString("content", msg.text)
            } else msg.text
            received.add(text)
        }
        assertTrue(received.contains("while you were gone 1"))
        assertTrue(received.contains("while you were gone 2"))

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `ORM auto-sync survives disconnect and reconnect`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("rcn_oa")
        val bob = registerAndConnect("rcn_ob")
        becomeFriends(alice, bob)

        val storyConfig = mapOf(
            "story" to ModelConfig(fields = mapOf("content" to "string"), sync = "gset")
        )
        alice.orm.define(storyConfig)
        bob.orm.define(storyConfig)

        // ORM works before
        alice.orm.model("story").create(mapOf("content" to "before"))
        val msg1 = bob.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg1.type)

        // Alice disconnects and reconnects
        alice.disconnect()
        delay(500)
        alice.connect()
        delay(1000)

        // ORM works after
        alice.orm.model("story").create(mapOf("content" to "after reconnect"))
        val msg2 = bob.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg2.type)
        val data = JSONObject(String(msg2.raw!!.modelSync.data.toByteArray()))
        assertEquals("after reconnect", data.getString("content"))

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `Bidirectional after both sides reconnect`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("rcn_ba")
        val bob = registerAndConnect("rcn_bb")
        becomeFriends(alice, bob)

        alice.disconnect()
        bob.disconnect()
        delay(500)

        alice.connect()
        bob.connect()
        delay(1000)

        sendAndVerify(alice, bob, "alice after both reconnect")
        sendAndVerify(bob, alice, "bob after both reconnect")

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `Rapid disconnect-reconnect cycles don't break messaging`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("rcn_rr")
        val bob = registerAndConnect("rcn_rrb")
        becomeFriends(alice, bob)

        repeat(3) {
            alice.disconnect()
            delay(300)
            alice.connect()
            delay(500)
        }

        sendAndVerify(alice, bob, "after rapid cycles")

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `Messages survive 5 second idle`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("rcn_idle_a")
        val bob = registerAndConnect("rcn_idle_b")
        becomeFriends(alice, bob)

        delay(5000)

        sendAndVerify(alice, bob, "after 5s idle")

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `Messages survive 35 second idle — proves ping keepalive works`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("rcn_35a")
        val bob = registerAndConnect("rcn_35b")
        becomeFriends(alice, bob)

        // 35 seconds — longer than the 30s ping interval.
        // If ping isn't working, the connection dies silently
        // and this test fails.
        delay(35_000)

        sendAndVerify(alice, bob, "after 35s idle — ping kept us alive")

        alice.disconnect()
        bob.disconnect()
    }
}
