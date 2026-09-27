package cc.opencar.assistant.feature.web

import cc.opencar.assistant.protocol.OaaCarAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CarAuthStoreTest {
    private val dir: File = Files.createTempDirectory("oaa-auth").toFile()
    private var clock = 1_000_000L
    private fun store() = CarAuthStore(File(dir, "clients.json"), now = { clock })

    @Test
    fun codeConfirmsOnceAndIssuesAWorkingToken() {
        val s = store()
        val req = s.request(OaaCarAuth.KIND_BROWSER, "Phone", "192.168.1.5")!!
        val ok = s.confirm(req.id, req.code) as CarAuthStore.Confirm.Ok
        assertEquals("Phone", s.resolve(ok.token)?.name)
        assertTrue(s.confirm(req.id, req.code) is CarAuthStore.Confirm.Failed)
        assertTrue(s.pending().isEmpty())
    }

    @Test
    fun tokensSurviveReloadAndAreStoredHashed() {
        val s = store()
        val (_, token) = s.addClient(OaaCarAuth.KIND_TOOL, "oaa-setup")
        assertFalse(File(dir, "clients.json").readText().contains(token))
        assertNotNull(store().resolve(token))
    }

    @Test
    fun requestExpires() {
        val s = store()
        val req = s.request(OaaCarAuth.KIND_BROWSER, "Phone", "10.0.0.2")!!
        clock += OaaCarAuth.CODE_TTL_MS + 1
        val r = s.confirm(req.id, req.code) as CarAuthStore.Confirm.Failed
        assertEquals(404, r.status)
        assertTrue(s.pending().isEmpty())
    }

    @Test
    fun tooManyWrongCodesKillTheRequest() {
        val s = store()
        val req = s.request(OaaCarAuth.KIND_BROWSER, "Phone", "10.0.0.2")!!
        val wrong = if (req.code == "000000") "111111" else "000000"
        repeat(OaaCarAuth.MAX_ATTEMPTS) {
            assertEquals(403, (s.confirm(req.id, wrong) as CarAuthStore.Confirm.Failed).status)
        }
        assertEquals(404, (s.confirm(req.id, req.code) as CarAuthStore.Confirm.Failed).status)
    }

    @Test
    fun oneOpenRequestPerSource() {
        val s = store()
        assertNotNull(s.request(OaaCarAuth.KIND_BROWSER, "A", "10.0.0.2"))
        assertNull(s.request(OaaCarAuth.KIND_BROWSER, "B", "10.0.0.2"))
        assertNotNull(s.request(OaaCarAuth.KIND_BROWSER, "C", "10.0.0.3"))
        assertEquals(2, s.pending().size)
    }

    @Test
    fun revokeAndHubReplacement() {
        val s = store()
        val (first, firstToken) = s.addClient(OaaCarAuth.KIND_HUB, "Hub", hubId = "h1")
        val (_, secondToken) = s.addClient(OaaCarAuth.KIND_HUB, "Hub", hubId = "h1")
        assertNull("re-pairing a hub replaces its old token", s.resolve(firstToken))
        assertNull(s.revoke(first.id))
        s.revokeHub("h1")
        assertNull(s.resolve(secondToken))
    }

    @Test
    fun pendingListenerSeesOpenAndClose() {
        val s = store()
        val seen = mutableListOf<Int>()
        s.onPendingChanged = { seen.add(it.size) }
        val req = s.request(OaaCarAuth.KIND_BROWSER, "Phone", "10.0.0.2")!!
        s.cancel(req.id)
        assertEquals(listOf(1, 0), seen)
    }

    @Test
    fun huSessionsAreInMemoryOnly() {
        val s = store()
        val hu = s.newHuSession()
        assertTrue(s.isHuSession(hu))
        assertFalse(store().isHuSession(hu))
        assertNull(s.resolve(hu))
    }
}
