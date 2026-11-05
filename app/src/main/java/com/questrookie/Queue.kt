package com.questrookie

import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Reports a stage plus byte progress; total <= 0 means "no measurable progress". */
typealias Progress = (stage: String, done: Long, total: Long) -> Unit

/** Waits before each retry after a dropped connection (headset slept, Wi-Fi blipped). Partial downloads resume. */
private val RETRY_DELAYS_MS = longArrayOf(15_000, 60_000, 180_000)

/** How often held-back jobs are re-checked against the Wi-Fi and charger rules. */
private const val GATE_POLL_MS = 15_000L

enum class QState { Waiting, Running, Done, Failed, Cancelled }

class QueueItem(
    val key: String, val title: String, val subtitle: String, val pkg: String?,
    /** Enough to rebuild this job after a restart. See AppState.rebuild. */
    val spec: JSONObject,
    /** Bytes the job needs on disk while it runs. */
    val reserve: Long = 0,
    /** Downloads wait for the Wi-Fi and charger rules; local installs don't. */
    val needsNetwork: Boolean = false,
    val thumb: File? = null, val icon: (() -> Drawable?)? = null,
    /** Catalog art for jobs without a local thumbnail, shown the way the Store shows it. */
    val thumbUrl: String = "", val iconUrl: String = "",
    val work: suspend (Progress) -> Unit,
) {
    val id = ids.incrementAndGet()
    var state by mutableStateOf(QState.Waiting)
    var stage by mutableStateOf("Waiting")
    var progress by mutableFloatStateOf(-1f)
    var detail by mutableStateOf("")
    /** Failed because the headset isn't paired; the row offers pairing instead of a plain retry. */
    var needsPairing by mutableStateOf(false)
    val active get() = state == QState.Waiting || state == QState.Running
    internal var job: Deferred<Unit>? = null
    private var lastTime = 0L
    private var lastBytes = 0L
    private var rate = 0.0

    fun update(stage: String, done: Long, total: Long) {
        if (stage != this.stage) { this.stage = stage; lastTime = 0; rate = 0.0 }
        if (total <= 0) { progress = -1f; detail = ""; return }
        progress = (done.toFloat() / total).coerceIn(0f, 1f)
        val now = System.nanoTime()
        if (lastTime != 0L && now > lastTime) {
            val instant = (done - lastBytes) / ((now - lastTime) / 1e9)
            rate = if (rate == 0.0) instant else rate * 0.8 + instant * 0.2
        }
        lastTime = now; lastBytes = done
        detail = "${formatSize(done)} of ${formatSize(total)}" + if (rate > 0 && stage == "Downloading") " · ${formatSize(rate.toLong())}/s" else ""
    }

    fun reset() { state = QState.Waiting; stage = "Waiting"; progress = -1f; detail = ""; needsPairing = false }

    private companion object { val ids = AtomicInteger() }
}

/**
 * Runs installs one after another so they don't fight over bandwidth, disk and the ADB link.
 * Unfinished jobs are saved to [file] on every change and rebuilt with [rebuild] on the next launch.
 */
