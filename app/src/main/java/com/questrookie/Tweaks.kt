package com.questrookie

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Headset tweaks via setprop/broadcast. setprops reset on reboot, so saved choices are re-applied on app start.
// "" = Default: clears the prop so the system/game picks its own value.
class Tweak(val key: String, val label: String, val hint: String, val options: List<Pair<String, String>>, val commands: (String) -> List<String>)

private fun props(vararg names: String) = { v: String -> names.map { "setprop $it '$v'" } }

/** The headset this app is running on; every option below is built from what it can do. */
val MODEL = headsetModel()
val TWEAKS = tweaksFor(MODEL)

/** Only rates the panel supports and resolutions relative to this model's default eye buffer: a prop the hardware can't honour is a silent no-op. */
fun tweaksFor(m: HeadsetModel): List<Tweak> = listOfNotNull(
    Tweak("refresh", "Refresh rate",
        "Higher is smoother but drains the battery faster. Games that set their own rate ignore this." +
            if (120 in m.rates) " 120 Hz needs Settings → Experimental → 120 Hz refresh rate turned on too." else "",
        listOf("Default" to "") + m.rates.map { "$it Hz" to "$it" },
        props("debug.oculus.refreshRate")),
    Tweak("texture", "Render resolution", "Eye-buffer size as a multiple of ${m.name}'s default ${m.eyeWidth}×${m.eyeHeight}. Sharper, but each step costs GPU headroom.",
        m.resolutions) { v ->
        if (v.isEmpty()) props("debug.oculus.textureWidth", "debug.oculus.textureHeight")("")
        else listOf("setprop debug.oculus.textureWidth $v", "setprop debug.oculus.textureHeight ${m.heightFor(v.toInt())}")
    },
    Tweak("foveation", "Foveation", "Renders the edges of the view at lower detail. Lower means sharper edges and more GPU load.",
        listOf("Default" to "", "Off" to "0", "Low" to "1", "High" to "3"),
        props("debug.oculus.foveation.level")),
    Tweak("dynfov", "Dynamic foveation", "On lets the runtime raise foveation only while the GPU is falling behind, so edges stay sharp when there is headroom.",
        listOf("Default" to "", "On" to "1", "Off" to "0"),
        props("debug.oculus.foveation.dynamic")),
    Tweak("perf", "CPU / GPU level", "Minimum clock level. Higher is smoother but runs hotter and shortens battery life.",
        listOf("Default" to "", "2" to "2", "3" to "3", "4 (max)" to "4"),
        props("debug.oculus.cpuLevel", "debug.oculus.gpuLevel")),
    Tweak("capture", "Recording / cast quality", "Resolution and bitrate used for recordings and casting.",
        listOf("Default" to "", "1080p" to "1080"), { v ->
        if (v.isEmpty()) props("debug.oculus.capture.width", "debug.oculus.capture.height", "debug.oculus.capture.bitrate", "debug.oculus.fullRateCapture")("")
        else listOf("setprop debug.oculus.capture.width 1920", "setprop debug.oculus.capture.height 1080",
            "setprop debug.oculus.capture.bitrate 10000000", "setprop debug.oculus.fullRateCapture 1")
    }),
    Tweak("capturefps", "Recording frame rate", "60 fps recordings and casts; smoother footage, bigger files.",
        listOf("Default" to "", "60 fps" to "60"),
        props("debug.oculus.capture.fps")),
    if (m.chroma) Tweak("chroma", "Chromatic aberration fix", "Off skips the colour-fringe correction: a slightly sharper, slightly cheaper frame on this panel.",
        listOf("Default" to "", "Off" to "0"),
        props("debug.oculus.forceChroma")) else null,
    Tweak("guardian", "Guardian", "Paused stops boundary warnings. Seated play only: nothing warns you before a wall.",
        listOf("On" to "", "Paused (seated only)" to "1"),
        props("debug.oculus.guardian_pause")),
    Tweak("prox", "Proximity sensor", "Off keeps the display awake when the headset is off your head. Handy for casting and testing.",
        listOf("On" to "", "Off (screen stays on)" to "off"), { v ->
        listOf("am broadcast -a com.oculus.vrpowermanager." + if (v.isEmpty()) "automation_disable" else "prox_close")
    }),
)

