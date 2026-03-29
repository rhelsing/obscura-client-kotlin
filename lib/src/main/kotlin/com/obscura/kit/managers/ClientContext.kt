package com.obscura.kit.managers

import com.obscura.kit.crypto.SignalStore
import com.obscura.kit.network.APIClient
import com.obscura.kit.stores.*

internal class ClientContext(
    val session: ClientSession,
    val api: APIClient,
    val signalStore: SignalStore,
    val messenger: MessengerDomain,
    val friends: FriendDomain,
    val devices: DeviceDomain,
    val messages: MessageDomain
) {
    lateinit var messageSender: MessageSender
}
