package com.questrookie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadsetTest {
    @Test
    fun quest1OnlyOffers72() {
        val m = headsetModel("monterey")
        assertEquals(listOf(72), m.rates)
        assertEquals(listOf("Default", "72 Hz"), tweaksFor(m).first { it.key == "refresh" }.options.map { it.first })
        assertTrue(tweaksFor(m).any { it.key == "chroma" })
    }

    @Test
    fun quest3Offers120AndNoChromaTweak() {
        val m = headsetModel("eureka")
        assertEquals(120, m.maxRate)
        assertTrue(tweaksFor(m).none { it.key == "chroma" })
        val presets = presetsFor(m).toMap()
        // 120 Hz gets its own preset at the default eye buffer; Max quality stays at 90 so its 1.4× buffer can hold the rate.
        assertEquals(mapOf("refresh" to "120", "texture" to "", "dynfov" to "1", "perf" to "4"), presets["120 Hz"])
        assertEquals("90", presets["Max quality"]!!["refresh"])
    }

    @Test
    fun no120PresetWithout120Panel() {
        assertTrue(presetsFor(headsetModel("monterey")).none { it.first == "120 Hz" })
        assertTrue(presetsFor(headsetModel("seacliff")).none { it.first == "120 Hz" })
        assertEquals("72", presetsFor(headsetModel("monterey")).toMap()["Max quality"]!!["refresh"])
    }

    @Test
    fun unknownDeviceFallsBackToQuest2Options() {
        assertEquals(listOf(72, 90, 120), headsetModel("goldfish_x86_64").rates)
        assertEquals(listOf(72, 90, 120), headsetModel(null).rates)
    }

    @Test
    fun resolutionsScaleTheModelsDefaultEyeBuffer() {
        val m = headsetModel("hollywood")
        val opts = m.resolutions
        assertEquals("Default" to "", opts[0])
        assertEquals("1.2× (1728)", opts[1].first)
        assertEquals(1900, m.heightFor(1728))
        val cmds = tweaksFor(m).first { it.key == "texture" }.commands("1728")
        assertEquals(listOf("setprop debug.oculus.textureWidth 1728", "setprop debug.oculus.textureHeight 1900"), cmds)
    }

    @Test
    fun vrApiLogAveragesFpsAndStaleFrames() {
        val line = "I VrApi   : FPS=%d/90,Prd=43ms,Tear=0,Early=0,Stale=%d,VSnc=1,Lat=-1,Fov=0,CPU4/GPU=2/3,1478/490MHz"
        val log = (1..20).joinToString("\n") { line.format(if (it % 2 == 0) 90 else 72, if (it % 2 == 0) 0 else 18) }
        val p = parseVrApi(log)!!
        assertEquals(90, p.target)
        assertEquals(81.0, p.fps, 0.01)
        assertEquals(10.0, p.stalePct, 0.01)
        assertEquals("81/90 fps · 10% stale", p.toString())
        assertNull(parseVrApi(log.lines().take(3).joinToString("\n")))
        assertNull(parseVrApi("nothing here"))
    }

    @Test
    fun adviceDropsTheRateBeforeTheResolution() {
        val q3 = headsetModel("eureka")
        assertNull(perfAdvice(Perf(90, 89.0, 1.0, 50), q3))
        assertEquals("Dropped frames at 90 Hz: try 72 Hz for this game", perfAdvice(Perf(90, 70.0, 12.0, 50), q3))
        assertEquals("Dropped frames at 120 Hz: try 90 Hz for this game", perfAdvice(Perf(120, 95.0, 15.0, 50), q3))
        assertTrue(perfAdvice(Perf(72, 60.0, 15.0, 50), q3)!!.startsWith("Dropped frames even at 72 Hz"))
    }
}
