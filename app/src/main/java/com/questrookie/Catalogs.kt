package com.questrookie

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A built-in list that installs straight from APK links: no mirror, password or key needed.
 * Parsed games are cached compactly per catalog, so app start never re-parses a big index.
 */
abstract class Catalog(val name: String) {
    /** Returns the games and fills [details] with each one's store-page text and screenshots, keyed by pkg. */
    protected abstract fun fetch(details: MutableMap<String, Details>): List<Game>

    private fun cacheFile(root: File) = File(root, "catalogs/$name.json")
    private fun detailsFile(root: File) = File(root, "catalogs/$name-details.json")

    fun cached(root: File): List<Game> = runCatching { readGames(cacheFile(root).readText()) }.getOrDefault(emptyList())

    /** Downloads the list; a failure keeps the last cached copy. */
    fun refresh(root: File): List<Game> {
        val details = HashMap<String, Details>()
        return runCatching { fetch(details) }
            .onSuccess { games ->
                cacheFile(root).apply { parentFile?.mkdirs() }.writeText(writeGames(games))
                detailsFile(root).writeText(writeDetails(details))
                loaded = details
            }
            .getOrElse { cached(root) }
    }

    // Kept out of the game list so app start never parses F-Droid's descriptions; loaded the first time a page needs one.
    @Volatile private var loaded: Map<String, Details>? = null

    /** A game's saved details, or null before the first refresh that saved them. Blocking; call off the main thread. */
    fun details(root: File, pkg: String): Details? = (loaded ?: synchronized(this) {
        loaded ?: runCatching { readDetails(detailsFile(root).readText()) }.getOrDefault(emptyMap()).also { loaded = it }
    })[pkg]
}

/** Store-page extras for a game. Text may be HTML (F-Droid) or plain; screenshots are full URLs. */
data class Details(
    val summary: String = "", val description: String = "", val screenshots: List<String> = emptyList(),
    val genres: List<String> = emptyList(), val publisher: String = "", val cover: String = "", val whatsNew: String = "",
)

val CATALOGS: List<Catalog> = listOf(SideQuest, FDroid, GitHubApps, TheVRSrc)

/** The free SideQuestRepos indexes. Glitch's CDN is shut down and androidapks.com re-hosts Play Store APKs; the nsfw index is never fetched. */
object SideQuest : Catalog("SideQuest") {
    private const val RAW = "https://raw.githubusercontent.com/the-expanse/SideQuestRepos/master"
    private val CATEGORIES = listOf("vr-games", "vr-apps", "android-games", "android-apps")

    override fun fetch(details: MutableMap<String, Details>) = CATEGORIES.flatMap { cat ->
        URL("$RAW/$cat/index-v1.json").openStream().reader().use {
            parseIndexV1(it, name, iconBase = "$RAW/$cat/icons/", skipHosts = listOf("cdn.glitch.com", "androidapks.com"), details = details)
        }
    }.ifEmpty { error("SideQuest returned no apps") }
}

/** F-Droid's main repo: free and open-source Android apps. Mostly phone apps; the source chip filters them. */
object FDroid : Catalog("F-Droid") {
    override fun fetch(details: MutableMap<String, Details>) = URL("https://f-droid.org/repo/index-v1.json").openStream().reader().use {
        parseIndexV1(it, name, iconBase = "https://f-droid.org/repo/icons-640/", details = details)
    }
}

/**
 * Hand-picked open-source Quest projects, each installed from its latest GitHub release.
 * GitHub doesn't say the package name, so these install without one (pkg is "github:owner/repo").
 */
object GitHubApps : Catalog("GitHub") {
    /** [hint] picks the right APK when a release has several; [blurb] is the store-page description (fetching each repo's would double the API calls). */
    private class Project(val repo: String, val title: String, val hint: String, val blurb: String)

