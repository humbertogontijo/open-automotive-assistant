package cc.opencar.assistant.apkdelta

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Zip-entry-level APK patches (`.oadp`): entries whose bytes already exist in the base APK
 * are copied from it, everything else (changed entries, signing block, central directory)
 * travels in the patch.
 *
 * Layout, big-endian: `OADP` magic, u32 version, base SHA-256 (32 bytes), target SHA-256
 * (32 bytes), u64 target size, u32 op count, ops as (u8 kind, u64 offset, u64 length), then
 * the DATA payload. COPY offsets point into the base APK; DATA offsets are relative to the
 * payload start and DATA ops appear in payload order, so a patch applies in one pass.
 */
object ApkDelta {
    const val COPY: Byte = 0
    const val DATA: Byte = 1

    private const val MAGIC = 0x4F414450
    private const val VERSION = 1
    private const val HEADER_BYTES = 4 + 4 + 32 + 32 + 8 + 4
    private const val OP_BYTES = 1 + 8 + 8
    private const val BUF = 64 * 1024

    data class Op(val kind: Byte, val offset: Long, val length: Long)

    class Header(val fromSha256: String, val toSha256: String, val toSize: Long, val ops: List<Op>) {
        val payloadOffset: Long get() = HEADER_BYTES + OP_BYTES.toLong() * ops.size
        val dataBytes: Long get() = ops.filter { it.kind == DATA }.sumOf { it.length }
        val patchSize: Long get() = payloadOffset + dataBytes
    }

    /** Write a patch turning [old] into [new]; throws [IOException] for archives it cannot parse (e.g. zip64). */
    fun diff(old: File, new: File, patch: File): Header {
        val ops = RandomAccessFile(old, "r").use { o ->
            RandomAccessFile(new, "r").use { n -> planOps(o, n) }
        }
        var payload = 0L
        val relative = ops.map { op ->
            if (op.kind == COPY) {
                op
            } else {
                Op(DATA, payload, op.length).also { payload += op.length }
            }
        }
        val header = Header(sha256(old), sha256(new), new.length(), relative)
        RandomAccessFile(new, "r").use { n ->
            DataOutputStream(patch.outputStream().buffered(BUF)).use { out ->
                writeHeader(out, header)
                val buf = ByteArray(BUF)
                ops.filter { it.kind == DATA }.forEach { op -> copyRange(n, op.offset, op.length, out, buf) }
            }
        }
        return header
    }

    /** Rebuild the target APK from [base] and [patch] into [out]. Callers verify [Header.toSha256]. */
    fun apply(base: File, patch: File, out: File): Header =
        DataInputStream(BufferedInputStream(patch.inputStream(), BUF)).use { input ->
            val header = readHeader(input)
            RandomAccessFile(base, "r").use { b ->
                out.outputStream().buffered(BUF).use { o ->
                    val buf = ByteArray(BUF)
                    var payload = 0L
                    for (op in header.ops) {
                        if (op.kind == COPY) {
                            if (op.offset + op.length > b.length()) throw IOException("patch does not match base")
                            copyRange(b, op.offset, op.length, o, buf)
                        } else {
                            if (op.offset != payload) throw IOException("corrupt patch")
                            copyStream(input, op.length, o, buf)
                            payload += op.length
                        }
                    }
                }
            }
            if (out.length() != header.toSize) throw IOException("patched size ${out.length()} != ${header.toSize}")
            header
        }

    fun readHeader(patch: File): Header =
        DataInputStream(BufferedInputStream(patch.inputStream(), BUF)).use { readHeader(it) }

