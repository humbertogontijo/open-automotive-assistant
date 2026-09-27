package cc.opencar.assistant.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OaaStaticTest {
    private val files = mapOf(
        "assets/app-ABC.js" to "raw".toByteArray(),
        "assets/app-ABC.js.br" to "br".toByteArray(),
        "assets/app-ABC.js.gz" to "gz".toByteArray(),
        "icons/sprite.svg" to "svg".toByteArray(),
    )
    private val read: (String) -> ByteArray? = { files[it] }

    @Test
    fun prefersBrotliThenGzipThenRaw() {
        val br = OaaStatic.resolve("assets/app-ABC.js", "gzip, deflate, br", read = read)!!
        assertEquals("br", br.encoding)
        assertArrayEquals("br".toByteArray(), br.bytes)
        assertEquals("gzip", OaaStatic.resolve("assets/app-ABC.js", "gzip, deflate", read = read)!!.encoding)
        val raw = OaaStatic.resolve("assets/app-ABC.js", null, read = read)!!
        assertNull(raw.encoding)
        assertArrayEquals("raw".toByteArray(), raw.bytes)
        assertNull(OaaStatic.resolve("assets/app-ABC.js", "br;q=0, gzip;q=0", read = read)!!.encoding)
    }

    @Test
    fun customSuffixesAndMissingVariants() {
        val apk = mapOf("assets/x.js" to "raw".toByteArray(), "assets/x.js.gzip" to "gz".toByteArray())
        val a = OaaStatic.resolve("assets/x.js", "gzip, br", linkedMapOf("br" to ".br", "gzip" to ".gzip")) { apk[it] }!!
        assertEquals("gzip", a.encoding)
        assertNull(OaaStatic.resolve("icons/sprite.svg", "br, gzip", read = read)!!.encoding)
        assertNull(OaaStatic.resolve("missing.js", "br", read = read))
    }

    @Test
    fun cacheAndPathPolicy() {
        assertEquals(OaaStatic.CACHE_IMMUTABLE, OaaStatic.resolve("assets/app-ABC.js", null, read = read)!!.cacheControl)
        assertEquals(OaaStatic.CACHE_NO_STORE, OaaStatic.resolve("icons/sprite.svg", null, read = read)!!.cacheControl)
        assertEquals(OaaStatic.CACHE_NO_STORE, OaaStatic.cacheControl(OaaStatic.INDEX))
        assertFalse(OaaStatic.isSafePath("../secret"))
        assertFalse(OaaStatic.isSafePath("/etc/passwd"))
        assertFalse(OaaStatic.isSafePath(""))
        assertTrue(OaaStatic.isSafePath("assets/app-ABC.js"))
        assertNull(OaaStatic.resolve("../x", null, read = read))
    }
}
