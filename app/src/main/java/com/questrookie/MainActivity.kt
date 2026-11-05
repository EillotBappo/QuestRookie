package com.questrookie

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Shortcuts.handle(this, intent)) return finish()
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val rookie = Rookie(getExternalFilesDir(null)!!, insecureTls = debuggable).apply { runCatching { loadConfig() } }
        val installer = newInstaller(this)
        setContent {
            QuestTheme {
                Surface(Modifier.fillMaxSize().backdrop(), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) { App(rookie, installer) }
            }
        }
    }

    // Settings → App size scales the activity's density itself, so dialogs, menus and popups (each its own window)
    // match the page. Text shrinks by the scale again through fontScale, as it always has here.
    override fun attachBaseContext(base: android.content.Context) {
        val scale = base.getSharedPreferences("ui", MODE_PRIVATE).getFloat("scale", 0.8f)
        val config = android.content.res.Configuration(base.resources.configuration).apply {
            densityDpi = (densityDpi * scale).toInt()
            fontScale *= scale
            // The screen holds more dp at a smaller density; left alone, full-screen dialogs size to 80% of it.
            screenWidthDp = (screenWidthDp / scale).toInt()
            screenHeightDp = (screenHeightDp / scale).toInt()
            smallestScreenWidthDp = (smallestScreenWidthDp / scale).toInt()
        }
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    /** Y cycles tabs whatever has focus; set by [App]. Unhandled, Android turns Y into Back (Virtual.kcm) or Space (Generic.kcm). */
    var onButtonY: (() -> Unit)? = null

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val handler = onButtonY
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_Y && handler != null) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) handler()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (Shortcuts.handle(this, intent)) moveTaskToBack(true)
    }
}

private enum class Section(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Store("Store", QIcons.Gamepad),
    MyGames("My games", QIcons.Apps),
    Downloads("Downloads", QIcons.Download),
    Settings("Settings", Icons.Default.Settings),
}

@Composable
fun App(rookie: Rookie, installer: Installer) {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val s = remember { AppState(ctx, rookie, installer, scope) }
    // setprops reset on reboot; re-apply saved tweaks once per launch if ADB is reachable.
    LaunchedEffect(Unit) {
        val n = withContext(Dispatchers.IO) { runCatching { TweakStore.applySaved(ctx, installer) }.getOrNull() }
        if (n != null && n > 0) s.status = "Re-applied ${count(n, "tweak")}"
        // Game-tweak automation watches the foreground from the service, so it works when games start from Home.
        if (Automation.wanted(ctx)) KeepAliveService.start(ctx)
    }

    var section by rememberSaveable { mutableStateOf(Section.Home) }
    // Back (and the controller's B, which Android turns into Back) goes to Store before it leaves the app.
    BackHandler(enabled = section != Section.Store) { section = Section.Store }
    val activity = LocalContext.current as? MainActivity
    DisposableEffect(activity) {
        activity?.onButtonY = { section = Section.entries[(section.ordinal + 1) % Section.entries.size] }
        onDispose { activity?.onButtonY = null }
    }
    val snackbar = remember { SnackbarHostState() }
    // Each new status becomes a snackbar; failures stay until dismissed and offer Retry.
    LaunchedEffect(s.status) {
        if (s.status.isEmpty()) return@LaunchedEffect
        val retry = s.retry
        val result = snackbar.showSnackbar(
            s.status, actionLabel = if (retry != null) "Retry" else null, withDismissAction = retry != null,
            duration = if (retry != null) SnackbarDuration.Indefinite else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) retry?.invoke()
        s.status = ""
    }
    Scaffold(
        containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface,
        snackbarHost = {
            SnackbarHost(snackbar) {
                Snackbar(
                    it, containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface,
                    actionColor = MaterialTheme.colorScheme.primary, dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) { pad ->
        // Side rail, like Horizon's own panels: a Quest window is wide, not tall, so vertical room goes to content.
        Row(Modifier.padding(pad).fillMaxSize()) {
            // A slim rail of round icon buttons, launcher style; the status strip above the page carries the headset's vitals.
            NavigationRail(containerColor = RailColor) {
                val colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                )
                // Scrolls when the window is too short for every tab (landscape phone, small Quest panel).
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    Section.entries.filter { it != Section.Settings }.forEach { sec ->
                        NavigationRailItem(
                            selected = section == sec, onClick = { section = sec },
                            icon = {
                                // Downloads shows how many installs are running or waiting.
                                val n = if (sec == Section.Downloads) s.queue.activeCount else 0
                                BadgedBox({ if (n > 0) Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("$n") } }) { Icon(sec.icon, sec.label) }
                            },
                            modifier = Modifier.padding(vertical = 4.dp).focusRing(RoundedCornerShape(16.dp)), colors = colors,
                        )
                    }
                }
                // Settings sits apart at the bottom, like Horizon's own panels.
                NavigationRailItem(
                    selected = section == Section.Settings, onClick = { section = Section.Settings },
                    icon = { Icon(Section.Settings.icon, Section.Settings.label) },
                    modifier = Modifier.padding(bottom = 8.dp).focusRing(RoundedCornerShape(16.dp)), colors = colors,
                )
            }
            Column(Modifier.weight(1f)) {
                // What's running right now, at the top of the page instead of a bare bar at the bottom.
                s.busy?.let { label ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box(Modifier.weight(1f).padding(horizontal = 32.dp).fillMaxWidth()) {
                    CompositionLocalProvider(LocalAppState provides s) { when (section) {
                        Section.Home -> HomeScreen(s)
                        Section.Store -> LibraryScreen(s)
                        Section.MyGames -> MyGamesScreen(s)
                        Section.Downloads -> DownloadsScreen(s)
                        Section.Settings -> TweaksScreen(s)
                    } }
                }
            }
        }
    }
    if (s.pairing) PairDialog(s)
}

/** A small icon + label pair under the rail logo. */
/** One-time Wireless debugging pairing, so the headset never needs a PC for `adb tcpip`. */
@Composable
private fun PairDialog(s: AppState) {
    var port by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { s.pairing = false },
        title = { Text("Pair with headset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Open Developer options and turn on Wireless debugging.\n2. Tap \"Pair device with pairing code\".\n3. Enter the port (after the colon) and the 6-digit code here.")
                TextButton({
                    // Horizon OS hands the bare action to Meta's page, which has no Wireless debugging, so ask Android's own Settings first.
                    val dev = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { s.ctx.startActivity(android.content.Intent(dev).setPackage("com.android.settings")) }
                        .recoverCatching { s.ctx.startActivity(dev) }
                        .onFailure { s.status = "Enable Developer mode in the Meta Horizon app first" }
                }, Modifier.focusRing(RoundedCornerShape(24.dp))) { Text("Open Developer options") }
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, singleLine = true, label = { Text("Pairing port") })
                OutlinedTextField(code, { code = it.filter(Char::isDigit) }, singleLine = true, label = { Text("Pairing code") })
            }
        },
        confirmButton = {
            Button({
                s.pairing = false
                s.task("Pairing") {
                    WirelessAdb(s.ctx).pairWith(port.toInt(), code)
                    s.installer.connect()
                    s.refreshApps()
                    "Paired and connected"
                }
            }, Modifier.focusRing(RoundedCornerShape(24.dp)), enabled = port.isNotEmpty() && code.length == 6) { Text("Pair") }
        },
        dismissButton = { TextButton({ s.pairing = false }, Modifier.focusRing(RoundedCornerShape(24.dp))) { Text("Cancel") } },
    )
}

