package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaMediaChunk
import cc.opencar.assistant.protocol.OaaMediaNames
import cc.opencar.assistant.protocol.OaaRpc
import cc.opencar.assistant.protocol.OaaWebRtc
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections

class WebRtcRelayTest {
    @TempDir
    lateinit var tmp: File

    private class FakeNode(override val nodeId: String, private val webrtc: Boolean = true) : NodeTransport {
        val sent: MutableList<JSONObject> = Collections.synchronizedList(ArrayList())
        override suspend fun rpc(
            method: String,
            path: String,
            query: String?,
            contentType: String?,
            body: ByteArray?,
            timeoutMs: Long,
        ) = OaaRpc.error(404, "fake")

        override suspend fun send(frame: String): Boolean {
            if (!webrtc) return false
            sent += JSONObject(frame)
            return true
        }
    }

    private class FakeUi : SignalPeer {
        val got: MutableList<JSONObject> = Collections.synchronizedList(ArrayList())
        override suspend fun send(text: String) {
            got += JSONObject(text)
        }
        fun last(): JSONObject = got.last()
    }

    private val sid = "sess_0123456789"
    private val iceTurn = IceConfig(
        stunUrls = listOf("stun:stun.example:3478"),
        turnUrls = listOf("turn:turn.example:3478"),
        turnSecret = "s3cret",
        ttlSec = 600,
        clock = { 1_700_000_000_000L },
    )

    private fun offer(sessionId: String = sid, v: Int = 1) = JSONObject()
        .put("type", OaaWebRtc.OFFER)
        .put("payload", JSONObject().put("v", v).put("sessionId", sessionId).put("sdp", "v=0\r\n"))
        .toString()

    private fun setup(webrtc: Boolean = true): Triple<NodeRegistry, FakeNode, WebRtcSignalRelay> {
        val registry = NodeRegistry(tmp)
        val node = FakeNode("car1", webrtc)
        registry.attachSession("car1", node)
        return Triple(registry, node, WebRtcSignalRelay(registry, iceTurn))
    }

    @Test
    fun offerAnswerIceRelay() = runBlocking {
        val (_, node, relay) = setup()
        val ui = FakeUi()
        relay.onUiFrame("car1", ui, offer())
        assertEquals(1, node.sent.size)
        val fwd = node.sent[0]
        assertEquals(OaaWebRtc.OFFER, fwd.getString("type"))
        val p = fwd.getJSONObject("payload")
        assertEquals(sid, p.getString("sessionId"))
        assertEquals(2, p.getJSONArray("iceServers").length(), "hub injects STUN + TURN for the car")

        val answer = JSONObject().put("type", OaaWebRtc.ANSWER)
            .put("payload", JSONObject().put("v", 1).put("sessionId", sid).put("sdp", "v=0\r\nanswer"))
        relay.onNodeFrame("car1", answer.toString())
        assertEquals(OaaWebRtc.ANSWER, ui.last().getString("type"))

        val ice = JSONObject().put("type", OaaWebRtc.ICE)
            .put("payload", JSONObject().put("v", 1).put("sessionId", sid).put("candidate", "candidate:1"))
        relay.onUiFrame("car1", ui, ice.toString())
        assertEquals(OaaWebRtc.ICE, node.sent.last().getString("type"))
        relay.onNodeFrame("car1", ice.toString())
        assertEquals(OaaWebRtc.ICE, ui.last().getString("type"))
    }

    @Test
    fun versionMismatchHangsUp() = runBlocking {
        val (_, node, relay) = setup()
        val ui = FakeUi()
        relay.onUiFrame("car1", ui, offer(v = 2))
        assertTrue(node.sent.isEmpty())
        val h = ui.last()
        assertEquals(OaaWebRtc.HANGUP, h.getString("type"))
        assertEquals(OaaWebRtc.REASON_VERSION, h.getJSONObject("payload").getString("reason"))
    }

