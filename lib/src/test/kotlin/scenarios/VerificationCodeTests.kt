package scenarios

import com.obscura.kit.crypto.VerificationCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.signal.libsignal.protocol.IdentityKeyPair

class VerificationCodeTests {

    @Test
    fun `Code is 4 digits`() {
        val key = ByteArray(33) { 0xAA.toByte() }
        val code = VerificationCode.fromKey(key)
        assertEquals(4, code.length)
        assertTrue(code.all { it.isDigit() })
    }

    @Test
    fun `Same key produces same code`() {
        val key = ByteArray(32) { 0xBB.toByte() }
        assertEquals(VerificationCode.fromKey(key), VerificationCode.fromKey(key))
    }

    @Test
    fun `Different keys produce different codes`() {
        val key1 = ByteArray(32) { 0xCC.toByte() }
        val key2 = ByteArray(32) { 0xDD.toByte() }
        assertNotEquals(VerificationCode.fromKey(key1), VerificationCode.fromKey(key2))
    }

    @Test
    fun `Recovery key code is stable across devices`() {
        val recoveryKey = ByteArray(32) { 0xEE.toByte() }
        assertEquals(
            VerificationCode.fromRecoveryKey(recoveryKey),
            VerificationCode.fromRecoveryKey(recoveryKey)
        )
    }

    @Test
    fun `Real Signal key pair produces valid codes`() {
        val alice = IdentityKeyPair.generate()
        val bob = IdentityKeyPair.generate()
        val aliceCode = VerificationCode.fromKey(alice.publicKey.serialize())
        val bobCode = VerificationCode.fromKey(bob.publicKey.serialize())
        assertEquals(4, aliceCode.length)
        assertEquals(4, bobCode.length)
    }

    @Test
    fun `Device list code changes when device added`() {
        val dev1 = "uuid-1" to ByteArray(33) { 0x11.toByte() }
        val dev2 = "uuid-2" to ByteArray(33) { 0x22.toByte() }
        val one = VerificationCode.fromDevices(listOf(dev1))
        val two = VerificationCode.fromDevices(listOf(dev1, dev2))
        assertNotEquals(one, two, "Adding a device should change the code")
    }

    @Test
    fun `Device order does not affect code`() {
        val dev1 = "aaa" to ByteArray(33) { 0x11.toByte() }
        val dev2 = "bbb" to ByteArray(33) { 0x22.toByte() }
        val order1 = VerificationCode.fromDevices(listOf(dev1, dev2))
        val order2 = VerificationCode.fromDevices(listOf(dev2, dev1))
        assertEquals(order1, order2, "Device order shouldn't affect code (sorted by UUID)")
    }
}
