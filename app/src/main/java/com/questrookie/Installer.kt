package com.questrookie

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.io.IOException

// Talks to the headset's own adbd over loopback; needed because normal apps can't write /sdcard/Android/obb.
// adbd only listens on 5555 after `tcpip 5555`; [enableTcp] does that on-device via Wireless debugging (see WirelessAdb).
class Installer(keyDir: File, private val enableTcp: (() -> Unit)? = null) {
    private val priv = File(keyDir, "adbkey")
    private val pub = File(keyDir, "adbkey.pub")
    private var dadb: Dadb? = null
    var connected by mutableStateOf(false); private set

    @Synchronized
    fun connect(): Dadb {
        dadb?.let { return it }
        if (!priv.exists()) AdbKeyPair.generate(priv, pub)
        val d = try {
            open()
        } catch (e: IOException) {
            val enable = enableTcp ?: throw e
            enable()
            // adbd restarts after tcpip; give it a few seconds to listen again.
            retry(5) { Thread.sleep(1_000); open() }
        }
        connected = true
        return d.also { dadb = it }
    }

    private fun open(): Dadb = Dadb.create("127.0.0.1", 5555, AdbKeyPair.read(priv, pub)).also {
        it.shell("echo ok") // forces auth; headset shows "Allow USB debugging?" first time
    }

    private fun <T> retry(times: Int, block: () -> T): T {
        repeat(times - 1) { runCatching { return block() } }
        return block()
    }

    /** Drops a dead connection (e.g. headset rebooted) so the next call reconnects. */
    private fun <T> adb(block: (Dadb) -> T): T = try {
        block(connect())
    } catch (e: IOException) {
        synchronized(this) { runCatching { dadb?.close() }; dadb = null; connected = false }
        throw e
    }

    fun shell(cmd: String): String = adb { d ->
        val r = d.shell(cmd)
        check(r.exitCode == 0) { r.allOutput.trim().ifEmpty { "exit code ${r.exitCode}" } }
        r.allOutput
    }

    /** Android refuses an update signed by a different key. Those installs go through [installWithSaves] instead. */
    fun install(apks: List<File>, obb: File?, pkg: String, progress: Progress) {
        try {
            installFiles(apks, obb, pkg, progress)
        } catch (e: Exception) {
            if (e.message?.contains("UPDATE_INCOMPATIBLE") != true) throw e
            // Uninstall backs up the saves; they come back once the new build is in.
            progress("Keeping saves", 0, 0)
            uninstall(pkg)
            installFiles(apks, obb, pkg, progress)
            restoreSaves(pkg)
        }
    }

    private fun installFiles(apks: List<File>, obb: File?, pkg: String, progress: Progress) = adb { d ->
        require(apks.isNotEmpty()) { "No APK found" }
        progress("Installing APK", 0, 0)
        if (apks.size == 1) d.install(apks[0]) else d.installMultiple(apks)
        val dir = obb?.takeIf { it.isDirectory } ?: return@adb
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        val remote = "/sdcard/Android/obb/$pkg"
        d.shell("rm -rf \"$remote\" && mkdir -p \"$remote\"")
        val total = files.sumOf { it.length() }
        var done = 0L
        files.forEach {
            progress("Copying OBB", done, total)
            d.push(it, "$remote/${it.relativeTo(dir).path}")
            done += it.length()
        }
    }

    /** Runs a release's install.txt the way Rookie does, with paths relative to the release folder. */
    fun runInstallTxt(dir: File, script: File, progress: Progress) = adb { d ->
        parseInstallTxt(script.readLines()).forEach { step ->
            when (step) {
                is InstallStep.Install -> { progress("Installing ${step.apk}", 0, 0); d.install(File(dir, step.apk)) }
                is InstallStep.Push -> {
                    val src = File(dir, step.src)
                    // Like adb: pushing to a path ending in / puts the file or folder inside it.
                    val dest = if (step.dest.endsWith("/")) step.dest + src.name else step.dest
                    progress("Copying ${src.name}", 0, 0)
                    if (src.isDirectory) src.walkTopDown().filter { it.isFile }.forEach { d.push(it, "$dest/${it.relativeTo(src).path}") }
                    else d.push(src, dest)
                }
                is InstallStep.Shell -> { progress("Running ${step.cmd.take(40)}", 0, 0); d.shell(step.cmd) }
            }
        }
    }

