package cc.opencar.assistant.feature.web

import cc.opencar.assistant.feature.web.HubEndpoints.Via
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun aFailedOpenTriesTheOtherEndpointNext() {
        val both = HubEndpoints(lan, cloud)
        assertEquals(Via.PUBLIC, both.choose(localReachable = true, lastFailed = Via.LOCAL))
        assertEquals(Via.LOCAL, both.choose(localReachable = false, lastFailed = Via.PUBLIC))
    }

    @Test
    fun aSingleEndpointIsAlwaysDialed() {
        assertEquals(Via.LOCAL, HubEndpoints(lan, null).choose(localReachable = false, lastFailed = Via.LOCAL))
        assertEquals(Via.PUBLIC, HubEndpoints(null, cloud).choose(localReachable = true))
        assertNull(HubEndpoints(null, null).choose(localReachable = true))
    }

    @Test
    fun oldCandidateListsSplitIntoLocalAndPublic() {
        assertEquals(HubEndpoints(lan, cloud), HubEndpoints.fromCandidates(listOf(cloud, lan)))
        assertEquals(HubEndpoints(lan, null), HubEndpoints.fromCandidates(listOf(lan)))
        val tunnel = NodeUrl("https://oaa.example.com")
        assertEquals(HubEndpoints(null, tunnel), HubEndpoints.fromCandidates(listOf(tunnel)))
        assertEquals(HubEndpoints(lan, cloud), HubEndpoints.fromCandidates(listOf(lan, tunnel), published = cloud))
    }

    @Test
    fun endpointsRoundTripThroughJson() {
        assertEquals(cloud, NodeUrl.parse(cloud.toJson().toString()))
        assertNull(NodeUrl.parse("""{"url":"ftp://x"}"""))
        assertNull(NodeUrl.parse(null as String?))
    }
}
