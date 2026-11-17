package com.utomat.flowlite

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncManager.request(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { App() }
        }
    }
}

/** Model download state per engine, kept outside composition so it survives screen changes. */
object Downloads {
    data class S(val done: Long = 0, val total: Long = 0, val running: Boolean = false, val error: String? = null)
    val state = MutableStateFlow<Map<String, S>>(emptyMap())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(ctx: Context, engine: String) {
        if (state.value[engine]?.running == true) return
        val app = ctx.applicationContext
        scope.launch {
            fun set(s: S) { state.value = state.value + (engine to s) }
            set(S(running = true))
            try {
                Models.download(app, Models.forEngine(engine)) { d, t -> set(S(d, t, running = true)) }
                set(S())
            } catch (e: Exception) {
                set(S(error = e.message ?: "Download failed"))
            }
        }
    }
}

private enum class Tab(val label: String) { Setup("Setup"), History("History"), Dictionary("Dictionary"), Settings("Settings") }

@Composable
private fun App() {
    var tab by remember { mutableStateOf(Tab.Setup) }
    Scaffold(bottomBar = {
        NavigationBar {
            Tab.entries.forEach { t ->
                NavigationBarItem(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) }, icon = {
                    Icon(when (t) { Tab.Setup -> Icons.Filled.Tune; Tab.History -> Icons.Filled.History
                        Tab.Dictionary -> Icons.Filled.Book; Tab.Settings -> Icons.Filled.Settings }, null)
                })
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
            when (tab) {
                Tab.Setup -> SetupScreen()
                Tab.History -> HistoryScreen()
                Tab.Dictionary -> DictionaryScreen()
                Tab.Settings -> SettingsScreen()
            }
        }
    }
}

private fun hasMic(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
private fun hasOverlay(ctx: Context) = Settings.canDrawOverlays(ctx)
private fun hasAccessibility(ctx: Context): Boolean {
    val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == ComponentName(ctx, FocusService::class.java) }
}
private fun isUnrestricted(ctx: Context) =
    (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

@Composable
private fun SetupScreen() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val prefs = remember { Prefs(ctx) }
    val mic = remember(tick) { hasMic(ctx) }
    val overlay = remember(tick) { hasOverlay(ctx) }
    val access = remember(tick) { hasAccessibility(ctx) }
    val battery = remember(tick) { isUnrestricted(ctx) }
    val modelReady = remember(tick) { Models.forEngine(prefs.engine).all { it.isReady(ctx) } }
    LaunchedEffect(mic, overlay) { if (mic && overlay) DictationService.start(ctx) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("FlowLite", style = MaterialTheme.typography.headlineMedium)
        Text("Grant these once. The bubble appears when you tap a text field. Tap it to start and tap again to finish, or hold it and release.")
        Step("Microphone", "Records your voice. Audio stays on the phone.", mic) {
            micLauncher.launch(buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray())
        }
        Step("Display over other apps", "Shows the floating bubble.", overlay) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        }
        // Android 13+ greys out Accessibility for apps installed from a downloaded file ("Restricted setting").
        // There is no API to check this flag, so the step counts as done once Accessibility is on.
        if (Build.VERSION.SDK_INT >= 33) {
            Step("Allow restricted settings", "Android blocks Accessibility for apps installed from a download. " +
                "Tap Open, then the three-dot menu at the top right, then Allow restricted settings. " +
                "No menu? Go to the next step first and tap FlowLite once, then come back.", access) {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }
        }
        Step("Accessibility", "Finds the focused text field and inserts the text. Turn on FlowLite under Installed apps. " +
            "Greyed out? Do the step above first.", access) {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        Step("Battery: Unrestricted", "Samsung stops background apps. Open Settings > Apps > FlowLite > Battery and pick Unrestricted.", battery) {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        }
        Step("Speech model", "Downloads once. Pick the engine in Settings.", modelReady) { Downloads.start(ctx, prefs.engine) }
        EngineDownload(prefs.engine)
    }
}

@Composable
private fun Step(title: String, body: String, done: Boolean, action: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (done) Icons.Filled.CheckCircle else Icons.Outlined.Circle, if (done) "Done" else "Not done",
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
            if (!done) OutlinedButton(onClick = action) { Text("Open") }
        }
    }
}

@Composable
private fun EngineDownload(engine: String) {
    val ctx = LocalContext.current
    val all by Downloads.state.collectAsState()
    val s = all[engine] ?: Downloads.S()
    val specs = Models.forEngine(engine)
    val ready = specs.all { it.isReady(ctx) } && !s.running
    val mb = specs.sumOf { it.totalBytes } / 1_000_000
    when {
        ready -> Text("Model ready ($mb MB)", style = MaterialTheme.typography.bodySmall)
        s.running -> Column {
            LinearProgressIndicator(progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f }, Modifier.fillMaxWidth())
            Text("${s.done / 1_000_000} of ${s.total / 1_000_000} MB", style = MaterialTheme.typography.bodySmall)
        }
        else -> Column {
            s.error?.let { Text("Download failed: $it", color = MaterialTheme.colorScheme.error) }
            Button(onClick = { Downloads.start(ctx, engine) }) { Text(if (s.error != null) "Retry" else "Download $mb MB") }
        }
    }
}

