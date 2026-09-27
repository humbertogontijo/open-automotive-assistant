package cc.opencar.assistant.feature.web

import cc.opencar.assistant.feature.web.HubEndpoints.Via
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class HubEndpointsTest {
    private val lan = NodeUrl("http://192.168.1.10:8788")
    private val cloud = NodeUrl("https://x.ui.nabu.casa", "/api/oaa_node/session")

    @Test
    fun localWinsOnlyWhenTheHubAnswersOnIt() {
        val both = HubEndpoints(lan, cloud)
        assertEquals(Via.LOCAL, both.choose(localReachable = true))
        assertEquals(Via.PUBLIC, both.choose(localReachable = false))
    }

    @Test
    fun aFailedLocalOpenTriesPublicNext() {
        val both = HubEndpoints(lan, cloud)
        assertEquals(Via.PUBLIC, both.choose(localReachable = true, lastFailed = Via.LOCAL))
        assertEquals(Via.LOCAL, both.choose(localReachable = true, lastFailed = Via.PUBLIC))
    }

    @Test
    fun anUnreachableLocalUrlIsNotDialedWhilePublicExists() {
        assertEquals(Via.PUBLIC, HubEndpoints(lan, cloud).choose(localReachable = false, lastFailed = Via.PUBLIC))
    }

    @Test
    fun aSingleEndpointIsAlwaysDialed() {
        assertEquals(Via.LOCAL, HubEndpoints(lan, null).choose(localReachable = false, lastFailed = Via.LOCAL))
        assertEquals(Via.PUBLIC, HubEndpoints(null, cloud).choose(localReachable = true))
        assertNull(HubEndpoints(null, null).choose(localReachable = true))
    }

    @Test
    fun bridgedOrTlsUrlsLookPublic() {
        assertFalse(lan.looksPublic)
        assertTrue(cloud.looksPublic)
        assertTrue(NodeUrl("https://oaa.example.com").looksPublic)
    }

    @Test
    fun endpointsRoundTripThroughJson() {
        assertEquals(cloud, NodeUrl.parse(cloud.toJson().toString()))
        assertNull(NodeUrl.parse("""{"url":"ftp://x"}"""))
        assertNull(NodeUrl.parse(null as String?))
    }

    @Test
    fun publicNodeFrameGivesThePublicEndpoint() {
        val frame = JSONObject("""{"v":1,"sessionPath":"/api/oaa_node/session","publicNodeUrl":"https://x.ui.nabu.casa/"}""")
        assertEquals(cloud, NodeUrl.fromPublicNode(frame))
        assertNull(NodeUrl.fromPublicNode(JSONObject("""{"v":1,"sessionPath":"/api/nodes/session"}""")))
    }
}
