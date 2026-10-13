package com.replymate.ai

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.replymate.ai.data.AppSettings
import com.replymate.ai.data.ReplyMode
import com.replymate.ai.data.SecretStore
import com.replymate.ai.data.SettingsRepository
import com.replymate.ai.logic.GeminiClient
import com.replymate.ai.logic.PendingReply
import com.replymate.ai.logic.ReplyQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF090F1E)
private val Panel = Color(0xFF141E32)
private val PanelSoft = Color(0xFF1C2940)
private val Mint = Color(0xFF64E8D5)
private val Blue = Color(0xFF7A94FF)
private val Muted = Color(0xFF9FAEC8)
private val Warm = Color(0xFFFFCA82)

class MainActivity : ComponentActivity() {
    private var reviewOpenSequence by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra("open_reviews", false) == true) reviewOpenSequence++
        setContent { ReplyMateApp(reviewOpenSequence) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_reviews", false)) reviewOpenSequence++
    }
}

private enum class Tab(val title: String) { HOME("Home"), RULES("Rules"), REVIEWS("Reviews"), SETTINGS("Settings") }

@Composable
private fun ReplyMateApp(reviewOpenSequence: Int) {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx.applicationContext) }
    val secrets = remember { SecretStore(ctx.applicationContext) }
    val scope = rememberCoroutineScope()
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val reviews by ReplyQueue.items.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.HOME) }
    LaunchedEffect(reviewOpenSequence) { if (reviewOpenSequence > 0) tab = Tab.REVIEWS }
    var hasKey by remember { mutableStateOf(secrets.hasKey()) }
    var listenerGranted by remember { mutableStateOf(false) }
    var notificationGranted by remember { mutableStateOf(false) }
    var askAuto by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current

    fun refresh() {
        listenerGranted = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        notificationGranted = Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        hasKey = secrets.hasKey()
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.lifecycle.addObserver(observer)
        refresh()
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Mint, secondary = Blue, background = Ink, surface = Panel,
        onPrimary = Ink, onSurface = Color.White, onBackground = Color.White
    )) {
        Scaffold(
            containerColor = Ink,
            bottomBar = {
                NavigationBar(containerColor = Panel, tonalElevation = 0.dp) {
                    val icons = listOf(Icons.Filled.Home, Icons.Filled.Tune, Icons.Filled.Forum, Icons.Filled.Settings)
                    Tab.entries.forEachIndexed { i, item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { BadgedBox(badge = {
                                if (item == Tab.REVIEWS && reviews.isNotEmpty()) Badge { Text(reviews.size.toString()) }
                            }) { Icon(icons[i], contentDescription = item.title) } },
                            label = { Text(item.title, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Ink, selectedTextColor = Mint,
                                indicatorColor = Mint, unselectedIconColor = Muted, unselectedTextColor = Muted)
                        )
                    }
                }
            }
        ) { insets ->
            Column(modifier = Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                .padding(horizontal = 19.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Header(tab, reviews.size)
                when (tab) {
                    Tab.HOME -> HomeScreen(settings, listenerGranted, notificationGranted, hasKey, reviews.size,
                        onEnable = { checked -> scope.launch { repo.setEnabled(checked) } },
                        onManual = { scope.launch { repo.setMode(ReplyMode.MANUAL) } },
                        onAuto = { askAuto = true },
                        onListener = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                        onNotification = {
                            if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        onConfigureKey = { tab = Tab.SETTINGS },
                        onSeeReviews = { tab = Tab.REVIEWS })
                    Tab.RULES -> RulesScreen(settings,
                        onAllowAll = { scope.launch { repo.setAllowAll(it) } },
                        onAllowed = { scope.launch { repo.setAllowed(it) } },
                        onBlocked = { scope.launch { repo.setBlocked(it) } },
                        onGroups = { scope.launch { repo.setGroups(it) } },
                        onCooldown = { scope.launch { repo.setCooldown(it) } },
                        onLimit = { scope.launch { repo.setDailyLimit(it) } })
                    Tab.REVIEWS -> ReviewScreen(reviews,
                        onApprove = { draft, message ->
                            val worked = ReplyQueue.approve(ctx, draft.id, message)
                            if (worked) scope.launch { repo.countSent() }
                            worked
                        }, onDismiss = { ReplyQueue.discard(it) })
                    Tab.SETTINGS -> SettingsScreen(settings, hasKey, secrets,
                        onHasKey = { hasKey = secrets.hasKey() },
                        onPersonality = { scope.launch { repo.setPersonality(it) } })
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        if (askAuto) AlertDialog(
            onDismissRequest = { askAuto = false },
            title = { Text("Enable automatic replies?") },
            text = { Text("ReplyMate may send messages without review. Only use this with consent and trusted contacts. WhatsApp can restrict unofficial automation. Daily limits and cooldowns apply.") },
            confirmButton = { TextButton(onClick = {
                scope.launch { repo.setMode(ReplyMode.AUTOMATIC) }
                askAuto = false
            }) { Text("I understand") } },
            dismissButton = { TextButton(onClick = { askAuto = false }) { Text("Stay manual") } }
        )
    }
}

@Composable
private fun Header(tab: Tab, count: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(Mint), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Quickreply, null, tint = Ink, modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("ReplyMate", fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
            Text("YOUR AI MESSAGE COMPANION", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
        }
        Surface(color = PanelSoft, shape = CircleShape) {
            Icon(Icons.Filled.AutoAwesome, null, tint = Mint, modifier = Modifier.padding(12.dp))
        }
    }
    Spacer(Modifier.height(3.dp))
    Column {
        Text(when (tab) {
            Tab.HOME -> "Overview"
            Tab.RULES -> "Your rules"
            Tab.REVIEWS -> "Review inbox${if (count > 0) " · $count" else ""}"
            Tab.SETTINGS -> "AI settings"
        }, fontWeight = FontWeight.Bold, fontSize = 29.sp)
        Text(when (tab) {
            Tab.HOME -> "Stay connected, on your terms."
            Tab.RULES -> "Choose exactly who gets an AI reply."
            Tab.REVIEWS -> "Nothing is sent without your approval."
            Tab.SETTINGS -> "Your AI key stays on this device."
        }, color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun HomeScreen(s: AppSettings, access: Boolean, notif: Boolean, key: Boolean, reviews: Int,
                       onEnable: (Boolean) -> Unit, onManual: () -> Unit, onAuto: () -> Unit,
                       onListener: () -> Unit, onNotification: () -> Unit,
                       onConfigureKey: () -> Unit, onSeeReviews: () -> Unit) {
    Surface(shape = RoundedCornerShape(26.dp), color = Panel) {
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF213D54), Color(0xFF17223B), Panel)))
            .padding(22.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = if (s.enabled) Mint.copy(alpha = 0.17f) else Color.White.copy(alpha = 0.08f), shape = RoundedCornerShape(50)) {
                    Text(if (s.enabled) "● ASSISTANT ACTIVE" else "● ASSISTANT PAUSED",
                        color = if (s.enabled) Mint else Muted, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.Bolt, null, tint = Mint, modifier = Modifier.size(29.dp))
            }
            Text("Thoughtful replies.\nYour decision.", fontWeight = FontWeight.Bold, fontSize = 30.sp,
                lineHeight = 36.sp)
            Text("Compose intelligent WhatsApp replies using Gemini 3.5 Flash-Lite.", color = Color(0xFFC1CFE0), fontSize = 13.sp)
            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Enable ReplyMate", fontWeight = FontWeight.SemiBold)
                    Text("Master switch", color = Muted, fontSize = 12.sp)
                }
                Switch(checked = s.enabled, enabled = access && key && (notif || s.mode == ReplyMode.AUTOMATIC),
                    onCheckedChange = onEnable)
            }
        }
    }
    if (!key || !access || !notif) {
        Block("Complete setup", "ReplyMate needs your API key and permission to read WhatsApp notifications.") {
            if (!key) SetupStep("1", "Connect Gemini API", "Add your private API key", onConfigureKey)
            if (!access) SetupStep("2", "Notification access", "Read WhatsApp messages and their reply actions", onListener)
            if (!notif) SetupStep("3", "Review notifications", "Let ReplyMate show draft alerts", onNotification)
        }
    }
    Block("Reply mode", "Choose how you want AI messages handled.") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeButton("Manual", "Approve each reply", Icons.Filled.TaskAlt, s.mode == ReplyMode.MANUAL, Modifier.weight(1f), onManual)
            ModeButton("Automatic", "Replies instantly", Icons.Filled.AutoMode, s.mode == ReplyMode.AUTOMATIC, Modifier.weight(1f), onAuto)
        }
        if (s.mode == ReplyMode.AUTOMATIC) Text("Automatic mode is experimental. Use trusted contacts only.", color = Warm, fontSize = 12.sp)
        if (!s.allowAll && s.allowed.isBlank()) Text("No approved contact names yet. Add names in Rules before expecting replies.", color = Warm, fontSize = 12.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Stat("${s.generated}", "Drafts today", Mint, Modifier.weight(1f))
        Stat("${s.sent}", "Sent today", Blue, Modifier.weight(1f))
        Stat("${s.skipped}", "Errors today", Warm, Modifier.weight(1f))
    }
    if (reviews > 0) ElevatedButton(onClick = onSeeReviews, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.elevatedButtonColors(containerColor = Mint, contentColor = Ink)) {
        Icon(Icons.Filled.Markunread, null); Spacer(Modifier.width(9.dp))
        Text("Review $reviews pending ${if (reviews == 1) "reply" else "replies"}")
    }
    InfoText("WhatsApp notifications may not include a reply action. Android, phone manufacturers, and WhatsApp can affect delivery.")
}