/** One-tap profiles. A tweak missing from a preset goes back to Default. */
val PRESETS = presetsFor(MODEL)

/** The preset whose values are all set right now, or null; Balanced = everything at Default. */
fun matchingPreset(valueOf: (Tweak) -> String): String? = PRESETS.firstOrNull { (_, p) -> TWEAKS.all { valueOf(it) == p[it.key].orEmpty() } }?.first

/** Which tweaks this game launches with: the global ones, a preset's, or its own. */
fun profileName(ctx: Context, pkg: String): String =
    if (!TweakStore.hasProfile(ctx, pkg)) "Global tweaks" else matchingPreset { TweakStore.getFor(ctx, pkg, it) } ?: "Custom"

/**
 * Battery saver at the lowest rate; Max quality at up to 90 Hz with a 1.4× eye buffer, the step most titles still hold.
 * 120 Hz gets its own preset at the default buffer: there's no GPU headroom for both, and a missed 120 reprojects to 60.
 */
fun presetsFor(m: HeadsetModel) = listOfNotNull(
    "Battery saver" to mapOf("refresh" to "${m.rates.min()}", "texture" to "", "foveation" to "3", "perf" to "2"),
    "Balanced" to emptyMap(),
    if (120 in m.rates) "120 Hz" to mapOf("refresh" to "120", "texture" to "", "dynfov" to "1", "perf" to "4") else null,
    "Max quality" to mapOf("refresh" to "${m.rates.filter { it <= 90 }.max()}", "texture" to m.resolutions[2].second, "foveation" to "0", "perf" to "4"),
)

/** App-wide size. Compact is the default: it fits more on a headset window. */
private val UI_SIZES = listOf("Compact" to 0.8f, "Medium" to 0.9f, "Standard" to 1f)

object TweakStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tweaks", Context.MODE_PRIVATE)
    fun get(ctx: Context, t: Tweak) = prefs(ctx).getString(t.key, "") ?: ""
    fun set(ctx: Context, t: Tweak, v: String) = prefs(ctx).edit().putString(t.key, v).apply()

    /** Re-applies only non-default choices, so a fresh install never touches the headset. */
    fun applySaved(ctx: Context, installer: Installer): Int {
        val chosen = TWEAKS.filter { get(ctx, it).isNotEmpty() }
        chosen.forEach { t -> t.commands(get(ctx, t)).forEach(installer::shell) }
        return chosen.size
    }

    private fun profile(ctx: Context, pkg: String) = ctx.getSharedPreferences("profile_$pkg", Context.MODE_PRIVATE)

    fun hasProfile(ctx: Context, pkg: String) = profile(ctx, pkg).all.isNotEmpty()

    /** This game's choice for [t]: its profile's when it has one, otherwise the global choice. */
    fun getFor(ctx: Context, pkg: String, t: Tweak) = if (hasProfile(ctx, pkg)) profile(ctx, pkg).getString(t.key, "") ?: "" else get(ctx, t)

    /** Saves one choice for this game. Its first change copies the global choices in, so the other tweaks keep behaving as before. */
    fun setFor(ctx: Context, pkg: String, t: Tweak, v: String) {
        val current = if (hasProfile(ctx, pkg)) TWEAKS.associate { it.key to getFor(ctx, pkg, it) } else null
        val edit = profile(ctx, pkg).edit().clear()
        profileWith(current, TWEAKS.associate { it.key to get(ctx, it) }, t.key, v).forEach { (k, value) -> edit.putString(k, value) }
        edit.apply()
    }

    fun clearProfile(ctx: Context, pkg: String) = profile(ctx, pkg).edit().clear().apply()

    /** Sets every tweak for a launch: the game's profile if it has one, otherwise the global choices. "" resets a tweak to Default. */
    fun applyForLaunch(ctx: Context, pkg: String, installer: Installer) {
        val saved = profile(ctx, pkg)
        val useProfile = hasProfile(ctx, pkg)
        TWEAKS.forEach { t ->
            val v = if (useProfile) saved.getString(t.key, "") ?: "" else get(ctx, t)
            t.commands(v).forEach(installer::shell)
        }
    }
}