    private val PROJECTS = listOf(
        Project("alvr-org/ALVR", "ALVR (PC VR streaming)", "alvr_client_android", "Stream SteamVR games from your PC to the headset over Wi-Fi."),
        Project("QuestCraftPlusPlus/QuestCraft", "QuestCraft", "", "Minecraft Java Edition running natively on the headset, in full VR."),
        Project("threethan/LightningLauncher", "Lightning Launcher", "", "A fast, customisable launcher that lists every app, sideloaded ones included."),
        Project("ComputerElite/QuestAppVersionSwitcher", "QuestAppVersionSwitcher", "", "Back up apps and their saves, and switch or downgrade between app versions."),
        Project("moonlight-stream/moonlight-android", "Moonlight (game streaming)", "nonRoot", "Play PC games on a big screen in the headset, streamed from Sunshine or NVIDIA GameStream."),
        Project("Basti564/Oculess", "Oculess", "", "Turn off Meta telemetry and other system features you don't want."),
        Project("TeamNewPipe/NewPipe", "NewPipe", "", "A lightweight YouTube client with no ads and no Google services."),
        // Team Beef ports: each needs the original game's data files copied to the headset.
        Project("DrBeef/QuestZDoom", "QuestZDoom (Doom in VR)", "questzdoom-", "Doom, Doom II, Heretic and Hexen in full VR. Needs the original game files."),
        Project("DrBeef/Doom3Quest", "Doom3Quest", "", "Doom 3 in VR with motion controls. Needs the original game files."),
        Project("DrBeef/Lambda1VR", "Lambda1VR (Half-Life in VR)", "", "Half-Life in VR on the Xash3D engine. Needs the original game files."),
        Project("DrBeef/Quake2Quest", "Quake2Quest", "", "Quake II in VR with motion controls. Needs the original game files."),
        Project("DrBeef/RTCWQuest", "RTCWQuest (Wolfenstein in VR)", "", "Return to Castle Wolfenstein in VR. Needs the original game files."),
        Project("Team-Beef-Studios/JKXR", "JKXR (Jedi Knight in VR)", "jkxr-", "Jedi Outcast and Jedi Academy in VR. Needs the original game files."),
        Project("WiVRn/WiVRn", "WiVRn (PC VR streaming)", "", "Open-source PC VR streaming for Linux PCs, built on OpenXR."),
        Project("Igalia/wolvic", "Wolvic (VR browser)", "oculusvr", "An open-source web browser made for VR headsets."),
        Project("termux/termux-app", "Termux", "universal", "A Linux terminal and package manager on the headset."),
        Project("RikkaApps/Shizuku", "Shizuku", "", "Lets other apps use ADB-level permissions without root."),
        Project("emanuele-f/PCAPdroid", "PCAPdroid", "", "See and record which servers your apps talk to. No root needed."),
        Project("PojavLauncherTeam/PojavLauncher", "PojavLauncher (Minecraft Java)", "", "Minecraft Java Edition launcher, played on a flat screen."),
    )

    override fun fetch(details: MutableMap<String, Details>) = PROJECTS.mapNotNull { p ->
        val c = URL("https://api.github.com/repos/${p.repo}/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        // GitHub allows 60 anonymous calls an hour. Hitting the limit (or losing the connection) mid-refresh would save a
        // partial list, so give up instead and keep the last full one. A project without a usable release is just skipped.
        if (c.responseCode == 403 || c.responseCode == 429) error("HTTP ${c.responseCode} from GitHub (rate limit)")
        runCatching {
            parseRelease(c.inputStream.bufferedReader().use { it.readText() }, p.repo, p.title, p.hint, details)
                ?.also { g -> details[g.pkg] = (details[g.pkg] ?: Details()).copy(description = p.blurb) }
        }.getOrNull()
    }.ifEmpty { error("GitHub returned no releases") }
}

/**
 * TheVRSrc mirror: fetches a config JSON for the base URI and token, then pulls the actual APK index.
 * Mirrors can change endpoints; this tries the standard locations Rookie uses.
 */
object TheVRSrc : Catalog("TheVRSrc") {
    private val CONFIG_URLS = listOf(
        "https://thevrsrc.github.io/the-vr-src/index.json",
        "https://raw.githubusercontent.com/TheVRSrc/thevrsrc/main/thevrsrc.json"
    )