    /**
     * `sh apply.sh BASE PATCH OUT` rebuilds the target on a device with only toybox: `dd` with
     * byte offsets, or `tail`/`head` where `dd` lacks them. Callers verify the output hash.
     */
    fun shellScript(header: Header): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("# OADP apply: sh apply.sh BASE PATCH OUT")
        appendLine("# from=${header.fromSha256} to=${header.toSha256} size=${header.toSize}")
        appendLine("B=\"\$1\"; P=\"\$2\"; O=\"\$3\"")
        // Not `r`: mksh predefines it as a history alias.
        appendLine("seg() { dd if=\"\$1\" bs=65536 iflag=skip_bytes,count_bytes skip=\"\$2\" count=\"\$3\" 2>/dev/null; }")
        appendLine("printf xyz > \"\$O.probe\"")
        appendLine("if [ \"\$(seg \"\$O.probe\" 1 1)\" != y ]; then")
        appendLine("  seg() { tail -c +\$((\$2 + 1)) \"\$1\" | head -c \"\$3\"; }")
        appendLine("fi")
        appendLine("rm -f \"\$O.probe\"")
        appendLine("{")
        for (op in header.ops) {
            if (op.kind == COPY) {
                appendLine("seg \"\$B\" ${op.offset} ${op.length}")
            } else {
                appendLine("seg \"\$P\" ${header.payloadOffset + op.offset} ${op.length}")
            }
        }
        appendLine("} > \"\$O\"")
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUF)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return hex(digest.digest())
    }

    private fun planOps(old: RandomAccessFile, new: RandomAccessFile): List<Op> {
        val spans = HashMap<String, Long>()
        val datas = HashMap<String, Long>()
        for (e in ZipLayout.read(old).entries) {
            val (spanKey, dataKey) = keys(old, e)
            spans.putIfAbsent(spanKey, e.headerOffset)
            if (dataKey != null) datas.putIfAbsent(dataKey, e.dataOffset)
        }

        val ops = ArrayList<Op>()
        fun add(kind: Byte, offset: Long, length: Long) {
            if (length <= 0) return
            val last = ops.lastOrNull()
            if (last != null && last.kind == kind && last.offset + last.length == offset) {
                ops[ops.size - 1] = Op(kind, last.offset, last.length + length)
            } else {
                ops += Op(kind, offset, length)
            }
        }

        var pos = 0L
        for (e in ZipLayout.read(new).entries) {
            add(DATA, pos, e.headerOffset - pos)
            val (spanKey, dataKey) = keys(new, e)
            val span = spans[spanKey]
            val data = dataKey?.let { datas[it] }
            when {
                span != null -> add(COPY, span, e.spanEnd - e.headerOffset)
                data != null -> {
                    add(DATA, e.headerOffset, e.dataOffset - e.headerOffset)
                    add(COPY, data, e.dataEnd - e.dataOffset)
                    add(DATA, e.dataEnd, e.spanEnd - e.dataEnd)
                }
                else -> add(DATA, e.headerOffset, e.spanEnd - e.headerOffset)
            }
            pos = e.spanEnd
        }
        add(DATA, pos, new.length() - pos)
        return ops
    }

    /** Content keys for a whole local record and for its data alone (null when empty). */
    private fun keys(raf: RandomAccessFile, e: ZipLayout.Entry): Pair<String, String?> {
        val span = MessageDigest.getInstance("SHA-256")
        val data = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUF)
        raf.seek(e.headerOffset)
        var pos = e.headerOffset
        while (pos < e.spanEnd) {
            val n = raf.read(buf, 0, minOf(BUF.toLong(), e.spanEnd - pos).toInt())
            if (n < 0) throw IOException("truncated archive")
            span.update(buf, 0, n)
            val from = maxOf(pos, e.dataOffset)
            val to = minOf(pos + n, e.dataEnd)
            if (to > from) data.update(buf, (from - pos).toInt(), (to - from).toInt())
            pos += n
        }
        val dataLen = e.dataEnd - e.dataOffset
        return "${e.spanEnd - e.headerOffset}:${hex(span.digest())}" to
            (if (dataLen > 0) "$dataLen:${hex(data.digest())}" else null)
    }

    private fun writeHeader(out: DataOutputStream, h: Header) {
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.write(unhex(h.fromSha256))
        out.write(unhex(h.toSha256))
        out.writeLong(h.toSize)
        out.writeInt(h.ops.size)
        for (op in h.ops) {
            out.writeByte(op.kind.toInt())
            out.writeLong(op.offset)
            out.writeLong(op.length)
        }
    }

    private fun readHeader(input: DataInputStream): Header {
        if (input.readInt() != MAGIC) throw IOException("not an OADP patch")
        val version = input.readInt()
        if (version != VERSION) throw IOException("unsupported OADP version $version")
        val from = ByteArray(32).also { input.readFully(it) }
        val to = ByteArray(32).also { input.readFully(it) }
        val size = input.readLong()
        val count = input.readInt()
        if (count < 0) throw IOException("corrupt patch")
        val ops = List(count) {
            val kind = input.readByte()
            if (kind != COPY && kind != DATA) throw IOException("corrupt patch")
            Op(kind, input.readLong(), input.readLong())
        }
        return Header(hex(from), hex(to), size, ops)
    }

    private fun copyRange(src: RandomAccessFile, offset: Long, length: Long, out: OutputStream, buf: ByteArray) {
        src.seek(offset)
        var left = length
        while (left > 0) {
            val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("unexpected end of file")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun copyStream(src: InputStream, length: Long, out: OutputStream, buf: ByteArray) {
        var left = length
        while (left > 0) {
            val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("truncated patch")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
