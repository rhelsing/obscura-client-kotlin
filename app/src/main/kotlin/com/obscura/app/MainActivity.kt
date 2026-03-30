package com.obscura.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
import com.obscura.kit.network.LoginScenario
import com.obscura.kit.orm.ModelConfig
import com.obscura.kit.orm.TypedModel
import com.obscura.kit.stores.FriendStatus
import androidx.compose.animation.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Canonical conversationId — same string from both sides. Must match iOS. */
fun canonicalConversationId(userId1: String, userId2: String): String {
    return listOf(userId1, userId2).sorted().joinToString("_")
}

// ─── Model definitions (same field names as iOS for interop) ──

@Serializable
data class DirectMessage(val conversationId: String, val content: String, val senderUsername: String)

@Serializable
data class Story(val content: String, val authorUsername: String, val mediaUrl: String? = null)

@Serializable
data class Profile(val displayName: String, val bio: String? = null)

@Serializable
data class AppSettings(val theme: String, val notificationsEnabled: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ObscuraApp

        setContent {
            MaterialTheme {
                var client by remember { mutableStateOf(app.client) }
                val authState = client?.authState?.collectAsState()?.value ?: AuthState.LOGGED_OUT

                when (authState) {
                    AuthState.LOGGED_OUT -> LoginScreen(app) { client = it }
                    AuthState.PENDING_APPROVAL -> LinkApprovalScreen(client!!)
                    AuthState.AUTHENTICATED -> AppScreen(client!!, app)
                }
            }
        }
    }
}

// ─── Login ────────────────────────────────────────────────────

@Composable
fun LoginScreen(app: ObscuraApp, onClient: (ObscuraClient) -> Unit) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Obscura", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))

        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = username, onValueChange = { username = it },
            label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Password (12+ chars)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
        )
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    if (username.isBlank() || password.length < 12) return@Button
                    scope.launch {
                        status = "Registering..."
                        try {
                            val c = withContext(Dispatchers.IO) {
                                val c = app.createClient(username)
                                c.register(username, password)
                                c.connect()
                                defineModels(c)
                                c
                            }
                            app.client = c
                            app.saveSession()
                            onClient(c)
                        } catch (e: Exception) {
                            status = e.message ?: "Registration failed"
                        }
                    }
                }
            ) { Text("Register") }

            OutlinedButton(
                onClick = {
                    if (username.isBlank() || password.length < 12) return@OutlinedButton
                    scope.launch {
                        status = "Logging in..."
                        try {
                            val c = withContext(Dispatchers.IO) {
                                val c = app.createClient(username)
                                val result = c.login(username, password)
                                when (result.scenario) {
                                    LoginScenario.EXISTING_DEVICE -> {
                                        c.connect()
                                        defineModels(c)
                                    }
                                    LoginScenario.NEW_DEVICE, LoginScenario.DEVICE_MISMATCH -> {
                                        // New device — needs approval from existing device
                                        c.loginAndProvision(username, password)
                                        c.connect()
                                        defineModels(c)
                                        // authState is PENDING_APPROVAL — UI will switch to LinkApprovalScreen
                                    }
                                    LoginScenario.INVALID_CREDENTIALS -> throw Exception("Wrong password")
                                    LoginScenario.USER_NOT_FOUND -> throw Exception("User not found")
                                }
                                c
                            }
                            app.client = c
                            app.saveSession()
                            onClient(c)
                        } catch (e: Exception) {
                            status = e.message ?: "Login failed"
                        }
                    }
                }
            ) { Text("Login") }
        }
    }
}

// ─── Device Approval (Waiting) ────────────────────────────────

@Composable
fun LinkApprovalScreen(client: ObscuraClient) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val linkCode = remember { client.generateLinkCode() }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Approve This Device", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "Open Obscura on your other device and paste this code to approve.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))

        // Show the link code (in production this would be a QR code)
        Surface(
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                linkCode,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3
            )
        }

        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            clipboardManager.setText(AnnotatedString(linkCode))
            Toast.makeText(context, "Link code copied", Toast.LENGTH_SHORT).show()
        }) { Text("Copy Code") }

        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(8.dp))
        Text("Waiting for approval...", style = MaterialTheme.typography.bodySmall)
    }
}

