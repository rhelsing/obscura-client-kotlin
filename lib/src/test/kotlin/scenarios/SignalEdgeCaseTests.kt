package scenarios

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Signal edge cases tested via public API only.
 * Register, befriend, send messages, reset sessions, send again.
 * No direct SignalStore access.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SignalEdgeCaseTests {

    companion object {
        private var serverUp = false

        @BeforeAll @JvmStatic fun check() {
            serverUp = checkServer()
        }
    }

    private fun need() = assumeTrue(serverUp)

    @Test @Order(1)
    fun `Basic Signal encrypt-decrypt works via public API`() = runBlocking {
        need()

        val alice = registerAndConnect("sig_alice")
        val bob = registerAndConnect("sig_bob")

        becomeFriends(alice, bob)
        sendAndVerify(alice, bob, "Signal test message 1")
        sendAndVerify(bob, alice, "Signal test reply 1")

        alice.disconnect(); bob.disconnect()
    }

    @Test @Order(2)
    fun `Session reset and messaging resumes`() = runBlocking {
        need()

        val alice = registerAndConnect("sig_reset_a")
        val bob = registerAndConnect("sig_reset_b")

        becomeFriends(alice, bob)

        // Send initial messages to establish sessions
        sendAndVerify(alice, bob, "Before reset")

        // Alice resets session with Bob
        alice.resetSessionWith(bob.userId!!, "test reset")
        val resetMsg = bob.waitForMessage()
        assertEquals("SESSION_RESET", resetMsg.type,
            "Bob should receive SESSION_RESET")

        // Messaging should work after reset (sessions rebuild via prekey exchange)
        delay(500)
        sendAndVerify(alice, bob, "After reset from Alice", timeoutMs = 30_000)
        sendAndVerify(bob, alice, "After reset from Bob", timeoutMs = 30_000)

        // Verify conversations state
        delay(300)
        val bobMsgs = bob.getMessages(alice.userId!!)
        assertTrue(bobMsgs.any { it.content == "Before reset" },
            "Bob should still have pre-reset message")
        assertTrue(bobMsgs.any { it.content == "After reset from Alice" },
            "Bob should have post-reset message")

        alice.disconnect(); bob.disconnect()
    }

    @Test @Order(3)
    fun `Reset all sessions and messaging resumes`() = runBlocking {
        need()

        val alice = registerAndConnect("sig_rstall_a")
        val bob = registerAndConnect("sig_rstall_b")

        becomeFriends(alice, bob)
        sendAndVerify(alice, bob, "Pre-reset-all message")

        // Alice resets ALL sessions
        alice.resetAllSessions("bulk test reset")
        val resetMsg = bob.waitForMessage()
        assertEquals("SESSION_RESET", resetMsg.type,
            "Bob should receive SESSION_RESET from resetAll")

        // Messaging should work after bulk reset (prekey exchange)
        delay(500)
        sendAndVerify(alice, bob, "Post-reset-all from Alice", timeoutMs = 30_000)
        sendAndVerify(bob, alice, "Post-reset-all from Bob", timeoutMs = 30_000)

        alice.disconnect(); bob.disconnect()
    }
}
