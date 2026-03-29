package scenarios

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Session reset: reset single session, message after reset, reset all sessions.
 * All E2E against live server using ObscuraClient public API only.
 */
class SessionResetTests {

    @Test
    fun `Session reset sends SESSION_RESET and messaging continues`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("sr_a")
        val bob = registerAndConnect("sr_b")
        becomeFriends(alice, bob)

        // Exchange a message first to confirm session works
        sendAndVerify(alice, bob, "Before reset")

        val bobMsgsBefore = bob.getMessages(alice.userId!!)
        assertTrue(bobMsgsBefore.any { it.content == "Before reset" },
            "Bob's conversations should contain pre-reset message")

        // Alice resets session with Bob
        alice.resetSessionWith(bob.userId!!, "test reset")

        val resetMsg = bob.waitForMessage()
        assertEquals("SESSION_RESET", resetMsg.type,
            "Bob should receive SESSION_RESET")
        delay(300)

        // Send after reset — needs prekey exchange, allow extra time
        sendAndVerify(alice, bob, "After reset", timeoutMs = 30_000)

        val bobMsgsAfter = bob.getMessages(alice.userId!!)
        assertTrue(bobMsgsAfter.any { it.content == "After reset" },
            "Bob's conversations should contain post-reset message")

        alice.disconnect(); bob.disconnect()
    }

    @Test
    fun `Reset all sessions completes without error`() = runBlocking {
        assumeTrue(checkServer())

        val alice = registerAndConnect("sr_c")
        val bob = registerAndConnect("sr_d")
        becomeFriends(alice, bob)

        // Exchange a message to establish session
        sendAndVerify(alice, bob, "Pre bulk reset")

        // Reset all sessions — should not throw
        alice.resetAllSessions("test bulk reset")
        delay(500)

        // No crash = success. Bob may or may not receive depending on session state.
        alice.disconnect(); bob.disconnect()
    }
}