// ─── Main App (Authenticated) ─────────────────────────────────

@Composable
fun AppScreen(client: ObscuraClient, app: ObscuraApp) {
    val scope = rememberCoroutineScope()
    val friends by client.friendList.collectAsState()
    val pending by client.pendingRequests.collectAsState()
    val connectionState by client.connectionState.collectAsState()

    var tab by remember { mutableStateOf("feed") }

    // Connect if needed
    LaunchedEffect(Unit) {
        if (client.connectionState.value != ConnectionState.CONNECTED) {
            withContext(Dispatchers.IO) { try { client.connect() } catch (_: Exception) {} }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Top bar
        Surface(tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(client.username ?: "", style = MaterialTheme.typography.titleMedium)
                Text(
                    connectionState.name.lowercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (connectionState == ConnectionState.CONNECTED)
                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }
        }

        // Content
        Box(Modifier.weight(1f)) {
            when (tab) {
                "friends" -> FriendsTab(client)
                "chat" -> ChatTab(client)
                "stories" -> StoriesTab(client)
                "profile" -> ProfileTab(client)
                "settings" -> SettingsTab(client, app)
            }
        }

        // Bottom nav
        NavigationBar {
            NavigationBarItem(selected = tab == "friends", onClick = { tab = "friends" },
                icon = {}, label = { Text("Friends") })
            NavigationBarItem(selected = tab == "chat", onClick = { tab = "chat" },
                icon = {}, label = { Text("Chat") })
            NavigationBarItem(selected = tab == "stories", onClick = { tab = "stories" },
                icon = {}, label = { Text("Stories") })
            NavigationBarItem(selected = tab == "profile", onClick = { tab = "profile" },
                icon = {}, label = { Text("Profile") })
            NavigationBarItem(selected = tab == "settings", onClick = { tab = "settings" },
                icon = {}, label = { Text("Settings") })
        }
    }
}

// ─── Stories Tab (typed ORM, 24h TTL, interop with iOS) ───────

@Composable
fun StoriesTab(client: ObscuraClient) {
    val scope = rememberCoroutineScope()
    var storyText by remember { mutableStateOf("") }

    val storyModel = remember(client) {
        TypedModel(client.orm.model("story"), Story.serializer())
    }
    val stories by storyModel.observe().collectAsState(emptyList())

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Stories", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = storyText, onValueChange = { storyText = it },
                modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Share a story...") }
            )
            Button(
                enabled = storyText.isNotBlank(),
                onClick = {
                    val text = storyText; storyText = ""
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            storyModel.create(Story(
                                content = text,
                                authorUsername = client.username ?: ""
                            ))
                        }
                    }
                }
            ) { Text("Post") }
        }

        Spacer(Modifier.height(12.dp))
        Text("Disappears after 24 hours", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(8.dp))

        val sorted = stories.sortedByDescending { it.timestamp }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sorted, key = { it.id }) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(entry.value.authorUsername, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(entry.value.content)
                    }
                }
            }
        }
    }
}

// ─── Profile Tab (typed ORM, syncs to friends) ───────────────