    @Test
    fun carVersionMismatchTearsDownBothSides() = runBlocking {
        val (_, node, relay) = setup()
        val ui = FakeUi()
        relay.onUiFrame("car1", ui, offer())
        val bad = JSONObject().put("type", OaaWebRtc.ANSWER)
            .put("payload", JSONObject().put("v", 9).put("sessionId", sid).put("sdp", "x"))
        relay.onNodeFrame("car1", bad.toString())
        assertEquals(OaaWebRtc.REASON_VERSION, ui.last().getJSONObject("payload").getString("reason"))
        assertEquals(OaaWebRtc.REASON_VERSION, node.sent.last().getJSONObject("payload").getString("reason"))
        assertEquals(0, relay.activeSessions())
    }

    @Test
    fun offlineNodeHangsUp() = runBlocking {
        val registry = NodeRegistry(tmp)
        val relay = WebRtcSignalRelay(registry, iceTurn)
        val ui = FakeUi()
        relay.onUiFrame("ghost", ui, offer())
        assertEquals(OaaWebRtc.REASON_OFFLINE, ui.last().getJSONObject("payload").getString("reason"))
        assertEquals(0, relay.activeSessions())
    }

    @Test
    fun nodeWithoutWebRtcIsUnsupported() = runBlocking {
        val (_, _, relay) = setup(webrtc = false)
        val ui = FakeUi()
        relay.onUiFrame("car1", ui, offer())
        assertEquals(OaaWebRtc.REASON_UNSUPPORTED, ui.last().getJSONObject("payload").getString("reason"))
        assertEquals(0, relay.activeSessions())
    }

    @Test
    fun secondOfferReplacesPriorSession() = runBlocking {
        val (_, node, relay) = setup()
        val a = FakeUi()
        val b = FakeUi()
        relay.onUiFrame("car1", a, offer("sess_aaaaaaaaaa"))
        relay.onUiFrame("car1", b, offer("sess_bbbbbbbbbb"))
        assertEquals(OaaWebRtc.REASON_REPLACED, a.last().getJSONObject("payload").getString("reason"))
        val hangups = node.sent.filter { it.getString("type") == OaaWebRtc.HANGUP }
        assertEquals("sess_aaaaaaaaaa", hangups.single().getJSONObject("payload").getString("sessionId"))
        assertEquals("sess_bbbbbbbbbb", relay.sessionFor("car1"))
        assertEquals(1, relay.activeSessions())
    }

    @Test
    fun foreignViewerCannotInjectIce() = runBlocking {
        val (_, node, relay) = setup()
        val owner = FakeUi()
        val intruder = FakeUi()
        relay.onUiFrame("car1", owner, offer())
        val before = node.sent.size
        val ice = JSONObject().put("type", OaaWebRtc.ICE)
            .put("payload", JSONObject().put("v", 1).put("sessionId", sid).put("candidate", "evil"))
        relay.onUiFrame("car1", intruder, ice.toString())
        assertEquals(before, node.sent.size)
    }

    @Test
    fun iceIsRateLimited() = runBlocking {
        val (_, node, relay) = setup()
        val ui = FakeUi()
        relay.onUiFrame("car1", ui, offer())
        val ice = JSONObject().put("type", OaaWebRtc.ICE)
            .put("payload", JSONObject().put("v", 1).put("sessionId", sid).put("candidate", "c")).toString()
        repeat(OaaWebRtc.MAX_ICE_PER_SESSION + 50) { relay.onUiFrame("car1", ui, ice) }
        val relayed = node.sent.count { it.getString("type") == OaaWebRtc.ICE }
        assertEquals(OaaWebRtc.MAX_ICE_PER_SESSION, relayed)
    }

    @Test
    fun uiCloseAndNodeDropCleanUp() = runBlocking {
        val (_, node, relay) = setup()
        val ui = FakeUi()
        relay.onUiFrame("car1", ui, offer())
        relay.onUiClosed(ui)
        assertEquals(OaaWebRtc.REASON_BYE, node.sent.last().getJSONObject("payload").getString("reason"))
        assertEquals(0, relay.activeSessions())

        relay.onUiFrame("car1", ui, offer("sess_cccccccccc"))
        relay.onNodeDetached("car1")
        assertEquals(OaaWebRtc.REASON_OFFLINE, ui.last().getJSONObject("payload").getString("reason"))
        assertEquals(0, relay.activeSessions())
    }

