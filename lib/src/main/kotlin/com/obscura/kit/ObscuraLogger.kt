package com.obscura.kit

/**
 * Structured logger for security-relevant events.
 * Matches iOS ObscuraLogger protocol.
 * Set ObscuraClient.logger to receive events.
 */
interface ObscuraLogger {
    fun log(message: String)
    fun decryptFailed(sourceUserId: String, reason: String)
    fun ackFailed(envelopeId: String, reason: String)
    fun tokenRefreshFailed(attempt: Int, reason: String)
    fun preKeyReplenishFailed(reason: String)
    fun identityChanged(address: String)
    fun sessionEstablishFailed(userId: String, reason: String)
    fun signatureVerificationFailed(sourceUserId: String, messageType: String)
    fun databaseError(store: String, operation: String, reason: String)
}

/** Silent logger — default. */
object NoOpLogger : ObscuraLogger {
    override fun log(message: String) {}
    override fun decryptFailed(sourceUserId: String, reason: String) {}
    override fun ackFailed(envelopeId: String, reason: String) {}
    override fun tokenRefreshFailed(attempt: Int, reason: String) {}
    override fun preKeyReplenishFailed(reason: String) {}
    override fun identityChanged(address: String) {}
    override fun sessionEstablishFailed(userId: String, reason: String) {}
    override fun signatureVerificationFailed(sourceUserId: String, messageType: String) {}
    override fun databaseError(store: String, operation: String, reason: String) {}
}

/** Prints to stderr — useful for development. */
object PrintLogger : ObscuraLogger {
    override fun log(message: String) = System.err.println("[ObscuraKit] $message")
    override fun decryptFailed(sourceUserId: String, reason: String) = log("decrypt failed from $sourceUserId: $reason")
    override fun ackFailed(envelopeId: String, reason: String) = log("ack failed for $envelopeId: $reason")
    override fun tokenRefreshFailed(attempt: Int, reason: String) = log("token refresh failed (attempt $attempt): $reason")
    override fun preKeyReplenishFailed(reason: String) = log("prekey replenish failed: $reason")
    override fun identityChanged(address: String) = log("identity changed for $address")
    override fun sessionEstablishFailed(userId: String, reason: String) = log("session establish failed for $userId: $reason")
    override fun signatureVerificationFailed(sourceUserId: String, messageType: String) = log("signature verification failed from $sourceUserId type=$messageType")
    override fun databaseError(store: String, operation: String, reason: String) = log("db error in $store.$operation: $reason")
}