@Composable
fun ProfileTab(client: ObscuraClient) {
    val scope = rememberCoroutineScope()

    val profileModel = remember(client) {
        TypedModel(client.orm.model("profile"), Profile.serializer())
    }
    val profiles by profileModel.observe().collectAsState(emptyList())

    val myProfile = profiles.firstOrNull { it.authorDeviceId == client.deviceId }
    var displayName by remember(myProfile) {
        mutableStateOf(myProfile?.value?.displayName ?: client.username ?: "")
    }
    var bio by remember(myProfile) {
        mutableStateOf(myProfile?.value?.bio ?: "")
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Profile", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = displayName, onValueChange = { displayName = it },
            label = { Text("Display Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = bio, onValueChange = { bio = it },
            label = { Text("Bio") }, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            scope.launch {
                withContext(Dispatchers.IO) {
                    profileModel.upsert(
                        "profile_${client.userId}",
                        Profile(displayName = displayName, bio = bio)
                    )
                }
            }
        }) { Text("Save") }

        val friendProfiles = profiles.filter { it.authorDeviceId != client.deviceId }
        if (friendProfiles.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Friend Profiles", style = MaterialTheme.typography.titleMedium)
            friendProfiles.forEach { p ->
                Text("${p.value.displayName} — ${p.value.bio ?: ""}", modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

// ─── Friends Tab ──────────────────────────────────────────────

@Composable
fun FriendsTab(client: ObscuraClient) {
    val scope = rememberCoroutineScope()
    val friends by client.friendList.collectAsState()
    val pending by client.pendingRequests.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var friendCode by remember { mutableStateOf("") }
    var linkCode by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    val myCode = remember(client.userId, client.username) {
        if (client.userId != null && client.username != null)
            FriendCode.encode(client.userId!!, client.username!!) else ""
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Friends", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
        }

        // My friend code
        OutlinedButton(onClick = {
            clipboardManager.setText(AnnotatedString(myCode))
            Toast.makeText(context, "Friend code copied", Toast.LENGTH_SHORT).show()
        }) { Text("Copy My Friend Code") }

        Spacer(Modifier.height(8.dp))

        // Add friend
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = friendCode, onValueChange = { friendCode = it },
                modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Paste friend code") }
            )
            Button(onClick = {
                scope.launch {
                    try {
                        val decoded = FriendCode.decode(friendCode)
                        withContext(Dispatchers.IO) { client.befriend(decoded.userId, decoded.username) }
                        friendCode = ""
                        status = "Request sent to ${decoded.username}"
                    } catch (e: Exception) { status = e.message ?: "Failed" }
                }
            }) { Text("Add") }
        }

        Spacer(Modifier.height(8.dp))

        // Approve a new device (existing device enters link code here)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = linkCode, onValueChange = { linkCode = it },
                modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Paste device link code") }
            )
            Button(onClick = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { client.validateAndApproveLink(linkCode) }
                        linkCode = ""
                        status = "Device approved"
                    } catch (e: Exception) { status = e.message ?: "Failed" }
                }
            }) { Text("Approve") }
        }

        Spacer(Modifier.height(16.dp))

        // Outgoing pending
        val sent = friends.filter { it.status == FriendStatus.PENDING_SENT }
        sent.forEach { f ->
            Text("${f.username} (pending)", color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 4.dp))
        }

        // Incoming requests
        pending.forEach { req ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(req.username)
                Button(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { client.acceptFriend(req.userId, req.username) }
                    }
                }) { Text("Accept") }
            }
        }

        // Accepted friends
        val accepted = friends.filter { it.status == FriendStatus.ACCEPTED }
        if (accepted.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            accepted.forEach { f ->
                Text(f.username, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

// ─── Chat Tab (ORM DirectMessage, reactive) ──────────────────

@Composable
fun ChatTab(client: ObscuraClient) {
    val scope = rememberCoroutineScope()
    val friends by client.friendList.collectAsState()
    var selectedFriend by remember { mutableStateOf<String?>(null) }
    var messageText by remember { mutableStateOf("") }

    val accepted = friends.filter { it.status == FriendStatus.ACCEPTED }

    // Observe ALL direct messages reactively from the ORM
    val messages = remember(client) {
        TypedModel(client.orm.model("directMessage"), DirectMessage.serializer())
    }
    val allMessages by messages.observe().collectAsState(emptyList())

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Chat", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        if (selectedFriend == null) {
            if (accepted.isEmpty()) {
                Text("No friends yet. Add someone in the Friends tab.", style = MaterialTheme.typography.bodyMedium)
            }
            accepted.forEach { f ->
                val convId = canonicalConversationId(client.userId ?: "", f.userId)
                val lastMsg = allMessages
                    .filter { it.value.conversationId == convId }
                    .maxByOrNull { it.timestamp }

                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        .clickable { selectedFriend = f.username },
                    tonalElevation = 1.dp,
                    shape = MaterialTheme.shapes.small
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(f.username, style = MaterialTheme.typography.titleSmall)
                        if (lastMsg != null) {
                            Text(
                                lastMsg.value.content.take(40),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        } else {
            val friend = accepted.find { it.username == selectedFriend }
            val friendId = friend?.userId
            val convId = if (friendId != null) canonicalConversationId(client.userId ?: "", friendId) else ""
            val dmModel = client.orm.model("directMessage")

            val conversationMsgs = allMessages
                .filter { it.value.conversationId == convId }
                .sortedBy { it.timestamp }

            val typers by dmModel.observeTyping(convId).collectAsState(emptyList())

            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selectedFriend = null }) { Text("<") }
                Text(selectedFriend!!, style = MaterialTheme.typography.titleLarge)
            }

            // Messages — grows from top, scrolls down
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Bottom
            ) {
                items(conversationMsgs, key = { it.id }) { msg ->
                    val isMe = msg.value.senderUsername == client.username
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            tonalElevation = if (isMe) 4.dp else 1.dp,
                            shape = MaterialTheme.shapes.medium,
                            color = if (isMe) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(msg.value.content, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                    }
                }
                // Typing bubble appears at the end (where next message would be)
                if (typers.isNotEmpty()) {
                    item(key = "__typing__") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            TypingBubble()
                        }
                    }
                }
            }

            // Input — pinned to bottom
            Divider()
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { newText ->
                        messageText = newText
                        if (newText.isNotBlank()) {
                            scope.launch { withContext(Dispatchers.IO) { dmModel.typing(convId) } }
                        }
                    },
                    modifier = Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("Message") }
                )
                Button(
                    enabled = messageText.isNotBlank(),
                    onClick = {
                        val text = messageText
                        messageText = ""
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                dmModel.stopTyping(convId)
                                messages.create(DirectMessage(
                                    conversationId = convId,
                                    content = text,
                                    senderUsername = client.username ?: ""
                                ))
                            }
                        }
                    }
                ) { Text("Send") }
            }
        }
    }
}

