package com.questrookie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogsTest {
    @Test
    fun streamsRealSideQuestIndexAndDropsDeadHosts() {
        val games = javaClass.classLoader!!.getResourceAsStream("sidequest-vr-games.json")!!.reader().use {
            parseIndexV1(it, "SideQuest", iconBase = "https://icons/", skipHosts = listOf("cdn.glitch.com", "androidapks.com"))
        }
        assertEquals(16, games.size)
        assertTrue(games.none { "glitch" in it.apkUrl || "androidapks" in it.apkUrl })
        val forest = games.first { it.pkg == "com.spliced.magicalforest" }
        assertEquals("113", forest.version)
        assertTrue(forest.apkUrl.endsWith("MagicalForestQuest-Android-Shipping-armv7-es2.apk"))
        assertTrue(forest.iconUrl.startsWith("https://icons/"))
    }

    @Test
    fun fdroidStyleEntryUsesLocalizedNameAndRelativeApk() {
        val index = """
            {"repo":{"address":"https://f-droid.org/repo","name":"F-Droid"},
             "requests":{"install":[]},
             "apps":[{"packageName":"org.example","icon":"org.example.5.png","lastUpdated":1700000000000,
                      "localized":{"de":{"name":"Beispiel"},"en-US":{"name":"Example","summary":"x"}}}],
             "packages":{"org.example":[
                {"apkName":"org.example_5.apk","versionCode":5,"versionName":"1.5","size":2097152,"hash":"x"},
                {"apkName":"org.example_4.apk","versionCode":4,"versionName":"1.4","size":1}]}}
        """.trimIndent()
        val g = parseIndexV1(index.reader(), "F-Droid", iconBase = "https://f-droid.org/repo/icons-640/").single()
        assertEquals("Example", g.name)
        assertEquals("5", g.version)
        assertEquals("2.0", g.sizeMb)
        assertEquals("https://f-droid.org/repo/org.example_5.apk", g.apkUrl)
        assertEquals("https://f-droid.org/repo/icons-640/org.example.5.png", g.iconUrl)
    }

    @Test
    fun indexKeepsDescriptionAndScreenshotsAsDetails() {
        val index = """
            {"repo":{"address":"https://f-droid.org/repo"},
             "apps":[{"packageName":"org.example","icon":"i.png","lastUpdated":1700000000000,
                      "localized":{"de":{"name":"Beispiel","phoneScreenshots":["de1.png"]},
                                   "en-US":{"name":"Example","summary":"Short","description":"<p>Long</p>","phoneScreenshots":["1.png","2.png"]}}}],
             "packages":{"org.example":[{"apkName":"e.apk","versionCode":5,"versionName":"1.5","size":1}]}}
        """.trimIndent()
        val details = HashMap<String, Details>()
        parseIndexV1(index.reader(), "F-Droid", iconBase = "", details = details)
        val d = details.getValue("org.example")
        assertEquals("Short", d.summary)
        assertEquals("<p>Long</p>", d.description)
        assertEquals(
            listOf("https://f-droid.org/repo/org.example/en-US/phoneScreenshots/1.png", "https://f-droid.org/repo/org.example/en-US/phoneScreenshots/2.png"),
            d.screenshots,
        )
    }

    @Test
    fun sideQuestTopLevelDescriptionIsKept() {
        val details = HashMap<String, Details>()
        javaClass.classLoader!!.getResourceAsStream("sidequest-vr-games.json")!!.reader().use {
            parseIndexV1(it, "SideQuest", iconBase = "", details = details)
        }
        assertTrue(details.getValue("com.spliced.magicalforest").description.startsWith("Users can pickup magical wands"))
    }

    @Test
    fun oculusDbGivesPlainDescriptionGenresAndCover() {
        val json = """{"display_long_description":"# THE BEST\n\n**SLASH** the beats, see [site](https://x.y)","genre_names":["Rhythm","Action"],
            "publisher_name":"Beat Games","imageLink":"/cdn/images/123"}"""
        val d = parseOculusDb(json)
        assertEquals("THE BEST\n\nSLASH the beats, see site", d.description)
        assertEquals(listOf("Rhythm", "Action"), d.genres)
        assertEquals("Beat Games", d.publisher)
        assertEquals("https://oculusdb.rui2015.me/cdn/images/123", d.cover)
    }

    @Test
    fun featureGraphicBecomesThumbnail() {
        val index = """
            {"repo":{"address":"https://f-droid.org/repo"},
             "apps":[{"packageName":"org.example","icon":"i.png","lastUpdated":1700000000000,
                      "localized":{"de":{"featureGraphic":"de.png"},"en-US":{"name":"Example","featureGraphic":"fg.png"}}}],
             "packages":{"org.example":[{"apkName":"e.apk","versionCode":5,"versionName":"1.5","size":1}]}}
        """.trimIndent()
        val g = parseIndexV1(index.reader(), "F-Droid", iconBase = "").single()
        assertEquals("https://f-droid.org/repo/org.example/en-US/fg.png", g.thumbUrl)
    }
}