class DownloadQueue(
    private val scope: CoroutineScope,
    private val file: File,
    private val rebuild: (JSONObject) -> QueueItem?,
    /** Reason a job must wait right now (e.g. no Wi-Fi), or null to run it. */
    private val blockReason: (QueueItem) -> String?,
    /** Called once the queue has run out of jobs, with how many installed and failed. */
    private val onDrained: (installed: Int, failed: Int) -> Unit,
    private val onDone: (QueueItem) -> Unit,
) {
    val items = mutableStateListOf<QueueItem>()
    private var runner: Job? = null
    // A failed job must only fail its item: under a plain Job a failing async also cancels the parent, which here is the
    // UI's composition scope, and that silently shuts down the Recomposer (the app stops redrawing).
    private val jobs = kotlinx.coroutines.SupervisorJob(scope.coroutineContext[Job])

    val activeCount get() = items.count { it.active }
    fun active(key: String) = items.lastOrNull { it.key == key && it.active }

    /** Restores jobs saved by [save]. A job that was running starts over as waiting. */
    fun load() {
        val saved = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return
        for (i in 0 until saved.length()) {
            val entry = saved.getJSONObject(i)
            val item = rebuild(entry.getJSONObject("spec")) ?: continue
            when (QState.valueOf(entry.getString("state"))) {
                QState.Failed -> { item.state = QState.Failed; item.stage = entry.optString("error", "Failed") }
                QState.Cancelled -> { item.state = QState.Cancelled; item.stage = "Cancelled" }
                else -> {}
            }
            items += item
        }
        kick()
    }

    private fun save() {
        val arr = JSONArray()
        items.filter { it.state != QState.Done }.forEach {
            arr.put(JSONObject().put("state", it.state.name).put("spec", it.spec).put("error", if (it.state == QState.Failed) it.stage else ""))
        }
        file.writeText(arr.toString())
    }

    fun add(item: QueueItem): Boolean {
        if (active(item.key) != null) return false
        items.removeAll { it.key == item.key }
        items += item
        save()
        kick()
        return true
    }

    fun cancel(item: QueueItem) {
        if (item.state == QState.Waiting) { item.state = QState.Cancelled; item.stage = "Cancelled"; save() }
        item.job?.cancel()
    }

    fun retry(item: QueueItem) { item.reset(); save(); kick() }
    fun retryFailed() { items.filter { it.state == QState.Failed }.forEach { it.reset() }; save(); kick() }
    fun remove(item: QueueItem) { items.remove(item); save() }
    fun clearFinished() { items.removeAll { !it.active }; save() }

    private fun kick() {
        if (runner?.isActive == true) return
        runner = scope.launch {
            var installed = 0
            var failed = 0
            var ran = false
            while (true) {
                val waiting = items.filter { it.state == QState.Waiting }
                if (waiting.isEmpty()) break
                val next = waiting.firstOrNull { blockReason(it) == null }
                if (next == null) {
                    // Everything left is held back, e.g. no Wi-Fi. Show why, then check again.
                    waiting.forEach { it.stage = blockReason(it) ?: "Waiting" }
                    delay(GATE_POLL_MS)
                    continue
                }
                ran = true
                when (run(next)) {
                    QState.Done -> installed++
                    QState.Failed -> failed++
                    else -> {}
                }
            }
            if (ran) onDrained(installed, failed)
        }
    }

    private suspend fun run(item: QueueItem): QState {
        item.state = QState.Running
        item.update("Starting", 0, 0)
        save()
        // Retries live inside the async so Cancel reaches them during the wait too.
        val job = scope.async(Dispatchers.IO + jobs) {
            var retries = 0
            while (true) {
                try {
                    return@async item.work(item::update)
                } catch (e: IOException) {
                    // An unpaired headset isn't a dropped connection: waiting won't fix it, so fail at once.
                    if (e is NeedsPairing || retries == RETRY_DELAYS_MS.size) throw e
                    android.util.Log.w("QuestRookie", "${item.title}: retrying after ${e.javaClass.simpleName}", e)
                    val wait = RETRY_DELAYS_MS[retries++]
                    item.stage = "Connection lost, retrying in ${wait / 1000}s"
                    item.detail = ""
                    delay(wait)
                }
            }
        }
        item.job = job
        try {
            job.await()
            item.state = QState.Done; item.stage = "Installed"; item.progress = 1f; item.detail = ""
            save()
            onDone(item)
        } catch (e: CancellationException) {
            item.state = QState.Cancelled; item.stage = "Cancelled"; item.detail = ""
            save()
        } catch (e: Exception) {
            item.state = QState.Failed; item.stage = friendlyError(e); item.detail = ""
            item.needsPairing = e is NeedsPairing
            save()
        }
        return item.state
    }
}

@Composable
fun QueueProgress(item: QueueItem, modifier: Modifier = Modifier) {
    if (item.state != QState.Running) return
    if (item.progress < 0) LinearProgressIndicator(modifier) else LinearProgressIndicator({ item.progress }, modifier)
}

@Composable
fun DownloadsScreen(s: AppState) {
    val items = s.queue.items
    val active = s.queue.activeCount
    val free = s.freeSpace()
    val failed = items.count { it.state == QState.Failed }
    val installed = items.count { it.state == QState.Done }
    val summary = listOfNotNull(
        "$active active".takeIf { active > 0 }, "$installed installed".takeIf { installed > 0 }, "$failed failed".takeIf { failed > 0 },
        "${formatSize(free)} free",
    ).joinToString(" · ")
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Downloads", summary) {
            QChip(selected = s.wifiOnly, onClick = { s.chooseWifiOnly(!s.wifiOnly) }, label = { Text("Wi-Fi only") }, modifier = Modifier.focusRing(RoundedCornerShape(8.dp)))
            QChip(selected = s.chargingOnly, onClick = { s.chooseChargingOnly(!s.chargingOnly) }, label = { Text("Charging only") }, modifier = Modifier.focusRing(RoundedCornerShape(8.dp)))
            if (failed > 0) FilledTonalButton({ s.queue.retryFailed() }, Modifier.focusRing(RoundedCornerShape(24.dp))) { Text("Retry failed ($failed)") }
            if (items.size > active) TextButton({ s.queue.clearFinished() }, Modifier.focusRing(RoundedCornerShape(24.dp))) { Text("Clear finished") }
        }
        if (items.isEmpty()) EmptyState(
            Icons.AutoMirrored.Filled.List, "Nothing queued",
            "Pick a game in the Store, or an APK in My games → Local files. Installs run here one after another.",
        )
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            val running = items.firstOrNull { it.state == QState.Running }
            val waiting = items.filter { it.state == QState.Waiting }
            val finished = items.filter { !it.active }
            if (running != null) item(key = "now") { NowRunning(s, running) }
            // Waiting jobs are "up next"; done, failed and cancelled ones are history, newest first.
            if (waiting.isNotEmpty()) {
                item(key = "next") { QueueHeading("Up next") }
                items(waiting, key = { it.id }) { QueueRow(s, it) }
            }
            if (finished.isNotEmpty()) {
                item(key = "finished") { QueueHeading("Finished") }
                items(finished.asReversed(), key = { it.id }) { QueueRow(s, it) }
            }
        }
    }
}