// ─── Typing Bubble (animated three dots) ──────────────────────

@Composable
fun TypingBubble() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")

    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(3) { index ->
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, easing = LinearEasing, delayMillis = index * 200),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "dot$index"
                )
                Surface(
                    modifier = Modifier.size(8.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = alpha)
                ) {}
            }
        }
    }
}

// ─── Settings Tab (typed ORM, private) ────────────────────────

@Composable
fun SettingsTab(client: ObscuraClient, app: ObscuraApp) {
    val scope = rememberCoroutineScope()

    val settingsModel = remember(client) {
        TypedModel(client.orm.model("settings"), AppSettings.serializer())
    }
    val settingsEntries by settingsModel.observe().collectAsState(emptyList())

    val current = settingsEntries.firstOrNull()?.value
    val currentTheme = current?.theme ?: "system"
    val notificationsEnabled = current?.notificationsEnabled ?: true

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        Text("Theme", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("light", "dark", "system").forEach { theme ->
                FilterChip(
                    selected = currentTheme == theme,
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                settingsModel.upsert("app_settings", AppSettings(
                                    theme = theme,
                                    notificationsEnabled = notificationsEnabled
                                ))
                            }
                        }
                    },
                    label = { Text(theme) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notifications", modifier = Modifier.weight(1f))
            Switch(
                checked = notificationsEnabled,
                onCheckedChange = { enabled ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            settingsModel.upsert("app_settings", AppSettings(
                                theme = currentTheme,
                                notificationsEnabled = enabled
                            ))
                        }
                    }
                }
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Settings sync to your other devices automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )

        Spacer(Modifier.height(32.dp))

        OutlinedButton(onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { client.logout() }
                app.clearSession()
            }
        }) { Text("Logout") }
    }
}

// ─── ORM Schema Definition ───────────────────────────────────

/** Define app models — delegates to ObscuraApp.defineModels() for single source of truth. */
private suspend fun defineModels(client: ObscuraClient) = ObscuraApp.defineModels(client)
