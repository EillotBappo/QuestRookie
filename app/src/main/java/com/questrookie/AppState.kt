package com.questrookie

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class GameState { NotInstalled, Installed, Update }

/** Partial downloads untouched for this long are deleted at startup. */
private const val PARTIAL_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

/** Shared state for every screen. Lives as long as the activity. */
class AppState(
    val ctx: Context,
    val rookie: Rookie,
    val installer: Installer,
    private val scope: CoroutineScope,
) {
    /** Extra Rookie-protocol sources beside the primary mirror, e.g. a vrhub-server on a Pi. */
    var extras by mutableStateOf(rookie.extras())
        private set
    var games by mutableStateOf(rookie.games() + extras.flatMap { it.games() })

    /** The built-in catalogs plus the F-Droid-style repos added in Sources, saved as name -> index URL. */
    var catalogs by mutableStateOf(CATALOGS + savedRepos().let { o -> o.keys().asSequence().map { RepoCatalog(it, o.getString(it)) }.toList() })
        private set
    private val repoPrefs get() = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
    private fun savedRepos() = JSONObject(repoPrefs.getString("repos", null) ?: "{}")

    /** True until the built-in catalogs have loaded from cache; Library shows skeleton cards meanwhile. */
    var loadingCatalogs by mutableStateOf(true)
        private set

    init {
        // The F-Droid cache alone is ~1 MB of JSON; parsing it on the main thread froze startup for seconds.
        scope.launch {
            val cached = withContext(Dispatchers.IO) { catalogs.flatMap { it.cached(rookie.root) } }
            games = games + cached
            loadingCatalogs = false
        }
    }

    /** The source a game downloads from. Catalog games use the primary only for its plain HTTP download. */
    fun sourceFor(g: Game) = extras.firstOrNull { it.label == g.source } ?: rookie

    fun thumbnail(g: Game) = sourceFor(g).thumbnail(g.pkg) ?: rookie.thumbnail(g.pkg)

    /** Store-page text and screenshots: a catalog's saved copy, or for mirror games OculusDB plus the mirror's own notes. Blocking. */
    fun details(g: Game): Details? {
        catalogs.firstOrNull { it.name == g.source }?.let { return it.details(rookie.root, g.pkg) }
        val notes = sourceFor(g).notes(g.release).orEmpty()
        return (oculusDb(ctx.cacheDir, g.pkg) ?: Details()).copy(whatsNew = notes).takeIf { it != Details() }
    }

    /**
     * Saves a new source and says what happened. [input] is a repo's index-v1.json URL (its apps load right away),
     * or anything Setup accepts (a config.json URL, pasted JSON), whose games show after the next refresh.
     */
    fun addSource(name: String, input: String, key: String): String {
        require(name.matches(Regex("[A-Za-z0-9 _.-]{1,24}")) && name != rookie.label && catalogs.none { it.name == name }) { "Pick a short name no other source uses" }
        require(extras.none { it.label == name }) { "A source called $name already exists" }
        val url = input.trim()
        if (url.endsWith("index-v1.json")) {
            require(url.startsWith("https://") || url.startsWith("http://")) { "Paste the repo's full http(s) link" }
            val repo = RepoCatalog(name, url)
            // refresh() falls back to the (empty) cache on any failure, so no apps means the link didn't work.
            val list = repo.refresh(rookie.root)
            require(list.isNotEmpty()) { "No apps found at that link" }
            repoPrefs.edit().putString("repos", savedRepos().put(name, url).toString()).apply()
            catalogs = catalogs + repo
            games = games + list
            return "Added $name · ${list.size} apps"
        }
        val src = rookie.extra(name)
        try {
            src.saveConfig(input)
            if (key.isNotBlank()) src.saveApiKey(key)
        } catch (e: Exception) {
            src.root.deleteRecursively()
            throw e
        }
        extras = rookie.extras()
        return "Added $name. Tap Refresh to load its games"
    }

    fun removeSource(name: String) {
        if (catalogs.any { it.name == name && it is RepoCatalog }) {
            repoPrefs.edit().putString("repos", savedRepos().apply { remove(name) }.toString()).apply()
            catalogs = catalogs.filter { it.name != name }
        }
        extras.firstOrNull { it.label == name }?.root?.deleteRecursively()
        extras = rookie.extras()
        games = games.filter { it.source != name }
    }
    var apps by mutableStateOf(emptyList<AppInfo>())
    var versions by mutableStateOf(emptyMap<String, Long>())
    var obbSizes by mutableStateOf(emptyMap<String, Long>())
    /** Package -> size of /sdcard/Android/data/<pkg>. */
    var dataSizes by mutableStateOf(emptyMap<String, Long>())
    /** Every installed package, system apps included, from `pm list packages`. Empty until connected. */
    var packages by mutableStateOf(emptySet<String>())
    /** Packages turned off with `pm disable-user`. */
    var disabled by mutableStateOf(emptySet<String>())
    /** Package -> last time the user opened it. Empty until usage access is granted. */
    var lastPlayed by mutableStateOf(emptyMap<String, Long>())
    /** Package -> total time in the foreground, in ms, all time. */
    var playtime by mutableStateOf(emptyMap<String, Long>())
    /** Foreground time across all apps over the last 7 days, in ms. */
    var weeklyPlaytime by mutableStateOf(0L)
    /** Non-null while a task is running; shown as the busy label. */
    var busy by mutableStateOf<String?>(null)
    var status by mutableStateOf("")
    /** Shows the Wireless debugging pair dialog. */
    var pairing by mutableStateOf(false)

    private val prefs = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
    /** Scales every dp and sp in the app. 1 is standard size. */
    var uiScale by mutableFloatStateOf(prefs.getFloat("scale", 0.8f))
        private set

    fun chooseUiScale(v: Float) {
        uiScale = v
        prefs.edit().putFloat("scale", v).apply()
    }

    /** Packages the user starred in Library. */
    var favourites by mutableStateOf(prefs.getStringSet("favourites", emptySet()).orEmpty())
        private set

    /** Hides titles marked (18+) everywhere in Library. On until the user turns it off. */
    var hideAdult by mutableStateOf(prefs.getBoolean("hideAdult", true))
        private set

    fun chooseHideAdult(v: Boolean) {
        hideAdult = v
        prefs.edit().putBoolean("hideAdult", v).apply()
    }

    fun toggleFavourite(pkg: String) {
        val next = favourites.toMutableSet().apply { if (!add(pkg)) remove(pkg) }
        favourites = next
        prefs.edit().putStringSet("favourites", next).apply()
    }

    private val downloadPrefs = ctx.getSharedPreferences("downloads", Context.MODE_PRIVATE)
    /** Downloads wait for Wi-Fi / a charger when these are on. Off by default. */
    var wifiOnly by mutableStateOf(downloadPrefs.getBoolean("wifiOnly", false))
        private set
    var chargingOnly by mutableStateOf(downloadPrefs.getBoolean("chargingOnly", false))
        private set

    fun chooseWifiOnly(v: Boolean) {
        wifiOnly = v
        downloadPrefs.edit().putBoolean("wifiOnly", v).apply()
    }

    fun chooseChargingOnly(v: Boolean) {
        chargingOnly = v
        downloadPrefs.edit().putBoolean("chargingOnly", v).apply()
    }

    private fun blockReason(item: QueueItem) = downloadBlock(
        item.needsNetwork, wifiOnly, onWifi = !wifiOnly || onWifi(ctx),
        chargingOnly = chargingOnly, charging = !chargingOnly || isCharging(ctx),
    )

    val queue = DownloadQueue(
        scope, File(ctx.filesDir, "queue.json"), ::rebuild, ::blockReason, ::notifyFinished,
    ) { item ->
        notifyInstalled(item)
        scope.launch { refreshApps() }
    }

    init {
        ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("done", "Installs", NotificationManager.IMPORTANCE_DEFAULT))
        // Before the queue starts, so no live download can lose its files.
        val cleared = clearStalePartials()
        if (cleared > 0) status = "Cleared ${count(cleared, "old partial download")}"
        queue.load()
        scope.launch { refreshApps() }
    }

    /** Partial downloads and leftover extractions older than [PARTIAL_MAX_AGE_MS] are deleted. A job that needs them again restarts. */
    private fun clearStalePartials(): Int {
        val cutoff = System.currentTimeMillis() - PARTIAL_MAX_AGE_MS
        val stale = (listOf(rookie) + extras).flatMap { it.downloadDir.listFiles().orEmpty().toList() }.filter { it.lastModified() < cutoff }
        stale.forEach { it.deleteRecursively() }
        return stale.size
    }

    private fun notifyFinished(installed: Int, failed: Int) {
        val note = Notification.Builder(ctx, "done")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Downloads finished")
            .setContentText("$installed installed" + if (failed > 0) ", $failed failed" else "")
            .setAutoCancel(true)
            .build()
        // Item ids start at 1, so 0 can't clash with a per-install notification.
        ctx.getSystemService(NotificationManager::class.java).notify(0, note)
    }

    // GitHub entries are keyed "github:owner/repo"; the real package name is learned from the APK the first time one installs.
    private val realPkgs = ctx.getSharedPreferences("packages", Context.MODE_PRIVATE)

    /** The Android package a title installs as. */
    fun installedPkg(g: Game): String = realPkg(g.pkg)
    private fun realPkg(key: String) = if (":" in key) realPkgs.getString(key, null) ?: key else key

    fun stateOf(g: Game): GameState {
        val installed = versions[installedPkg(g)] ?: return GameState.NotInstalled
        return if ((g.version.toLongOrNull() ?: 0) > installed) GameState.Update else GameState.Installed
    }

    /** Rerun of the last failed task; the snackbar offers Retry while it's set. */
    var retry by mutableStateOf<(() -> Unit)?>(null)

    /** Why the VRSrc mirror list last failed to refresh, or null; the store's VRSrc row shows it with a Copy button. */
    var vrsrcError by mutableStateOf<Throwable?>(null)

    fun refreshVrsrc() {
        CoroutineScope(Dispatchers.IO + scope.coroutineContext).launch {
            try {
                vrsrcError = null // Clear previous error state immediately
                val progress: Progress = { _, _, _ -> /* optional UI sync */ }
                rookie.refreshMeta(progress)
                withContext(Dispatchers.Main) { status = "VR Source refreshed" }
            } catch (e: Exception) {
                vrsrcError = e // Catches any unexpected throws from the fallback logic above
                withContext(Dispatchers.Main) { status = "VR Source refresh failed" }
            }
        }
    }

    /** Runs one foreground job off the main thread, reporting its result in [status]. */
    fun task(label: String, block: suspend () -> String): kotlinx.coroutines.Job = scope.launch {
        busy = label
        retry = null
        status = try { withContext(Dispatchers.IO) { block() } } catch (e: Exception) { retry = { task(label, block) }; friendlyError(e) }
        busy = null
    }

    suspend fun refreshApps() {
        val (list, obb, usage) = withContext(Dispatchers.IO) {
            Triple(
                // Mirror lists are games; the built-in catalogs (F-Droid above all) are mostly apps and tools.
                installedApps(ctx, games.filter { g -> catalogs.none { it.name == g.source } }.mapTo(HashSet()) { it.pkg }),
                if (installer.connected) runCatching { installer.obbSizes() }.getOrDefault(emptyMap()) else emptyMap(),
                usageSince(0),
            )
        }
        val week = withContext(Dispatchers.IO) { usageSince(System.currentTimeMillis() - WEEK_MS) }
        apps = list
        obbSizes = obb
        versions = list.associate { it.pkg to it.versionCode }
        lastPlayed = usage.mapValues { it.value.lastTimeUsed }
        playtime = usage.mapValues { it.value.totalTimeInForeground }
        weeklyPlaytime = week.values.sumOf { it.totalTimeInForeground }
        val (data, pkgs) = withContext(Dispatchers.IO) {
            if (!installer.connected) Pair(emptyMap<String, Long>(), emptySet<String>())
            else runCatching { Pair(installer.dataSizes(), installer.packageNames()) }
                .getOrDefault(Pair(emptyMap<String, Long>(), emptySet<String>()))
        }
        dataSizes = data
        packages = pkgs
        disabled = withContext(Dispatchers.IO) {
            if (installer.connected) runCatching { installer.disabledPackages() }.getOrDefault(emptySet()) else emptySet()
        }
    }

    /** Usage access is a special permission the app grants itself over ADB, see [grantUsageAccess]. */
    fun hasUsageAccess(): Boolean =
        ctx.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED

    private fun usageSince(sinceMs: Long): Map<String, UsageStats> =
        if (!hasUsageAccess()) emptyMap()
        else ctx.getSystemService(UsageStatsManager::class.java)
            .queryAndAggregateUsageStats(sinceMs, System.currentTimeMillis())

    fun grantUsageAccess() = task("Granting usage access") {
        installer.shell("appops set --uid ${ctx.packageName} GET_USAGE_STATS allow")
        refreshApps()
        if (hasUsageAccess()) "Usage access granted" else "Couldn't grant usage access"
    }

    fun freeSpace(): Long = StatFs(rookie.root.path).availableBytes

    fun totalSpace(): Long = StatFs(rookie.root.path).totalBytes

    /** Peak disk need for a game: the downloaded parts plus the joined archive, before the parts are deleted. */
    private fun reserveFor(g: Game) = ((g.sizeMb.toDoubleOrNull() ?: 0.0) * 2 * 1_048_576).toLong()

    /** Space promised to jobs already queued, so two big games can't both pass the check. */
    private fun pendingBytes() = queue.items.filter { it.active }.sumOf { it.reserve }

    fun install(g: Game) {
        val need = reserveFor(g)
        val free = freeSpace() - pendingBytes()
        if (need > free) {
            status = "Not enough space for ${g.name}: needs ${formatSize(need)}, ${formatSize(free.coerceAtLeast(0))} free"
            return
        }
        enqueue(gameItem(g))
    }

    fun updateAll() = games.filter { stateOf(it) == GameState.Update }.forEach { install(it) }

    fun installLocal(a: LocalApk) {
        val need = a.size + (a.obb?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L)
        val free = freeSpace() - pendingBytes()
        if (need > free) {
            status = "Not enough space for ${a.label}: needs ${formatSize(need)}, ${formatSize(free.coerceAtLeast(0))} free"
            return
        }
        enqueue(
            localItem(
                a.label, "Local APK · v${a.versionName} · ${formatSize(a.size)}", a.pkg,
                a.files.map { it.path }, a.obb?.path, icon = { a.info.loadIcon(ctx.packageManager) }, reserve = need,
            )
        )
    }

    fun enqueue(item: QueueItem) {
        if (queue.add(item)) KeepAliveService.start(ctx)
    }

    /** Queues an APK from any http(s) link. Its size is unknown up front, so no space check. */
    fun installUrl(url: String) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            status = "Paste a full http(s) link to an APK"
            return
        }
        enqueue(urlItem(url))
    }

    /** ADB when it's there; else Android's own installer for APK-only titles. OBB data has no ADB-free path (Android 11+ hides other apps' obb folders). */
    private fun installAny(apks: List<File>, obb: File?, pkg: String, p: Progress) {
        // A cut-off download or an HTML error page saved as .apk fails here, before any OBB is pushed. Split APKs don't parse alone, so one valid base is enough.
        check(apks.any { ctx.packageManager.getPackageArchiveInfo(it.path, 0) != null }) { "Not a valid APK: the download was cut off, or the link or file isn't an APK" }
        when {
            installer.connected || runCatching { installer.connect() }.isSuccess -> installer.install(apks, obb, pkg, p)
            obb == null || !obb.isDirectory -> PackageInstall.install(ctx, apks, p)
            else -> throw NeedsPairing()
        }
    }

    private fun urlItem(url: String) = QueueItem(
        "url:$url", url.substringBefore('?').substringAfterLast('/').ifEmpty { url }, "Direct APK link", null,
        spec = JSONObject().put("type", "url").put("url", url), needsNetwork = true,
    ) { p ->
        val apk = File(ctx.cacheDir, "link.apk")
        try {
            rookie.fetchUrl(url, apk, p)
            installAny(listOf(apk), null, "", p)
        } finally {
            apk.delete()
        }
    }

    private fun gameItem(g: Game) = QueueItem(
        g.release, g.name, "v${g.version} · ${g.sizeLabel}", g.pkg,
        spec = JSONObject().put("type", "game").put("name", g.name).put("release", g.release).put("pkg", g.pkg)
            .put("version", g.version).put("updated", g.updated).put("sizeMb", g.sizeMb).put("source", g.source).put("apkUrl", g.apkUrl)
            .put("iconUrl", g.iconUrl).put("thumbUrl", g.thumbUrl),
        reserve = reserveFor(g), thumb = thumbnail(g), needsNetwork = true, thumbUrl = g.thumbUrl, iconUrl = g.iconUrl,
    ) { p ->
        // An update keeps Android/data, but a save backup first makes a bad build or a signature change recoverable.
        if (stateOf(g) == GameState.Update && installer.connected) runCatching { installer.backupSaves(installedPkg(g)) }
        if (g.apkUrl.isNotEmpty()) {
            val apk = File(ctx.cacheDir, apkFileName(g.pkg))
            try {
                rookie.fetchUrl(g.apkUrl, apk, p)
                val pkg = if (":" in g.pkg) ctx.packageManager.getPackageArchiveInfo(apk.path, 0)?.packageName?.also {
                    realPkgs.edit().putString(g.pkg, it).apply()
                }.orEmpty() else g.pkg
                installAny(listOf(apk), null, pkg, p)
            } finally { apk.delete() }
            return@QueueItem
        }
        val dir = sourceFor(g).fetch(g, p)
        try {
            val script = dir.installTxt()
            if (script != null) installer.runInstallTxt(dir, script, p) else installAny(dir.apks(), File(dir, g.pkg), g.pkg, p)
        } finally { dir.deleteRecursively() }
    }

    private fun localItem(title: String, subtitle: String, pkg: String, files: List<String>, obb: String?, icon: (() -> Drawable?)? = null, reserve: Long = 0) = QueueItem(
        "local:" + files.first(), title, subtitle, pkg,
        spec = JSONObject().put("type", "local").put("title", title).put("subtitle", subtitle).put("pkg", pkg)
            .put("files", JSONArray(files)).put("obb", obb ?: JSONObject.NULL),
        icon = icon, reserve = reserve,
    ) { p -> installAny(files.map(::File), obb?.let(::File), pkg, p) }

    /** Rebuilds a queued job from its saved spec. */
    private fun rebuild(s: JSONObject): QueueItem? = when (s.getString("type")) {
        "game" -> gameItem(Game(
            s.getString("name"), s.getString("release"), s.getString("pkg"), s.getString("version"), s.getString("updated"), s.getString("sizeMb"),
            source = s.optString("source", "Mirror"), apkUrl = s.optString("apkUrl"), iconUrl = s.optString("iconUrl"), thumbUrl = s.optString("thumbUrl"),
        ))
        "local" -> {
            val files = s.getJSONArray("files")
            localItem(
                s.getString("title"), s.getString("subtitle"), s.getString("pkg"),
                List(files.length()) { files.getString(it) }, if (s.isNull("obb")) null else s.getString("obb"),
            )
        }
        "url" -> urlItem(s.getString("url"))
        else -> null
    }

    private fun notifyInstalled(item: QueueItem) {
        val note = Notification.Builder(ctx, "done")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Installed")
            .setContentText(item.title)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(item.id, note)
    }

    /** Applies the game's tweaks over ADB first (if connected), then starts it. */
    /** Opens an app; [key] may be a catalog key like "github:owner/repo". Says so when there's nothing to open. */
    fun launch(key: String) {
        val pkg = realPkg(key)
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: run { status = "This app has no screen to open"; return }
        scope.launch {
            if (installer.connected) withContext(Dispatchers.IO) { runCatching { TweakStore.applyForLaunch(ctx, pkg, installer) } }
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
