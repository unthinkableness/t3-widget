package dev.local.t3widget.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import dev.local.t3widget.T3Links
import dev.local.t3widget.data.ConnectionCache
import dev.local.t3widget.data.Settings
import dev.local.t3widget.data.SetupLink
import dev.local.t3widget.data.Store
import dev.local.t3widget.data.WidgetState
import dev.local.t3widget.live.LiveUpdateService
import dev.local.t3widget.update.CheckOutcome
import dev.local.t3widget.update.UpdateState
import dev.local.t3widget.update.UpdateStore
import dev.local.t3widget.update.UpdateWorker
import dev.local.t3widget.update.Updater
import androidx.lifecycle.lifecycleScope
import dev.local.t3widget.work.Refresher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * App settings. Also the widget's configure activity (optional on Android 12+, reachable via long-press >
 * Settings on the widget) and the handler for `t3widget://setup#…` links / QR codes.
 */
class SettingsActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    /** A parsed setup link waiting for the user's confirmation. */
    private val pendingSetup = MutableStateFlow<SetupLink.Result?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val configuring = appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        if (configuring) {
            // If the user backs out, the launcher still gets a valid result for an optional config.
            setResult(RESULT_OK, resultIntent())
        }
        if (savedInstanceState == null) {
            handleSetupIntent(intent)
            handleInstallIntent(intent)
        }
        // Updates: make sure the 6-hourly check is scheduled, and check now (throttled) since the app was opened.
        UpdateWorker.schedulePeriodic(this)
        UpdateWorker.checkOnAppOpen(this)

        val store = Store(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SettingsScreen(
                    store = store,
                    configuring = configuring,
                    pendingSetup = pendingSetup,
                    onSaved = {
                        applied(this)
                        if (configuring) {
                            setResult(RESULT_OK, resultIntent())
                            finish()
                        }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSetupIntent(intent)
        handleInstallIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Updater.uiVisible = true
    }

    override fun onResume() {
        super.onResume()
        // Coming back from "Install unknown apps": continue a waiting install.
        lifecycleScope.launch { Updater.onSettingsResumed(applicationContext) }
    }

    override fun onStop() {
        Updater.uiVisible = false
        super.onStop()
    }

    /** "Update ready" notification / widget dot: show the installer confirmation (or start the install). */
    private fun handleInstallIntent(intent: Intent?) {
        if (intent?.action != ACTION_INSTALL_UPDATE) return
        Updater.promptInstall(this)
    }

    private fun handleSetupIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.dataString ?: return
        pendingSetup.value = SetupLink.parse(data)
    }

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    companion object {
        const val ACTION_INSTALL_UPDATE = "dev.local.t3widget.action.INSTALL_UPDATE"

        /** After saving: fallback job, immediate refresh, live service. */
        fun applied(context: Context) {
            Refresher.schedulePeriodic(context)
            Refresher.refreshNow(context)
            LiveUpdateService.startIfWantedAsync(context)
        }
    }
}

private val Bg = Color(0xFF121114)
private val Fg = Color(0xFFF2F2F2)
private val Sub = Color(0xFF9A9AA0)
private val Faint = Color(0xFF6E6E74)

