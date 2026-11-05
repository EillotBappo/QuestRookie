package com.questrookie

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AppInfo(
    val pkg: String, val label: String, val versionName: String, val versionCode: Long,
    val apks: List<String>, val apkSize: Long, val launchable: Boolean, val isGame: Boolean,
    /** When the current build was installed or last updated. */
    val installedAt: Long,
)

/** A package is a game if the mirror lists it or Android tags it as one. */
fun isGame(ai: ApplicationInfo, mirrorPkgs: Set<String>) = ai.packageName in mirrorPkgs || ai.category == ApplicationInfo.CATEGORY_GAME

fun installedApps(ctx: Context, mirrorPkgs: Set<String>): List<AppInfo> {
    val pm = ctx.packageManager
    return pm.getInstalledPackages(0).mapNotNull { p ->
        val ai = p.applicationInfo ?: return@mapNotNull null
        if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 || p.packageName == ctx.packageName) return@mapNotNull null
        val apks = listOf(ai.sourceDir) + ai.splitSourceDirs.orEmpty()
        AppInfo(
            p.packageName, ai.loadLabel(pm).toString(), p.versionName.orEmpty(), p.longVersionCode,
            apks, apks.sumOf { File(it).length() }, pm.getLaunchIntentForPackage(p.packageName) != null, isGame(ai, mirrorPkgs),
            p.lastUpdateTime,
        )
    }.sortedBy { it.label.lowercase() }
}

class NeedsPairing : java.io.IOException("Pair with Wireless debugging first")

/** The headset's own adbd over loopback; turned on through Wireless debugging once the headset is paired. */
fun newInstaller(ctx: Context) = Installer(ctx.filesDir) { if (WirelessAdb.isPaired(ctx)) WirelessAdb(ctx).enableTcpip() else throw NeedsPairing() }

fun connect(s: AppState) = s.task("Connecting to headset") {
    try {
        s.installer.connect()
    } catch (e: NeedsPairing) {
        s.pairing = true
        throw e
    }
    s.refreshApps(); "Headset connected"
}

/** Headset battery level in percent, or null if Android doesn't report one. */
fun batteryPercent(ctx: Context): Int? {
    val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    return if (level < 0 || scale <= 0) null else level * 100 / scale
}

/** Battery temperature in °C, or null if Android doesn't report one. */
fun batteryTempC(ctx: Context): Float? {
    val t = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: return null
    return if (t == Int.MIN_VALUE) null else t / 10f
}

/** Minutes under an hour, otherwise hours to one decimal place. */
fun formatHours(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes < 60) "${minutes} min" else "%.1f h".format(ms / 3_600_000.0)
}

/** True when the library lists a newer build than the installed one. */
private fun hasUpdate(a: AppInfo, game: Game?) = game != null && (game.version.toLongOrNull() ?: 0) > a.versionCode

private enum class Kind(val label: String) { All("All"), Games("Games"), Apps("Apps") }

