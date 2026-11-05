package com.questrookie

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SourcesTest {
    @Test
    fun extraSourceIsSavedListedAndTagsItsGames() {
        val root = Files.createTempDirectory("qr").toFile()
        try {
            val primary = Rookie(root)
            primary.extra("Pi").saveConfig("""{"baseUri":"http://192.168.1.50:39457/","password":"cGFzcw=="}""")

            val pi = primary.extras().single()
            assertEquals("Pi", pi.label)
            assertEquals("http://192.168.1.50:39457", pi.baseUri)
            assertEquals("pass", pi.password)

            File(pi.metaDir, "VRP-GameList.txt").apply { parentFile.mkdirs() }.writeText(
                "Game Name;Release Name;Package Name;Version Code;Last Updated;Size (MB)\nAlpha;Alpha v1;com.a;1;2026-10-01;10\n",
            )
            assertEquals(listOf("Pi"), pi.games().map { it.source })
        } finally {
            root.deleteRecursively()
        }
    }
}
