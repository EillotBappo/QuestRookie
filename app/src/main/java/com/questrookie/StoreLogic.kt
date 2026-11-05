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

private const val ROW_SIZE = 20

/** The store home's one shelf: the newest titles across every source. */
fun newAndUpdated(games: List<Game>, hideAdult: Boolean) =
    latestPerTitle(games).filter { !(hideAdult && it.isAdult) }.sortedByDescending { it.updated }.take(ROW_SIZE)

/**
 * The store home's lists under the shelf, one per source, VRSrc's first. [vrsrc] is the source labels VRSrc's games carry;
 * while [vrsrcSetUp] its list stays even when empty, so the home can say it failed to load.
 */
fun sourceLists(games: List<Game>, vrsrc: Set<String>, vrsrcSetUp: Boolean): List<Pair<String, List<Game>>> {
    val bySource = games.groupBy { if (it.source in vrsrc) "VRSrc" else it.source }
    val own = bySource["VRSrc"].orEmpty()
    return listOfNotNull(("VRSrc" to own).takeIf { vrsrcSetUp || own.isNotEmpty() }) + bySource.filterKeys { it != "VRSrc" }.toList()
}

/** Browse all's default order: newest first. */
fun recommended(games: List<Game>) = games.sortedByDescending { it.updated }

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