@Composable
private fun QueueHeading(text: String) =
    Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** The job in progress, large: art, stage, a thick bar and Cancel. */
@Composable
private fun NowRunning(s: AppState, item: QueueItem) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
        val art = Modifier.fillMaxWidth(.4f).widthIn(max = 300.dp).aspectRatio(16 / 9f).clip(RoundedCornerShape(16.dp))
        if (item.icon != null) Box(art.background(colors.surfaceContainerHighest), contentAlignment = Alignment.Center) { AppIcon("q${item.id}", 72.dp, item.icon) }
        else GameArt(item.thumb, item.title, art, url = item.thumbUrl, icon = item.iconUrl)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(item.stage.uppercase(), color = colors.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            QueueProgress(item, Modifier.fillMaxWidth().height(12.dp).clip(CircleShape))
            Text(listOf(item.subtitle, item.detail).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1)
        }
        OutlinedButton({ s.queue.cancel(item) }, Modifier.height(Size.control).focusRing(RoundedCornerShape(Radius.pill))) { Text("Cancel", color = colors.error) }
    }
}

@Composable
private fun QueueRow(s: AppState, item: QueueItem) {
    val colors = MaterialTheme.colorScheme
    val running = item.state == QState.Running
    Card(Modifier.glass(), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val art = Modifier.fillMaxWidth(.3f).widthIn(max = 144.dp).aspectRatio(16 / 9f).clip(MaterialTheme.shapes.small)
            if (item.icon != null) Box(art.background(colors.surfaceContainerHighest), contentAlignment = Alignment.Center) { AppIcon("q${item.id}", 48.dp, item.icon) }
            else GameArt(item.thumb, item.title, art, url = item.thumbUrl, icon = item.iconUrl)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    when (item.state) {
                        QState.Waiting -> Pill("Queued")
                        QState.Running -> Pill("Working", colors.primaryContainer, colors.onPrimaryContainer)
                        QState.Done -> Pill("Done", Green, Color(0xFF062015))
                        QState.Failed -> Pill("Failed", colors.error, Color(0xFF2A0606))
                        QState.Cancelled -> Pill("Cancelled")
                    }
                }
                Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (item.state != QState.Waiting) Text(
                    listOf(item.stage, item.detail).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (item.state == QState.Failed) colors.error else colors.onSurface,
                )
                else if (item.stage != "Waiting") Text(item.stage, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                QueueProgress(item, Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            when (item.state) {
                QState.Waiting, QState.Running -> IconButton({ s.queue.cancel(item) }, Modifier.focusRing(CircleShape)) { Icon(Icons.Default.Close, "Cancel") }
                QState.Failed, QState.Cancelled -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.needsPairing) FilledTonalButton({ s.pairing = true }, Modifier.focusRing(RoundedCornerShape(24.dp))) { Text("Pair headset") }
                    IconButton({ s.queue.retry(item) }, Modifier.focusRing(CircleShape)) { Icon(Icons.Default.Refresh, "Retry") }
                    IconButton({ s.queue.remove(item) }, Modifier.focusRing(CircleShape)) { Icon(Icons.Default.Delete, "Remove") }
                }
                QState.Done -> Row(verticalAlignment = Alignment.CenterVertically) {
                    item.pkg?.let { FilledTonalButton({ s.launch(it) }, Modifier.focusRing(RoundedCornerShape(24.dp))) { Text("Launch") } }
                    IconButton({ s.queue.remove(item) }, Modifier.focusRing(CircleShape)) { Icon(Icons.Default.Delete, "Remove") }
                }
            }
        }
    }
}

/** Foreground service so Android doesn't freeze the process while the browser or a game is in front. */
// ponytail: runs until the app process dies; stop it when the queue drains if the notification bothers anyone.
class KeepAliveService : android.app.Service() {
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: android.content.Intent?) = null

    override fun onCreate() {
        super.onCreate()
        // Its own ADB link: the activity's Installer dies with the activity, this one has to outlive it.
        if (Automation.wanted(this)) scope.launch { Automation(this@KeepAliveService, newInstaller(this@KeepAliveService)).run() }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        getSystemService(android.app.NotificationManager::class.java)
            .createNotificationChannel(android.app.NotificationChannel("work", "Downloads & shortcuts", android.app.NotificationManager.IMPORTANCE_LOW))
        startForeground(1, android.app.Notification.Builder(this, "work").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Quest Rookie").setContentText("Downloads, library shortcuts and game tweaks").build())
        // Sticky: the tweak watcher should come back if Android reclaims the process while a game is up.
        return START_STICKY
    }

    companion object {
        fun start(ctx: android.content.Context) { ctx.startForegroundService(android.content.Intent(ctx, KeepAliveService::class.java)) }
    }
}
