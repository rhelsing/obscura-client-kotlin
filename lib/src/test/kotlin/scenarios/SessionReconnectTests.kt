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
class SessionReconnectTests {

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
                alice!!.register("kt_rc_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")
                bob = ObscuraClient(ObscuraConfig(API))
                bob!!.register("kt_rc2_${System.currentTimeMillis()}_${(1000..9999).random()}", "testpass123!xyz")
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
    fun `Signal session survives disconnect and reconnect`() = runBlocking {
        need()
        alice!!.send(bob!!.username!!, "before disconnect")
        val first = bob!!.waitForMessage()
        assertEquals("TEXT", first.type)
        assertEquals("before disconnect", first.text)

        bob!!.disconnect()
        Thread.sleep(1000)
        bob!!.connect()

        alice!!.send(bob!!.username!!, "after reconnect")
        val second = bob!!.waitForMessage()
        assertEquals("TEXT", second.type)
        assertEquals("after reconnect", second.text)
        assertEquals(alice!!.userId, second.sourceUserId)
    }

    @Test @Order(2)
    fun `Self-friend rejection`() = runBlocking {
        need()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { alice!!.befriend(alice!!.userId!!, alice!!.username!!) }
        }
        assertTrue(ex.message!!.contains("Cannot befriend yourself"))

        alice!!.disconnect(); bob!!.disconnect()
    }
}
