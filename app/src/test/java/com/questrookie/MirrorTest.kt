package com.questrookie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MirrorTest {
    @Test
    fun passwordWorksEncodedOrPlain() {
        val encoded = java.util.Base64.getEncoder().encodeToString("gL59VfgPxoHR".toByteArray())
        assertEquals("gL59VfgPxoHR", configPassword(encoded))
        assertEquals("your password", configPassword("your password"))  // not Base64 at all
        assertEquals("test", configPassword("test"))                    // valid Base64, but decodes to binary
    }

    @Test
    fun gameListColumnsFoundByHeaderName() {
        val games = parseGameList(
            listOf(
                "Game Name;Release Name;Package Name;Version Code;Last Updated;Size (MB);Downloads;Rating",
                "Alpha;Alpha v2;com.a;2;2026-10-01;512;87.5;4",
                "Placeholder;P v1;com.p;1;2026-10-01;0;1;1",
                "",
            ),
        )
        assertEquals(listOf(Game("Alpha", "Alpha v2", "com.a", "2", "2026-10-01", "512", 87.5, rating = 4.0)), games)
    }

    @Test
    fun vrhubServerListHasNoHeaderAndSizeInBytes() {
        // Format written by vrhub-server's GenerateVRPGameListContent.
        val games = parseGameList(listOf("Beat Game;Beat Game v5;com.beat;5;1048576000;12", "Empty;Empty v1;com.e;1;0;0", ""))
        assertEquals(listOf(Game("Beat Game", "Beat Game v5", "com.beat", "5", "", "1000.0", 12.0)), games)
    }

}