@Composable
private fun RulesScreen(s: AppSettings, onAllowAll: (Boolean) -> Unit, onAllowed: (String) -> Unit,
                        onBlocked: (String) -> Unit, onGroups: (Boolean) -> Unit,
                        onCooldown: (Int) -> Unit, onLimit: (Int) -> Unit) {
    Block("Contact access", "Exact notification names only. One name per line.") {
        ControlRow("Allow any contact", "OFF is safer: approved names only", s.allowAll, onAllowAll)
        var allowed by remember(s.allowed) { mutableStateOf(s.allowed) }
        OutlinedTextField(value = allowed, onValueChange = { allowed = it; onAllowed(it) },
            label = { Text("Approved contacts") }, placeholder = { Text("Alex\nMom\n+94771234567") },
            minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
        Text("A displayed WhatsApp sender name is not a verified identity; identical names can match.", color = Warm, fontSize = 11.sp)
        var blocked by remember(s.blocked) { mutableStateOf(s.blocked) }
        OutlinedTextField(value = blocked, onValueChange = { blocked = it; onBlocked(it) },
            label = { Text("Never reply to these names") }, placeholder = { Text("Private Contact") },
            minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
        ControlRow("Include group chats", "Not recommended; off by default", s.includeGroups, onGroups)
    }
    Block("Safety limits", "Prevent bursts and accidental repetitive replies.") {
        Text("Minimum time between replies to a contact: ${s.cooldownMinutes} minutes", fontSize = 13.sp)
        Slider(value = s.cooldownMinutes.toFloat(), onValueChange = { onCooldown(it.toInt()) }, valueRange = 1f..120f, steps = 118)
        Text("Automatic send limit per day: ${s.dailyLimit}", fontSize = 13.sp)
        Slider(value = s.dailyLimit.toFloat(), onValueChange = { onLimit(it.toInt()) }, valueRange = 1f..50f, steps = 48)
        InfoText("Cooldowns reset if Android restarts the service. Daily sent counters persist on the phone.")
    }
}

@Composable
private fun ReviewScreen(reviews: List<PendingReply>, onApprove: (PendingReply, String) -> Boolean, onDismiss: (String) -> Unit) {
    if (reviews.isEmpty()) {
        Block("All caught up", "New AI drafts will appear here while manual mode is active.") {
            Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.MarkEmailRead, null, tint = Mint, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp)); Text("No pending replies", fontWeight = FontWeight.SemiBold)
                Text("Your messages remain under your control", color = Muted, fontSize = 12.sp)
            }
        }
    }
    reviews.forEach { draft ->
        Block(draft.sender, "Suggested response • Expires after 5 minutes") {
            Text("THEIR MESSAGE", fontSize = 10.sp, color = Muted, letterSpacing = 1.sp)
            Text(draft.incoming.take(350), fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            var response by remember(draft.id) { mutableStateOf(draft.draft) }
            var outcome by remember(draft.id) { mutableStateOf("") }
            OutlinedTextField(value = response, onValueChange = { response = it.take(700) },
                label = { Text("Edit AI reply before sending") }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                maxLines = 6, shape = RoundedCornerShape(15.dp))
            if (outcome.isNotEmpty()) Text(outcome, color = Warm, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onDismiss(draft.id) }, modifier = Modifier.weight(1f)) { Text("Discard") }
                Button(onClick = {
                    if (!onApprove(draft, response)) outcome = "Reply action expired or unavailable; open WhatsApp to respond manually."
                }, enabled = response.isNotBlank(), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Send, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text("Approve")
                }
            }
        }
    }
    InfoText("Reviews live only in app memory. Android can clear drafts if it stops the app process. No personal messages are saved to storage.")
}

