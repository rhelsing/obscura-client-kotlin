package scenarios

import com.obscura.kit.orm.ModelConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * ORM Message Tests — proves DirectMessage model works as chat transport.
 *
 * These validate that chat messages sent as MODEL_SYNC (type 30) arrive
 * correctly, show up in conversations, and survive offline/reconnect.
 * This is the interop path — iOS sends DirectMessage the same way.
 */
class ORMMessageTests {

    private val messageSchema = mapOf(
        "directMessage" to ModelConfig(
            fields = mapOf("conversationId" to "string", "content" to "string", "senderUsername" to "string"),
            sync = "gset"
        )
    )

    @Test
    fun `send via ORM DirectMessage arrives as MODEL_SYNC`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("omsg_a")
        val bob = registerAndConnect("omsg_b")
        becomeFriends(alice, bob)

        alice.orm.define(messageSchema)
        bob.orm.define(messageSchema)

        // Alice sends via client.send() which now uses the ORM path
        alice.send(bob.username!!, "Hello via ORM!")

        val msg = bob.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg.type, "Message should arrive as MODEL_SYNC, not TEXT")

        val sync = msg.raw!!.modelSync
        assertEquals("directMessage", sync.model)

        val data = JSONObject(String(sync.data.toByteArray()))
        assertEquals("Hello via ORM!", data.getString("content"))
        assertEquals(alice.username, data.getString("senderUsername"))
        val expectedConvId = listOf(alice.userId!!, bob.userId!!).sorted().joinToString("_")
        assertEquals(expectedConvId, data.getString("conversationId"),
            "conversationId should be canonical (sorted userIds)")

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `ORM message shows up in conversations`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("omsg_ca")
        val bob = registerAndConnect("omsg_cb")
        becomeFriends(alice, bob)

        alice.orm.define(messageSchema)
        bob.orm.define(messageSchema)

        alice.send(bob.username!!, "Check my conversations")
        bob.waitForMessage(15_000)
        delay(500)

        // Bob's conversations should have the message
        val msgs = bob.getMessages(alice.userId!!)
        assertTrue(msgs.any { it.content == "Check my conversations" },
            "Message should appear in Bob's conversation with Alice")

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `Bidirectional ORM messages`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("omsg_ba")
        val bob = registerAndConnect("omsg_bb")
        becomeFriends(alice, bob)

        alice.orm.define(messageSchema)
        bob.orm.define(messageSchema)

        // Alice → Bob
        alice.send(bob.username!!, "Hey Bob")
        val msg1 = bob.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg1.type)

        // Bob → Alice
        bob.send(alice.username!!, "Hey Alice")
        val msg2 = alice.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg2.type)
        val data = JSONObject(String(msg2.raw!!.modelSync.data.toByteArray()))
        assertEquals("Hey Alice", data.getString("content"))

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `ORM message arrives after offline reconnect`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("omsg_oa")
        val bob = registerAndConnect("omsg_ob")
        becomeFriends(alice, bob)

        alice.orm.define(messageSchema)
        bob.orm.define(messageSchema)

        // Bob goes offline
        bob.disconnect()
        delay(500)

        // Alice sends while Bob is away
        alice.send(bob.username!!, "You there?")
        delay(500)

        // Bob reconnects
        bob.connect()

        val msg = bob.waitForMessage(15_000)
        assertEquals("MODEL_SYNC", msg.type)
        val data = JSONObject(String(msg.raw!!.modelSync.data.toByteArray()))
        assertEquals("You there?", data.getString("content"))

        // Should also be in conversations
        delay(500)
        val msgs = bob.getMessages(alice.userId!!)
        assertTrue(msgs.any { it.content == "You there?" })

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `Multiple ORM messages survive offline`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("omsg_ma")
        val bob = registerAndConnect("omsg_mb")
        becomeFriends(alice, bob)

        alice.orm.define(messageSchema)
        bob.orm.define(messageSchema)

        bob.disconnect()
        delay(500)

        alice.send(bob.username!!, "Message 1")
        delay(200)
        alice.send(bob.username!!, "Message 2")
        delay(200)
        alice.send(bob.username!!, "Message 3")
        delay(500)

        bob.connect()

        val received = mutableListOf<String>()
        repeat(3) {
            val msg = bob.waitForMessage(15_000)
            assertEquals("MODEL_SYNC", msg.type)
            val d = JSONObject(String(msg.raw!!.modelSync.data.toByteArray()))
            received.add(d.getString("content"))
        }

        assertTrue(received.containsAll(listOf("Message 1", "Message 2", "Message 3")))

        alice.disconnect()
        bob.disconnect()
    }

    @Test
    fun `Legacy TEXT still works without directMessage model defined`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("omsg_la")
        val bob = registerAndConnect("omsg_lb")
        becomeFriends(alice, bob)

        // Deliberately do NOT define directMessage model
        // send() should fall back to TEXT (type 0)
        alice.send(bob.username!!, "Legacy hello")

        val msg = bob.waitForMessage(15_000)
        assertEquals("TEXT", msg.type, "Without directMessage model, should fall back to TEXT")
        assertEquals("Legacy hello", msg.text)

        alice.disconnect()
        bob.disconnect()
    }
}