private fun applyTweak(s: AppState, t: Tweak, v: String) {
    t.commands(v).forEach(s.installer::shell)
    TweakStore.set(s.ctx, t, v)
}

@Composable
fun TweaksScreen(s: AppState) {
    val values = remember { mutableStateMapOf<String, String>().apply { TWEAKS.forEach { put(it.key, TweakStore.get(s.ctx, it)) } } }
    Column(Modifier.fillMaxSize()) {
        // Requested vs. actual: a game that sets its own rate, or a Quest 1 asked for 90 Hz, shows up here as a mismatch.
        val rate by produceState(currentRefreshRate(s.ctx)) {
            while (true) { kotlinx.coroutines.delay(5_000); value = currentRefreshRate(s.ctx) }
        }
        ScreenHeader("Settings", "${MODEL.name}" + (rate?.let { " · running at $it Hz" } ?: "") + " · re-applied after each reboot") {
            // One pill, not a button per preset: four buttons beside the vitals squeezed the last one into a column.
            DropPill(PRESETS.map { "Preset: ${it.first}" to it.first }, matchingPreset { values[it.key].orEmpty() } ?: "Preset: Custom", Modifier.width(240.dp), enabled = s.busy == null) { name ->
                applyPreset(s, values, name, PRESETS.first { it.first == name }.second)
            }
            // Tweaks go over ADB, so this stays in the header instead of a banner that eats the list's space.
            if (!s.installer.connected) FilledTonalButton({ connect(s) }, Modifier.focusRing(RoundedCornerShape(24.dp))) { Text("Connect ADB") }
        }
        // Staggered: cards differ a lot in height, and a plain grid leaves a hole beside every short one.
        // Headset tweaks first (the presets above drive them), then the app's own settings.
        LazyVerticalStaggeredGrid(
            StaggeredGridCells.Adaptive(360.dp), Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalItemSpacing = 16.dp,
        ) {
            item(key = "h-perf", span = StaggeredGridItemSpan.FullLine) { SectionTitle("Headset performance") }
            items(TWEAKS, key = { it.key }) { t -> TweakCard(s, t, values[t.key].orEmpty()) { values[t.key] = it } }
            item(key = "automation") { AutomationCard(s) }
            item(key = "h-app", span = StaggeredGridItemSpan.FullLine) { SectionTitle("App and headset") }
            item(key = "sources") { SourcesCard(s) }
            item(key = "headset") { HeadsetCard(s) }
            item(key = "content") { ContentCard(s) }
            item(key = "uisize") { UiSizeCard(s) }
        }
    }
}