@Composable
private fun SettingsScreen(s: AppSettings, hasKey: Boolean, secrets: SecretStore,
                           onHasKey: () -> Unit, onPersonality: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var keyInput by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf("") }
    Block("Gemini connection", "Google Gemini 3.5 Flash-Lite") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, tint = Mint)
            Spacer(Modifier.width(9.dp))
            Text(if (hasKey) "API key securely saved" else "No API key saved", fontSize = 13.sp,
                color = if (hasKey) Mint else Warm, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(value = keyInput, onValueChange = { keyInput = it; saveMessage = "" },
            label = { Text(if (hasKey) "Replace API key" else "Gemini API key") },
            visualTransformation = if (reveal) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { reveal = !reveal }) {
                Icon(if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "Toggle key visibility")
            } }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp), singleLine = true)
        if (saveMessage.isNotBlank()) Text(saveMessage, color = Mint, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                val saved = secrets.save(keyInput)
                saveMessage = if (saved) "Key saved to Android Keystore." else "Could not encrypt key on this device."
                if (saved) { keyInput = ""; onHasKey() }
            }, enabled = keyInput.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Save key") }
            OutlinedButton(onClick = { secrets.clear(); onHasKey(); saveMessage = "API key removed." }) { Text("Remove") }
        }
        Text("Create a key at aistudio.google.com/api-keys. Never commit it to GitHub or share it.", fontSize = 12.sp, color = Muted)
    }
    Block("Assistant personality", "Tell Gemini how to speak on your behalf.") {
        var personality by remember(s.personality) { mutableStateOf(s.personality) }
        OutlinedTextField(value = personality, onValueChange = { personality = it.take(600); onPersonality(personality) },
            modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8,
            label = { Text("AI instructions") }, shape = RoundedCornerShape(15.dp))
    }
    Block("Try the AI", "Generate a local preview. This does not message anyone.") {
        var input by remember { mutableStateOf("Hi, are you available today?") }
        var result by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        OutlinedTextField(value = input, onValueChange = { input = it.take(1000) },
            modifier = Modifier.fillMaxWidth(), label = { Text("Test incoming message") },
            shape = RoundedCornerShape(15.dp), maxLines = 4)
        Button(onClick = {
            val key = secrets.read()
            if (key.isNullOrBlank()) { result = "Save your Gemini API key first."; return@Button }
            busy = true; result = ""
            scope.launch {
                result = withContext(Dispatchers.IO) {
                    runCatching { GeminiClient.draft(key, "Demo contact", input, s.personality) }
                        .getOrElse { "Test failed: ${it.message ?: "Network/API problem"}" }
                }
                busy = false
            }
        }, enabled = !busy && input.isNotBlank()) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Science, null)
            Spacer(Modifier.width(8.dp)); Text(if (busy) "Generating" else "Generate test reply")
        }
        AnimatedVisibility(result.isNotBlank()) {
            Surface(color = PanelSoft, shape = RoundedCornerShape(14.dp)) {
                Text(result, fontSize = 13.sp, modifier = Modifier.padding(15.dp))
            }
        }
    }
    InfoText("Your API key is encrypted at rest. In this client-only design, it is decrypted briefly to call Google's HTTPS API. Message text sent to Gemini is subject to Google's terms and data-processing policies.")
}

