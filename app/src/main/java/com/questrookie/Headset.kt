package com.questrookie

import android.content.Context
import android.os.Build

/**
 * What this headset can actually do. Meta's panels and defaults differ per model, and a prop the hardware can't honour
 * (90 Hz on a Quest 1) is silently ignored, so the Tweaks page only offers what will take effect.
 * [eyeWidth]/[eyeHeight] are the system's default eye-buffer size; render-resolution options are multiples of it.
 */
data class HeadsetModel(
    val name: String, val device: String, val rates: List<Int>, val eyeWidth: Int, val eyeHeight: Int,
    /** LCD/OLED models that still apply software chromatic-aberration correction (Quest 1 and 2). */
    val chroma: Boolean,
) {
    val maxRate get() = rates.max()
    /** Height for a chosen eye-buffer width, keeping the model's aspect. */
    fun heightFor(width: Int) = (width.toLong() * eyeHeight / eyeWidth).toInt()
    /** Default plus three steps up; the top step is what the GPU can still feed at 72 Hz in most titles. */
    val resolutions: List<Pair<String, String>> get() = listOf("Default" to "") + RES_STEPS.map { m ->
        val w = (eyeWidth * m).toInt() / 16 * 16
        "%.1f× (%d)".format(java.util.Locale.US, m, w) to w.toString()
    }
}

private val RES_STEPS = listOf(1.2, 1.4, 1.6)

private val MODELS = listOf(
    HeadsetModel("Quest 1", "monterey", listOf(72), 1216, 1344, chroma = true),
    HeadsetModel("Quest 2", "hollywood", listOf(72, 90, 120), 1440, 1584, chroma = true),
    HeadsetModel("Quest Pro", "seacliff", listOf(72, 90), 1800, 1920, chroma = false),
    HeadsetModel("Quest 3", "eureka", listOf(72, 90, 120), 1680, 1760, chroma = false),
    HeadsetModel("Quest 3S", "panther", listOf(72, 90, 120), 1440, 1584, chroma = false),
)

/** The model for a Build.DEVICE codename; an unknown device (emulator, phone, a future Quest) gets Quest 2's options. */
fun headsetModel(device: String? = Build.DEVICE): HeadsetModel =
    MODELS.firstOrNull { it.device == device } ?: MODELS[1].copy(name = "Headset", device = device.orEmpty())

/** The rate the display is running right now. The 2D panel runs at the system compositor rate, so this is the headset's real rate. */
// DisplayManager, not ctx.display: that throws on the application context and doesn't exist before API 30 (Quest 1).
fun currentRefreshRate(ctx: Context): Int? = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
    ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.refreshRate?.toInt()?.takeIf { it > 0 }

/** One session's frame stats from the VR runtime's once-a-second `VrApi` logcat lines (`FPS=68/90,…,Stale=3,…`). */
data class Perf(val target: Int, val fps: Double, val stalePct: Double, val samples: Int) {
    override fun toString() = "%.0f/%d fps · %.0f%% stale".format(java.util.Locale.US, fps, target, stalePct)
}

private val FPS = Regex("FPS=(\\d+)/(\\d+)")
private val STALE = Regex("Stale=(\\d+)")

/** Averages every VrApi line in [log]; null when there are too few to mean anything (the game ran for seconds, or logs nothing). */
fun parseVrApi(log: String, minSamples: Int = 10): Perf? {
    var n = 0; var fps = 0L; var target = 0; var stale = 0L
    for (line in log.lineSequence()) {
        val m = FPS.find(line) ?: continue
        n++; fps += m.groupValues[1].toLong(); target = m.groupValues[2].toInt()
        stale += STALE.find(line)?.groupValues?.get(1)?.toLong() ?: 0
    }
    if (n < minSamples || target == 0) return null
    return Perf(target, fps.toDouble() / n, stale * 100.0 / (n.toLong() * target), n)
}

/** What to change after a session, or null when it ran clean. Reprojection past a few percent feels worse than a lower rate. */
fun perfAdvice(p: Perf, m: HeadsetModel): String? = when {
    p.stalePct < 5 -> null
    p.target > m.rates.min() -> "Dropped frames at ${p.target} Hz: try ${m.rates.filter { it < p.target }.max()} Hz for this game"
    else -> "Dropped frames even at ${p.target} Hz: lower the render resolution or raise the CPU/GPU level"
}
