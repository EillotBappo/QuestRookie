package com.questrookie

import org.junit.Assert.assertEquals
import org.junit.Test

class InstallTxtTest {
    @Test
    fun parsesRookieStyleScript() {
        val steps = parseInstallTxt(
            listOf(
                "rem comment",
                "adb uninstall com.foo",
                "adb install -r -g \"My Game.apk\"",
                "adb -d push com.foo /sdcard/Android/obb/",
                "adb shell pm grant com.foo android.permission.RECORD_AUDIO",
                "",
            ),
        )
        assertEquals(
            listOf(
                InstallStep.Shell("pm uninstall com.foo"),
                InstallStep.Install("My Game.apk"),
                InstallStep.Push("com.foo", "/sdcard/Android/obb/"),
                InstallStep.Shell("pm grant com.foo android.permission.RECORD_AUDIO"),
            ),
            steps,
        )
    }
}