@Composable
private fun Block(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(23.dp), color = Panel, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.045f))) {
        Column(Modifier.fillMaxWidth().padding(19.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Column {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, fontSize = 12.sp, color = Muted)
            }
            content()
        }
    }
}

@Composable
private fun SetupStep(number: String, title: String, desc: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = PanelSoft, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).background(Mint.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Text(number, color = Mint, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(desc, fontSize = 11.sp, color = Muted, maxLines = 2) }
            Icon(Icons.Filled.ChevronRight, null, tint = Mint)
        }
    }
}

@Composable
private fun ModeButton(title: String, desc: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
                       chosen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(16.dp),
        color = if (chosen) Mint.copy(alpha = 0.12f) else PanelSoft,
        border = BorderStroke(1.dp, if (chosen) Mint else Color.Transparent)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = if (chosen) Mint else Muted, modifier = Modifier.size(24.dp))
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(desc, color = Muted, fontSize = 10.sp, maxLines = 2)
        }
    }
}

@Composable
private fun Stat(value: String, title: String, color: Color, modifier: Modifier) {
    Surface(modifier = modifier, color = Panel, shape = RoundedCornerShape(17.dp)) {
        Column(Modifier.padding(vertical = 17.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = color, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text(title, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ControlRow(title: String, desc: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(desc, color = Muted, fontSize = 11.sp) }
        Spacer(Modifier.width(10.dp))
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun InfoText(text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Info, null, tint = Warm, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}
