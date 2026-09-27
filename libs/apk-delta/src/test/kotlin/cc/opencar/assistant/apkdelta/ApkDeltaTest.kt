package cc.opencar.assistant.apkdelta

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApkDeltaTest {
    private val dir = Files.createTempDirectory("apk-delta").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun blob(seed: Int, size: Int) = Random(seed).nextBytes(size)

    /** Writes a zip; `stored` entries mimic uncompressed dex/native libs. */
    private fun zip(name: String, entries: List<Triple<String, ByteArray, Boolean>>, signingBlock: Boolean = false): File {
        val f = File(dir, name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((path, bytes, stored) in entries) {
                val e = ZipEntry(path)
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = bytes.size.toLong()
                    e.compressedSize = bytes.size.toLong()
                    e.crc = CRC32().apply { update(bytes) }.value
                }
                z.putNextEntry(e)
                z.write(bytes)
                z.closeEntry()
            }
        }
        return if (signingBlock) withSigningBlock(f) else f
    }

    /** Splices a fake APK Signing Block before the central directory, fixing up the EOCD offset. */
    private fun withSigningBlock(f: File): File {
        val bytes = f.readBytes()
        val eocd = (bytes.size - 22 downTo 0).first {
            bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == 5.toByte() && bytes[it + 3] == 6.toByte()
        }
        val cd = (0..3).fold(0L) { acc, i -> acc or ((bytes[eocd + 16 + i].toLong() and 0xFF) shl (8 * i)) }.toInt()
        val payload = blob(99, 500)
        val size = payload.size + 8 + 16
        fun u64(v: Long) = ByteArray(8) { ((v shr (8 * it)) and 0xFF).toByte() }
        val block = u64(size.toLong()) + payload + u64(size.toLong()) + "APK Sig Block 42".toByteArray()
        val out = bytes.copyOfRange(0, cd) + block + bytes.copyOfRange(cd, bytes.size)
        val newCd = cd + block.size
        val at = eocd + block.size + 16
        for (i in 0..3) out[at + i] = ((newCd shr (8 * i)) and 0xFF).toByte()
        f.writeBytes(out)
        return f
    }

    private fun roundTrip(old: File, new: File): ApkDelta.Header {
        val patch = File(dir, "p-${old.name}-${new.name}.oadp")
        val header = ApkDelta.diff(old, new, patch)
        val out = File(dir, "out-${new.name}")
        ApkDelta.apply(old, patch, out)
        assertEquals(ApkDelta.sha256(new), ApkDelta.sha256(out))
        assertEquals(header.patchSize, patch.length())
        return header
    }

    @Test
    fun unchangedEntriesAreCopied() {
        val big = blob(1, 400_000)
        val lib = blob(2, 300_000)
        val old = zip("old.apk", listOf(Triple("classes.dex", big, true), Triple("lib/a.so", lib, false), Triple("assets/x.js", blob(3, 1000), false)), true)
        val new = zip("new.apk", listOf(Triple("classes.dex", big, true), Triple("lib/a.so", lib, false), Triple("assets/x.js", blob(4, 1000), false)), true)
        val h = roundTrip(old, new)
        assertTrue(h.dataBytes < 10_000, "dataBytes=${h.dataBytes}")
        assertEquals(ApkDelta.sha256(old), h.fromSha256)
    }

    @Test
    fun addedRemovedAndRenamedEntries() {
        val shared = blob(5, 200_000)
        val old = zip("old.apk", listOf(Triple("gone.bin", blob(6, 5000), true), Triple("a/hash-1.js", shared, true)))
        val new = zip("new.apk", listOf(Triple("b/hash-2.js", shared, true), Triple("added.bin", blob(7, 5000), false)))
        val h = roundTrip(old, new)
        assertTrue(h.dataBytes < 20_000, "dataBytes=${h.dataBytes}")
    }

    @Test
    fun unrelatedArchivesStillRoundTrip() {
        val old = zip("old.apk", listOf(Triple("x", blob(8, 10_000), false)))
        val new = zip("new.apk", listOf(Triple("y", blob(9, 10_000), true)), true)
        roundTrip(old, new)
    }

    @Test
    fun shellScriptReferencesPayload() {
        val big = blob(10, 100_000)
        val old = zip("old.apk", listOf(Triple("a", big, true)))
        val new = zip("new.apk", listOf(Triple("a", big, true), Triple("b", blob(11, 100), true)))
        val patch = File(dir, "s.oadp")
        val h = ApkDelta.diff(old, new, patch)
        val script = ApkDelta.shellScript(h)
        assertTrue(script.contains("seg \"\$B\" 0 "))
        assertTrue(script.contains("seg \"\$P\" ${h.payloadOffset} "))
    }

    @Test
    fun rejectsWrongBaseAndGarbage() {
        val old = zip("old.apk", listOf(Triple("a", blob(12, 50_000), true)))
        val new = zip("new.apk", listOf(Triple("a", blob(12, 50_000), true), Triple("b", blob(13, 10), true)))
        val patch = File(dir, "w.oadp")
        ApkDelta.diff(old, new, patch)
        val tiny = zip("tiny.apk", listOf(Triple("z", blob(14, 10), true)))
        assertFailsWith<IOException> { ApkDelta.apply(tiny, patch, File(dir, "w-out")) }
        val junk = File(dir, "junk").apply { writeBytes(blob(15, 100)) }
        assertFailsWith<IOException> { ApkDelta.apply(old, junk, File(dir, "j-out")) }
        assertFailsWith<IOException> { ApkDelta.diff(junk, new, File(dir, "j.oadp")) }
    }
}
