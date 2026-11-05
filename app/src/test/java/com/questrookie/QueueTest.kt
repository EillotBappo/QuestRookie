package com.questrookie

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QueueTest {
    /** A failed job used to cancel the queue's scope, which in the app is the UI's: the Recomposer shut down and the app froze. */
    @Test
    fun failedJobLeavesScopeAlive() {
        val parent = Job()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val queue = DownloadQueue(scope, File.createTempFile("queue", ".json").apply { deleteOnExit() }, { null }, { null }, { _, _ -> }) {}
        val item = QueueItem("k", "t", "s", null, JSONObject()) { error("install refused") }
        queue.add(item)
        val deadline = System.currentTimeMillis() + 5_000
        while (item.state != QState.Failed && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(QState.Failed, item.state)
        assertTrue("queue failure cancelled its parent scope", parent.isActive)
        scope.cancel()
    }
}
