package cc.opencar.assistant.server

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DemoDvrTest {
    private val node = DemoNodeTransport("demo", EventBus())

    private fun get(path: String, query: String? = null): JSONObject = runBlocking {
        val res = node.rpc("GET", path, query, null, null, 1000)
        assertEquals(200, res.status)
        JSONObject(String(checkNotNull(res.body), Charsets.UTF_8))
    }

    @Test
    fun timelineListsGroupsWithOneFilePerCamera() {
        val tl = get("/api/dvr/timeline")
        assertEquals(listOf("front", "right", "rear", "left"), tl.getJSONArray("roles").map { it.toString() })
        val segs = tl.getJSONArray("segments")
        val first = segs.getJSONObject(0)
        val cams = first.getJSONObject("cameras")
        assertEquals(4, cams.length())
        assertEquals(first.getString("id") + "_front.mp4", cams.getJSONObject("front").getString("name"))
        assertTrue(segs.getJSONObject(segs.length() - 1).getBoolean("active"))
        val missingRear = (0 until segs.length()).map { segs.getJSONObject(it) }
            .count { !it.getJSONObject("cameras").has("rear") }
        assertEquals(1, missingRear)
    }

    @Test
    fun playResolvesEveryCameraAndOneRole() {
        val seg = get("/api/dvr/timeline").getJSONArray("segments").getJSONObject(0)
        val at = seg.getLong("startUtcMs") + 90_000
        val all = get("/api/dvr/play", "atMs=$at")
        assertEquals(seg.getString("id"), all.getString("group"))
        assertEquals(at, all.getLong("atUtcMs"))
        assertEquals(90_000L, all.getJSONObject("cameras").getJSONObject("left").getLong("offsetMs"))
        assertFalse(all.has("role"))

        val rear = get("/api/dvr/play", "atMs=$at&role=rear")
        assertEquals("rear", rear.getString("role"))
        assertEquals(seg.getString("id") + "_rear.mp4", rear.getString("name"))
        assertEquals(90_000L, rear.getLong("offsetMs"))
        assertEquals(seg.getLong("startUtcMs"), rear.getLong("fileStartUtcMs"))
    }
}