/** One button per preset, the one whose values are all set right now ticked. [values] is the caller's view of the global tweaks. */
@Composable
fun PresetButtons(s: AppState, values: MutableMap<String, String>) = PRESETS.forEach { (name, preset) ->
    // The preset whose values are all set right now is the active one; Balanced = everything at Default.
    val active = TWEAKS.all { values[it.key].orEmpty() == preset[it.key].orEmpty() }
    val pad = Modifier.focusRing(RoundedCornerShape(24.dp))
    if (active) Button({ applyPreset(s, values, name, preset) }, pad, enabled = s.busy == null) {
        Icon(Icons.Default.Check, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(name)
    }
    else FilledTonalButton({ applyPreset(s, values, name, preset) }, pad, enabled = s.busy == null) { Text(name) }
}

private fun applyPreset(s: AppState, values: MutableMap<String, String>, name: String, preset: Map<String, String>) = s.task("Applying $name") {
    TWEAKS.forEach { t ->
        val v = preset[t.key].orEmpty()
        applyTweak(s, t, v)
        values[t.key] = v
    }
    "Preset: $name" + if (preset["refresh"] == "120") " · also turn on Settings → Experimental → 120 Hz refresh rate" else ""
}

/** Rookie's Quest options and ADB command window: username, OS updates, a raw shell command and ADB key reset. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HeadsetCard(s: AppState) {
    var name by remember { mutableStateOf("") }
    var cmd by remember { mutableStateOf("") }
    var updates by remember { mutableStateOf<Boolean?>(null) }
    var confirmReboot by remember { mutableStateOf(false) }
    if (confirmReboot) AlertDialog(
        onDismissRequest = { confirmReboot = false },
        title = { Text("Reboot headset?") },
        text = { Text("Running installs stop, and Wireless debugging is off after the restart until you turn it back on.") },
        confirmButton = {
            Button({ confirmReboot = false; s.task("Rebooting") { s.installer.reboot(); "Headset is rebooting" } }, Modifier.focusRing(RoundedCornerShape(24.dp))) { Text("Reboot") }
        },
        dismissButton = { TextButton({ confirmReboot = false }, Modifier.focusRing(RoundedCornerShape(24.dp))) { Text("Cancel") } },
    )
    LaunchedEffect(s.installer.connected) {
        if (s.installer.connected) updates = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { s.installer.osUpdatesEnabled() }.getOrNull() }
    }
    val ready = s.installer.connected && s.busy == null
    Card(Modifier.glass(), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Headset", style = MaterialTheme.typography.titleMedium)
            Text("Some games read the player name from /sdcard. Commands run as adb shell.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it }, Modifier.weight(1f), singleLine = true, label = { Text("Username") })
                FilledTonalButton({ s.task("Setting username") { s.installer.setUsername(name.trim()); "Username set to ${name.trim()}" } }, Modifier.focusRing(RoundedCornerShape(24.dp)), enabled = ready && name.isNotBlank()) { Text("Set") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(cmd, { cmd = it }, Modifier.weight(1f), singleLine = true, label = { Text("Shell command") })
                FilledTonalButton({ s.task("Running command") { s.installer.shell(cmd).trim().ifEmpty { "Done" }.take(300) } }, Modifier.focusRing(RoundedCornerShape(24.dp)), enabled = ready && cmd.isNotBlank()) { Text("Run") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                updates?.let { on ->
                    FilledTonalButton({
                        s.task(if (on) "Disabling OS updates" else "Enabling OS updates") { s.installer.setOsUpdates(!on); updates = !on; if (on) "OS updates off" else "OS updates on" }
                    }, Modifier.focusRing(RoundedCornerShape(24.dp)), enabled = ready) { Text(if (on) "Turn off OS updates" else "Turn on OS updates") }
                }
                TextButton({ s.installer.resetAuth(); s.status = "ADB key reset; connect again and allow it in the headset" }, Modifier.focusRing(RoundedCornerShape(24.dp)), enabled = s.busy == null) { Text("Reset ADB key") }
                TextButton({ confirmReboot = true }, Modifier.focusRing(RoundedCornerShape(24.dp)), enabled = ready) { Text("Reboot headset", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** Per-game tweaks from any launcher, plus the thermal and battery guards. All run in [KeepAliveService] via [Automation]. */
@Composable
private fun AutomationCard(s: AppState) {
    val prefs = remember { Automation.prefs(s.ctx) }
    var profiles by remember { mutableStateOf(prefs.getBoolean("profiles", true)) }
    var thermal by remember { mutableStateOf(prefs.getBoolean("thermal", true)) }
    var battery by remember { mutableStateOf(prefs.getBoolean("battery", false)) }
    fun set(key: String, v: Boolean) {
        prefs.edit().putBoolean(key, v).apply()
        if (Automation.wanted(s.ctx)) KeepAliveService.start(s.ctx)
    }
    SettingsCard("Automation", "Game tweaks apply when a game starts from Home too, not only from here. Hot headset: CPU/GPU level steps down one notch until it cools. Low battery: the Battery saver preset until you plug in.") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!s.hasUsageAccess()) FilledTonalButton({ s.grantUsageAccess() }, Modifier.focusRing(RoundedCornerShape(Radius.pill)), enabled = s.installer.connected && s.busy == null) { Text("Grant usage access over ADB (needed to see which game is running)") }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(profiles, { profiles = it; set("profiles", it) }, Modifier.focusRing(RoundedCornerShape(Radius.pill))); Text("Apply game tweaks from any launcher")
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(thermal, { thermal = it; set("thermal", it) }, Modifier.focusRing(RoundedCornerShape(Radius.pill))); Text("Step CPU/GPU level down when hot")
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(battery, { battery = it; set("battery", it) }, Modifier.focusRing(RoundedCornerShape(Radius.pill))); Text("Battery saver under 20% (unless charging)")
            }
        }
    }
}

