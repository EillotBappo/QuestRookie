package com.questrookie

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** [popularity] is the list's Downloads column; 0 when the list has none. A non-empty [apkUrl] installs that APK directly instead of a mirror release. */
data class Game(
    val name: String, val release: String, val pkg: String, val version: String, val updated: String, val sizeMb: String,
    val popularity: Double = 0.0, val source: String = "Mirror", val apkUrl: String = "",
    /** Store rating (0 = none) and how many ratings it is based on, from the list's Rating columns. */
    val rating: Double = 0.0, val ratingCount: Int = 0,
    /** Cover or icon to fetch when the card is shown (catalog games); mirror games use their thumbnail files instead. */
    val iconUrl: String = "",
    /** Wide banner art (F-Droid feature graphic); used instead of the icon when it's a good thumbnail. */
    val thumbUrl: String = "",
) {
    /** Lists mark adult titles "(18+)" in the name or release. */
    val isAdult get() = "(18+)" in name || "(18+)" in release
}

// Mirrors Rookie's public-mirror protocol: config {baseUri, password(base64), fallbacks?} -> meta.7z -> md5(release+"\n")/*.7z.NNN
// [insecureTls] skips certificate checks on mirror requests (QuestVault's --no-check-certificate); debug builds only.
// [label] tags this source's games (Game.source); the primary mirror is "Mirror", extra sources use their folder name.
class Rookie(val root: File, private val insecureTls: Boolean = false, val label: String = "Mirror") {
    // Mirrors reject non-rclone clients. Same version QuestVault sends.
    private val userAgent = "rclone/v1.72.1"
    val metaDir = File(root, "meta")
    val downloadDir = File(root, "downloads")
    private val configFile = File(root, "config.json")

    var baseUri = ""; private set
    var password = ""; private set
    /** Optional mirror API key, sent as X-API-Key only when set. */
    var apiKey = ""; private set
    /** Primary mirror first, then any "fallbacks" from the config. */
    var mirrors = emptyList<String>(); private set

    fun loadConfig(): Boolean {
        if (!configFile.exists()) return false
        val j = JSONObject(configFile.readText())
        baseUri = normalize(j.getString("baseUri"))
        password = configPassword(j.getString("password"))
        apiKey = j.optString("apiKey")
        val fallbacks = j.optJSONArray("fallbacks")?.let { a -> List(a.length()) { normalize(a.getString(it)) } }.orEmpty()
        mirrors = (listOf(baseUri) + fallbacks).distinct()
        return true
    }

    /** Accepts pasted config JSON or a URL that serves it. */
    fun saveConfig(input: String) {
        root.mkdirs()
        val text = input.trim()
        val j = when {
            text.isEmpty() -> error("Paste a config JSON or a URL that serves it")
            text.startsWith("http") -> JSONObject(URL(text).readText())
            else -> JSONObject(text)
        }
        if (apiKey.isNotEmpty() && !j.has("apiKey")) j.put("apiKey", apiKey)
        configFile.writeText(j.toString())
        loadConfig()
    }

    fun saveApiKey(key: String) {
        val j = JSONObject(configFile.readText()).put("apiKey", key.trim())
        configFile.writeText(j.toString())
        loadConfig()
    }

    suspend fun refreshMeta(progress: Progress): List<Game> = fetchMeta(progress)

    private suspend fun fetchMeta(progress: Progress): List<Game> {
        firstMirror { base ->
            val archive = File(root, "meta.7z")
            try {
                download("$base/meta.7z", archive) { done, total -> progress("Downloading game list", done, total) }
                progress("Extracting game list", 0, 0)
                metaDir.deleteRecursively()
                extract(archive, metaDir) { _, _ -> }
            } finally {
                archive.delete()
            }
        }
        return games()
    }

    fun games(): List<Game> {
        val list = metaDir.listFiles { f -> f.name.endsWith("ameList.txt") }?.firstOrNull() ?: return emptyList()
        return parseGameList(list.readLines()).map { it.copy(source = label) }
    }

    /** Another Rookie-protocol source (a vrhub-server, a second mirror) stored in [root]/sources/<name>. */
    fun extra(name: String) = Rookie(File(root, "sources/$name"), insecureTls, name)

    /** Extra sources set up earlier, in name order. */
    fun extras(): List<Rookie> = File(root, "sources").listFiles { f -> File(f, "config.json").exists() }.orEmpty()
        .sortedBy { it.name }.map { extra(it.name).apply { loadConfig() } }

    fun thumbnail(pkg: String) = File(metaDir, ".meta/thumbnails/$pkg.jpg").takeIf { it.exists() }

    fun notes(release: String) = File(metaDir, ".meta/notes/$release.txt").takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    /** Downloads + extracts a release; returns its folder (apk(s) + optional <pkg>/ obb dir). Cancellable. */
    suspend fun fetch(game: Game, progress: Progress): File = firstMirror { base -> fetchFrom(base, game, progress) }

    /** Downloads one APK from any link. No mirror fallback. Cancellable. */
    suspend fun fetchUrl(url: String, dest: File, progress: Progress) =
        download(url, dest) { done, total -> progress("Downloading", done, total) }