private enum class AppSort(val label: String) {
    Name("Name"), Size("Largest"), LastPlayed("Last played"), Installed("Recently installed"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InstalledScreen(s: AppState, picker: @Composable RowScope.() -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(Kind.All) }
    var sort by rememberSaveable { mutableStateOf(AppSort.Name) }
    var updatesOnly by rememberSaveable { mutableStateOf(false) }
    var hiddenOnly by rememberSaveable { mutableStateOf(false) }
    var gallery by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<AppInfo?>(null) }
    val latest = remember(s.games) { s.games.groupBy { it.pkg }.mapValues { (_, l) -> l.maxBy { it.version.toLongOrNull() ?: 0 } } }
    val updates = remember(s.apps, latest) { s.apps.count { hasUpdate(it, latest[it.pkg]) } }
    val hidden = remember(s.apps, s.disabled) { s.apps.count { it.pkg in s.disabled } }
    val battery = remember { batteryPercent(s.ctx) }
    val shown = remember(s.apps, query, kind, sort, updatesOnly, hiddenOnly, latest, s.lastPlayed, s.playtime, s.obbSizes, s.disabled) {
        s.apps.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
            .filter { kind == Kind.All || (kind == Kind.Games) == it.isGame }
            .filter { !updatesOnly || hasUpdate(it, latest[it.pkg]) }
            .filter { !hiddenOnly || it.pkg in s.disabled }
            .let { l ->
                when (sort) {
                    AppSort.Size -> l.sortedByDescending { it.apkSize + (s.obbSizes[it.pkg] ?: 0) }
                    AppSort.LastPlayed -> l.sortedByDescending { s.lastPlayed[it.pkg] ?: 0L }
                    AppSort.Installed -> l.sortedByDescending { it.installedAt }
                    AppSort.Name -> l
                }
            }
    }
    val total = remember(s.apps, s.obbSizes) { s.apps.sumOf { it.apkSize + (s.obbSizes[it.pkg] ?: 0) } }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "My games",
            "${count(s.apps.size, "app")} · ${formatSize(total)} · battery ${battery?.let { "$it%" } ?: "unknown"}" +
                (if (s.weeklyPlaytime > 0) " · ${formatHours(s.weeklyPlaytime)} played this week" else ""),
        ) {
            picker()
            SearchField(query, { query = it }, "Search apps", Modifier.weight(1f).widthIn(max = 360.dp))
        }
        FlowRow(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SortMenu(sort, AppSort.entries, { it.label }) { sort = it }
            Kind.entries.forEach { k ->
                QChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) }, modifier = Modifier.align(Alignment.CenterVertically).focusRing(RoundedCornerShape(8.dp)))
            }
            QChip(
                selected = updatesOnly, onClick = { updatesOnly = !updatesOnly }, label = { Text("Updates ($updates)") },
                modifier = Modifier.align(Alignment.CenterVertically).focusRing(RoundedCornerShape(8.dp)),
            )
            QChip(
                selected = hiddenOnly, onClick = { hiddenOnly = !hiddenOnly }, label = { Text("Hidden ($hidden)") },
                modifier = Modifier.align(Alignment.CenterVertically).focusRing(RoundedCornerShape(8.dp)),
            )
            QChip(
                selected = gallery, onClick = { gallery = !gallery }, label = { Text("Gallery") },
                modifier = Modifier.align(Alignment.CenterVertically).focusRing(RoundedCornerShape(8.dp)),
            )
    }
        if (!s.hasUsageAccess()) Banner("Grant usage access to sort by last played.", "Grant over ADB") { s.grantUsageAccess() }
        if (!s.installer.connected) Banner("Connect over ADB to back up, uninstall and see OBB sizes.", "Connect") { connect(s) }
        if (shown.isEmpty()) EmptyState(
            Icons.Default.CheckCircle, "No apps",
            when {
                s.apps.isEmpty() -> "Nothing sideloaded yet."
                query.isNotEmpty() -> "No apps match \"$query\"."
                else -> "None of your apps match these filters."
            },
        )
        else if (gallery) LazyVerticalGrid(
            GridCells.Adaptive(240.dp), Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(shown, key = { it.pkg }) { a -> AppTile(s, a, latest[a.pkg]) { confirm = a } }
        }
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(shown, key = { it.pkg }) { a -> AppRow(s, a, latest[a.pkg]) { confirm = a } }
        }
    }
    confirm?.let { a ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Uninstall ${a.label}?") },
            text = { Text("This removes the app and its OBB from the headset. Its saves in Android/data are backed up first; use Restore saves after reinstalling.") },
            confirmButton = {
                Button({
                    confirm = null
                    s.task("Uninstalling ${a.label}") { s.installer.uninstall(a.pkg); s.refreshApps(); "Uninstalled ${a.label}" }
                }, Modifier.focusRing(RoundedCornerShape(24.dp)), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Uninstall") }
            },
            dismissButton = { TextButton({ confirm = null }, Modifier.focusRing(RoundedCornerShape(24.dp))) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AppRow(s: AppState, a: AppInfo, game: Game?, onUninstall: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val obb = s.obbSizes[a.pkg]
    val update = hasUpdate(a, game)
    var tune by remember { mutableStateOf(false) }
    if (tune) TuneDialog(s, a, game) { tune = false }
    // Tuner-style row: washed in the game's art colour, wide cover, big name, then what it launches with.
    val tint = gameTint(s, game)
    val wash = tint?.let { Modifier.background(Brush.horizontalGradient(0f to it.copy(alpha = .28f), 1f to it.copy(alpha = .06f))) } ?: Modifier
    Card(Modifier.glass().then(wash).then(tuneTap(a) { tune = true }), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (game != null) GameArt(s, game, Modifier.width(112.dp).aspectRatio(16 / 9f).clip(RoundedCornerShape(Radius.chip)))
            else AppIcon(a.pkg, 48.dp) { s.ctx.packageManager.getApplicationIcon(a.pkg) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(a.label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (a.isGame) Pill("Game")
                    if (update) Pill("Update v${game!!.version}", Orange, Color(0xFF221500))
                    if (a.pkg in s.disabled) Pill("Disabled")
                }
                if (a.isGame) Row(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    listOf("refresh", "texture", "perf").mapNotNull { k -> TWEAKS.firstOrNull { it.key == k } }.forEach { t ->
                        val v = TweakStore.getFor(s.ctx, a.pkg, t)
                        Stat(t.label, t.options.firstOrNull { it.second == v }?.first ?: v)
                    }
                }
                // One line of details, so more rows fit on the panel.
                Text(
                    "${a.pkg} · v${a.versionName} · APK ${formatSize(a.apkSize)}" + (obb?.let { " · OBB ${formatSize(it)}" } ?: "") +
                        (s.playtime[a.pkg]?.takeIf { it > 0 }?.let { " · played\u00A0${formatHours(it).replace(' ', '\u00A0')}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // Measured, not guessed: the last session's frame stats and what to change, from Automation.
                Automation.lastPerf(s.ctx, a.pkg)?.let { Text("Last session $it", style = MaterialTheme.typography.bodySmall, color = if ("—" in it) Orange else colors.onSurfaceVariant, maxLines = 2) }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (update) Button({ s.install(game!!) }, Modifier.focusRing(RoundedCornerShape(24.dp))) { Text("Update") }
                    if (a.launchable && a.pkg !in s.disabled) FilledTonalButton({ s.launch(a.pkg) }, Modifier.focusRing(RoundedCornerShape(24.dp))) { Text("Launch") }
                    if (a.isGame) {
                        val fav = a.pkg in s.favourites
                        IconButton({ s.toggleFavourite(a.pkg) }, Modifier.focusRing(CircleShape)) {
                            Icon(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (fav) "Unfavourite" else "Favourite", tint = if (fav) colors.primary else colors.onSurfaceVariant)
                        }
                    }
                    AppMenu(s, a, onUninstall)
                }
                if (a.isGame) Text(profileName(s.ctx, a.pkg), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** A small grey label over a big value, the way a tuner lists a game's settings. */
@Composable
private fun Stat(label: String, value: String) = Column {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

/** The game's art colour, for tinting its row and tuning dialog; null when the store has no art for it. */
@Composable
private fun gameTint(s: AppState, game: Game?): Color? {
    val art = game?.let { rememberArt(s.thumbnail(it), it.thumbUrl.ifEmpty { it.iconUrl }) }
    return remember(art) { art?.let(::glowColor) }
}

@Composable
private fun AppMenu(s: AppState, a: AppInfo, onUninstall: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton({ menu = true }, Modifier.focusRing(CircleShape)) { Icon(Icons.Default.MoreVert, "More") }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(
                text = { Text("Back up APK, OBB + saves") }, enabled = s.installer.connected,
                onClick = { menu = false; s.task("Backing up ${a.label}") { "Saved to ${s.installer.backup(a)}" } },
            )
            val off = a.pkg in s.disabled
            DropdownMenuItem(
                text = { Text(if (off) "Enable app" else "Disable app") }, enabled = s.installer.connected,
                onClick = {
                    menu = false
                    s.task(if (off) "Enabling ${a.label}" else "Disabling ${a.label}") {
                        s.installer.setEnabled(a.pkg, enabled = off)
                        s.refreshApps()
                        if (off) "Enabled ${a.label}" else "Disabled ${a.label}"
                    }
                },
            )
            DropdownMenuItem(
                text = { Text("Restore saves") }, enabled = s.installer.connected,
                onClick = {
                    menu = false
                    s.task("Restoring saves for ${a.label}") {
                        if (s.installer.restoreSaves(a.pkg)) "Saves restored for ${a.label}" else "No save backup for ${a.label}"
                    }
                },
            )
            DropdownMenuItem(
                text = { Text("Uninstall", color = if (s.installer.connected) colors.error else Color.Unspecified) },
                enabled = s.installer.connected, onClick = { menu = false; onUninstall() },
            )
        }
    }
}

/** Games open their tuning dialog on a tap; other apps have no tap action. */
@Composable
fun tuneTap(a: AppInfo, onTap: () -> Unit): Modifier {
    if (!a.isGame) return Modifier
    val shape = RoundedCornerShape(Radius.tile)
    return Modifier.clip(shape).focusRing(shape).clickable(onClick = onTap)
}

private const val GLOBAL = "Use global tweaks"
private const val CUSTOM = "Custom"

/** A label over its control, a tuner panel's building block. */
@Composable
private fun TuneField(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        content()
    }

/**
 * One game's own headset tweaks, tuner style: a profile pick (global, a preset, or Custom) over a two-column grid
 * of pickers. Saved on each pick and applied the next time the game launches. [game] tints the panel with its art.
 */
@Composable
fun TuneDialog(s: AppState, a: AppInfo, game: Game? = null, onDismiss: () -> Unit) {
    val values = remember { mutableStateMapOf<String, String>().apply { TWEAKS.forEach { put(it.key, TweakStore.getFor(s.ctx, a.pkg, it)) } } }
    var own by remember { mutableStateOf(TweakStore.hasProfile(s.ctx, a.pkg)) }
    val ring = Modifier.focusRing(RoundedCornerShape(24.dp))
    val tint = gameTint(s, game)
    val profile = if (!own) GLOBAL else matchingPreset { values[it.key].orEmpty() } ?: CUSTOM
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = tint?.let { lerp(MaterialTheme.colorScheme.surfaceContainerHigh, it, .22f) } ?: AlertDialogDefaults.containerColor,
        title = { Text(a.label, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Automation.lastPerf(s.ctx, a.pkg)?.let { Text("Last session $it", style = MaterialTheme.typography.bodySmall, color = if ("—" in it) Orange else MaterialTheme.colorScheme.onSurfaceVariant) }
                TuneField("Profile") {
                    val options = listOf(GLOBAL) + PRESETS.map { it.first } + listOfNotNull(CUSTOM.takeIf { profile == CUSTOM })
                    DropPill(options.map { it to it }, profile, Modifier.fillMaxWidth()) { pick ->
                        when (pick) {
                            GLOBAL -> { TweakStore.clearProfile(s.ctx, a.pkg); TWEAKS.forEach { values[it.key] = TweakStore.get(s.ctx, it) }; own = false }
                            CUSTOM -> Unit
                            else -> {
                                val preset = PRESETS.first { it.first == pick }.second
                                TWEAKS.forEach { t -> preset[t.key].orEmpty().let { v -> TweakStore.setFor(s.ctx, a.pkg, t, v); values[t.key] = v } }
                                own = true
                            }
                        }
                    }
                }
                // Two per row, like a tuner's manual-tuning panel.
                TWEAKS.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { t ->
                            TuneField(t.label, Modifier.weight(1f)) {
                                DropPill(t.options, values[t.key].orEmpty(), Modifier.fillMaxWidth()) { v -> TweakStore.setFor(s.ctx, a.pkg, t, v); values[t.key] = v; own = true }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            if (a.launchable && a.pkg !in s.disabled) Button({ onDismiss(); s.launch(a.pkg) }, ring) { Text("Launch") }
            else TextButton(onDismiss, ring) { Text("Close") }
        },
    )
}

@Composable
private fun AppTile(s: AppState, a: AppInfo, game: Game?, onUninstall: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val update = hasUpdate(a, game)
    var tune by remember { mutableStateOf(false) }
    if (tune) TuneDialog(s, a, game) { tune = false }
    Card(Modifier.glass().then(tuneTap(a) { tune = true }), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppIcon(a.pkg, 96.dp) { s.ctx.packageManager.getApplicationIcon(a.pkg) }
            Text(a.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "v${a.versionName} · ${formatSize(a.apkSize + (s.obbSizes[a.pkg] ?: 0))}",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (a.isGame) Pill("Game")
                if (update) Pill("Update", Orange, Color(0xFF221500))
                if (a.pkg in s.disabled) Pill("Disabled")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (update) Button({ s.install(game!!) }, Modifier.focusRing(RoundedCornerShape(24.dp))) { Text("Update") }
                if (a.launchable && a.pkg !in s.disabled) FilledTonalButton({ s.launch(a.pkg) }, Modifier.focusRing(RoundedCornerShape(24.dp))) { Text("Launch") }
                AppMenu(s, a, onUninstall)
            }
        }
    }
}

class LocalApk(
    val pkg: String, val label: String, val versionName: String, val versionCode: Long,
    val files: List<File>, val obb: File?, val size: Long, val info: ApplicationInfo,
)

fun hasStorageAccess(ctx: Context) =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

/** APKs on shared storage; APKs of one package in one folder install together (splits), a <pkg>/ folder next to them is the OBB. */
fun scanLocal(ctx: Context): List<LocalApk> {
    val root = Environment.getExternalStorageDirectory()
    val pm = ctx.packageManager
    return root.walkTopDown().maxDepth(5)
        .onEnter { it == root || !(it.name.startsWith('.') || (it.parentFile == root && it.name == "Android")) }
        .filter { it.isFile && it.extension.equals("apk", true) }
        .mapNotNull { f -> pm.getPackageArchiveInfo(f.path, 0)?.let { f to it } }
        .groupBy { (f, p) -> f.parent + "|" + p.packageName }
        .mapNotNull { (_, group) ->
            val (base, info) = group.firstOrNull { !it.first.name.startsWith("split_") } ?: group.first()
            val ai = info.applicationInfo?.apply { sourceDir = base.path; publicSourceDir = base.path } ?: return@mapNotNull null
            val files = group.map { it.first }
            val obb = File(base.parentFile, info.packageName).takeIf { it.isDirectory }
            val size = files.sumOf { it.length() } + (obb?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0)
            LocalApk(info.packageName, ai.loadLabel(pm).toString(), info.versionName.orEmpty(), info.longVersionCode, files, obb, size, ai)
        }.sortedBy { it.label.lowercase() }
}

@Composable
fun LocalScreen(s: AppState, picker: @Composable RowScope.() -> Unit = {}) {
    var granted by remember { mutableStateOf(hasStorageAccess(s.ctx)) }
    var scan by remember { mutableIntStateOf(0) }
    var link by remember { mutableStateOf("") }
    val found by produceState<List<LocalApk>?>(null, granted, scan) {
        value = null
        if (granted) value = withContext(Dispatchers.IO) { runCatching { scanLocal(s.ctx) }.getOrDefault(emptyList()) }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("My games", "APKs already on the headset: Download, QuestRookie/Backups and other folders") {
            picker()
            FilledTonalButton({ scan++ }, Modifier.focusRing(RoundedCornerShape(24.dp)), enabled = granted) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Rescan")
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                link, { link = it }, Modifier.weight(1f), singleLine = true, shape = CircleShape,
                placeholder = { Text("Paste an APK link (https://…)") },
            )
            FilledTonalButton(
                { s.installUrl(link.trim()); link = "" }, Modifier.focusRing(RoundedCornerShape(24.dp)),
                enabled = link.isNotBlank(),
            ) { Text("Install link") }
        }
        if (granted && !s.installer.connected) Banner("Without ADB, Android asks you to confirm each APK; games with OBB data still need ADB.", "Connect") { connect(s) }
        val list = found
        when {
            !granted -> EmptyState(
                Icons.Default.Lock, "Storage access needed",
                "Quest Rookie needs access to shared storage to find APK files. It can grant this to itself over ADB.",
            ) {
                Button({
                    s.task("Granting storage access") {
                        val pkg = s.ctx.packageName
                        s.installer.shell("appops set --uid $pkg MANAGE_EXTERNAL_STORAGE allow; pm grant $pkg android.permission.READ_EXTERNAL_STORAGE; true")
                        granted = hasStorageAccess(s.ctx)
                        if (granted) "Storage access granted" else "Couldn't grant storage access"
                    }
                }, Modifier.focusRing(RoundedCornerShape(24.dp)), enabled = s.busy == null) { Text("Grant over ADB") }
            }
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyState(Icons.Default.Search, "No APKs found", "Copy APKs to the headset's Download folder, or back up an installed app.")
            else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(list, key = { it.files.first().path }) { LocalRow(s, it) }
            }
        }
    }
}

@Composable
private fun LocalRow(s: AppState, a: LocalApk) {
    val colors = MaterialTheme.colorScheme
    val installed = s.versions[a.pkg]
    val icon = { a.info.loadIcon(s.ctx.packageManager) }
    val folder = a.files.first().parent.orEmpty().removePrefix(Environment.getExternalStorageDirectory().path).ifEmpty { "/" }
    Card(Modifier.glass(), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AppIcon("local:" + a.files.first().path, load = icon)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(a.label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (a.info.category == ApplicationInfo.CATEGORY_GAME) Pill("Game")
                    if (a.obb != null) Pill("OBB")
                    when {
                        installed == null -> {}
                        installed < a.versionCode -> Pill("Newer than installed", Orange, Color(0xFF221500))
                        installed == a.versionCode -> Pill("Installed", Green, Color(0xFF062015))
                        else -> Pill("Older than installed")
                    }
                }
                Text("${a.pkg} · v${a.versionName} · ${formatSize(a.size)}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(folder, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button({ s.installLocal(a) }, Modifier.focusRing(RoundedCornerShape(24.dp))) { Text("Install") }
        }
    }
}

/** Installed apps, storage and APK files on the headset, behind one switch. Phase 2 merges them into one page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyGamesScreen(s: AppState) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val pages = listOf("Installed", "Storage", "Local files")
    // The sub-page picker sits in each page's top bar, so the list starts right under one row.
    val picker: @Composable RowScope.() -> Unit = { DropPill(pages.mapIndexed { i, l -> l to "$i" }, "$page", Modifier.width(170.dp)) { page = it.toInt() } }
    Column(Modifier.fillMaxSize()) {
        when (page) { 0 -> InstalledScreen(s, picker); 1 -> StorageScreen(s, picker); else -> LocalScreen(s, picker) }
    }
}
