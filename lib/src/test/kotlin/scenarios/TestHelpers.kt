package scenarios

import com.obscura.kit.ObscuraClient
import com.obscura.kit.ObscuraConfig
import com.obscura.kit.AuthState
import com.obscura.kit.stores.FriendStatus
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Assertions.*

const val API = "https://obscura.barrelmaker.dev"
const val TEST_PASSWORD = "testpass123!xyz"

fun checkServer(): Boolean = try {
    java.net.URL("$API/openapi.yaml").openConnection().apply {
        connectTimeout = 5000; readTimeout = 5000
    }.getInputStream().close(); true
} catch (e: Exception) { false }

fun uniqueName(prefix: String): String =
    "kt_${prefix}_${System.currentTimeMillis()}_${(1000..9999).random()}"

suspend fun registerAndConnect(prefix: String): ObscuraClient {
    val client = ObscuraClient(ObscuraConfig(API))
    client.register(uniqueName(prefix), TEST_PASSWORD)
    assertEquals(AuthState.AUTHENTICATED, client.authState.value)
    assertNotNull(client.userId)
    assertNotNull(client.deviceId)
    client.connect()
    return client
}

suspend fun becomeFriends(a: ObscuraClient, b: ObscuraClient) {
    val aFriendsBefore = a.friendList.value.size
    val bFriendsBefore = b.friendList.value.size

    a.befriend(b.userId!!, b.username!!)
    delay(300)

    b.waitForMessage() // FRIEND_REQUEST
    delay(300)
    assertTrue(b.pendingRequests.value.any { it.userId == a.userId },
        "Receiver should see pending request from sender")

    b.acceptFriend(a.userId!!, a.username!!)
    a.waitForMessage() // FRIEND_RESPONSE
    delay(300)

    assertTrue(a.friendList.value.any { it.userId == b.userId && it.status == FriendStatus.ACCEPTED },
        "Sender should see receiver as ACCEPTED friend")
    assertTrue(b.friendList.value.any { it.userId == a.userId && it.status == FriendStatus.ACCEPTED },
        "Receiver should see sender as ACCEPTED friend")
    assertEquals(aFriendsBefore + 1, a.friendList.value.size)
    assertEquals(bFriendsBefore + 1, b.friendList.value.size)
}

suspend fun sendAndVerify(sender: ObscuraClient, receiver: ObscuraClient, text: String, timeoutMs: Long = 15_000) {
    sender.send(receiver.username!!, text)
    val msg = receiver.waitForMessage(timeoutMs)
    assertEquals("TEXT", msg.type)
    assertEquals(text, msg.text)
    assertEquals(sender.userId, msg.sourceUserId)
    delay(500)

    // Verify receiver's conversations has it
    val recvMsgs = receiver.getMessages(sender.userId!!)
    assertTrue(recvMsgs.any { it.content == text },
        "Receiver's conversations should contain '$text'")
}
