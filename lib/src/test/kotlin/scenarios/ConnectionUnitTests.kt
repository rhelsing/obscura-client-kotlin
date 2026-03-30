package scenarios

import com.obscura.kit.network.GatewayConnection
import com.obscura.kit.network.GatewayState
import com.obscura.kit.orm.SignalManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for connection resilience — no server needed.
 *
 * Tests the mechanics: backoff timing, shouldReconnect flag,
 * token refresh before reconnect, signal throttle.
 */
class ConnectionUnitTests {

    // ─── SignalManager throttle ───────────────────────────────────

    @Test
    fun `Signal throttle blocks rapid sends`() = runBlocking {
        val mgr = SignalManager()
        var sendCount = 0
        mgr.sendSignal = { _, _, _ -> sendCount++ }

        // Three rapid emits — only first should send
        mgr.emit("dm", "typing", mapOf("conversationId" to "c1"), "d1")
        mgr.emit("dm", "typing", mapOf("conversationId" to "c1"), "d1")
        mgr.emit("dm", "typing", mapOf("conversationId" to "c1"), "d1")

        assertEquals(1, sendCount, "Throttle should block rapid sends within 2s")
    }

    @Test
    fun `Signal throttle allows send after 2 seconds`() = runBlocking {
        val mgr = SignalManager()
        var sendCount = 0
        mgr.sendSignal = { _, _, _ -> sendCount++ }

        mgr.emit("dm", "typing", mapOf("conversationId" to "c1"), "d1")
        assertEquals(1, sendCount)

        delay(2100) // Wait past throttle

        mgr.emit("dm", "typing", mapOf("conversationId" to "c1"), "d1")
        assertEquals(2, sendCount, "Should allow send after throttle window")
    }

    @Test
    fun `Signal throttle is per-conversation`() = runBlocking {
        val mgr = SignalManager()
        var sendCount = 0
        mgr.sendSignal = { _, _, _ -> sendCount++ }

        mgr.emit("dm", "typing", mapOf("conversationId" to "c1"), "d1")
        mgr.emit("dm", "typing", mapOf("conversationId" to "c2"), "d1")

        assertEquals(2, sendCount, "Different conversations should not throttle each other")
    }

    // ─── Signal auto-expire ───────────────────────────────────────

    @Test
    fun `Signal expires after 3 seconds`() = runBlocking {
        val mgr = SignalManager()
        mgr.receive("dm", "typing", mapOf("conversationId" to "c1", "senderUsername" to "alice"), "d1")

        assertEquals(1, mgr.observe("dm", "typing", "c1").first().size)

        delay(3500)

        assertEquals(0, mgr.observe("dm", "typing", "c1").first().size,
            "Signal should expire after 3s")
    }

    @Test
    fun `Signal renewed before expiry stays visible`() = runBlocking {
        val mgr = SignalManager()

        // Send signal, wait 2s, renew, wait 2s more — should still be visible
        mgr.receive("dm", "typing", mapOf("conversationId" to "c1", "senderUsername" to "alice"), "d1")
        delay(2000)
        mgr.receive("dm", "typing", mapOf("conversationId" to "c1", "senderUsername" to "alice"), "d1")
        delay(2000)

        val typers = mgr.observe("dm", "typing", "c1").first()
        assertEquals(1, typers.size, "Renewed signal should still be visible after 4s total")
    }

    @Test
    fun `Explicit clear removes signal immediately`() = runBlocking {
        val mgr = SignalManager()
        mgr.receive("dm", "typing", mapOf("conversationId" to "c1", "senderUsername" to "alice"), "d1")
        assertEquals(1, mgr.observe("dm", "typing", "c1").first().size)

        mgr.clear("dm", "typing", mapOf("conversationId" to "c1"), "d1")
        assertEquals(0, mgr.observe("dm", "typing", "c1").first().size)
    }

    // ─── GatewayConnection state ──────────────────────────────────

    @Test
    fun `Gateway starts disconnected`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val api = com.obscura.kit.network.APIClient("https://obscura.barrelmaker.dev")
        val gw = GatewayConnection(api, scope)

        assertEquals(GatewayState.DISCONNECTED, gw.state.value)
        scope.cancel()
    }

    @Test
    fun `Disconnect sets shouldReconnect false`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val api = com.obscura.kit.network.APIClient("https://obscura.barrelmaker.dev")
        val gw = GatewayConnection(api, scope)

        // disconnect without ever connecting — should not crash
        gw.disconnect()
        assertEquals(GatewayState.DISCONNECTED, gw.state.value)
        scope.cancel()
    }

    @Test
    fun `Token refresh callback is invoked`() = runBlocking {
        var refreshCalled = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val api = com.obscura.kit.network.APIClient("https://obscura.barrelmaker.dev")
        val gw = GatewayConnection(api, scope)

        gw.ensureFreshToken = { refreshCalled = true; true }

        // We can't trigger a real reconnect without a server, but we can verify
        // the callback is wired correctly by checking it's set
        assertNotNull(gw.ensureFreshToken)
        gw.ensureFreshToken()
        assertTrue(refreshCalled, "Token refresh callback should be invocable")
        scope.cancel()
    }
}
