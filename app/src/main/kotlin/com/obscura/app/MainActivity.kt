package com.obscura.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.obscura.kit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as ObscuraApp
        val client = app.client

        setContent {
            MaterialTheme {
                ObscuraAppUI(client, app)
            }
        }
    }
}

@Composable
fun ObscuraAppUI(client: ObscuraClient, app: ObscuraApp? = null) {
    val scope = rememberCoroutineScope()
    val connectionState by client.connectionState.collectAsState()
    val authState by client.authState.collectAsState()
    val friendList by client.friendList.collectAsState()
    val pendingRequests by client.pendingRequests.collectAsState()

    var statusText by remember { mutableStateOf("Ready") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Status bar
        Text(
            text = "Auth: $authState | WS: $connectionState",
            style = MaterialTheme.typography.labelMedium
        )
        Text(text = statusText, style = MaterialTheme.typography.bodySmall)

        Divider()

        if (authState == AuthState.LOGGED_OUT) {
            RegisterScreen(client, app) { statusText = it }
        } else {
            ConnectedScreen(client, app, friendList, pendingRequests) { statusText = it }
        }
    }
}

@Composable
fun RegisterScreen(client: ObscuraClient, app: ObscuraApp? = null, onStatus: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Register / Login", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password (12+ chars)") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    try {
                        onStatus("Registering '$username'...")
                        withContext(Dispatchers.IO) { client.register(username, password) }
                    } catch (e: CancellationException) {
                        // Expected — composable left composition after auth state changed
                    } catch (e: Exception) {
                        onStatus("Error: ${e::class.simpleName}: ${e.message ?: e.cause?.message ?: e.toString()}")
                    }
                }
            }) { Text("Register") }

            Button(onClick = {
                scope.launch {
                    try {
                        onStatus("Logging in...")
                        withContext(Dispatchers.IO) { client.login(username, password) }
                    } catch (e: CancellationException) {
                        // Expected — composable left composition after auth state changed
                    } catch (e: Exception) {
                        onStatus("Error: ${e::class.simpleName}: ${e.message ?: e.cause?.message ?: e.toString()}")
                    }
                }
            }) { Text("Login") }
        }
    }
}

@Composable
fun ConnectedScreen(
    client: ObscuraClient,
    app: ObscuraApp? = null,
    friends: List<com.obscura.kit.stores.FriendData>,
    pending: List<com.obscura.kit.stores.FriendData>,
    onStatus: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var targetUserId by remember { mutableStateOf("") }
    var targetUsername by remember { mutableStateOf("") }
    var messageText by remember { mutableStateOf("") }
    var selectedFriend by remember { mutableStateOf<String?>(null) }
    val conversations by client.conversations.collectAsState()
    val events = remember { mutableStateListOf<String>() }

    // Connect WebSocket when this screen appears (skip if already connected)
    LaunchedEffect(Unit) {
        if (client.connectionState.value != ConnectionState.CONNECTED) {
            withContext(Dispatchers.IO) {
                try { client.connect() } catch (_: Exception) {}
            }
        }
    }

    // Collect incoming events
    LaunchedEffect(Unit) {
        client.events.collect { msg ->
            events.add(0, "${msg.type}: ${msg.text.take(50).ifEmpty { msg.username }}")
            if (events.size > 20) events.removeAt(events.lastIndex)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val clipboardManager = LocalClipboardManager.current
        val context = LocalContext.current
        val userId = client.userId ?: ""
        @OptIn(ExperimentalFoundationApi::class)
        Text(
            "User: ${client.username} ($userId)",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboardManager.setText(AnnotatedString(userId))
                    Toast.makeText(context, "userId copied", Toast.LENGTH_SHORT).show()
                }
            )
        )

        // Befriend
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = targetUserId,
                onValueChange = { targetUserId = it },
                label = { Text("Friend userId") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(onClick = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.befriend(targetUserId, targetUsername.ifEmpty { "friend" }) }
                        targetUserId = ""
                        onStatus("Friend request sent!")
                    } catch (e: Exception) { onStatus("Error: ${e.message}") }
                }
            }) { Text("Add") }
        }

        // Pending requests
        if (pending.isNotEmpty()) {
            Text("Pending (${pending.size}):", style = MaterialTheme.typography.labelLarge)
            pending.forEach { req ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(req.username)
                    Button(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { client.acceptFriend(req.userId, req.username) }
                            onStatus("Accepted ${req.username}")
                        }
                    }) { Text("Accept") }
                }
            }
        }

        // Friends list
        Text("Friends (${friends.size}):", style = MaterialTheme.typography.labelLarge)
        friends.forEach { friend ->
            TextButton(onClick = { selectedFriend = friend.username }) {
                Text(if (friend.username == selectedFriend) "> ${friend.username}" else friend.username)
            }
        }

        // Chat with selected friend
        selectedFriend?.let { friendName ->
            Divider()
            Text("Chat: $friendName", style = MaterialTheme.typography.titleSmall)

            val msgs = conversations[friendName] ?: emptyList()
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(msgs) { msg ->
                    Text("${msg.authorDeviceId.take(8)}: ${msg.content}", style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Message") }
                )
                Button(onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { client.send(friendName, messageText) }
                            messageText = ""
                            onStatus("Sent!")
                        } catch (e: Exception) { onStatus("Error: ${e.message}") }
                    }
                }) { Text("Send") }
            }
        }

        // Event log
        Divider()
        Text("Events:", style = MaterialTheme.typography.labelSmall)
        events.take(5).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }

        // Logout
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { client.logout() }
                onStatus("Logged out")
            }
        }) { Text("Logout") }
    }
}