    /** Some releases read the player name from these files on /sdcard (from Rookie's UsernameForm). */
    fun setUsername(name: String) {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,32}"))) { "Use letters, numbers, . _ or - only" }
        val json = "{\"username\":\"$name\"}"
        shell((listOf("settings put global username $name") + USER_JSONS.map { "echo '$json' > /sdcard/$it" }).joinToString(" && "))
    }

    fun osUpdatesEnabled() = "com.oculus.updater" !in disabledPackages()

    /** Meta's updater is the one system package Rookie lets you switch off; [setEnabled] refuses com.oculus.*. */
    fun setOsUpdates(enabled: Boolean) {
        shell(if (enabled) "pm enable com.oculus.updater" else "pm disable-user --user 0 com.oculus.updater")
    }

    /** The connection drops as the headset goes down, so a lost link here is success, not failure. */
    fun reboot() {
        runCatching { shell("reboot") }
        synchronized(this) { runCatching { dadb?.close() }; dadb = null; connected = false }
    }

    /** Forgets this app's ADB key so the headset asks "Allow USB debugging?" again (Rookie's Reset ADB auth). */
    fun resetAuth() {
        synchronized(this) { runCatching { dadb?.close() }; dadb = null; connected = false }
        priv.delete(); pub.delete()
    }

    /** Backs up the saves first: uninstalling deletes /sdcard/Android/data/<pkg>. */
    fun uninstall(pkg: String) {
        backupSaves(pkg)
        shell("pm uninstall $pkg")
    }

    /** Copies an installed app's APK(s), OBB and saves to /sdcard/QuestRookie/Backups/<pkg>, a layout Local files can reinstall. */
    fun backup(app: AppInfo): String {
        val dest = "$BACKUPS/${app.pkg}"
        val cmds = mutableListOf("rm -rf '$dest'", "mkdir -p '$dest'")
        app.apks.forEachIndexed { i, path -> cmds += "cp '$path' '$dest/${if (i == 0) app.pkg else File(path).nameWithoutExtension}.apk'" }
        cmds += "if [ -d /sdcard/Android/obb/${app.pkg} ]; then cp -r /sdcard/Android/obb/${app.pkg} '$dest/'; fi"
        shell(cmds.joinToString(" && "))
        backupSaves(app.pkg)
        return dest
    }

    /** Copies /sdcard/Android/data/<pkg> to the saves backup. Returns false if the app has no save folder. Never wipes an old backup unless there is something to replace it with. */
    fun backupSaves(pkg: String): Boolean =
        shell("if [ -d '$SAVES/$pkg' ]; then rm -rf '${savesDir(pkg)}' && mkdir -p '${savesDir(pkg)}' && cp -r '$SAVES/$pkg/.' '${savesDir(pkg)}/' && echo yes; fi")
            .contains("yes")

    /** Copies the saves backup back into /sdcard/Android/data/<pkg>. Returns false if there is no backup. */
    fun restoreSaves(pkg: String): Boolean =
        shell("if [ -d '${savesDir(pkg)}' ]; then mkdir -p '$SAVES/$pkg' && cp -r '${savesDir(pkg)}/.' '$SAVES/$pkg/' && echo yes; fi")
            .contains("yes")

    /** Turns a user app off without uninstalling it. Only ever called for non-system packages. */
    fun setEnabled(pkg: String, enabled: Boolean) {
        // Meta's own packages stay untouched: disabling them can break the headset.
        require(!pkg.startsWith("com.oculus.") && !pkg.startsWith("com.meta.")) { "Not a user app: $pkg" }
        shell(if (enabled) "pm enable $pkg" else "pm disable-user --user 0 $pkg")
    }

    fun disabledPackages(): Set<String> = shell("pm list packages -d").lines()
        .filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }.toSet()

    /** Removes an OBB folder left behind by an app that is no longer installed. */
    fun removeObb(pkg: String) = shell("rm -rf '/sdcard/Android/obb/$pkg'")

    fun packageNames(): Set<String> = shell("pm list packages").lines()
        .filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }.toSet()

    fun obbSizes(): Map<String, Long> = duSizes("/sdcard/Android/obb")

    fun dataSizes(): Map<String, Long> = duSizes("/sdcard/Android/data")

    private fun duSizes(dir: String): Map<String, Long> = shell("du -sk $dir/* 2>/dev/null; true").lines().mapNotNull { line ->
        val (kb, path) = line.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
        path.substringAfterLast('/') to (kb.trim().toLongOrNull() ?: return@mapNotNull null) * 1024
    }.toMap()

    private fun savesDir(pkg: String) = "$BACKUPS/saves/$pkg"

    companion object {
        const val BACKUPS = "/sdcard/QuestRookie/Backups"
        const val SAVES = "/sdcard/Android/data"
        private val USER_JSONS = listOf("user.json", "vrmoo.cn.json", "qq1091481055.json", "dollarvr.com.json")
    }
}

fun File.apks() = listFiles { f -> f.extension.equals("apk", true) }.orEmpty().toList()
