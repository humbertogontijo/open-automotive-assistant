package cc.opencar.assistant.protocol

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OaaProtocolTest {
    @Test
    fun frameShapeAndVersion() {
        val f = JSONObject(OaaFrames.frame(OaaFrames.OTA_STATUS, OaaFrames.versioned().put("state", "installing")))
        assertEquals(OaaFrames.OTA_STATUS, f.getString("type"))
        assertTrue(OaaFrames.isCurrentVersion(f.getJSONObject("payload")))
        assertFalse(OaaFrames.isCurrentVersion(JSONObject().put("v", OaaFrames.VERSION + 1)))
        assertFalse(OaaFrames.isCurrentVersion(JSONObject()))
        assertFalse(OaaFrames.isCurrentVersion(null))
        assertFalse(JSONObject(OaaFrames.frame(OaaFrames.PING)).has("payload"))
        assertNull(OaaFrames.parse("not json"))
    }

    @Test
    fun hangupCarriesReasonAndSession() {
        val f = JSONObject(OaaFrames.hangup("abcdefgh", OaaWebRtc.REASON_BUSY))
        assertEquals(OaaWebRtc.HANGUP, f.getString("type"))
        val p = f.getJSONObject("payload")
        assertEquals("abcdefgh", p.getString("sessionId"))
        assertEquals(OaaWebRtc.REASON_BUSY, p.getString("reason"))
        assertTrue(OaaFrames.isCurrentVersion(p))
        assertFalse(JSONObject(OaaFrames.hangup(null, OaaWebRtc.REASON_BYE)).getJSONObject("payload").has("sessionId"))
    }

    @Test
    fun rpcCodecIsBinarySafe() {
        val bin = byteArrayOf(0, 1, 0xff.toByte(), 0x80.toByte())
        val req = OaaRpc.Request("7", "POST", "/debug/x", "a=1&b=2", "application/octet-stream", bin)
        val decoded = OaaRpc.decodeRequest(OaaFrames.parse(OaaRpc.encodeRequest(req))!!.getJSONObject("payload"))
        assertEquals("/debug/x?a=1&b=2", decoded.target)
        assertEquals("application/octet-stream", decoded.contentType)
        assertArrayEquals(bin, decoded.body)

        val res = OaaRpc.Response(200, "application/zip", "attachment; filename=\"x.zip\"", bin)
        val payload = JSONObject(OaaRpc.encodeResponse("7", res)).getJSONObject("payload")
        assertTrue(payload.has("bodyB64"))
        val back = OaaRpc.decodeResponse(payload)
        assertArrayEquals(bin, back.body)
        assertEquals("attachment; filename=\"x.zip\"", back.contentDisposition)

        val text = JSONObject(OaaRpc.encodeResponse("8", OaaRpc.Response(200, "application/json", body = "{}".toByteArray())))
        assertEquals("{}", text.getJSONObject("payload").getString("body"))
    }

    @Test
    fun rpcRequestWithoutBodyOrQuery() {
        val req = OaaRpc.Request("1", "get", "/api/status")
        val p = OaaFrames.parse(OaaRpc.encodeRequest(req))!!.getJSONObject("payload")
        assertFalse(p.has("query"))
        assertFalse(p.has("body") || p.has("bodyB64"))
        val decoded = OaaRpc.decodeRequest(p)
        assertEquals("GET", decoded.method)
        assertEquals("/api/status", decoded.target)
        assertNull(decoded.body)
    }

    @Test
    fun textualContentTypes() {
        assertTrue(OaaRpc.isTextual(null))
        assertTrue(OaaRpc.isTextual("application/json; charset=utf-8"))
        assertTrue(OaaRpc.isTextual("application/x-www-form-urlencoded"))
        assertTrue(OaaRpc.isTextual("text/html"))
        assertFalse(OaaRpc.isTextual("video/mp4"))
        assertFalse(OaaRpc.isTextual(OaaOta.APK_MIME))
    }

    @Test
    fun otaAndSessionValidators() {
        assertTrue(OaaOta.isSha256("a".repeat(64)))
        assertFalse(OaaOta.isSha256("A".repeat(64)))
        assertFalse(OaaOta.isSha256("../" + "a".repeat(61)))
        assertTrue(OaaWebRtc.isValidSessionId("abc_DEF-123"))
        assertFalse(OaaWebRtc.isValidSessionId("short"))
        assertFalse(OaaWebRtc.isValidSessionId("has space in it"))
    }

    @Test
    fun mediaChunkRoundTrip() {
        val payload = ByteArray(300) { it.toByte() }
        val bytes = OaaMediaChunk.encode(0xFFFF_FFFFL, 3, OaaMediaChunk.FLAG_EOF, payload, offset = 100, length = 50)
        val f = OaaMediaChunk.decode(bytes)!!
        assertEquals(0xFFFF_FFFFL, f.reqId)
        assertEquals(3L, f.seq)
        assertTrue(f.isEof)
        assertFalse(f.isInit)
        assertArrayEquals(payload.copyOfRange(100, 150), f.payload)
        assertNull(OaaMediaChunk.decode(ByteArray(3)))
    }
}
