package com.questrookie

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Per-app storage, plus OBB and save folders left behind by apps that are no longer installed. */
@Composable
fun StorageScreen(s: AppState, picker: @Composable RowScope.() -> Unit = {}) {
    var removing by remember { mutableStateOf<String?>(null) }
    val rows = remember(s.apps, s.obbSizes, s.dataSizes) {
        s.apps.sortedByDescending { it.apkSize + (s.obbSizes[it.pkg] ?: 0) + (s.dataSizes[it.pkg] ?: 0) }
    }
    // Without the package list every folder looks orphaned, so leftovers only show once it has loaded.
    val leftovers = remember(s.obbSizes, s.dataSizes, s.packages) {
        if (s.packages.isEmpty()) emptyList()
        else (s.obbSizes.keys + s.dataSizes.keys).filter { it !in s.packages && it != s.ctx.packageName }.sorted()
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("My games", "Storage · ${formatSize(s.freeSpace())} free") { picker(); StorageBar(s) }
        if (!s.installer.connected) Banner("Connect over ADB to see OBB and save sizes.", "Connect") { connect(s) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text("Installed apps", style = MaterialTheme.typography.titleMedium) }
            items(rows, key = { it.pkg }) { a ->
                // OBB and save sizes come over ADB; until then they're unknown, not zero.
                val obb = s.obbSizes[a.pkg]
                val saves = s.dataSizes[a.pkg]
                StorageRow(
                    title = a.label,
                    detail = listOfNotNull("APK ${formatSize(a.apkSize)}", obb?.let { "OBB ${formatSize(it)}" }, saves?.let { "saves ${formatSize(it)}" }).joinToString(" · "),
                    total = formatSize(a.apkSize + (obb ?: 0) + (saves ?: 0)),
                )
            }
            if (leftovers.isNotEmpty()) {
                item { Text("Left behind by uninstalled apps", style = MaterialTheme.typography.titleMedium) }
                items(leftovers, key = { it }) { pkg ->
                    val obb = s.obbSizes[pkg]
                    val saves = s.dataSizes[pkg]
                    Card(Modifier.glass(), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(pkg, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(obb?.let { "OBB ${formatSize(it)}" }, saves?.let { "saves ${formatSize(it)}" }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (saves != null) FilledTonalButton(
                                { s.task("Backing up saves for $pkg") { if (s.installer.backupSaves(pkg)) "Saves backed up for $pkg" else "No saves for $pkg" } },
                                Modifier.focusRing(RoundedCornerShape(24.dp)), enabled = s.busy == null,
                            ) { Text("Back up saves") }
                            if (obb != null) Button({ removing = pkg }, Modifier.focusRing(RoundedCornerShape(24.dp)), enabled = s.busy == null) { Text("Remove OBB") }
                        }
                    }
                }
            }
        }
    }
    removing?.let { pkg ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove OBB for $pkg?") },
            text = { Text("Deletes the leftover OBB folder. Its saves in Android/data are not touched.") },
            confirmButton = {
                Button({
                    removing = null
                    s.task("Removing OBB for $pkg") { s.installer.removeObb(pkg); s.refreshApps(); "Removed OBB for $pkg" }
                }, Modifier.focusRing(RoundedCornerShape(24.dp)), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Remove") }
            },
            dismissButton = { TextButton({ removing = null }, Modifier.focusRing(RoundedCornerShape(24.dp))) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StorageRow(title: String, detail: String, total: String) {
    Card(Modifier.glass(), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Pill(total)
        }
    }
}