@Composable
private fun HistoryScreen() {
    val ctx = LocalContext.current
    val db = remember { FlowDb.get(ctx) }
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    val items by remember(q) { db.transcripts().search(q) }.collectAsState(emptyList())
    Column {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), label = { Text("Search") }, singleLine = true)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth()) {
                    var showRaw by remember { mutableStateOf(false) }
                    val shown = if (showRaw) t.rawText else t.text
                    Column(Modifier.padding(12.dp)) {
                        Text(shown)
                        if (t.rawText.isNotBlank() && t.rawText != t.text)
                            TextButton({ showRaw = !showRaw }) { Text(if (showRaw) "Show cleaned text" else "Show original") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AssistChip(onClick = {}, label = { Text(if (t.device == "linux") "Linux" else "Android") })
                            Spacer(Modifier.width(8.dp))
                            Text(DateUtils.getRelativeTimeSpanString(t.createdAt).toString() +
                                (if (t.transcribeMs > 0) " · %.1f s · %s".format(t.transcribeMs / 1000.0, t.model ?: "") else ""),
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("FlowLite", shown))
                            }) { Icon(Icons.Filled.ContentCopy, "Copy") }
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    db.transcripts().delete(t.id)
                                    SyncManager.request(ctx)
                                }
                            }) { Icon(Icons.Filled.Delete, "Delete") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DictionaryScreen() {
    val ctx = LocalContext.current
    val db = remember { FlowDb.get(ctx) }
    val scope = rememberCoroutineScope()
    val words by remember { db.dictionary().observe() }.collectAsState(emptyList())
    var word by remember { mutableStateOf("") }
    var repl by remember { mutableStateOf("") }
    Column(Modifier.padding(vertical = 8.dp)) {
        Text("Words FlowLite should spell your way. Add a replacement to swap a spoken phrase for any text, such as \"my email\" for your address or a full signature.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(word, { word = it }, Modifier.fillMaxWidth(), label = { Text("Word or phrase") }, singleLine = true)
        OutlinedTextField(repl, { repl = it }, Modifier.fillMaxWidth(), label = { Text("Replace with (optional, can be several lines)") }, minLines = 1, maxLines = 6)
        Button(enabled = word.isNotBlank(), onClick = {
            val w = word.trim(); val r = repl.trim().ifBlank { null }
            scope.launch(Dispatchers.IO) {
                db.dictionary().upsert(DictWord(SyncRules.dictionaryId(w), w, r, System.currentTimeMillis()))
                SyncManager.request(ctx)
            }
            word = ""; repl = ""
        }, modifier = Modifier.padding(vertical = 8.dp)) { Text("Add") }
        LazyColumn {
            items(words, key = { it.id }) { w ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(w.word + (w.replacement?.let { " → $it" } ?: ""), Modifier.weight(1f))
                    IconButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            db.dictionary().upsert(w.copy(deleted = true, dirty = true, updatedAt = System.currentTimeMillis()))
                            SyncManager.request(ctx)
                        }
                    }) { Icon(Icons.Filled.Delete, "Delete ${w.word}") }
                }
            }
        }
    }
}

/** Parakeet v3 languages (Greek included) plus auto detect. */
private val LANGUAGES = listOf("auto" to "Auto detect", "en" to "English", "el" to "Greek", "bg" to "Bulgarian",
    "hr" to "Croatian", "cs" to "Czech", "da" to "Danish", "nl" to "Dutch", "et" to "Estonian", "fi" to "Finnish",
    "fr" to "French", "de" to "German", "hu" to "Hungarian", "it" to "Italian", "lv" to "Latvian", "lt" to "Lithuanian",
    "mt" to "Maltese", "pl" to "Polish", "pt" to "Portuguese", "ro" to "Romanian", "sk" to "Slovak", "sl" to "Slovenian",
    "es" to "Spanish", "sv" to "Swedish", "ru" to "Russian", "uk" to "Ukrainian")

private val STYLES = listOf(Cleanup.Style.FORMAL to "Formal: full punctuation", Cleanup.Style.DEFAULT to "As spoken",
    Cleanup.Style.CASUAL to "Casual: no final period", Cleanup.Style.VERY_CASUAL to "Very casual: lowercase, no final period")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StylePicker(prefs: Prefs, category: Cleanup.Style, label: String) {
    var open by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(prefs.style(category)) }
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(STYLES.first { it.first == value }.second, {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
        ExposedDropdownMenu(open, { open = false }) {
            STYLES.forEach { (style, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { open = false; value = style; prefs.setStyle(category, style) })
            }
        }
    }
}