@SuppressLint("BatteryLife")
@Composable
private fun SettingsScreen(
    store: Store,
    configuring: Boolean,
    pendingSetup: MutableStateFlow<SetupLink.Result?>,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var lan by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var fake by remember { mutableStateOf(true) }
    var live by remember { mutableStateOf(true) }
    val state by store.state.collectAsState(WidgetState.EMPTY)
    val setup by pendingSetup.collectAsState()
    var conn by remember { mutableStateOf(ConnectionCache()) }
    // Bumped on resume so permission / battery status re-reads after returning from system screens.
    var resumeTick by remember { mutableIntStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumeTick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    suspend fun reload() {
        val s = store.settings()
        url = s.serverUrl; lan = s.lanUrl; token = s.token; fake = s.useFakeData; live = s.liveUpdates
        conn = store.connectionCache()
        loaded = true
    }
    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(state.updatedAt, state.error) { conn = store.connectionCache() }

    suspend fun save(newSettings: Settings) {
        val old = store.settings()
        if (old.serverUrl != newSettings.serverUrl.trim() || old.token != newSettings.token.trim()) store.resetConnection()
        store.saveSettings(newSettings)
        reload()
        onSaved()
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick++ }

    // ---- setup link confirmation
    when (val r = setup) {
        is SetupLink.Result.Ok -> AlertDialog(
            onDismissRequest = { pendingSetup.value = null },
            title = { Text("Connect to your T3 PC?") },
            text = {
                Text(
                    buildString {
                        append("Server: ${r.config.url}\n")
                        if (r.config.lanUrl.isNotBlank()) append("Home network: ${r.config.lanUrl}\n")
                        append("\nThis replaces the current server and token and turns demo data off.")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingSetup.value = null
                    scope.launch {
                        save(
                            Settings(
                                serverUrl = r.config.url, lanUrl = r.config.lanUrl, token = r.config.token,
                                useFakeData = false, liveUpdates = store.settings().liveUpdates,
                            )
                        )
                        Toast.makeText(context, "Connected. Refreshing…", Toast.LENGTH_SHORT).show()
                        // Live updates need the (silent) notification; ask right away on Android 13+.
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }) { Text("Connect") }
            },
            dismissButton = { TextButton(onClick = { pendingSetup.value = null }) { Text("Cancel") } },
        )
        is SetupLink.Result.Invalid -> AlertDialog(
            onDismissRequest = { pendingSetup.value = null },
            title = { Text("Setup link not valid") },
            text = { Text(r.reason) },
            confirmButton = { TextButton(onClick = { pendingSetup.value = null }) { Text("OK") } },
        )
        null -> {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("T3 Widget", style = MaterialTheme.typography.headlineSmall, color = Fg)
        Text(
            if (configuring) "Set up the widget. You can change this later from the widget's settings."
            else "Settings for the T3 Code home-screen widget.",
            color = Sub,
        )

        // ---- connection
        Text("Connection", style = MaterialTheme.typography.titleSmall, color = Fg)
        Text(
            "Easiest: run the setup script on your PC and scan its QR code with the camera, " +
                "or copy the t3widget://setup link and tap Paste setup link.",
            color = Sub, style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(enabled = loaded, onClick = {
            val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            pendingSetup.value = SetupLink.parse(text)
        }) { Text("Paste setup link") }

        OutlinedTextField(
            value = url, onValueChange = { url = it }, enabled = loaded,
            label = { Text("Server URL (T3 Connect tunnel)") },
            placeholder = { Text("https://prod-….t3coderelay.com") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = lan, onValueChange = { lan = it }, enabled = loaded,
            label = { Text("Home network URL (optional)") },
            placeholder = { Text("http://192.168.x.y:3773") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = token, onValueChange = { token = it }, enabled = loaded,
            label = { Text("Token") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        ToggleRow("Use demo data", "Fake numbers, no server needed", fake, loaded) { fake = it }
        ToggleRow(
            "Live updates",
            "Keeps a connection open so the widget updates within seconds. Shows a silent notification.",
            live, loaded,
        ) { live = it }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = loaded, onClick = {
                scope.launch {
                    save(Settings(serverUrl = url, lanUrl = lan, token = token, useFakeData = fake, liveUpdates = live))
                }
            }) { Text(if (configuring) "Save & add widget" else "Save") }
            OutlinedButton(onClick = {
                if (!LiveUpdateService.kick(context)) Refresher.refreshNow(context)
            }) { Text("Refresh now") }
        }

        HorizontalDivider(color = Color(0xFF2D2C30))

        // ---- background permissions
        val tick = resumeTick // read so this block recomposes on resume
        Text("Keep it running", style = MaterialTheme.typography.titleSmall, color = Fg)
        Text(
            "Samsung closes background apps aggressively. For live updates, allow the notification " +
                "(it stays silent and collapsed) and let the app run unrestricted in the background.",
            color = Sub, style = MaterialTheme.typography.bodySmall,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = remember(tick) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            }
            StatusRow("Notification", if (granted) "Allowed" else "Not allowed") {
                if (!granted) OutlinedButton(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text("Allow")
                }
            }
        }
        val exempt = remember(tick) {
            context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
        }
        StatusRow("Battery", if (exempt) "Unrestricted" else "Optimised (may be killed)") {
            if (!exempt) OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                    )
                }
            }) { Text("Allow") }
        }
        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                )
            }
        }) { Text("App info (Battery → Unrestricted)") }

        HorizontalDivider(color = Color(0xFF2D2C30))

        // ---- app updates
        UpdatesSection(tick)

        HorizontalDivider(color = Color(0xFF2D2C30))

        // ---- status
        Text("Status", style = MaterialTheme.typography.titleSmall, color = Fg)
        Text(
            buildString {
                append("${state.running} running · ${state.needsYou} needs you\n")
                state.topNeedsYouTitle?.let { append("Needs you: $it\n") }
                state.usage.forEach { append("${it.label}: ${it.percent}%\n") }
                append("Updated: ")
                append(if (state.updatedAt > 0) DateFormat.getDateTimeInstance().format(Date(state.updatedAt)) else "never")
                state.via?.let { append(" via $it") }
                if (LiveUpdateService.isRunning) append(if (LiveUpdateService.isConnected) "\nLive: connected" else "\nLive: reconnecting")
                else append("\nLive: off")
                conn.tunnelStatus?.let { append("\nTunnel: $it") }
                conn.environmentId?.let { append("\nEnvironment: $it") }
                state.error?.let { append("\nError: $it") }
            },
            color = Color(0xFFBDBDC2),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Card tap opens T3 (${T3Links.installedPackage(context) ?: "not installed"}). " +
                "\"Needs you\" opens that thread. Usage bars refresh.",
            color = Faint, style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Fg)
            Text(subtitle, color = Sub, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun StatusRow(title: String, value: String, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Fg)
            Text(value, color = Sub, style = MaterialTheme.typography.bodySmall)
        }
        action()
    }
}

