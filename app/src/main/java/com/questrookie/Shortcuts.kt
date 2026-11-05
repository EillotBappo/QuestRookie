package com.questrookie

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.Html
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

// Serves one installable web app per sideloaded app on localhost (a secure origin, so Quest Browser allows
// "Add to library"). Each page fires questrookie://launch/<pkg>; MainActivity launches the real app.
// A service worker caches every page, so library icons keep working when this server isn't running.
// KeepAliveService stops Android freezing the process, which would hang every page load.
object Shortcuts {
    const val PORT = 8765
    private var server: ServerSocket? = null

    fun start(ctx: Context) {
        serve(ctx.applicationContext) // bind now so the browser never races the service
        KeepAliveService.start(ctx)
    }

    fun apps(ctx: Context) = ctx.packageManager.getInstalledApplications(0)
        .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != ctx.packageName }
        .filter { ctx.packageManager.getLaunchIntentForPackage(it.packageName) != null }
        .sortedBy { label(ctx, it.packageName) }

    private fun label(ctx: Context, pkg: String) = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun serve(ctx: Context) {
        if (server != null) return
        val s = ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1")).also { server = it }
        thread(isDaemon = true) {
            while (true) {
                val sock = s.accept()
                thread {
                    sock.use {
                        val path = it.getInputStream().bufferedReader().readLine()?.split(' ')?.getOrNull(1) ?: return@use
                        val (type, body) = route(ctx, path.substringBefore('?'))
                        val head = if (body == null) "HTTP/1.1 404 Not Found\r\n\r\n"
                        else "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        it.getOutputStream().apply { write(head.toByteArray()); body?.let(::write); flush() }
                    }
                }
            }
        }
    }

    private fun route(ctx: Context, path: String): Pair<String, ByteArray?> {
        val html = "text/html; charset=utf-8"
        if (path == "/") return html to listPage(ctx).toByteArray()
        if (path == "/sw.js") return "text/javascript" to SW.toByteArray()
        val m = Regex("^/a/([A-Za-z0-9._]+)/(|manifest\\.json|icon\\.png)$").find(path) ?: return "" to null
        val (pkg, file) = m.destructured
        if (ctx.packageManager.getLaunchIntentForPackage(pkg) == null) return "" to null
        val name = label(ctx, pkg)
        return when (file) {
            "manifest.json" -> "application/manifest+json" to org.json.JSONObject(mapOf(
                "id" to "/a/$pkg/", "name" to name, "short_name" to name, "start_url" to "/a/$pkg/", "scope" to "/a/$pkg/",
                "display" to "standalone", "background_color" to "#181a24",
                "icons" to org.json.JSONArray(listOf(mapOf("src" to "icon.png", "sizes" to "512x512", "type" to "image/png"))),
            )).toString().toByteArray()
            "icon.png" -> "image/png" to icon(ctx, pkg)
            else -> html to appPage(pkg, esc(name)).toByteArray()
        }
    }

    private fun icon(ctx: Context, pkg: String): ByteArray {
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        ctx.packageManager.getApplicationIcon(pkg).apply { setBounds(0, 0, 512, 512); draw(Canvas(bmp)) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun esc(s: String) = Html.escapeHtml(s)

    private const val STYLE = """<meta name="viewport" content="width=device-width, initial-scale=1"><style>
body{margin:0;padding:24px;background:#181a24;color:#eee;font:18px system-ui,sans-serif}
a.b{display:inline-block;padding:16px 32px;border-radius:12px;background:#ffaa28;color:#181a24;font-weight:700;text-decoration:none}
li{display:flex;align-items:center;gap:16px;margin:12px 0}li img{width:64px;height:64px;border-radius:12px}li span{flex:1}
</style><script>navigator.serviceWorker&&navigator.serviceWorker.register('/sw.js')</script>"""

    private fun listPage(ctx: Context) = """<!doctype html><html><head><meta charset="utf-8"><title>Quest Rookie shortcuts</title>$STYLE</head><body>
<h2>Add apps to your library</h2>
<p>For each app: tap <b>Open</b>, then ⋯ menu → <b>Add to library</b>, then go back.</p><ul style="padding:0;list-style:none">
${apps(ctx).joinToString("\n") { a -> val p = a.packageName
        """<li><img src="/a/$p/icon.png"><span>${esc(label(ctx, p))}</span><a class="b" href="/a/$p/?add=1">Open</a></li>""" }}
</ul></body></html>"""

    // ?add=1 (from the list page) skips the auto-launch so you can use "Add to library" first.
    private fun appPage(pkg: String, name: String) = """<!doctype html><html><head><meta charset="utf-8"><title>$name</title>
<link rel="manifest" href="manifest.json">$STYLE</head><body style="text-align:center">
<img src="icon.png" width="160" style="border-radius:24px"><h2>$name</h2>
<p><a class="b" id="go" href="intent://launch/$pkg#Intent;scheme=questrookie;package=com.questrookie;end">Launch</a></p>
<p>Not in your library yet? ⋯ menu → Add to library</p>
<script>if(!location.search.includes('add=1'))location.href=document.getElementById('go').href</script>
</body></html>"""

    private const val SW = """self.addEventListener('install',()=>self.skipWaiting());
self.addEventListener('activate',e=>e.waitUntil(clients.claim()));
self.addEventListener('fetch',e=>{const u=new URL(e.request.url);const k=u.origin+u.pathname;
e.respondWith(fetch(e.request).then(r=>{const c=r.clone();caches.open('qr').then(x=>x.put(k,c));return r})
.catch(()=>caches.match(k)))});"""

    fun openInBrowser(ctx: Context) = ctx.startActivity(
        Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://localhost:$PORT/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    /** Handles questrookie://launch/<pkg>; returns true if it launched something. */
    fun handle(ctx: Context, intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != "questrookie" || uri.host != "launch") return false
        val pkg = uri.lastPathSegment ?: return false
        ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false)
        return true
    }
}
