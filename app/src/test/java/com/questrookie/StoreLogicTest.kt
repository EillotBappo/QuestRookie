package com.questrookie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreLogicTest {
    @Test
    fun gameProfileStartsFromGlobalsThenKeepsItsOwnValues() {
        val globals = mapOf("refresh" to "90", "perf" to "")
        // First change: the globals come along so the other tweaks don't jump.
        assertEquals(mapOf("refresh" to "120", "perf" to ""), profileWith(null, globals, "refresh", "120"))
        // Existing profile: its values win over the globals; "" stores Default.
        assertEquals(mapOf("refresh" to "72", "perf" to ""), profileWith(mapOf("refresh" to "72", "perf" to "4"), globals, "perf", ""))
    }

    @Test
    fun friendlyErrorsReadAsPlainLanguage() {
        // A name that won't resolve is usually no internet; a refused or unreachable address is that server, not the internet.
        assertEquals("No internet connection, or the address is wrong", friendlyError(java.net.UnknownHostException("t.me")))
        assertEquals("Couldn't reach the server", friendlyError(java.net.ConnectException("refused")))
        assertEquals("Couldn't reach the server", friendlyError(java.net.NoRouteToHostException("no route")))
        assertEquals("The server took too long to answer", friendlyError(java.net.SocketTimeoutException()))
        assertEquals("The server refused access (HTTP 403)", friendlyError(IllegalStateException("HTTP 403 for https://go.srcdl1.xyz/meta.7z")))
        assertEquals("Not found on the server (HTTP 404)", friendlyError(IllegalStateException("HTTP 404 for https://x")))
        assertEquals("The server had a problem (HTTP 502)", friendlyError(IllegalStateException("HTTP 502 for https://x")))
        assertEquals("Disk full", friendlyError(RuntimeException("Disk full\n\tat com.x.Y")))
        assertEquals("RuntimeException", friendlyError(RuntimeException()))
    }

    @Test
    fun onlyWideBigImagesAreGoodThumbnails() {
        assertTrue(isGoodThumbnail(1024, 500))
        assertTrue(isGoodThumbnail(640, 360))
        assertFalse(isGoodThumbnail(720, 720))   // OculusDB cover: square
        assertFalse(isGoodThumbnail(512, 512))   // SideQuest icon
        assertFalse(isGoodThumbnail(300, 150))   // wide but tiny
    }

    private fun g(name: String, source: String, updated: String = "2026-01-01", pop: Double = 0.0, rating: Double = 0.0, count: Int = 0) =
        Game(name, "$name v1 ($source)", "pkg.$name", "1", updated, "1.0", popularity = pop, source = source, rating = rating, ratingCount = count)

    @Test
    fun newAndUpdatedIsNewestFirstAcrossSources() {
        val games = listOf(g("Old", "Mirror", "2025-01-01"), g("New", "Repo", "2026-06-01"), g("Ad (18+)", "Mirror", "2026-12-01"))
        assertEquals(listOf("New", "Old"), newAndUpdated(games, hideAdult = true).map { it.name })
    }

    @Test
    fun sourceListsPutVrsrcFirstAndKeepItWhenItFailed() {
        val rp = g("Rp", "Repo"); val mx = g("Mx", "Mirror"); val tv = g("Tv", "TheVRSrc")
        val vrsrc = setOf("Mirror", "TheVRSrc")
        assertEquals(listOf("VRSrc" to listOf(mx, tv), "Repo" to listOf(rp)), sourceLists(listOf(rp, mx, tv), vrsrc, vrsrcSetUp = true))
        // Set up but its list didn't load: the empty VRSrc list stays, so the home can say so.
        assertEquals(listOf("VRSrc" to emptyList<Game>(), "Repo" to listOf(rp)), sourceLists(listOf(rp), vrsrc, vrsrcSetUp = true))
        // Not set up: only the other sources.
        assertEquals(listOf("Repo" to listOf(rp)), sourceLists(listOf(rp), vrsrc, vrsrcSetUp = false))
    }

    @Test
    fun latestPerTitleKeepsNewestVersion() {
        val old = g("A", "Mirror").copy(version = "3")
        val new = old.copy(version = "10", release = "A v10")
        assertEquals(listOf(new), latestPerTitle(listOf(old, new)))
    }

    @Test
    fun updatedAgoReadsLikeAStore() {
        val today = java.time.LocalDate.of(2026, 10, 30)
        assertEquals("Updated today", updatedAgo("2026-10-30", today))
        assertEquals("Updated yesterday", updatedAgo("2026-10-29", today))
        assertEquals("Updated 5 days ago", updatedAgo("2026-10-25", today))
        assertEquals("Updated 3 weeks ago", updatedAgo("2026-10-09", today))
        assertEquals("Updated 2 months ago", updatedAgo("2026-08-15", today))
        assertEquals("Updated 4 years ago", updatedAgo("2022-03-04", today))
        assertEquals("Updated 2026-13-99", updatedAgo("2026-13-99", today))
        assertEquals(null, updatedAgo("", today))
    }

    @Test
    fun countsReadAsEnglish() {
        assertEquals("1 title", count(1, "title"))
        assertEquals("0 titles", count(0, "title"))
        assertEquals("4,942 titles", count(4942, "title"))
    }

    @Test
    fun downloadNamesAreSafeFileNames() {
        assertEquals("com.beatgames.beatsaber.apk", apkFileName("com.beatgames.beatsaber"))
        // GitHub catalog entries have no real package name; their key holds a slash and a colon.
        assertEquals("github_RikkaApps_Shizuku.apk", apkFileName("github:RikkaApps/Shizuku"))
    }
}
