package com.obscura.kit.network

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import com.obscura.kit.crypto.fromBase64Url
import java.security.MessageDigest
import java.util.*

/**
 * HTTP API client for Obscura server.
 * Pure functional — does NOT auto-store tokens.
 */
class APIClient(private val baseUrl: String) {

    private val httpClient = OkHttpClient.Builder()
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS)) // TLS 1.2+ only, no cleartext
        .build()
    private val JSON_MEDIA = "application/json".toMediaType()
    private val PROTOBUF_MEDIA = "application/x-protobuf".toMediaType()
    private val OCTET_MEDIA = "application/octet-stream".toMediaType()

    var token: String? = null

    // ============================================================
    // Auth
    // ============================================================

    /**
     * Register a new user. Returns JSON with token.
     */
    suspend fun registerUser(username: String, password: String): JSONObject {
        return postJson("/v1/users", JSONObject().apply {
            put("username", username)
            put("password", password)
        }, auth = false)
    }

    /**
     * Provision a device with Signal keys. Returns device-scoped JWT.
     */
    suspend fun provisionDevice(
        name: String,
        identityKey: String,
        registrationId: Int,
        signedPreKey: JSONObject,
        oneTimePreKeys: JSONArray
    ): JSONObject {
        return postJson("/v1/devices", JSONObject().apply {
            put("name", name)
            put("identityKey", identityKey)
            put("registrationId", registrationId)
            put("signedPreKey", signedPreKey)
            put("oneTimePreKeys", oneTimePreKeys)
        })
    }

    /**
     * Login with optional deviceId for device-scoped token.
     */
    suspend fun loginWithDevice(username: String, password: String, deviceId: String? = null): JSONObject {
        return postJson("/v1/sessions", JSONObject().apply {
            put("username", username)
            put("password", password)
            if (deviceId != null) put("deviceId", deviceId)
        }, auth = false)
    }

    /**
     * Logout (invalidate refresh token).
     */
    suspend fun logout(refreshToken: String): String {
        val body = JSONObject().apply { put("refreshToken", refreshToken) }
        return deleteWithBody("/v1/sessions", body)
    }

    /**
     * Refresh session token.
     */
    suspend fun refreshSession(refreshToken: String): JSONObject {
        return postJson("/v1/sessions/refresh", JSONObject().apply {
            put("refreshToken", refreshToken)
        }, auth = false)
    }

    // ============================================================
    // Device Management
    // ============================================================

    suspend fun listDevices(): JSONArray {
        val response = getJson("/v1/devices")
        return response.getJSONArray("devices")
    }

    suspend fun getDevice(deviceId: String): JSONObject {
        return getJson("/v1/devices/${enc(deviceId)}")
    }

    suspend fun deleteDevice(deviceId: String): String {
        return delete("/v1/devices/${enc(deviceId)}")
    }

    /**
     * Upload PreKeys for the authenticated device.
     */
    suspend fun uploadDeviceKeys(
        identityKey: String,
        registrationId: Int,
        signedPreKey: JSONObject,
        oneTimePreKeys: JSONArray
    ) {
        val body = JSONObject().apply {
            put("identityKey", identityKey)
            put("registrationId", registrationId)
            put("signedPreKey", signedPreKey)
            put("oneTimePreKeys", oneTimePreKeys)
        }
        val request = Request.Builder()
            .url("$baseUrl/v1/devices/keys")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer ${token ?: throw IllegalStateException("No token")}")
            .build()
        executeString(request) // ignore empty response body
    }

    /**
     * Fetch PreKey bundles for all devices of a user.
     */
    suspend fun fetchPreKeyBundles(userId: String): JSONArray {
        return getJsonArray("/v1/users/${enc(userId)}")
    }

    // ============================================================
    // Messages (protobuf)
    // ============================================================

    /**
     * Send a batch of messages as protobuf binary.
     */
    suspend fun sendMessage(protobufData: ByteArray): ByteArray {
        val request = Request.Builder()
            .url("$baseUrl/v1/messages")
            .post(protobufData.toRequestBody(PROTOBUF_MEDIA))
            .addHeader("Authorization", "Bearer ${token ?: throw IllegalStateException("No token")}")
            .addHeader("Content-Type", "application/x-protobuf")
            .addHeader("Idempotency-Key", contentBasedUUID(protobufData))
            .build()

        return executeBytes(request)
    }

    // ============================================================
    // Attachments
    // ============================================================

    suspend fun uploadAttachment(blob: ByteArray): JSONObject {
        val request = Request.Builder()
            .url("$baseUrl/v1/attachments")
            .post(blob.toRequestBody(OCTET_MEDIA))
            .addHeader("Authorization", "Bearer ${token ?: throw IllegalStateException("No token")}")
            .build()

        return JSONObject(executeString(request))
    }

    suspend fun fetchAttachment(id: String): ByteArray {
        val request = Request.Builder()
            .url("$baseUrl/v1/attachments/${enc(id)}")
            .get()
            .addHeader("Authorization", "Bearer ${token ?: throw IllegalStateException("No token")}")
            .build()

        return executeBytes(request)
    }

    // ============================================================
    // Backup
    // ============================================================

    suspend fun uploadBackup(data: ByteArray, etag: String? = null): String? {
        val builder = Request.Builder()
            .url("$baseUrl/v1/backup")
            .post(data.toRequestBody(OCTET_MEDIA))
            .addHeader("Authorization", "Bearer ${token ?: throw IllegalStateException("No token")}")
            .addHeader("Content-Length", data.size.toString())

        if (etag != null) {
            builder.addHeader("If-Match", etag)
        } else {
            builder.addHeader("If-None-Match", "*")
        }

        val response = httpClient.newCall(builder.build()).execute()
        if (!response.isSuccessful) throw HttpException(response.code, response.body?.string() ?: "")
        return response.header("ETag")
    }

    suspend fun downloadBackup(etag: String? = null): Pair<ByteArray, String?>? {
        val builder = Request.Builder()
            .url("$baseUrl/v1/backup")
            .get()
            .addHeader("Authorization", "Bearer ${token ?: throw IllegalStateException("No token")}")

        if (etag != null) builder.addHeader("If-None-Match", etag)

        val response = httpClient.newCall(builder.build()).execute()
        if (response.code == 304 || response.code == 404) return null
        if (!response.isSuccessful) throw HttpException(response.code, response.body?.string() ?: "")
        return Pair(response.body?.bytes() ?: ByteArray(0), response.header("ETag"))
    }

    suspend fun checkBackup(): Triple<Boolean, String?, Long?> {
        val request = Request.Builder()
            .url("$baseUrl/v1/backup")
            .head()
            .addHeader("Authorization", "Bearer ${token ?: throw IllegalStateException("No token")}")
            .build()

        val response = httpClient.newCall(request).execute()
        if (response.code == 404) return Triple(false, null, null)
        if (!response.isSuccessful) throw HttpException(response.code, "")
        return Triple(true, response.header("ETag"), response.header("Content-Length")?.toLongOrNull())
    }

    // ============================================================
    // Gateway
    // ============================================================

    suspend fun fetchGatewayTicket(): String {
        val result = postJson("/v1/gateway/ticket", JSONObject())
        return result.getString("ticket")
    }

    fun getGatewayUrl(ticket: String): String {
        val wsBase = baseUrl.replace("https://", "wss://").replace("http://", "ws://")
        return "$wsBase/v1/gateway?ticket=${java.net.URLEncoder.encode(ticket, "UTF-8")}"
    }

    // ============================================================
    // Token Utilities
    // ============================================================

    fun decodeToken(t: String? = token): JSONObject? {
        val tok = t ?: return null
        return try {
            val payload = tok.split(".")[1]
            val decoded = payload.fromBase64Url()
            JSONObject(String(decoded))
        } catch (e: Exception) {
            null
        }
    }

    fun getUserId(t: String? = token): String? {
        val payload = decodeToken(t) ?: return null
        return payload.optStringOrNull("sub")
            ?: payload.optStringOrNull("user_id")
            ?: payload.optStringOrNull("userId")
            ?: payload.optStringOrNull("id")
    }

    fun getDeviceId(t: String? = token): String? {
        val payload = decodeToken(t) ?: return null
        return payload.optStringOrNull("device_id")
            ?: payload.optStringOrNull("deviceId")
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        return if (has(key) && !isNull(key)) getString(key) else null
    }

    // ============================================================
    // Internal HTTP helpers
    // ============================================================

    private fun postJson(path: String, body: JSONObject, auth: Boolean = true): JSONObject {
        val builder = Request.Builder()
            .url("$baseUrl$path")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .addHeader("Content-Type", "application/json")

        if (auth && token != null) {
            builder.addHeader("Authorization", "Bearer $token")
        }

        return JSONObject(executeString(builder.build()))
    }

    private fun getJson(path: String): JSONObject {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .get()
            .addHeader("Authorization", "Bearer ${token ?: throw IllegalStateException("No token")}")
            .build()

        return JSONObject(executeString(request))
    }

    private fun getJsonArray(path: String): JSONArray {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .get()
            .addHeader("Authorization", "Bearer ${token ?: throw IllegalStateException("No token")}")
            .build()

        return JSONArray(executeString(request))
    }

    private fun deleteWithBody(path: String, body: JSONObject): String {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .delete(body.toString().toRequestBody(JSON_MEDIA))
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer ${token ?: throw IllegalStateException("No token")}")
            .build()

        return executeString(request)
    }

    private fun delete(path: String): String {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .delete()
            .addHeader("Authorization", "Bearer ${token ?: throw IllegalStateException("No token")}")
            .build()

        return executeString(request)
    }

    private fun executeString(request: Request): String {
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val body = response.body?.string() ?: ""
            throw HttpException(response.code, body)
        }
        return response.body?.string() ?: ""
    }

    private fun executeBytes(request: Request): ByteArray {
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val body = response.body?.string() ?: ""
            throw HttpException(response.code, body)
        }
        return response.body?.bytes() ?: ByteArray(0)
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun contentBasedUUID(data: ByteArray): String {
        // UUID v5-style: deterministic UUID from content hash (safe retry = same key)
        return UUID.nameUUIDFromBytes(data).toString()
    }
}

class HttpException(val statusCode: Int, val body: String) : IOException("HTTP $statusCode")
