package com.questrookie

// Pure store logic: no Android classes, so it runs in plain JVM unit tests.

/** A game's tweak profile after setting [key] to [value]. A game's first change starts from the [globals]; "" means Default. */
fun profileWith(profile: Map<String, String>?, globals: Map<String, String>, key: String, value: String) = (profile ?: globals) + (key to value)

/** One short sentence for a failure, never a stack trace. Mirror/HTTP errors arrive as "HTTP <code> for <url>". */
fun friendlyError(e: Throwable): String {
    val msg = e.message.orEmpty()
    val code = Regex("""^HTTP (\d{3})""").find(msg)?.groupValues?.get(1)?.toInt()
    return when {
        // A name that won't resolve usually means no internet; a refused or unreachable address is that one server.
        e is java.net.UnknownHostException -> "No internet connection, or the address is wrong"
        e is java.net.ConnectException || e is java.net.NoRouteToHostException -> "Couldn't reach the server"
        e is java.net.SocketTimeoutException -> "The server took too long to answer"
        code == 401 || code == 403 -> "The server refused access (HTTP $code)"
        code == 404 -> "Not found on the server (HTTP 404)"
        code != null -> "The server had a problem (HTTP $code)"
        else -> msg.lineSequence().first().trim().ifEmpty { e.javaClass.simpleName }
    }
}

/** Wide and big enough to fill a card. Anything else (square covers, icons, tiny banners) is shown as an icon instead. */
fun isGoodThumbnail(w: Int, h: Int) = w >= 400 && w >= h * 1.3f

/** Lists carry several releases of one game; browsing shows the newest per source. */
fun latestPerTitle(games: List<Game>): List<Game> =
    games.groupBy { it.source to it.pkg }.values.map { l -> l.maxBy { it.version.toLongOrNull() ?: 0 } }

enum class RowKind { Updates, New, Popular, TopRated, VRSrc, SideQuest, OpenSource, Favourites }

class HomeRow(val kind: RowKind, val title: String, val games: List<Game>)

private const val ROW_SIZE = 20

/**
 * The store home's rows, in display order. F-Droid's phone apps never appear here; empty rows are dropped,
 * except the VRSrc row when its mirror is set up ([vrsrc] is that source's label): empty there means its list failed to load.
 */
fun homeRows(games: List<Game>, stateOf: (Game) -> GameState, favourites: Set<String>, hideAdult: Boolean, vrsrc: String? = null): List<HomeRow> {
    val pool = latestPerTitle(games).filter { it.source != FDroid.name && !(hideAdult && it.isAdult) }
    val newest = pool.sortedByDescending { it.updated }
    return listOf(
        HomeRow(RowKind.Updates, "Updates for you", pool.filter { stateOf(it) == GameState.Update }),
        HomeRow(RowKind.New, "New & updated", newest),
        HomeRow(RowKind.Popular, "Popular", pool.filter { it.popularity > 0 }.sortedByDescending { it.popularity }),
        HomeRow(RowKind.TopRated, "Top rated", pool.filter { it.rating > 0 && it.ratingCount >= 5 }.sortedWith(compareByDescending<Game> { it.rating }.thenByDescending { it.ratingCount })),
        HomeRow(RowKind.VRSrc, "VRSrc", newest.filter { it.source == vrsrc }),
        HomeRow(RowKind.SideQuest, "VR from SideQuest", newest.filter { it.source == SideQuest.name }),
        HomeRow(RowKind.OpenSource, "Open-source picks", newest.filter { it.source == GitHubApps.name }),
        HomeRow(RowKind.Favourites, "Your favourites", newest.filter { it.pkg in favourites }),
    ).map { HomeRow(it.kind, it.title, it.games.take(ROW_SIZE)) }.filter { it.games.isNotEmpty() || (it.kind == RowKind.VRSrc && vrsrc != null) }
}

/** Browse all's default order: titles made for the headset first, F-Droid's phone apps after; newest first within each. */
fun recommended(games: List<Game>) = games.sortedWith(compareBy<Game> { it.source == FDroid.name }.thenByDescending { it.updated })

/** "Updated 3 weeks ago" from a yyyy-MM-dd date, store-style; the raw text when it doesn't parse, null when there's none. */
fun updatedAgo(day: String, today: java.time.LocalDate): String? {
    if (day.isBlank()) return null
    val date = runCatching { java.time.LocalDate.parse(day.take(10)) }.getOrNull() ?: return "Updated $day"
    val days = java.time.temporal.ChronoUnit.DAYS.between(date, today)
    fun ago(n: Long, unit: String) = "Updated $n $unit${if (n == 1L) "" else "s"} ago"
    return when {
        days <= 0 -> "Updated today"
        days == 1L -> "Updated yesterday"
        days < 14 -> ago(days, "day")
        days < 60 -> ago(days / 7, "week")
        days < 365 -> ago(days / 30, "month")
        else -> ago(days / 365, "year")
    }
}

/** "1 title", "4,942 titles": a grouped number and a noun that agrees with it. */
fun count(n: Int, noun: String) = "%,d %s".format(java.util.Locale.US, n, if (n == 1) noun else noun + "s")

/** A temp file name for a title's APK. Catalog keys like "github:owner/repo" hold characters a file name can't. */
fun apkFileName(pkg: String) = pkg.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk"
