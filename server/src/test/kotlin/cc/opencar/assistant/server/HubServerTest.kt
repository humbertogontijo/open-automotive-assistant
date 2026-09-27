package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaCarAuth
import cc.opencar.assistant.protocol.OaaFrames
import cc.opencar.assistant.protocol.OaaHeaders
import cc.opencar.assistant.protocol.OaaOta
import cc.opencar.assistant.protocol.OaaPaths
import cc.opencar.assistant.protocol.OaaRpc
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import cc.opencar.assistant.server.ota.ArtifactStore
import cc.opencar.assistant.server.ota.OtaRollouts
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Collections

class HubServerTest {
    @TempDir
    lateinit var tmp: File

    private var server: OaaHubServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop()
    }

    private class FakeNode(override val nodeId: String) : NodeTransport {
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
            sent += JSONObject(frame)
            return true
        }
    }

    private data class Reply(val code: Int, val headers: Map<String, List<String>>, val body: ByteArray) {
        fun text() = body.toString(Charsets.UTF_8)
        fun json() = JSONObject(text())
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    private fun http(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): Reply {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 5000
        conn.requestMethod = method
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        val bytes = runCatching {
            (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        }.getOrDefault(ByteArray(0))
        val reply = Reply(code, conn.headerFields.filterKeys { it != null }, bytes)
        conn.disconnect()
        return reply
    }

    /** Demo node plus `/api/echo` and an oversized `/api/big` for proxy tests. */
    private class TestNode(bus: EventBus) : DemoNodeTransport("demo", bus) {
        override suspend fun rpc(
            method: String,
            path: String,
            query: String?,
            contentType: String?,
            body: ByteArray?,
            timeoutMs: Long,
        ): OaaRpc.Response = when (path) {
            "/api/echo" -> OaaRpc.Response(
                200,
                "application/json",
                body = JSONObject()
                    .put("method", method)
                    .put("query", query ?: "")
                    .put("contentType", contentType ?: "")
                    .put("body", body?.toString(Charsets.UTF_8) ?: "")
                    .toString().toByteArray(),
            )
            "/api/big" -> OaaRpc.Response(200, "application/octet-stream", body = ByteArray(OaaRpc.MAX_BODY_BYTES + 1))
            else -> super.rpc(method, path, query, contentType, body, timeoutMs)
        }
    }

    private fun startHub(human: Int, node: Int): OaaHubServer =
        OaaHubServer(tmp, humanPort = human, nodePort = node, demoNode = ::TestNode, mdnsEnabled = false).also {
            server = it
            it.start(wait = false)
            Thread.sleep(600)
        }

    private fun setupAdmin(base: String): String {
        val r = http(
            "$base/api/auth/setup",
            "POST",
            mapOf("Content-Type" to "application/json"),
            """{"username":"admin","password":"secret123"}""".toByteArray(),
        )
        assertEquals(200, r.code, r.text())
        return r.json().getString("token")
    }

    @Test
    fun authSetupAndLogin() {
        val auth = AuthStore(tmp)
        assertTrue(auth.needsSetup())
        val admin = auth.createAdmin("admin", "secret123", "Admin")
        assertEquals("admin", admin.username)
        assertTrue(admin.isAdmin)
        assertFalse(auth.needsSetup())
        val session = auth.loginLocal("admin", "secret123")
        assertNotNull(session)
        assertNull(auth.loginLocal("admin", "wrong"))
        assertEquals("admin", auth.resolveSession(session!!.token)?.username)
    }

    @Test
    fun haLoginNeverLinksByUsername() {
        val auth = AuthStore(tmp)
        val local = auth.createAdmin("admin", "secret123", "Admin")
        val ha = auth.get(auth.loginHa("ha-123", "admin", "HA Admin").userId)!!
        assertNotEquals(local.id, ha.id)
        assertNotEquals("admin", ha.username)
        assertFalse(ha.isAdmin)
        assertEquals(ha.id, auth.get(auth.loginHa("ha-123", "admin", "HA Admin").userId)!!.id)
    }

    @Test
    fun dualPortsHealthAndFaceSeparation() {
        val hub = startHub(18787, 18788)
        assertEquals(200, http("http://127.0.0.1:18787/api/health").code)
        val nodeHealth = http("http://127.0.0.1:18788/api/health")
        assertEquals(200, nodeHealth.code)
        assertEquals("node", nodeHealth.json().getString("face"))
        assertEquals(hub.hub.identity.id, nodeHealth.json().getString("hubId"))
        assertEquals(401, http("http://127.0.0.1:18787/api/nodes").code)
        assertEquals(401, http("http://127.0.0.1:18787/api/webrtc/ice").code)
        assertEquals(401, http("http://127.0.0.1:18787/debug/export").code)
        assertEquals(404, http("http://127.0.0.1:18788/api/webrtc/ice").code)
        assertEquals(404, http("http://127.0.0.1:18788/debug/export").code)
    }

    @Test
    fun staticBundleIsPrecompressedAndHashedAssetsAreImmutable() {
        startHub(18827, 18828)
        val index = http("http://127.0.0.1:18827/")
        assertEquals(200, index.code)
        assertEquals("no-store", index.header("Cache-Control"))
        val entry = Regex(""""static/(assets/app-[A-Z0-9]+\.js)""").find(index.text())?.groupValues?.get(1)
        assertNotNull(entry, "hashed entry in index.html")
        val br = http("http://127.0.0.1:18827/static/$entry", headers = mapOf("Accept-Encoding" to "gzip, br"))
        assertEquals(200, br.code)
        assertEquals("br", br.header("Content-Encoding"))
        assertEquals("public, max-age=31536000, immutable", br.header("Cache-Control"))
        assertEquals("Accept-Encoding", br.header("Vary"))
        val raw = http("http://127.0.0.1:18827/static/$entry")
        assertNull(raw.header("Content-Encoding"))
        assertTrue(raw.body.size > br.body.size)
        assertEquals("no-store", http("http://127.0.0.1:18827/static/icons/sprite.svg").header("Cache-Control"))
    }

    @Test
    fun proxyForwardsQueryContentTypeAndBinary() {
        val hub = startHub(18797, 18798)
        val base = "http://127.0.0.1:18797"
        val admin = mapOf("Authorization" to "Bearer ${setupAdmin(base)}", OaaHeaders.NODE to "demo")

        val echo = http("$base/api/echo?x=1&y=two", headers = admin).json()
        assertEquals("x=1&y=two", echo.getString("query"))

        val posted = http(
            "$base/api/echo",
            "POST",
            admin + ("Content-Type" to "text/plain; charset=utf-8"),
            "héllo".toByteArray(),
        ).json()
        assertEquals("POST", posted.getString("method"))
        assertEquals("text/plain; charset=utf-8", posted.getString("contentType"))
        assertEquals("héllo", posted.getString("body"))

        val zip = http("$base/debug/export", headers = admin)
        assertEquals(200, zip.code)
        assertEquals("application/zip", zip.header("Content-Type"))
        assertTrue(zip.header("Content-Disposition")!!.contains("oaa-debug.zip"))
        assertArrayEquals(DemoNodeTransport.DEMO_ZIP, zip.body)

        assertEquals(413, http("$base/api/big", headers = admin).code)
        assertEquals(413, http("$base/api/echo", "POST", admin, ByteArray(OaaRpc.MAX_BODY_BYTES + 1)).code)

        val noNode = mapOf("Authorization" to admin.getValue("Authorization"))
        assertEquals(400, http("$base/api/echo", headers = noNode).code)
        assertEquals(200, http("$base/api/echo?node=demo", headers = noNode).code)
        assertEquals(200, http("$base/api/echo", headers = noNode + ("Cookie" to "oaa_node=demo")).code)
        assertEquals(503, http("$base/api/echo", headers = noNode + (OaaHeaders.NODE to "ghost")).code)

        val status = http("$base/api/status", headers = noNode).json()
        assertEquals("hub", status.getString("role"))
        assertEquals(1, status.getJSONObject("fleet").getJSONArray("nodes").length())
    }

    @Test
    fun debugProxyRequiresAdmin() {
        val hub = startHub(18807, 18808)
        val base = "http://127.0.0.1:18807"
        setupAdmin(base)
        val auth = hub.hub.auth
        val user = auth.loginHa("ha-user", "bob", "Bob")
        assertFalse(auth.get(user.userId)!!.isAdmin)
        val headers = mapOf("Authorization" to "Bearer ${user.token}", OaaHeaders.NODE to "demo")
        assertEquals(200, http("$base/api/echo", headers = headers).code)
        assertEquals(403, http("$base/debug/export", headers = headers).code)
        assertEquals(403, http("$base/api/ota/artifacts", headers = headers).code)
    }

    @Test
    fun artifactUploadAndNodeDownload() {
        val hub = startHub(18817, 18818)
        val base = "http://127.0.0.1:18817"
        val adminAuth = mapOf("Authorization" to "Bearer ${setupAdmin(base)}")
        val apk = ByteArray(4096) { (it % 253).toByte() }
        val up = http(
            "$base/api/ota/artifacts",
            "POST",
            adminAuth + mapOf(
                "Content-Type" to OaaOta.APK_MIME,
                OaaHeaders.OTA_PACKAGE to "cc.opencar.assistant",
                OaaHeaders.OTA_VERSION_NAME to "0.1.0",
                OaaHeaders.OTA_VERSION_CODE to "2",
            ),
            apk,
        )
        assertEquals(200, up.code, up.text())
        val sha = up.json().getJSONObject("artifact").getString("sha256")
        assertEquals(sha256(apk), sha)
        assertEquals(1, http("$base/api/ota/artifacts", headers = adminAuth).json().getJSONArray("artifacts").length())

        val code = hub.hub.registry.createPairingCode().code
        val (_, token) = hub.hub.registry.pair(code, "car1", "Car", null)!!
        val url = "http://127.0.0.1:18818/api/nodes/artifacts/$sha"
        assertEquals(401, http(url).code)
        assertEquals(401, http(url, headers = mapOf("Authorization" to "Bearer nope")).code)
        val full = http(url, headers = mapOf("Authorization" to "Bearer $token"))
        assertEquals(200, full.code)
        assertArrayEquals(apk, full.body)
        val part = http(url, headers = mapOf("Authorization" to "Bearer $token", "Range" to "bytes=100-199"))
        assertEquals(206, part.code)
        assertArrayEquals(apk.copyOfRange(100, 200), part.body)
        assertEquals(404, http("http://127.0.0.1:18818/api/nodes/artifacts/${"0".repeat(64)}", headers = mapOf("Authorization" to "Bearer $token")).code)

        val rollout = http(
            "$base/api/ota/rollouts",
            "POST",
            adminAuth + ("Content-Type" to "application/json"),
            """{"artifact":"$sha","nodes":["car1"]}""".toByteArray(),
        )
        assertEquals(200, rollout.code, rollout.text())
        val target = rollout.json().getJSONObject("rollout").getJSONObject("targets").getJSONObject("car1")
        assertEquals(OaaOta.STATE_PENDING, target.getString("state"), "car1 is offline, offer waits for hello")
    }

    @Test
    fun reportedPublicNodeReachesCarsAndServesBridgeArtifactPath() {
        val hub = startHub(18837, 18838)
        val base = "http://127.0.0.1:18837"
        val admin = mapOf("Authorization" to "Bearer ${setupAdmin(base)}", "Content-Type" to "application/json")
        val registry = hub.hub.registry
        val (_, token) = registry.pair(registry.createPairingCode().code, "car1", "Car", null)!!
        val car = FakeNode("car1")
        registry.attachSession("car1", car)

        val report = """{"url":"https://x.ui.nabu.casa/","sessionPath":"/api/oaa_node/session"}""".toByteArray()
        val user = hub.hub.auth.loginHa("ha-user", "bob", "Bob")
        assertEquals(403, http("$base${OaaPaths.NODES_PUBLIC_URL}", "POST", mapOf("Authorization" to "Bearer ${user.token}"), report).code)
        assertEquals(400, http("$base${OaaPaths.NODES_PUBLIC_URL}", "POST", admin, """{"url":"ftp://x"}""".toByteArray()).code)

        val res = http("$base${OaaPaths.NODES_PUBLIC_URL}", "POST", admin, report)
        assertEquals(200, res.code, res.text())
        assertEquals("https://x.ui.nabu.casa", res.json().getString("publicNodeUrl"))
        assertEquals("reported", res.json().getString("source"))
        val frame = car.sent.single()
        assertEquals(OaaFrames.PUBLIC_NODE, frame.getString("type"))
        assertEquals("https://x.ui.nabu.casa", frame.getJSONObject("payload").getString("publicNodeUrl"))
        assertEquals("/api/oaa_node/session", frame.getJSONObject("payload").getString("sessionPath"))

        http("$base${OaaPaths.NODES_PUBLIC_URL}", "POST", admin, report)
        assertEquals(1, car.sent.size, "unchanged URL is not re-sent")
        val status = http("$base/api/status", headers = admin).json().getJSONObject("hub")
        assertEquals("https://x.ui.nabu.casa", status.getString("publicNodeUrl"))
        assertEquals("https://x.ui.nabu.casa/api/oaa_node", status.getString("publicDialUrl"))

        val apk = ByteArray(2048) { it.toByte() }
        val sha = hub.hub.artifacts.put(apk.inputStream(), "cc.opencar.assistant", "0.1.0", 2).sha256
        val lan = http("http://127.0.0.1:18838/api/oaa_node/artifacts/$sha", headers = mapOf("Authorization" to "Bearer $token"))
        assertEquals(200, lan.code, "LAN cars download offers that carry the bridge path")
        assertArrayEquals(apk, lan.body)
        assertEquals(401, http("http://127.0.0.1:18838/api/oaa_node/artifacts/$sha").code)
    }

    @Test
    fun rolloutReoffersUntilHelloReportsHash() = runBlocking {
        val registry = NodeRegistry(tmp)
        val artifacts = ArtifactStore(tmp)
        val rollouts = OtaRollouts(tmp, registry, artifacts)
        val code = registry.createPairingCode().code
        registry.pair(code, "car1", "Car", null)
        val node = FakeNode("car1")
        registry.attachSession("car1", node)
        val apk = "apk-bytes".toByteArray()
        val artifact = artifacts.put(apk.inputStream(), "cc.opencar.assistant", "0.1.0", 2)

        val r = rollouts.create(artifact, listOf("car1"))
        val offer = node.sent.single()
        assertEquals(OaaFrames.OTA_OFFER, offer.getString("type"))
        assertEquals(artifact.sha256, offer.getJSONObject("payload").getString("sha256"))
        assertEquals("/api/nodes/artifacts/${artifact.sha256}", offer.getJSONObject("payload").getString("path"))
        assertEquals(OaaOta.STATE_OFFERED, rollouts.stateFor("car1")!!["state"])

        rollouts.onStatus("car1", JSONObject().put("rolloutId", r.id).put("state", OaaOta.STATE_INSTALLED))
        assertEquals(OaaOta.STATE_OFFERED, rollouts.stateFor("car1")!!["state"], "installed is only trusted from hello")

        rollouts.onHello("car1", "f".repeat(64))
        assertEquals(2, node.sent.size, "wrong build → offer again")

        rollouts.onHello("car1", artifact.sha256)
        assertEquals(OaaOta.STATE_INSTALLED, rollouts.stateFor("car1")!!["state"])
        assertTrue(rollouts.get(r.id)!!.done)
    }

    private fun storedZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                val e = java.util.zip.ZipEntry(name).apply {
                    method = java.util.zip.ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    crc = java.util.zip.CRC32().apply { update(bytes) }.value
                }
                z.putNextEntry(e)
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun rolloutOffersDeltaFromInstalledArtifact() = runBlocking {
        val registry = NodeRegistry(tmp)
        val artifacts = ArtifactStore(tmp)
        val rollouts = OtaRollouts(tmp, registry, artifacts)
        listOf("car1", "car2").forEach { id ->
            registry.pair(registry.createPairingCode().code, id, id, null)
        }
        val car1 = FakeNode("car1").also { registry.attachSession("car1", it) }
        val car2 = FakeNode("car2").also { registry.attachSession("car2", it) }

        val lib = kotlin.random.Random(1).nextBytes(200_000)
        val oldApk = storedZip("lib/a.so" to lib, "classes.dex" to "v1".toByteArray())
        val newApk = storedZip("lib/a.so" to lib, "classes.dex" to "v2".toByteArray())
        val old = artifacts.put(oldApk.inputStream(), "cc.opencar.assistant", "0.1.0", 1)
        val new = artifacts.put(newApk.inputStream(), "cc.opencar.assistant", "0.1.1", 2)
        registry.updateHello("car1", null, null, AppInfo(apkSha256 = old.sha256))
        registry.updateHello("car2", null, null, AppInfo(apkSha256 = "e".repeat(64)))

        rollouts.create(new, listOf("car1", "car2"))
        val delta = car1.sent.single().getJSONObject("payload").getJSONObject("delta")
        assertEquals(old.sha256, delta.getString("from"))
        val patchSha = delta.getString("sha256")
        assertEquals("/api/nodes/artifacts/$patchSha", delta.getString("path"))
        assertTrue(delta.getLong("size") < newApk.size / 2)
        assertTrue(artifacts.isDelta(patchSha))
        assertFalse(car2.sent.single().getJSONObject("payload").has("delta"), "unknown installed build → full APK only")

        val base = File(tmp, "base.apk").apply { writeBytes(oldApk) }
        val out = File(tmp, "out.apk")
        cc.opencar.assistant.apkdelta.ApkDelta.apply(base, artifacts.file(patchSha)!!, out)
        assertArrayEquals(newApk, out.readBytes())
    }

    @Test
    fun logRelaySubscribesWhileViewersAttached() = runBlocking {
        val registry = NodeRegistry(tmp)
        val relay = LogRelay(registry)
        val code = registry.createPairingCode().code
        registry.pair(code, "car1", "Car", null)
        assertFalse(relay.subscribe("car1", SignalPeer { }, null), "offline car")

        val node = FakeNode("car1")
        registry.attachSession("car1", node)
        val seen = Collections.synchronizedList(ArrayList<String>())
        val viewer = SignalPeer { seen += it }
        assertTrue(relay.subscribe("car1", viewer, "tok"))
        val sub = node.sent.last()
        assertEquals(OaaFrames.LOG_SUBSCRIBE, sub.getString("type"))
        assertEquals("tok", sub.getJSONObject("payload").getString("token"))

        relay.onLog("car1", "line 1")
        assertEquals(listOf("line 1"), seen.toList())

        relay.onHello("car1")
        assertEquals(OaaFrames.LOG_SUBSCRIBE, node.sent.last().getString("type"), "resubscribe after reconnect")

        relay.unsubscribe("car1", viewer)
        assertEquals(OaaFrames.LOG_UNSUBSCRIBE, node.sent.last().getString("type"))
        val before = node.sent.size
        relay.onHello("car1")
        assertEquals(before, node.sent.size, "no viewers, no resubscribe")
    }

    @Test
    fun helloUpdatesAppInfo() = runBlocking {
        val hub = HubContext(tmp)
        val code = hub.registry.createPairingCode().code
        hub.registry.pair(code, "car1", "Car", null)
        hub.nodeListener.onHello(
            "car1",
            JSONObject()
                .put("name", "My Car")
                .put("app", JSONObject().put("package", "cc.opencar.assistant").put("versionName", "0.1.0").put("versionCode", 3).put("apkSha256", "a".repeat(64))),
        )
        val rec = hub.registry.get("car1")!!
        assertEquals("My Car", rec.name)
        assertEquals("0.1.0", rec.app?.versionName)
        assertEquals(3L, rec.app?.versionCode)
        assertEquals("a".repeat(64), rec.app?.apkSha256)
        hub.registry.flush()
    }

    /** Minimal car auth API: one request, code 123456, records the hub link it was sent. */
    private fun startStubCar(port: Int, linked: MutableList<JSONObject>): ApplicationEngine =
        embeddedServer(CIO, port = port, host = "127.0.0.1") {
            routing {
                post(OaaPaths.AUTH_PAIR_REQUEST) {
                    val req = JSONObject(call.receiveText())
                    assertEquals(OaaCarAuth.KIND_HUB, req.getString("kind"))
                    call.respondText(
                        """{"ok":true,"requestId":"r1","expiresAtMs":${System.currentTimeMillis() + 60_000},""" +
                            """"nodeId":"car-1","name":"Test car","integration":"demo"}""",
                        ContentType.Application.Json,
                    )
                }
                post(OaaPaths.AUTH_PAIR_CONFIRM) {
                    val req = JSONObject(call.receiveText())
                    if (req.optString("code") != "123456") {
                        call.respondText("""{"ok":false,"error":"wrong code"}""", ContentType.Application.Json, HttpStatusCode.Forbidden)
                    } else {
                        linked += req.getJSONObject("hub")
                        call.respondText("""{"ok":true,"token":"car-token","nodeId":"car-1"}""", ContentType.Application.Json)
                    }
                }
            }
        }.also { it.start(wait = false) }

    @Test
    fun hubInvitesCarWithCodeShownOnCar() {
        val hub = startHub(18837, 18838)
        val linked = Collections.synchronizedList(ArrayList<JSONObject>())
        val car = startStubCar(18839, linked)
        try {
            val base = "http://127.0.0.1:18837"
            val json = mapOf("Content-Type" to "application/json")
            val invite = """{"host":"127.0.0.1","port":18839}""".toByteArray()
            assertEquals(401, http("$base/api/nodes/invite", "POST", json, invite).code)

            val admin = json + ("Authorization" to "Bearer ${setupAdmin(base)}")
            val offer = http("$base/api/nodes/invite", "POST", admin, invite)
            assertEquals(200, offer.code, offer.text())
            val inviteId = offer.json().getString("inviteId")
            assertEquals("car-1", offer.json().getString("nodeId"))

            val wrong = http("$base/api/nodes/invite/$inviteId/confirm", "POST", admin, """{"code":"000000"}""".toByteArray())
            assertEquals(403, wrong.code)
            assertNull(hub.hub.registry.get("car-1"), "failed confirm must not leave a node behind")

            val ok = http("$base/api/nodes/invite/$inviteId/confirm", "POST", admin, """{"code":"123 456"}""".toByteArray())
            assertEquals(200, ok.code, ok.text())
            val link = linked.single()
            assertEquals(hub.hub.identity.id, link.getString("hubId"))
            assertEquals(18838, link.getInt("nodePort"))
            val record = hub.hub.registry.resolveToken(link.getString("nodeToken"))
            assertEquals("car-1", record?.id)
            assertEquals("car-token", record?.carToken)
            assertEquals("http://127.0.0.1:18839", record?.lanUrl)
            assertTrue(http("$base/api/nodes", headers = admin).text().contains("car-1"))
        } finally {
            car.stop(100, 500)
        }
    }

    @Test
    fun discoveredListsOnlyUnpairedCarsForAdmins() {
        val hub = startHub(18847, 18848)
        val base = "http://127.0.0.1:18847"
        val now = System.currentTimeMillis()
        hub.hub.discovered.put(DiscoveredCar("demo", "Demo", "demo", null, "10.0.0.2", 8787, now))
        hub.hub.discovered.put(DiscoveredCar("car-9", "EX5", "antora1000", "0.1", "10.0.0.3", 8787, now))
        assertEquals(401, http("$base/api/nodes/discovered").code)
        val admin = mapOf("Authorization" to "Bearer ${setupAdmin(base)}")
        val cars = http("$base/api/nodes/discovered", headers = admin).json().getJSONArray("cars")
        assertEquals(1, cars.length())
        assertEquals("car-9", cars.getJSONObject(0).getString("id"))
        assertEquals("10.0.0.3", cars.getJSONObject(0).getString("host"))
    }

    @Test
    fun hostPortParsing() {
        assertEquals("10.0.0.3" to 8787, splitHostPort("10.0.0.3", 8787))
        assertEquals("10.0.0.3" to 9000, splitHostPort("10.0.0.3:9000", 8787))
        assertEquals("fe80::1" to 9000, splitHostPort("[fe80::1]:9000", 8787))
        assertEquals("fe80::1" to 8787, splitHostPort("fe80::1", 8787))
    }

    private fun sha256(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
