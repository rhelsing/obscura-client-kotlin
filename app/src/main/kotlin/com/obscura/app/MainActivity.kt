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
import com.obscura.kit.FriendCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ObscuraApp

        setContent {
            MaterialTheme {
                val currentUsername by app.currentUsername.collectAsState()
                val client = app.client
                val authState = client?.authState?.collectAsState()?.value ?: AuthState.LOGGED_OUT

                if (client != null && authState == AuthState.AUTHENTICATED) {
                    val friendList by client.friendList.collectAsState()
                    val pendingRequests by client.pendingRequests.collectAsState()
                    ConnectedScreen(client, app, friendList, pendingRequests)
                } else {
                    RegisterScreen(app)
                }
            }
        }
    }
}

@Composable
fun RegisterScreen(app: ObscuraApp) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("Ready") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = statusText, style = MaterialTheme.typography.bodySmall)
        Divider()
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
                if (username.isBlank()) return@Button
                scope.launch {
                    try {
                        statusText = "Registering '$username'..."
                        android.util.Log.d("REG", "0: button clicked, about to withContext IO")
                        android.util.Log.d("REG", "1: createClientForUser($username)")
                        withContext(Dispatchers.IO) {
                            app.createClientForUser(username)
                            android.util.Log.d("REG", "2: client created, calling register")
                            app.client!!.register(username, password)
                            android.util.Log.d("REG", "3: register returned, authState=${app.client!!.authState.value}")
                        }
                        android.util.Log.d("REG", "4: withContext done")
                    } catch (e: CancellationException) {
                        android.util.Log.d("REG", "CANCELLED: ${e.message}")
                    } catch (e: Exception) {
                        android.util.Log.e("REG", "ERROR: ${e::class.simpleName}: ${e.message}", e)
                        statusText = "Error: ${e::class.simpleName}: ${e.message}"
                    }
                }
            }) { Text("Register") }

            Button(onClick = {
                if (username.isBlank()) return@Button
                scope.launch {
                    try {
                        statusText = "Logging in..."
                        withContext(Dispatchers.IO) {
                            app.createClientForUser(username)
                            app.client!!.login(username, password)
                        }
                    } catch (e: CancellationException) {
                    } catch (e: Exception) {
                        statusText = "Error: ${e::class.simpleName}: ${e.message}"
                    }
                }
            }) { Text("Login") }
        }
    }
}

@Composable
fun ConnectedScreen(
    client: ObscuraClient,
    app: ObscuraApp,
    friends: List<com.obscura.kit.stores.FriendData>,
    pending: List<com.obscura.kit.stores.FriendData>
) {
    val scope = rememberCoroutineScope()
    var messageText by remember { mutableStateOf("") }
    var selectedFriend by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf("") }
    val conversations by client.conversations.collectAsState()
    val connectionState by client.connectionState.collectAsState()
    val events = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        if (client.connectionState.value != ConnectionState.CONNECTED) {
            withContext(Dispatchers.IO) {
                try { client.connect() } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(Unit) {
        client.events.collect { msg ->
            events.add(0, "${msg.type}: ${msg.text.take(50).ifEmpty { msg.username }}")
            if (events.size > 20) events.removeAt(events.lastIndex)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Auth: AUTHENTICATED | WS: $connectionState", style = MaterialTheme.typography.labelMedium)
        if (statusText.isNotEmpty()) Text(statusText, style = MaterialTheme.typography.bodySmall)

        val clipboardManager = LocalClipboardManager.current
        val context = LocalContext.current
        val myCode = remember(client.userId, client.username) {
            if (client.userId != null && client.username != null)
                FriendCode.encode(client.userId!!, client.username!!)
            else ""
        }

        Text("${client.username}", style = MaterialTheme.typography.titleMedium)

        // My friend code — tap to copy
        OutlinedButton(onClick = {
            clipboardManager.setText(AnnotatedString(myCode))
            Toast.makeText(context, "Friend code copied!", Toast.LENGTH_SHORT).show()
        }) {
            Text("Copy my friend code", style = MaterialTheme.typography.bodySmall)
        }

        // Paste friend code to add
        var friendCode by remember { mutableStateOf("") }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = friendCode,
                onValueChange = { friendCode = it },
                label = { Text("Paste friend code") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(onClick = {
                scope.launch {
                    try {
                        val decoded = FriendCode.decode(friendCode)
                        withContext(Dispatchers.IO) { client.befriend(decoded.userId, decoded.username) }
                        friendCode = ""
                        statusText = "Request sent to ${decoded.username}"
                    } catch (e: Exception) { statusText = "Error: ${e.message}" }
                }
            }) { Text("Add") }
        }

        // Outgoing requests (you sent, waiting for them to accept)
        val sentRequests = friends.filter { it.status == com.obscura.kit.stores.FriendStatus.PENDING_SENT }
        if (sentRequests.isNotEmpty()) {
            sentRequests.forEach { req ->
                Text("${req.username} (pending)", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline)
            }
        }

        // Incoming requests (they sent, waiting for you to accept)
        if (pending.isNotEmpty()) {
            pending.forEach { req ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(req.username)
                    Button(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { client.acceptFriend(req.userId, req.username) }
                            statusText = "Accepted ${req.username}"
                        }
                    }) { Text("Accept") }
                }
            }
        }

        val acceptedFriends = friends.filter { it.status == com.obscura.kit.stores.FriendStatus.ACCEPTED }
        Text("Friends (${acceptedFriends.size}):", style = MaterialTheme.typography.labelLarge)
        acceptedFriends.forEach { friend ->
            TextButton(onClick = { selectedFriend = friend.username }) {
                Text(if (friend.username == selectedFriend) "> ${friend.username}" else friend.username)
            }
        }

        selectedFriend?.let { friendName ->
            Divider()
            Text("Chat: $friendName", style = MaterialTheme.typography.titleSmall)
            val msgs = conversations[friendName] ?: emptyList()
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(msgs) { msg ->
                    Text("${msg.authorDeviceId.take(8)}: ${msg.content}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                        } catch (e: Exception) { statusText = "Error: ${e.message}" }
                    }
                }) { Text("Send") }
            }
        }

        Divider()
        Text("Events:", style = MaterialTheme.typography.labelSmall)
        events.take(5).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { client.logout() }
                app.clearSession()
            }
        }) { Text("Logout") }
    }
}
