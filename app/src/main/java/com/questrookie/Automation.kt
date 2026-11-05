package com.questrookie

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.delay

/**
 * Applies per-game tweaks whichever way a game is started (Home, the Explore tab, a voice command), not only from this app,
 * and keeps the headset out of trouble: steps the CPU/GPU level down when it reports thermal pressure and drops to the
 * battery preset when the battery is low. Runs inside [KeepAliveService]; each check is one UsageStats query every [POLL_MS].
 */
class Automation(private val ctx: Context, private val installer: Installer) {
    private val prefs = ctx.getSharedPreferences("automation", Context.MODE_PRIVATE)
    private val perfPrefs = ctx.getSharedPreferences("perf", Context.MODE_PRIVATE)
    private var foreground: String? = null
    private var since = System.currentTimeMillis()
    /** Tweak keys this class has overridden on top of the user's choices, so they can be put back. */
    private var overridden = emptySet<String>()

    suspend fun run() {
        while (true) {
            runCatching { tick() }
            delay(POLL_MS)
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val pkg = foregroundPackage(now) ?: return
        if (pkg != foreground) {
            val was = foreground
            foreground = pkg
            if (was != null && TweakStore.hasProfile(ctx, was)) afterGame(was, now - since)
            since = now
            if (pkg != ctx.packageName && TweakStore.hasProfile(ctx, pkg) && autoProfiles) {
                if (!installer.connected) runCatching { installer.connect() }
                if (installer.connected) runCatching { TweakStore.applyForLaunch(ctx, pkg, installer) }
            }
        }
        if (installer.connected) guard()
    }

    private fun foregroundPackage(now: Long): String? {
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val events = usm.queryEvents(now - POLL_MS * 3, now)
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.getNextEvent(e)) if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        return last
    }

    /** Reads the session's frame stats and files advice for the game's row. The log buffer holds a few minutes, so long sessions report their tail. */
    private fun afterGame(pkg: String, durationMs: Long) {
        if (!installer.connected || durationMs < MIN_SESSION_MS) return
        runCatching {
            val perf = parseVrApi(installer.shell("logcat -d -s VrApi:I 2>/dev/null; true"))
            val advice = perf?.let { perfAdvice(it, headsetModel()) }
            perfPrefs.edit().putString(pkg, perf?.toString()?.let { if (advice != null) "$it — $advice" else it }).apply()
            // Globals come back once the game is gone.
            if (autoProfiles) TweakStore.applySaved(ctx, installer)
        }
    }

    /** Thermal and battery overrides; each one undone as soon as its cause clears. */
    private fun guard() {
        val perf = TWEAKS.first { it.key == "perf" }
        val hot = thermal && ctx.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        val low = lowBattery && (batteryPercent(ctx) ?: 100) < LOW_BATTERY && !isCharging(ctx)
        val want = mutableMapOf<String, String>()
        if (low) want += BATTERY_PRESET
        else if (hot) TweakStore.get(ctx, perf).toIntOrNull()?.takeIf { it > 2 }?.let { want["perf"] = (it - 1).toString() }
        val undo = overridden - want.keys
        undo.forEach { k -> TWEAKS.first { it.key == k }.let { t -> t.commands(TweakStore.get(ctx, t)).forEach(installer::shell) } }
        want.filterKeys { it !in overridden }.forEach { (k, v) -> TWEAKS.first { it.key == k }.commands(v).forEach(installer::shell) }
        overridden = want.keys
    }

    val autoProfiles get() = prefs.getBoolean("profiles", true)
    val thermal get() = prefs.getBoolean("thermal", true)
    val lowBattery get() = prefs.getBoolean("battery", false)

    companion object {
        const val POLL_MS = 2_000L
        private const val MIN_SESSION_MS = 60_000L
        private const val LOW_BATTERY = 20
        private val BATTERY_PRESET = mapOf("refresh" to "72", "texture" to "", "foveation" to "3", "perf" to "2")

        fun prefs(ctx: Context) = ctx.getSharedPreferences("automation", Context.MODE_PRIVATE)
        /** "68/90 fps · 12% stale — try 72 Hz…" from the game's last session, or null. */
        fun lastPerf(ctx: Context, pkg: String): String? = ctx.getSharedPreferences("perf", Context.MODE_PRIVATE).getString(pkg, null)
        /** Automation needs usage access and something to apply; no point keeping a service up otherwise. */
        fun wanted(ctx: Context) = prefs(ctx).getBoolean("profiles", true) || prefs(ctx).getBoolean("thermal", true) || prefs(ctx).getBoolean("battery", false)
    }
}
