package com.questrookie

import java.io.File

/** One `adb …` line from a release's install.txt, which Rookie runs instead of a plain APK install. */
sealed class InstallStep {
    data class Install(val apk: String) : InstallStep()
    data class Push(val src: String, val dest: String) : InstallStep()
    data class Shell(val cmd: String) : InstallStep()
}

/** Splits on spaces outside double quotes. */
private fun words(line: String) = Regex("\"([^\"]*)\"|(\\S+)").findAll(line).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()

/** Parses install.txt like Rookie: only lines starting with `adb` count; `-d`/`-s <id>` device selectors are dropped. */
fun parseInstallTxt(lines: List<String>): List<InstallStep> = lines.mapNotNull { raw ->
    val line = raw.trim()
    if (!line.startsWith("adb ")) return@mapNotNull null
    var w = words(line).drop(1)
    while (w.firstOrNull() == "-d" || w.firstOrNull() == "-e") w = w.drop(1)
    if (w.firstOrNull() == "-s") w = w.drop(2)
    when (w.firstOrNull()) {
        // Flags like -r -g come before the path; the APK is always last.
        "install" -> w.lastOrNull()?.takeIf { it.endsWith(".apk", true) }?.let { InstallStep.Install(it) }
        "push" -> if (w.size >= 3) InstallStep.Push(w[w.size - 2], w.last()) else null
        // Shell text is passed as written; quoting the remainder again would change its meaning.
        "shell" -> InstallStep.Shell(line.substringAfter(" shell ").trim())
        "uninstall" -> w.getOrNull(1)?.let { InstallStep.Shell("pm uninstall $it") }
        else -> null
    }
}

fun File.installTxt() = listFiles { f -> f.name.equals("install.txt", true) }?.firstOrNull()