    @Test
    fun invalidSessionIdRejected() = runBlocking {
        val (_, node, relay) = setup()
        val ui = FakeUi()
        relay.onUiFrame("car1", ui, offer("../x"))
        assertTrue(node.sent.isEmpty())
        assertEquals(OaaWebRtc.REASON_INVALID, ui.last().getJSONObject("payload").getString("reason"))
    }

    @Test
    fun turnCredentialsAreEphemeralHmac() {
        val servers = iceTurn.iceServers("user:42")
        assertEquals(2, servers.size)
        val turn = servers[1]
        val username = turn["username"] as String
        assertEquals("1700000600:user_42", username)
        assertEquals(IceConfig.hmacSha1("s3cret", username), turn["credential"])
        assertFalse(servers.toString().contains("s3cret"))

        val stunOnly = IceConfig(listOf("stun:a"), listOf("turn:b"), null, 600)
        assertFalse(stunOnly.turnEnabled)
        assertEquals(1, stunOnly.iceServers("u").size)
    }

    @Test
    fun mediaChunkFramingRoundTrip() {
        val payload = ByteArray(1000) { (it % 251).toByte() }
        val bytes = OaaMediaChunk.encode(
            reqId = 0xFFFF_FFF0L,
            seq = 7,
            flags = OaaMediaChunk.FLAG_EOF,
            payload = payload,
            offset = 10,
            length = 500,
        )
        assertEquals(OaaMediaChunk.HEADER_BYTES + 500, bytes.size)
        val f = OaaMediaChunk.decode(bytes)
        assertNotNull(f)
        f!!
        assertEquals(OaaMediaChunk.TYPE_CHUNK, f.type)
        assertEquals(0xFFFF_FFF0L, f.reqId)
        assertEquals(7L, f.seq)
        assertTrue(f.isEof)
        assertArrayEquals(payload.copyOfRange(10, 510), f.payload)
        assertNull(OaaMediaChunk.decode(ByteArray(3)))
        assertThrows(IllegalArgumentException::class.java) {
            OaaMediaChunk.encode(1, 0, 0, ByteArray(OaaWebRtc.CHUNK_MAX_BYTES + 1))
        }
    }

    @Test
    fun largeTransferSplitsIntoSequencedChunks() {
        val body = ByteArray(OaaWebRtc.CHUNK_MAX_BYTES + 100) { 1 }
        val frames = ArrayList<ByteArray>()
        var seq = 0L
        var off = 0
        while (off < body.size) {
            val n = minOf(OaaWebRtc.CHUNK_MAX_BYTES, body.size - off)
            val last = off + n >= body.size
            frames += OaaMediaChunk.encode(3, seq++, if (last) OaaMediaChunk.FLAG_EOF else 0, body, off, n)
            off += n
        }
        val decoded = frames.map { OaaMediaChunk.decode(it)!! }
        assertTrue(decoded.last().isEof)
        assertEquals(listOf(0L, 1L), decoded.map { it.seq })
        assertArrayEquals(body, decoded.fold(ByteArray(0)) { acc, f -> acc + f.payload })
    }

    @Test
    fun basenameAclRejectsTraversal() {
        assertTrue(OaaMediaNames.isSafeBasename("dvr_20260926_101500.mp4"))
        assertFalse(OaaMediaNames.isSafeBasename("../secrets.db"))
        assertFalse(OaaMediaNames.isSafeBasename("/sdcard/x.mp4"))
        assertFalse(OaaMediaNames.isSafeBasename("a/b.mp4"))
        assertFalse(OaaMediaNames.isSafeBasename("a\\b.mp4"))
        assertFalse(OaaMediaNames.isSafeBasename(".hidden"))
        assertFalse(OaaMediaNames.isSafeBasename(""))
        assertFalse(OaaMediaNames.isSafeBasename(null))
    }
}
