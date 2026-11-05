package com.questrookie

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Installs APKs through Android's own PackageInstaller, no ADB. Android asks the user to confirm once per install, so this is
 * the path for an unpaired headset; OBB data still needs ADB (Android 11+ hides other apps' Android/obb even from storage managers).
 */
object PackageInstall {
    private val waiting = ConcurrentHashMap<Int, Pair<CountDownLatch, Array<String?>>>()

    fun install(ctx: Context, apks: List<File>, progress: Progress) {
        val pi = ctx.packageManager.packageInstaller
        val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        val result = arrayOfNulls<String>(1)
        val latch = CountDownLatch(1)
        waiting[id] = latch to result
        pi.openSession(id).use { s ->
            val total = apks.sumOf { it.length() }
            var done = 0L
            apks.forEach { f ->
                progress("Installing ${f.name}", done, total)
                s.openWrite(f.name, 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                done += f.length()
            }
            val intent = Intent(ctx, Receiver::class.java).setAction(ACTION).setPackage(ctx.packageName)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender)
        }
        progress("Confirm the install in the headset", 0, 0)
        check(latch.await(10, TimeUnit.MINUTES)) { "No answer to the install prompt" }
        result[0]?.let { error(it) }
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Android's confirm dialog; from a background service it needs its own task.
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                else -> waiting.remove(id)?.let { (latch, result) ->
                    if (status != PackageInstaller.STATUS_SUCCESS) result[0] = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install refused (status $status)"
                    latch.countDown()
                }
            }
        }
    }

    private const val ACTION = "com.questrookie.INSTALL_RESULT"
}