    private suspend fun fetchFrom(base: String, game: Game, progress: Progress): File {
        val hash = MessageDigest.getInstance("MD5").digest((game.release + "\n").toByteArray())
            .joinToString("") { "%02x".format(it) }
        val dirUrl = "$base/$hash/"
        val parts = Regex("href=\"([^\"]+)\"").findAll(open(dirUrl).inputStream.bufferedReader().readText())
            .map { it.groupValues[1].substringAfterLast('/') }
            .filter { it.contains(".7z") }.distinct().sorted().toList()
        require(parts.isNotEmpty()) { "No archive parts found at $dirUrl" }

        downloadDir.mkdirs()
        val out = File(downloadDir, game.release)
        // Join split parts into one .7z as we go; delete each part once appended to cap disk use.
        // A dropped connection keeps the joined file so the retry resumes at the first missing part.
        val joined = File(downloadDir, "$hash.7z")
        val sizes = parts.map { open(dirUrl + it, "HEAD").run { contentLengthLong.also { disconnect() } } }
        val total = if (sizes.any { it < 0 }) 0 else sizes.sum()
        val first = resumeAt(joined, sizes)
        val base = sizes.take(first).sum()
        try {
            // Up to PARALLEL parts download at once (mirrors cap each connection), appended to the joined file in order.
            // ponytail: finished parts can pile up behind a slow earlier one; bounded in practice because parts are equal-sized.
            val partDone = LongArray(parts.size)
            val sem = kotlinx.coroutines.sync.Semaphore(PARALLEL)
            kotlinx.coroutines.coroutineScope {
                val fetched = parts.mapIndexed { i, name ->
                    if (i < first) null else async(kotlinx.coroutines.Dispatchers.IO) {
                        sem.withPermit {
                            val part = File(downloadDir, name)
                            // A dropped connection keeps the partial part so the retry resumes it with a Range request.
                            try {
                                download(dirUrl + name, part) { n, _ -> partDone[i] = n; progress("Downloading", base + partDone.sum(), total) }
                            } catch (e: Throwable) {
                                if (e !is java.io.IOException) part.delete()
                                throw e
                            }
                            part
                        }
                    }
                }
                fetched.forEach { d ->
                    val part = d?.await() ?: return@forEach
                    joined.appendBytesFrom(part)
                    part.delete()
                }
            }
        } catch (e: Throwable) {
            if (e !is java.io.IOException) joined.delete()
            throw e
        }
        try {
            extract(joined, downloadDir) { d, t -> progress("Extracting", d, t) }
        } catch (e: Throwable) {
            out.deleteRecursively()
            joined.delete()
            throw e
        }
        joined.delete()
        return out
    }

    /** Parts already appended to [joined] by an earlier attempt. Clears [joined] if it doesn't end on a part boundary. */
    private fun resumeAt(joined: File, sizes: List<Long>): Int {
        val have = joined.length()
        var acc = 0L
        var n = 0
        while (n < sizes.size && sizes[n] > 0 && acc + sizes[n] <= have) { acc += sizes[n]; n++ }
        if (have > 0 && acc == have) return n
        joined.delete()
        return 0
    }

    /** Runs [block] against each mirror in order until one succeeds. Cancellation stops at once; otherwise the last error is thrown. */
    private suspend fun <T> firstMirror(block: suspend (String) -> T): T {
        var last: Exception? = null
        for (base in mirrors) {
            try {
                return block(base)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("No mirror configured")
    }

    private fun normalize(u: String) = u.trim().trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" }

    /** [from] > 0 asks for the rest of the file. 416 is allowed through then: the caller decides what it means. */
    private fun open(url: String, method: String = "GET", from: Long = 0) = (URL(url).openConnection() as HttpURLConnection).apply {
        throttle()
        // The key and the TLS bypass are for the mirror only; direct APK links (GitHub etc.) never see them.
        val mirror = mirrors.any { url.startsWith(it) }
        if (mirror && insecureTls && this is HttpsURLConnection) { sslSocketFactory = TRUST_ALL.socketFactory; hostnameVerifier = HostnameVerifier { _, _ -> true } }
        requestMethod = method
        if (mirror && apiKey.isNotEmpty()) setRequestProperty("X-API-Key", apiKey)
        // Without these a stalled mirror holds the download forever.
        connectTimeout = 15_000
        readTimeout = 60_000
        setRequestProperty("User-Agent", userAgent)
        if (from > 0) setRequestProperty("Range", "bytes=$from-")
        if (responseCode !in 200..299 && !(from > 0 && responseCode == 416)) error("HTTP $responseCode for $url")
    }

    /** Writes into [dest]. If a partial file exists, continues it; a server that ignores Range sends the whole file and it is overwritten. */
    private suspend fun download(url: String, dest: File, onBytes: (Long, Long) -> Unit) {
        val have = if (dest.exists()) dest.length() else 0L
        val c = open(url, from = have)
        // 416: the partial file is already the whole file, or longer than the server's copy. Start clean.
        if (c.responseCode == 416) {
            c.disconnect()
            dest.delete()
            return download(url, dest, onBytes)
        }
        val resumed = have > 0 && c.responseCode == 206
        val base = if (resumed) have else 0L
        val total = c.contentLengthLong + base
        c.inputStream.use { input -> FileOutputStream(dest, resumed).use { copy(input, it) { done -> onBytes(base + done, total) } } }
    }

    private suspend fun extract(archive: File, dest: File, progress: (Long, Long) -> Unit) {
        SevenZFile.builder().setFile(archive).setPassword(password.toCharArray()).get().use { z ->
            val total = z.entries.sumOf { it.size }
            var done = 0L
            for (entry in z.entries) {
                val out = File(dest, entry.name)
                require(out.canonicalPath.startsWith(dest.canonicalPath)) { "Bad entry ${entry.name}" }
                if (entry.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                val start = done
                z.getInputStream(entry).use { input -> out.outputStream().use { copy(input, it) { n -> done = start + n; progress(done, total) } } }
            }
        }
    }
}

/** Concurrent archive parts per release. Three fills a home line against a per-connection cap without tripping mirror rate limits. */
private const val PARALLEL = 3

private val TRUST_ALL: SSLContext by lazy {
    SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        }), null)
    }
}