private val HISTORY_DAYS = listOf(0 to "Forever", 7 to "7 days", 30 to "30 days", 90 to "90 days", 365 to "1 year")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val db = remember { FlowDb.get(ctx) }
    val scope = rememberCoroutineScope()
    var engine by remember { mutableStateOf(prefs.engine) }
    val langRow by remember { db.settings().observe("language") }.collectAsState(null)
    val fillerRow by remember { db.settings().observe("remove_filler_words") }.collectAsState(null)
    val daysRow by remember { db.settings().observe("history_days") }.collectAsState(null)
    val historyDays = daysRow?.let { SyncRules.parseJson(it.valueJson) as? Number }?.toInt() ?: 0
    val language = langRow?.let { SyncRules.parseJson(it.valueJson) as? String } ?: "auto"
    val fillers = fillerRow?.let { SyncRules.parseJson(it.valueJson) as? Boolean } ?: true
    fun setShared(key: String, json: String) = scope.launch(Dispatchers.IO) {
        db.settings().upsert(SharedSetting(SyncRules.settingId(key), key, json, System.currentTimeMillis()))
        SyncManager.request(ctx)
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Engine", style = MaterialTheme.typography.titleMedium)
        listOf("parakeet" to "Parakeet TDT 0.6B v3 (default, on device)", "whisper" to "Whisper base (on device)",
            "cloud" to "Cloud (OpenAI-compatible, sends audio)").forEach { (id, label) ->
            Row(Modifier.fillMaxWidth().clickable { engine = id; prefs.engine = id }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(engine == id, { engine = id; prefs.engine = id }); Text(label)
            }
        }
        if (engine == "cloud") {
            var url by remember { mutableStateOf(prefs.cloudUrl) }
            var key by remember { mutableStateOf(prefs.cloudKey) }
            var model by remember { mutableStateOf(prefs.cloudModel) }
            OutlinedTextField(url, { url = it; prefs.cloudUrl = it }, Modifier.fillMaxWidth(), label = { Text("Base URL, e.g. https://api.openai.com/v1") }, singleLine = true)
            OutlinedTextField(key, { key = it; prefs.cloudKey = it }, Modifier.fillMaxWidth(), label = { Text("API key") },
                singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(model, { model = it; prefs.cloudModel = it }, Modifier.fillMaxWidth(), label = { Text("Model") }, singleLine = true)
        }
        EngineDownload(engine)

        Text("Tap the bubble to start and tap again to finish, or hold it and release. Drag it to the bottom centre to hide it for 10 minutes.",
            style = MaterialTheme.typography.bodySmall)
        Text("Style", style = MaterialTheme.typography.titleMedium)
        listOf(Cleanup.Style.CASUAL to "Messages", Cleanup.Style.FORMAL to "Email and documents", Cleanup.Style.DEFAULT to "Other apps")
            .forEach { (category, label) -> StylePicker(prefs, category, label) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Remove filler words (synced)", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(fillers, { setShared("remove_filler_words", it.toString()) })
        }
        var open by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(open, { open = it }) {
            OutlinedTextField(LANGUAGES.firstOrNull { it.first == language }?.second ?: language, {}, readOnly = true,
                label = { Text("Language (synced)") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
            ExposedDropdownMenu(open, { open = false }) {
                LANGUAGES.forEach { (code, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { open = false; setShared("language", SyncRules.toJson(code)) })
                }
            }
        }

        var daysOpen by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(daysOpen, { daysOpen = it }) {
            OutlinedTextField(HISTORY_DAYS.firstOrNull { it.first == historyDays }?.second ?: "$historyDays days", {}, readOnly = true,
                label = { Text("Keep history (synced)") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(daysOpen) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
            ExposedDropdownMenu(daysOpen, { daysOpen = false }) {
                HISTORY_DAYS.forEach { (days, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { daysOpen = false; setShared("history_days", days.toString()) })
                }
            }
        }
        OutlinedButton({ ctx.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }, Modifier.fillMaxWidth()) {
            Text("Turn on the FlowLite voice keyboard")
        }

        var ai by remember { mutableStateOf(prefs.aiRewrite) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
            Column(Modifier.weight(1f)) {
                Text("AI rewrite", style = MaterialTheme.typography.titleMedium)
                Text("Sends the text to the AI service below. Select text first, then dictate an instruction such as \"make this formal\".",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(ai, { ai = it; prefs.aiRewrite = it })
        }
        if (ai) {
            var url by remember { mutableStateOf(prefs.cloudUrl) }
            var key by remember { mutableStateOf(prefs.cloudKey) }
            var model by remember { mutableStateOf(prefs.aiModel) }
            var prompt by remember { mutableStateOf(prefs.aiPrompt) }
            OutlinedTextField(url, { url = it; prefs.cloudUrl = it }, Modifier.fillMaxWidth(), label = { Text("Base URL, e.g. https://api.openai.com/v1") }, singleLine = true)
            OutlinedTextField(key, { key = it; prefs.cloudKey = it }, Modifier.fillMaxWidth(), label = { Text("API key") },
                singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(model, { model = it; prefs.aiModel = it }, Modifier.fillMaxWidth(), label = { Text("Chat model") }, singleLine = true)
            OutlinedTextField(prompt, { prompt = it; prefs.aiPrompt = it }, Modifier.fillMaxWidth(), label = { Text("Rewrite prompt") })
        }

        Text("Sync", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        SyncSection(prefs)

        Text("Updates", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        UpdateSection()
    }
}

private const val SETUP_GUIDE = "https://github.com/mchristo839/flowlite/blob/main/docs/self-host.md"

@Composable
private fun SyncSection(prefs: Prefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(prefs.serverUrl) }
    var email by remember { mutableStateOf(prefs.email) }
    var password by remember { mutableStateOf("") }
    var signedIn by remember { mutableStateOf(prefs.token != null) }
    val live by SyncManager.status.collectAsState()
    var message by remember { mutableStateOf(prefs.syncStatus) }
    LaunchedEffect(live) { if (live.isNotEmpty()) { message = live; signedIn = prefs.token != null } }

    if (!signedIn) {
        Text("Sync is optional. Without it, your dictation and history stay on this phone and nothing is sent anywhere.",
            style = MaterialTheme.typography.bodySmall)
        Text("To keep history, dictionary and settings the same on all your devices, run your own sync server and sign in to it here. " +
            "Audio never leaves your device. Only text is synced.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SETUP_GUIDE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Set-up guide") }
    }
    OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("Server address") },
        placeholder = { Text("https://sync.yourdomain.com") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    if (!signedIn) {
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        Button(onClick = {
            scope.launch {
                message = "Signing in"
                message = withContext(Dispatchers.IO) {
                    if (!server.trim().startsWith("https://")) return@withContext "Use an https:// address. Your login would otherwise travel unencrypted."
                    try {
                        if (server.trimEnd('/') != prefs.serverUrl) listOf("transcripts", "dictionary", "settings").forEach { prefs.setCursor(it, "") }
                        prefs.serverUrl = server
                        val r = SyncClient(prefs.serverUrl, null).login(email.trim(), password)
                        val userId = r.getJSONObject("record").getString("id")
                        val account = prefs.serverUrl + "|" + userId
                        // Another account's records must never be pushed to or shown under this one.
                        // With no stored account, any row that came from a server belongs to an unknown account.
                        if (if (prefs.account.isEmpty()) FlowDb.get(ctx).hasServerRows() else prefs.account != account) {
                            FlowDb.get(ctx).clearAllTables()
                            listOf("transcripts", "dictionary", "settings").forEach { prefs.setCursor(it, "") }
                        }
                        prefs.account = account
                        prefs.email = email.trim()
                        prefs.token = r.getString("token")
                        prefs.userId = userId
                        prefs.lastRefresh = System.currentTimeMillis()
                        SyncManager.sync(ctx); prefs.syncStatus
                    } catch (e: SyncException) {
                        if (e.code == 400) "The server rejected that email or password."
                        else if (e.code == 404) "That server answered, but it isn't a FlowLite sync server."
                        else "Sign in failed: ${e.message}"
                    } catch (e: Exception) { "Can't reach that address. Check it and that the server is running. (${e.message})" }
                }
                password = ""
                signedIn = prefs.token != null
            }
        }) { Text("Sign in") }
    } else {
        Text("Signed in as ${prefs.email}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { message = "Syncing"; withContext(Dispatchers.IO) { SyncManager.sync(ctx) }; message = prefs.syncStatus } }) { Text("Sync now") }
            OutlinedButton(onClick = { prefs.token = null; prefs.userId = null; signedIn = false; message = "Signed out" }) { Text("Sign out") }
        }
    }
    Text(message, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun UpdateSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("Version ${Updater.installedVersion(ctx)}. FlowLite checks GitHub for updates every few hours.") }
    OutlinedButton(onClick = {
        scope.launch {
            message = "Checking"
            message = try {
                val apk = withContext(Dispatchers.IO) { Updater.download(ctx) }
                if (apk == null) "Up to date" else { ctx.startActivity(Updater.installIntent(ctx, apk)); "Opening installer" }
            } catch (e: Exception) { "Update failed: ${e.message}" }
        }
    }) { Text("Check for updates") }
    Text(message, style = MaterialTheme.typography.bodySmall)
}
