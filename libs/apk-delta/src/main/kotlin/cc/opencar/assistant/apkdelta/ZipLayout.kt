package cc.opencar.assistant.apkdelta

import java.io.IOException
import java.io.RandomAccessFile

/** Byte layout of a (non-zip64) APK: local records, then the optional APK Signing Block, then the central directory. */
internal class ZipLayout(val entries: List<Entry>) {
    /**
     * One local record. [spanEnd] is where the next record (or the signing block / central
     * directory) starts, so the span also covers any data descriptor or alignment gap.
     */
    class Entry(val headerOffset: Long, val dataOffset: Long, val dataEnd: Long, val spanEnd: Long)

    companion object {
        private const val EOCD_SIG = 0x06054b50L
        private const val CD_SIG = 0x02014b50L
        private const val LOCAL_SIG = 0x04034b50L
        private const val EOCD_MIN = 22
        private const val MAX_COMMENT = 0xFFFF
        private val SIG_BLOCK_MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)

        fun read(raf: RandomAccessFile): ZipLayout {
            val len = raf.length()
            val eocd = findEocd(raf, len)
            val count = u16(raf, eocd + 10)
            val cdSize = u32(raf, eocd + 12)
            val cdOffset = u32(raf, eocd + 16)
            if (count == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) throw IOException("zip64 not supported")
            if (cdOffset + cdSize > eocd) throw IOException("bad central directory")

            val raw = ArrayList<Triple<Long, Long, Long>>(count)
            var p = cdOffset
            repeat(count) {
                if (u32(raf, p) != CD_SIG) throw IOException("bad central directory entry")
                val compSize = u32(raf, p + 20)
                val nameLen = u16(raf, p + 28)
                val extraLen = u16(raf, p + 30)
                val commentLen = u16(raf, p + 32)
                val lho = u32(raf, p + 42)
                if (u32(raf, lho) != LOCAL_SIG) throw IOException("bad local header")
                val dataOffset = lho + 30 + u16(raf, lho + 26) + u16(raf, lho + 28)
                raw += Triple(lho, dataOffset, dataOffset + compSize)
                p += 46L + nameLen + extraLen + commentLen
            }

            val entriesEnd = signingBlockStart(raf, cdOffset) ?: cdOffset
            val sorted = raw.sortedBy { it.first }
            val entries = sorted.mapIndexed { i, (lho, dataOffset, dataEnd) ->
                val spanEnd = sorted.getOrNull(i + 1)?.first ?: entriesEnd
                if (dataEnd > spanEnd) throw IOException("overlapping zip entries")
                Entry(lho, dataOffset, dataEnd, spanEnd)
            }
            return ZipLayout(entries)
        }

        private fun findEocd(raf: RandomAccessFile, len: Long): Long {
            if (len < EOCD_MIN) throw IOException("not a zip")
            val window = minOf(len, (EOCD_MIN + MAX_COMMENT).toLong()).toInt()
            val buf = ByteArray(window)
            raf.seek(len - window)
            raf.readFully(buf)
            for (i in window - EOCD_MIN downTo 0) {
                if (le32(buf, i) == EOCD_SIG) return len - window + i
            }
            throw IOException("not a zip (no end of central directory)")
        }

        private fun signingBlockStart(raf: RandomAccessFile, cdOffset: Long): Long? {
            if (cdOffset < 32) return null
            val magic = ByteArray(16)
            raf.seek(cdOffset - 16)
            raf.readFully(magic)
            if (!magic.contentEquals(SIG_BLOCK_MAGIC)) return null
            val size = u64(raf, cdOffset - 24)
            val start = cdOffset - size - 8
            return start.takeIf { size > 0 && it >= 0 }
        }

        private fun u16(raf: RandomAccessFile, at: Long): Int {
            raf.seek(at)
            return raf.read() or (raf.read() shl 8)
        }

        private fun u32(raf: RandomAccessFile, at: Long): Long {
            val b = ByteArray(4)
            raf.seek(at)
            raf.readFully(b)
            return le32(b, 0)
        }

        private fun u64(raf: RandomAccessFile, at: Long): Long {
            val b = ByteArray(8)
            raf.seek(at)
            raf.readFully(b)
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
            return v
        }

        private fun le32(b: ByteArray, i: Int): Long =
            (b[i].toLong() and 0xFF) or
                ((b[i + 1].toLong() and 0xFF) shl 8) or
                ((b[i + 2].toLong() and 0xFF) shl 16) or
                ((b[i + 3].toLong() and 0xFF) shl 24)
    }
}
