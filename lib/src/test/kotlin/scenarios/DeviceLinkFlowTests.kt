package scenarios

import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
import com.obscura.kit.AuthState
import com.obscura.kit.crypto.SyncBlob
import com.obscura.kit.stores.FriendData
import com.obscura.kit.stores.FriendStatus
import com.obscura.kit.stores.MessageData
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Device link flow: provision second device via loginAndProvision,
 * verify server shows both devices.
 * Uses only public API + shared helpers.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DeviceLinkFlowTests {

    companion object {
        private var serverUp = false

        @BeforeAll @JvmStatic fun check() {
            serverUp = checkServer()
        }
    }

    private fun need() = assumeTrue(serverUp)

    @Test @Order(1)
    fun `Provision second device and verify server shows both`() = runBlocking {
        need()

        val username = uniqueName("dlf_user")
        val device1 = ObscuraClient(ObscuraConfig(API, deviceName = "Device 1"))
        device1.register(username, TEST_PASSWORD)
        assertEquals(AuthState.AUTHENTICATED, device1.authState.value)
        assertNotNull(device1.deviceId)

        // Provision device 2 via loginAndProvision
        val device2 = ObscuraClient(ObscuraConfig(API, deviceName = "Device 2"))
        device2.loginAndProvision(username, TEST_PASSWORD, "Device 2")
        assertEquals(AuthState.AUTHENTICATED, device2.authState.value)
        assertNotNull(device2.deviceId)
        assertNotEquals(device1.deviceId, device2.deviceId,
            "Devices should have different IDs")

        // Verify server shows both devices
        val devices = device1.api.listDevices()
        assertEquals(2, devices.length(), "Server should show 2 devices")
    }

    @Test @Order(2)
    fun `Provisioned device can connect and receive messages`() = runBlocking {
        need()

        val username = uniqueName("dlf_recv")
        val device1 = ObscuraClient(ObscuraConfig(API, deviceName = "Device 1"))
        device1.register(username, TEST_PASSWORD)

        val device2 = ObscuraClient(ObscuraConfig(API, deviceName = "Device 2"))
        device2.loginAndProvision(username, TEST_PASSWORD, "Device 2")

        // Befriend a third user to test message delivery to provisioned device
        val carol = registerAndConnect("dlf_carol")
        device1.connect(); device2.connect()

        becomeFriends(device1, carol)

        // Device2 may have queued friend sync messages — drain them
        try {
            while (true) { device2.waitForMessage(2_000) }
        } catch (_: Exception) { /* drained */ }

        // Carol sends to user - both devices should receive
        carol.send(username, "Message for both devices")

        val msg1 = device1.waitForMessage()
        assertEquals("TEXT", msg1.type)
        assertEquals("Message for both devices", msg1.text)

        val msg2 = device2.waitForMessage()
        assertEquals("TEXT", msg2.type)
        assertEquals("Message for both devices", msg2.text)

        device1.disconnect(); device2.disconnect(); carol.disconnect()
    }

    @Test @Order(3)
    fun `SyncBlob export includes messages`() {
        // Local test - verify serialization includes messages (no server needed)
        val friends = listOf(FriendData(
            userId = "u1", username = "alice",
            status = FriendStatus.ACCEPTED
        ))
        val messages = mapOf("alice" to listOf(MessageData(
            id = "m1", conversationId = "alice", authorDeviceId = "dev1",
            content = "hello", timestamp = 1000, type = "text"
        )))

        val compressed = SyncBlob.export(friends, messages)
        val parsed = SyncBlob.parse(compressed)

        assertNotNull(parsed)
        assertEquals(1, parsed!!.friends.size)
        assertEquals(1, parsed.messages.size)
        assertEquals("hello", parsed.messages[0]["content"])
    }
}
