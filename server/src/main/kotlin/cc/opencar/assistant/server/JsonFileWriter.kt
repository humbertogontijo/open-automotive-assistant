package cc.opencar.assistant.server

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * Persists a store off the request path: [schedule] queues one write on a shared background
 * thread, and calls made before it runs collapse into that write. Files are replaced atomically.
 */
internal class JsonFileWriter(private val file: File, private val render: () -> String) {
    private val pending = AtomicBoolean(false)

    init {
        file.parentFile?.mkdirs()
    }

    fun schedule() {
        if (pending.compareAndSet(false, true)) executor.execute(::write)
    }

    /** Write now if a change is queued, or wait for an in-flight write (shutdown). */
    fun flush() = write()

    @Synchronized
    private fun write() {
        if (!pending.getAndSet(false)) return
        val dir = file.parentFile ?: return
        if (!dir.isDirectory) return
        runCatching {
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeText(render())
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { log.warning("persist ${file.name} failed: ${it.message}") }
    }

    private companion object {
        val log: Logger = Logger.getLogger("oaa.hub")
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "oaa-persist").apply { isDaemon = true } }
    }
}