/** Where games come from; opens the Sources editor. */
@Composable
private fun SourcesCard(s: AppState) {
    var editing by remember { mutableStateOf(false) }
    val names = listOfNotNull("Mirror".takeIf { s.rookie.mirrors.isNotEmpty() }) + s.catalogs.map { it.name } + s.extras.map { it.label }
    SettingsCard("Sources", names.joinToString(", ")) {
        FilledTonalButton({ editing = true }, Modifier.focusRing(RoundedCornerShape(Radius.pill))) { Text("Manage sources") }
        FilledTonalButton({ Shortcuts.start(s.ctx); Shortcuts.openInBrowser(s.ctx) }, Modifier.focusRing(RoundedCornerShape(Radius.pill))) { Text("Shortcuts") }
    }
    if (editing) SourcesDialog(s) { editing = false }
}

/** Content preferences that used to sit among the Library filters. */
@Composable
private fun ContentCard(s: AppState) {
    SettingsCard("Hide 18+", "Hide titles marked 18+ everywhere in the store.") {
        Switch(s.hideAdult, { s.chooseHideAdult(it) }, Modifier.focusRing(RoundedCornerShape(Radius.pill)))
    }
}

/** App-wide size, a card like the tweaks beside it rather than unlabelled chips in the header. */
@Composable
private fun UiSizeCard(s: AppState) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    SettingsCard("App size", "Scales QuestRookie itself. Compact fits the most on a headset window.") {
        UI_SIZES.forEach { (name, v) ->
            QChip(
                // The size is applied to the whole activity when it starts (see MainActivity), so it restarts to take effect.
                selected = s.uiScale == v, onClick = { s.chooseUiScale(v); activity?.recreate() }, label = { Text(name) },
                modifier = Modifier.focusRing(RoundedCornerShape(8.dp)),
                leadingIcon = if (s.uiScale == v) {{ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }} else null,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

/** Title, one line of explanation, then the controls: the shape every settings card shares. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SettingsCard(title: String, hint: String, content: @Composable () -> Unit) {
    Card(Modifier.glass(), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TweakCard(s: AppState, t: Tweak, value: String, onChange: (String) -> Unit) {
    Card(Modifier.glass(), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t.label, style = MaterialTheme.typography.titleMedium)
            Text(t.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DropPill(t.options, value, Modifier.fillMaxWidth(), enabled = s.busy == null) { v ->
                val label = t.options.first { it.second == v }.first
                s.task("Applying ${t.label}") {
                    applyTweak(s, t, v)
                    onChange(v)
                    "${t.label}: $label"
                }
            }
        }
    }
}
