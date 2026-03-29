package com.obscura.kit.network

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import com.obscura.kit.crypto.fromBase64Url
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

    /**
     * Register a new user. Returns typed AuthResponse with token.
     */
    suspend fun registerUser(username: String, password: String): AuthResponse {
        val json = postJson("/v1/users", RegisterUserRequest(username, password).toJson(), auth = false)
        return AuthResponse(
            token = json.getString("token"),
            refreshToken = json.optStringOrNull("refreshToken"),
            deviceId = json.optStringOrNull("deviceId")
        )
    }

    /**
     * Provision a device with Signal keys. Returns typed ProvisionResponse.
     */
    suspend fun provisionDevice(request: ProvisionDeviceRequest): ProvisionResponse {
        val json = postJson("/v1/devices", request.toJson())
        return ProvisionResponse(
            token = json.getString("token"),
            refreshToken = json.optStringOrNull("refreshToken"),
            deviceId = json.optStringOrNull("deviceId") ?: getDeviceId(json.getString("token")) ?: ""
        )
    }

    /**
     * Login with optional deviceId for device-scoped token.
     */
    suspend fun loginWithDevice(username: String, password: String, deviceId: String? = null): AuthResponse {
        val json = postJson("/v1/sessions", LoginRequest(username, password, deviceId).toJson(), auth = false)
        return AuthResponse(
            token = json.getString("token"),
            refreshToken = json.optStringOrNull("refreshToken"),
            deviceId = json.optStringOrNull("deviceId")
        )
    }

    /**
     * Logout (invalidate refresh token).
     */
    suspend fun logout(refreshToken: String): String {
        return deleteWithBody("/v1/sessions", LogoutRequest(refreshToken).toJson())
    }

    /**
     * Refresh session token. Returns just the new token string.
     */
    suspend fun refreshSession(refreshToken: String): AuthResponse {
        val json = postJson("/v1/sessions/refresh", RefreshTokenRequest(refreshToken).toJson(), auth = false)
        return AuthResponse(
            token = json.getString("token"),
            refreshToken = json.optStringOrNull("refreshToken"),
            deviceId = null
        )
    }

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
    suspend fun uploadDeviceKeys(request: UploadDeviceKeysRequest) {
        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/devices/keys")
            .post(request.toJson().toString().toRequestBody(JSON_MEDIA))
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer ${token ?: throw IllegalStateException("No token")}")
            .build()
        executeString(httpRequest) // ignore empty response body
    }

    /**
     * Fetch PreKey bundles for all devices of a user.
     */
    suspend fun fetchPreKeyBundles(userId: String): JSONArray {
        return getJsonArray("/v1/users/${enc(userId)}")
    }

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

    suspend fun uploadAttachment(blob: ByteArray): AttachmentUploadResponse {
        val request = Request.Builder()
            .url("$baseUrl/v1/attachments")
            .post(blob.toRequestBody(OCTET_MEDIA))
            .addHeader("Authorization", "Bearer ${token ?: throw IllegalStateException("No token")}")
            .build()

        val json = JSONObject(executeString(request))
        return AttachmentUploadResponse(
            id = json.getString("id"),
            expiresAt = json.optLong("expiresAt", 0)
        )
    }

    suspend fun fetchAttachment(id: String): ByteArray {
        val request = Request.Builder()
            .url("$baseUrl/v1/attachments/${enc(id)}")
            .get()
            .addHeader("Authorization", "Bearer ${token ?: throw IllegalStateException("No token")}")
            .build()

        return executeBytes(request)
    }

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

    suspend fun checkBackup(): BackupCheckResponse {
        val request = Request.Builder()
            .url("$baseUrl/v1/backup")
            .head()
            .addHeader("Authorization", "Bearer ${token ?: throw IllegalStateException("No token")}")
            .build()

        val response = httpClient.newCall(request).execute()
        if (response.code == 404) return BackupCheckResponse(exists = false, etag = null, lastModified = null)
        if (!response.isSuccessful) throw HttpException(response.code, "")
        return BackupCheckResponse(
            exists = true,
            etag = response.header("ETag"),
            lastModified = response.header("Content-Length")?.toLongOrNull()
        )
    }

    suspend fun fetchGatewayTicket(): String {
        val result = postJson("/v1/gateway/ticket", JSONObject())
        return result.getString("ticket")
    }

    fun getGatewayUrl(ticket: String): String {
        val wsBase = baseUrl.replace("https://", "wss://").replace("http://", "ws://")
        return "$wsBase/v1/gateway?ticket=${java.net.URLEncoder.encode(ticket, "UTF-8")}"
    }

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