    override fun fetch(details: MutableMap<String, Details>): List<Game> {
        var baseUri = ""; var token = ""
        for (cfgUrl in CONFIG_URLS) {
            try {
                val cfgText = URL(cfgUrl).openStream().reader().use { it.readText() }
                val c = JSONObject(cfgText)
                if (c.has("baseUri") && c.has("password")) {
                    baseUri = c.getString("baseUri").trimEnd('/') + "/"
                    token = c.getString("password")
                    break
                }
            } catch (_: Exception) { /* try next */ }
        }
        require(baseUri.isNotEmpty()) { "TheVRSrc config not found" }

        // Fetch the actual game index from the mirror using the token.
        val idxText = URL("$baseUri/index.json?token=$token").openStream().reader().use { it.readText() }
        return JSONArray(idxText).let { arr ->
            List(arr.length()) { arr.getJSONObject(it) } .mapNotNull { j -> parseTheVRSrcGame(j, details) }.ifEmpty {
                error("TheVRSrc returned no apps")
            }
        }
    }

    private fun parseTheVRSrcGame(j: JSONObject, details: MutableMap<String, Details>): Game? {
        val name = j.optString("name", "").takeIf { it.isNotEmpty() } ?: return null
        val pkg = j.optString("package", j.optString("pkg", "")).ifEmpty { "thvrsrc:${name}" }
        val apkUrl = j.optString("apkUrl", j.optString("url", "")).takeIf { it.isNotEmpty() } ?: return null
        val version = j.optString("version", j.optString("ver", ""))
        val sizeBytes = j.optLong("size", 0L)
        val iconUrl = j.optString("icon", j.optString("cover", "")).let {
            if (it.isNotEmpty() && it.startsWith('/')) "https://thevrsrc.github.io$it" else it
        }
        val desc = j.optString("description", j.optString("summary", ""))
        details[pkg] = Details(summary = desc, description = desc)
        return Game(
            name = name, release = "$name v${version ?: "latest"} (TheVRSrc)", pkg = pkg, version = version,
            updated = j.optString("updated", ""), sizeMb = mb(sizeBytes),
            source = "TheVRSrc", apkUrl = apkUrl, iconUrl = iconUrl,
        )
    }
}

/** One latest-release response from the GitHub API, or null when it has no matching APK. Release notes go to [details]. */
fun parseRelease(json: String, repo: String, title: String, hint: String = "", details: MutableMap<String, Details>? = null): Game? {
    val r = JSONObject(json)
    val assets = r.getJSONArray("assets")
    val apk = List(assets.length()) { assets.getJSONObject(it) }
        .filter { it.getString("name").endsWith(".apk") }
        .let { l -> l.firstOrNull { hint.isNotEmpty() && hint in it.getString("name") } ?: l.firstOrNull() } ?: return null
    val tag = r.getString("tag_name")
    plainMarkdown(r.optString("body")).takeIf { it.isNotEmpty() }?.let { details?.put("github:$repo", Details(whatsNew = it)) }
    return Game(
        name = title, release = "$title $tag (GitHub)", pkg = "github:$repo", version = tag.removePrefix("v"),
        updated = r.optString("published_at").take(10), sizeMb = mb(apk.optLong("size")),
        source = "GitHub", apkUrl = apk.getString("browser_download_url"),
        iconUrl = "https://github.com/${repo.substringBefore('/')}.png?size=256",
    )
}

/**
 * Streams an F-Droid index-v1 (SideQuestRepos uses the same format). F-Droid's is ~70 MB, too big to load whole,
 * so only the fields a Game needs are kept, plus store-page text and screenshots into [details] when given.
 * APK and screenshot paths are relative to the index's repo address.
 */
fun parseIndexV1(
    reader: Reader, source: String, iconBase: String, skipHosts: List<String> = emptyList(), details: MutableMap<String, Details>? = null,
): List<Game> {
    class App(val name: String, val icon: String, val updated: Long, val feature: String)
    // Screenshots are "<locale>/phoneScreenshots/<file>" until the repo address is known.
    val pages = HashMap<String, Details>()
    class Version(val apk: String, val code: Long, val name: String, val size: Long)
    var address = ""
    val apps = HashMap<String, App>()
    val versions = HashMap<String, Version>()
    JsonReader(reader).use { r ->
        r.beginObject()
        while (r.hasNext()) when (r.nextName()) {
            "repo" -> readObject(r) { key -> if (key == "address") address = r.nextString() else r.skipValue() }
            "apps" -> {
                r.beginArray()
                while (r.hasNext()) {
                    var pkg = ""; var name = ""; var icon = ""; var updated = 0L; var added = 0L; var localName = ""
                    var summary = ""; var description = ""; var shots = emptyList<String>(); var feature = ""
                    readObject(r) { key ->
                        when (key) {
                            "packageName" -> pkg = r.nextString()
                            "name" -> name = r.nextString()
                            "icon" -> icon = r.nextString()
                            "lastUpdated" -> updated = r.nextLong()
                            "added" -> added = r.nextLong()
                            // SideQuestRepos keeps its text at the top level.
                            "summary" -> summary = r.nextString()
                            "description" -> description = r.nextString()
                            // F-Droid keeps most text per locale; take en-US, else the first locale that has it.
                            "localized" -> readObject(r) { locale ->
                                val en = locale == "en-US"
                                readObject(r) { field ->
                                    when (field) {
                                        "name" -> r.nextString().let { if (en || localName.isEmpty()) localName = it }
                                        "summary" -> r.nextString().let { if (en || summary.isEmpty()) summary = it }
                                        "description" -> r.nextString().let { if (en || description.isEmpty()) description = it }
                                        "featureGraphic" -> r.nextString().let { if (en || feature.isEmpty()) feature = "$locale/$it" }
                                        "phoneScreenshots" -> {
                                            val files = ArrayList<String>()
                                            r.beginArray(); while (r.hasNext()) files += "$locale/phoneScreenshots/" + r.nextString(); r.endArray()
                                            if (en || shots.isEmpty()) shots = files
                                        }
                                        else -> r.skipValue()
                                    }
                                }
                            }
                            else -> r.skipValue()
                        }
                    }
                    if (pkg.isNotEmpty()) apps[pkg] = App(name.ifEmpty { localName }.ifEmpty { pkg }, icon, if (updated > 0) updated else added, feature)
                    if (pkg.isNotEmpty() && details != null && (summary.isNotEmpty() || description.isNotEmpty() || shots.isNotEmpty()))
                        pages[pkg] = Details(summary = summary.trim(), description = description.trim(), screenshots = shots)
                }
                r.endArray()
            }
            "packages" -> readObject(r) { pkg ->
                // Newest version first; the rest are skipped.
                r.beginArray()
                var first: Version? = null
                while (r.hasNext()) {
                    if (first != null) { r.skipValue(); continue }
                    var apk = ""; var code = 0L; var vName = ""; var size = 0L
                    readObject(r) { key ->
                        when (key) {
                            "apkName" -> apk = r.nextString()
                            "versionCode" -> code = if (r.peek() == JsonToken.STRING) r.nextString().toLongOrNull() ?: 0 else r.nextLong()
                            "versionName" -> vName = r.nextString()
                            "size" -> size = r.nextLong()
                            else -> r.skipValue()
                        }
                    }
                    first = Version(apk, code, vName, size)
                }
                r.endArray()
                first?.let { versions[pkg] = it }
            }
            else -> r.skipValue()
        }
    }
    val base = address.trimEnd('/') + "/"
    pages.forEach { (pkg, d) -> details?.put(pkg, d.copy(screenshots = d.screenshots.map { base + pkg + "/" + it.replace(" ", "%20") })) }
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    return apps.mapNotNull { (pkg, app) ->
        val v = versions[pkg] ?: return@mapNotNull null
        val apk = if (v.apk.startsWith("http")) v.apk else base + v.apk
        if (skipHosts.any { it in apk }) return@mapNotNull null
        Game(
            name = app.name, release = "${app.name} v${v.name} ($source)", pkg = pkg, version = v.code.toString(),
            updated = if (app.updated > 0) day.format(Date(app.updated)) else "", sizeMb = mb(v.size),
            source = source, apkUrl = apk, iconUrl = app.icon.takeIf { it.isNotEmpty() }?.let { iconBase + it.replace(" ", "%20") }.orEmpty(),
            thumbUrl = app.feature.takeIf { it.isNotEmpty() }?.let { base + pkg + "/" + it.replace(" ", "%20") }.orEmpty(),
        )
    }
}

/** Reads an object's members; [field] must consume each value (read it or skipValue). JSON null is skipped. */
private inline fun readObject(r: JsonReader, field: (String) -> Unit) {
    r.beginObject()
    while (r.hasNext()) {
        val key = r.nextName()
        if (r.peek() == JsonToken.NULL) r.skipValue() else field(key)
    }
    r.endObject()
}

private fun mb(bytes: Long) = "%.1f".format(Locale.US, bytes / 1_048_576.0)

private fun writeGames(games: List<Game>) = JSONArray(games.map { g ->
    JSONObject().put("n", g.name).put("r", g.release).put("p", g.pkg).put("v", g.version).put("u", g.updated)
        .put("s", g.sizeMb).put("src", g.source).put("a", g.apkUrl).put("i", g.iconUrl).put("t", g.thumbUrl)
}).toString()

private fun readGames(text: String): List<Game> {
    val a = JSONArray(text)
    return List(a.length()) { a.getJSONObject(it) }.map { j ->
        Game(
            j.getString("n"), j.getString("r"), j.getString("p"), j.getString("v"), j.getString("u"), j.getString("s"),
            source = j.getString("src"), apkUrl = j.getString("a"), iconUrl = j.optString("i"), thumbUrl = j.optString("t"),
        )
    }
}

private fun writeDetails(all: Map<String, Details>) = JSONObject().apply {
    all.forEach { (pkg, d) ->
        put(pkg, JSONObject().put("m", d.summary).put("d", d.description).put("s", JSONArray(d.screenshots)).put("w", d.whatsNew))
    }
}.toString()

private fun readDetails(text: String): Map<String, Details> {
    val o = JSONObject(text)
    return o.keys().asSequence().associateWith { pkg ->
        val j = o.getJSONObject(pkg)
        val s = j.optJSONArray("s")
        Details(summary = j.optString("m"), description = j.optString("d"), screenshots = List(s?.length() ?: 0) { s!!.getString(it) }, whatsNew = j.optString("w"))
    }
}

private const val OCULUS_DB = "https://oculusdb.rui2015.me"

/**
 * Store text for a Meta Store title from OculusDB (a community mirror of the store's data), by package name.
 * The answer is kept in [cacheDir], so each game is looked up once. Null when offline or unknown. Blocking.
 */
fun oculusDb(cacheDir: File, pkg: String): Details? {
    val cached = File(cacheDir, "oculusdb/$pkg.json")
    val json = cached.takeIf { it.exists() }?.readText() ?: runCatching {
        val c = URL("$OCULUS_DB/api/v1/packagename/" + java.net.URLEncoder.encode(pkg, "UTF-8")).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.inputStream.bufferedReader().use { it.readText() }.also { cached.apply { parentFile?.mkdirs() }.writeText(it) }
    }.getOrNull() ?: return null
    return runCatching { parseOculusDb(json) }.getOrNull()
}

/** Store descriptions and release notes are Markdown; headings, bold and links are flattened to plain text. */
fun plainMarkdown(md: String) = md
    .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
    .replace(Regex("(?m)^#+\\s*"), "")
    .replace("**", "").replace("__", "")
    .trim()

fun parseOculusDb(json: String): Details {
    val j = JSONObject(json)
    val genres = j.optJSONArray("genre_names")
    val text = plainMarkdown(j.optString("display_long_description"))
    return Details(
        description = text, genres = List(genres?.length() ?: 0) { genres!!.getString(it) },
        publisher = j.optString("publisher_name"),
        cover = j.optString("imageLink").takeIf { it.isNotEmpty() }?.let { if (it.startsWith("/")) OCULUS_DB + it else it }.orEmpty(),
    )
}