@Composable
private fun UpdatesSection(resumeTick: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updates = remember { UpdateStore(context) }
    val upd by updates.state.collectAsState(UpdateState())
    val auto by updates.autoInstall.collectAsState(true)
    val busy by Updater.busy.collectAsState()
    val canInstall = remember(resumeTick) { Updater.canInstall(context) }
    val newer = upd.latestCode > Updater.installedCode

    Text("App updates", style = MaterialTheme.typography.titleSmall, color = Fg)
    Text(
        buildString {
            append("Version ${Updater.installedName} (${Updater.installedCode})")
            if (newer && upd.latestName != null) append(" · latest ${upd.latestName}")
            append('\n')
            append(busy ?: upd.message ?: "Not checked yet")
            if (upd.lastCheckAt > 0) append("\nLast check: ${Updater.formatTime(upd.lastCheckAt)}")
            if (busy == null && upd.outcome == CheckOutcome.NETWORK_ERROR && upd.nextAutoCheckAt > 0) {
                append("\nNext automatic try: ${Updater.formatTime(upd.nextAutoCheckAt)}")
            }
        },
        color = if (busy == null && upd.outcome == CheckOutcome.ERROR) Color(0xFFEF5350) else Color(0xFFBDBDC2),
        style = MaterialTheme.typography.bodySmall,
    )
    if (newer && !upd.latestNotes.isNullOrBlank()) {
        Text("What's new: ${upd.latestNotes}", color = Sub, style = MaterialTheme.typography.bodySmall)
    }
    ToggleRow(
        "Auto-install updates",
        "Download new versions from GitHub every 6 hours and install them. Off: just notify.",
        auto, true,
    ) { on -> scope.launch { updates.setAutoInstall(on) } }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(enabled = busy == null, onClick = { UpdateWorker.checkNow(context) }) { Text("Check for updates") }
        if (newer) {
            Button(enabled = busy == null, onClick = { (context as? Activity)?.let { Updater.promptInstall(it) } }) {
                Text("Install ${upd.latestName ?: ""}".trim())
            }
        }
    }
    if (!canInstall) {
        StatusRow("Install unknown apps", "Not allowed. Needed so T3 Widget can install its own updates.") {
            OutlinedButton(onClick = { Updater.openInstallPermissionSettings(context) }) { Text("Allow") }
        }
    }
}
