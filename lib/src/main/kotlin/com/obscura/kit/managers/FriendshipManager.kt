package com.obscura.kit.managers

import com.obscura.kit.stores.FriendStatus
import com.obscura.kit.stores.FriendSyncAction
import obscura.v2.Client.ClientMessage

/**
 * Befriend, acceptFriend, and syncFriendToOwnDevices.
 */
internal class FriendshipManager(
    private val ctx: ClientContext
) {
    private val session get() = ctx.session
    private val messenger get() = ctx.messenger
    private val friends get() = ctx.friends
    private val devices get() = ctx.devices
    private val messageSender get() = ctx.messageSender
    suspend fun befriend(targetUserId: String, targetUsername: String) {
        messenger.fetchPreKeyBundles(targetUserId)

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.FRIEND_REQUEST)
            .setUsername(session.username ?: "").setTimestamp(System.currentTimeMillis()).build()

        messageSender.sendToAllDevices(targetUserId, msg)
        friends.add(targetUserId, targetUsername, FriendStatus.PENDING_SENT)

        syncFriendToOwnDevices(targetUsername, FriendSyncAction.ADD.value, FriendStatus.PENDING_SENT.value)
    }

    suspend fun acceptFriend(targetUserId: String, targetUsername: String) {
        messenger.fetchPreKeyBundles(targetUserId)

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.FRIEND_RESPONSE)
            .setUsername(session.username ?: "").setAccepted(true)
            .setTimestamp(System.currentTimeMillis()).build()

        messageSender.sendToAllDevices(targetUserId, msg)
        friends.add(targetUserId, targetUsername, FriendStatus.ACCEPTED)

        syncFriendToOwnDevices(targetUsername, FriendSyncAction.ADD.value, FriendStatus.ACCEPTED.value)
    }

    suspend fun syncFriendToOwnDevices(friendUsername: String, action: String, status: String) {
        val selfTargets = devices.getSelfSyncTargets().filter { it != session.deviceId }
        if (selfTargets.isEmpty()) return

        val msg = ClientMessage.newBuilder()
            .setType(ClientMessage.Type.FRIEND_SYNC)
            .setTimestamp(System.currentTimeMillis())
            .setFriendSync(obscura.v2.friendSync {
                username = friendUsername
                this.action = action
                this.status = status
                timestamp = System.currentTimeMillis()
            }).build()

        for (devId in selfTargets) {
            messenger.queueMessage(devId, msg, session.userId)
        }
        messenger.flushMessages()
    }
}