// ponytail: one request per second app-wide, like rclone's --tpslimit 1; add the burst of 3 if lists feel slow.
private var lastRequest = 0L

@Synchronized
private fun throttle() {
    val wait = lastRequest + 1_000 - System.currentTimeMillis()
    if (wait > 0) Thread.sleep(wait)
    lastRequest = System.currentTimeMillis()
}

/** Columns are found by header name, so a reordered or extended list still parses. Rows of size 0 are placeholders and dropped. */
fun parseGameList(lines: List<String>): List<Game> {
    val header = lines.firstOrNull()?.split(';')?.map { it.trim() } ?: return emptyList()
    if ("Package Name" !in header) return parseHeaderlessList(lines)
    fun col(name: String, fallback: Int) = header.indexOf(name).takeIf { it >= 0 } ?: fallback
    val name = col("Game Name", 0); val release = col("Release Name", 1); val pkg = col("Package Name", 2)
    val version = col("Version Code", 3); val updated = col("Last Updated", 4); val size = col("Size (MB)", 5)
    val downloads = header.indexOf("Downloads")
    val rating = header.indexOf("Rating")
    val ratingCount = header.indexOf("Rating Count")
    return lines.drop(1).mapNotNull { line ->
        val c = line.split(';')
        val sizeMb = c.getOrNull(size)?.trim() ?: return@mapNotNull null
        if ((sizeMb.toDoubleOrNull() ?: 0.0) <= 0) return@mapNotNull null
        Game(
            c.getOrNull(name) ?: return@mapNotNull null, c.getOrNull(release) ?: return@mapNotNull null,
            c.getOrNull(pkg) ?: return@mapNotNull null, c.getOrNull(version).orEmpty(), c.getOrNull(updated).orEmpty(), sizeMb,
            c.getOrNull(downloads)?.trim()?.toDoubleOrNull() ?: 0.0,
            rating = c.getOrNull(rating)?.trim()?.toDoubleOrNull() ?: 0.0,
            ratingCount = c.getOrNull(ratingCount)?.trim()?.toDoubleOrNull()?.toInt() ?: 0,
        )
    }
}

/** vrhub-server's list: no header, `name;release;package;versionCode;sizeBytes;popularity`. */
private fun parseHeaderlessList(lines: List<String>): List<Game> = lines.mapNotNull { line ->
    val c = line.split(';')
    if (c.size < 5) return@mapNotNull null
    val bytes = c[4].trim().toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
    Game(
        c[0], c[1], c[2], c[3].trim(), "", "%.1f".format(java.util.Locale.US, bytes / 1_048_576.0),
        c.getOrNull(5)?.trim()?.toDoubleOrNull() ?: 0.0,
    )
}

/** Server configs may carry the password Base64-encoded or plain. Decodes when it's Base64 of printable text, else uses it as-is. */
// ponytail: a plain password that is also valid Base64 of printable ASCII gets decoded; add an explicit flag if that ever bites.
fun configPassword(raw: String): String {
    val decoded = runCatching { String(Base64.getDecoder().decode(raw), Charsets.UTF_8) }.getOrNull()
    return if (!decoded.isNullOrEmpty() && decoded.all { it in ' '..'~' }) decoded else raw
}

/** Stream copy that honours coroutine cancellation and reports progress about once per MB. */
private suspend fun copy(input: InputStream, out: OutputStream, onBytes: (Long) -> Unit) {
    val ctx = currentCoroutineContext()
    val buf = ByteArray(1 shl 16)
    var done = 0L
    var last = 0L
    while (true) {
        ctx.ensureActive()
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        done += n
        if (done - last >= 1_000_000) { onBytes(done); last = done }
    }
    onBytes(done)
}

private fun File.appendBytesFrom(src: File) =
    java.io.FileOutputStream(this, true).use { out -> src.inputStream().use { it.copyTo(out) } }
