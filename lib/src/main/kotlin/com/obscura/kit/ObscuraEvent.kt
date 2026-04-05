package com.obscura.kit

import com.obscura.kit.orm.OrmEntry
import com.obscura.kit.stores.FriendData

/**
 * Typed event stream — bridges subscribe to `client.events` and relay to JS.
 * Replaces raw incomingMessages channel + separate StateFlow observations.
 */
sealed class ObscuraEvent {
    data class FriendsUpdated(val friends: List<FriendData>) : ObscuraEvent()
    data class ConnectionChanged(val state: ConnectionState) : ObscuraEvent()
    data class AuthChanged(val state: AuthState) : ObscuraEvent()
    data class MessageReceived(val model: String, val entry: OrmEntry) : ObscuraEvent()
    data class TypingChanged(val conversationId: String, val typers: List<String>) : ObscuraEvent()
}
