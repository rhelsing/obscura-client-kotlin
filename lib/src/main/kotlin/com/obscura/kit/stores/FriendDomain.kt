package com.obscura.kit.stores

import com.obscura.kit.db.ObscuraDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class FriendStatus(val value: String) {
    PENDING_SENT("pending_sent"),
    PENDING_RECEIVED("pending_received"),
    ACCEPTED("accepted")
}

enum class FriendSyncAction(val value: String) {
    ADD("add"),
    REMOVE("remove")
}

data class DeviceTarget(
    val deviceId: String,
    val userId: String,
    val registrationId: Int = 1
)

data class FriendData(
    val userId: String,
    val username: String,
    val status: FriendStatus,
    val devices: List<FriendDeviceInfo> = emptyList()
)

data class FriendDeviceInfo(
    val deviceUuid: String,
    val deviceId: String,
    val deviceName: String,
    val signalIdentityKey: ByteArray? = null
)

/**
 * FriendDomain - Confined coroutines. Manages friend state + device lists.
 */
class FriendDomain internal constructor(private val db: ObscuraDatabase) {
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    suspend fun add(userId: String, username: String, status: FriendStatus, devices: List<FriendDeviceInfo> = emptyList()) =
        withContext(dispatcher) {
            val devicesJson = JSONArray(devices.map { d ->
                JSONObject().apply {
                    put("deviceUuid", d.deviceUuid)
                    put("deviceId", d.deviceId)
                    put("deviceName", d.deviceName)
                }
            }).toString()

            val now = System.currentTimeMillis()
            db.friendQueries.insert(userId, username, status.value, devicesJson, now, now)
        }

    suspend fun getAccepted(): List<FriendData> = withContext(dispatcher) {
        db.friendQueries.selectByStatus(FriendStatus.ACCEPTED.value).executeAsList().map { it.toFriendData() }
    }

    suspend fun getPending(): List<FriendData> = withContext(dispatcher) {
        val sent = db.friendQueries.selectByStatus(FriendStatus.PENDING_SENT.value).executeAsList()
        val received = db.friendQueries.selectByStatus(FriendStatus.PENDING_RECEIVED.value).executeAsList()
        (sent + received).map { it.toFriendData() }
    }

    suspend fun getAll(): List<FriendData> = withContext(dispatcher) {
        db.friendQueries.selectAll().executeAsList().map { it.toFriendData() }
    }

    suspend fun getFanOutTargets(userId: String): List<DeviceTarget> = withContext(dispatcher) {
        val friend = db.friendQueries.selectById(userId).executeAsOneOrNull() ?: return@withContext emptyList()
        parseDevices(friend.devices).map { d ->
            DeviceTarget(deviceId = d.deviceId, userId = userId)
        }
    }

    suspend fun getAllFriendDeviceTargets(): List<String> = withContext(dispatcher) {
        val accepted = db.friendQueries.selectByStatus(FriendStatus.ACCEPTED.value).executeAsList()
        accepted.flatMap { friend ->
            parseDevices(friend.devices).map { it.deviceId }
        }
    }

    suspend fun updateDevices(userId: String, devices: List<FriendDeviceInfo>) = withContext(dispatcher) {
        val friend = db.friendQueries.selectById(userId).executeAsOneOrNull() ?: return@withContext
        val devicesJson = JSONArray(devices.map { d ->
            JSONObject().apply {
                put("deviceUuid", d.deviceUuid)
                put("deviceId", d.deviceId)
                put("deviceName", d.deviceName)
            }
        }).toString()
        db.friendQueries.insert(userId, friend.username, friend.status, devicesJson, friend.created_at, System.currentTimeMillis())
    }

    suspend fun remove(userId: String) = withContext(dispatcher) {
        db.friendQueries.deleteById(userId)
    }

    suspend fun exportAll(): String = withContext(dispatcher) {
        val friends = db.friendQueries.selectAll().executeAsList()
        val arr = JSONArray(friends.map { f ->
            JSONObject().apply {
                put("userId", f.user_id)
                put("username", f.username)
                put("status", f.status)
                put("devices", JSONArray(f.devices))
            }
        })
        arr.toString()
    }

    suspend fun importAll(data: String) = withContext(dispatcher) {
        val arr = JSONArray(data)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val now = System.currentTimeMillis()
            db.friendQueries.insert(
                obj.getString("userId"),
                obj.getString("username"),
                obj.getString("status"),
                obj.optString("devices", "[]"),
                now, now
            )
        }
    }

    private fun com.obscura.kit.Friend.toFriendData(): FriendData {
        return FriendData(
            userId = user_id,
            username = username,
            status = FriendStatus.entries.find { it.value == status } ?: FriendStatus.PENDING_SENT,
            devices = parseDevices(devices)
        )
    }

    private fun parseDevices(json: String): List<FriendDeviceInfo> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                FriendDeviceInfo(
                    deviceUuid = obj.optString("deviceUuid", ""),
                    deviceId = obj.optString("deviceId", ""),
                    deviceName = obj.optString("deviceName", "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
