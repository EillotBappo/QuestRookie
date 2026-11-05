package com.questrookie

import android.os.PowerManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** At-a-glance headset state plus what people do most: presets, recent games, storage and connection. */
@Composable
fun HomeScreen(s: AppState) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Home", MODEL.name)
        // Same staggered grid as Settings: the cards differ a lot in height.
        LazyVerticalStaggeredGrid(
            StaggeredGridCells.Adaptive(360.dp), Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalItemSpacing = 16.dp,
        ) {
            item(key = "headset") { HeadsetStatusCard(s) }
            item(key = "presets") { PresetsCard(s) }
            item(key = "recent") { RecentGamesCard(s) }
            item(key = "storage") { StorageConnectionCard(s) }
        }
    }
}

@Composable
private fun HomeCard(title: String, content: @Composable ColumnScope.() -> Unit) =
    Card(Modifier.glass(), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }

@Composable
private fun StatLine(label: String, value: String, tint: Color = MaterialTheme.colorScheme.onSurface) =
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = tint)
    }

private data class HeadsetNow(val pct: Int?, val charging: Boolean, val temp: Float?, val thermal: Int, val rate: Int?)

private val THERMAL = listOf("Normal", "Light", "Moderate", "Severe", "Critical", "Emergency", "Shutdown")

@Composable
private fun HeadsetStatusCard(s: AppState) {
    val power = remember { s.ctx.getSystemService(PowerManager::class.java) }
    fun read() = HeadsetNow(batteryPercent(s.ctx), isCharging(s.ctx), batteryTempC(s.ctx), power.currentThermalStatus, currentRefreshRate(s.ctx))
    // Android sends Compose no cheap change callback for any of these, so poll every 5 s like Settings does for the rate.
    val now by produceState(read()) { while (true) { kotlinx.coroutines.delay(5_000); value = read() } }
    val (pct, charging, temp, thermal, rate) = now
    HomeCard("Headset") {
        pct?.let {
            val tint = when { it < 20 -> MaterialTheme.colorScheme.error; it < 50 -> Orange; else -> Green }
            StatLine("Battery", "$it%" + if (charging) " · charging" else "", tint)
        }
        // Same "hot" line as Automation: the system's own thermal status at Moderate or worse.
        val hot = thermal >= PowerManager.THERMAL_STATUS_MODERATE
        StatLine(
            "Heat", listOfNotNull(temp?.let { "%.1f °C".format(java.util.Locale.US, it) }, THERMAL.getOrNull(thermal)).joinToString(" · "),
            if (hot) Orange else MaterialTheme.colorScheme.onSurface,
        )
        rate?.let { StatLine("Refresh rate", "$it Hz") }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PresetsCard(s: AppState) {
    val values = remember { mutableStateMapOf<String, String>().apply { TWEAKS.forEach { put(it.key, TweakStore.get(s.ctx, it)) } } }
    HomeCard("Performance") {
        Text("Applies to every game that has no tweaks of its own.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { PresetButtons(s, values) }
    }
}

@Composable
private fun RecentGamesCard(s: AppState) {
    val recent = remember(s.apps, s.lastPlayed) {
        s.apps.filter { it.isGame && (s.lastPlayed[it.pkg] ?: 0L) > 0 }.sortedByDescending { s.lastPlayed[it.pkg] }.take(3)
    }
    HomeCard("Continue playing") {
        if (recent.isEmpty()) Text("Games you play show up here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        recent.forEach { a -> RecentGameRow(s, a) }
    }
}

@Composable
private fun RecentGameRow(s: AppState, a: AppInfo) {
    var tune by remember { mutableStateOf(false) }
    if (tune) TuneDialog(s, a) { tune = false }
    Row(
        Modifier.fillMaxWidth().then(tuneTap(a) { tune = true }).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(a.pkg, 40.dp) { s.ctx.packageManager.getApplicationIcon(a.pkg) }
        Column(Modifier.weight(1f)) {
            Text(a.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Automation.lastPerf(s.ctx, a.pkg)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if ("—" in it) Orange else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (a.launchable && a.pkg !in s.disabled) FilledTonalButton({ s.launch(a.pkg) }, Modifier.focusRing(RoundedCornerShape(24.dp))) { Text("Launch") }
    }
}

@Composable
private fun StorageConnectionCard(s: AppState) {
    val ring = Modifier.focusRing(RoundedCornerShape(24.dp))
    HomeCard("Storage and connection") {
        StorageBar(s, Modifier.fillMaxWidth())
        StatLine("Free space", formatSize(s.freeSpace()))
        StatLine("ADB", if (s.installer.connected) "Connected" else "Not connected", if (s.installer.connected) Green else MaterialTheme.colorScheme.onSurfaceVariant)
        val n = s.queue.activeCount
        StatLine("Downloads", if (n > 0) "$n running" else "None running")
        if (!s.installer.connected) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton({ connect(s) }, ring, enabled = s.busy == null) { Text("Connect") }
            if (!WirelessAdb.isPaired(s.ctx)) TextButton({ s.pairing = true }, ring) { Text("Pair") }
        }
    }
}
