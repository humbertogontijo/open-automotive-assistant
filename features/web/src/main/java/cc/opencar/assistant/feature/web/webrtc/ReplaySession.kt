package cc.opencar.assistant.feature.web.webrtc

import android.util.Log
import cc.opencar.assistant.feature.dvr.DvrController
import cc.opencar.assistant.feature.dvr.DvrTimelineMath
import cc.opencar.assistant.feature.dvr.RecordingSampleReader
import cc.opencar.assistant.protocol.OaaFrames
import cc.opencar.assistant.protocol.OaaWebRtc
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Replays DVR recordings on a session's camera tracks: every selected camera
 * reads its own file and all of them are paced by one [ReplayClock], so the
 * viewer just shows the tracks. Playback runs on from group to group, skips
 * gaps, and reports [OaaWebRtc.REPLAY_STATE] (position, group, the epoch anchor)
 * on every change. All state lives on the replay thread; the public methods
 * queue commands.
 */
class ReplaySession(
    private val dvr: DvrController,
    roles: Collection<String>,
    /** Writes one Annex-B AccessUnit on [role]'s track with RTP time [rtpMs]. */
    private val write: (role: String, annexB: ByteArray, rtpMs: Long) -> Boolean,
    private val onState: (JSONObject) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private sealed interface Cmd {
        data class Seek(val atMs: Long, val paused: Boolean?) : Cmd
        data class Speed(val speed: Double) : Cmd
        data class Roles(val roles: Set<String>) : Cmd
        data object Pause : Cmd
        data object Resume : Cmd
        data object Stop : Cmd
    }

    private inner class Cursor(val role: String) {
        var file: DvrTimelineMath.CameraFile? = null
        var reader: RecordingSampleReader? = null
        var pending: RecordingSampleReader.Sample? = null
        var exhausted = false
        /** Frames before this wall time only rebuild the decoder state; they go out at once. */
        var prerollUntil = Long.MIN_VALUE
        /** While paused, also send the first frame at or after [prerollUntil] so the target shows. */
        var showTarget = false

        fun peek(): RecordingSampleReader.Sample? {
            pending?.let { return it }
            while (!exhausted) {
                val r = reader
                if (r != null) {
                    val s = runCatching { r.next() }.getOrNull()
                    if (s != null) {
                        pending = s
                        return s
                    }
                }
                openNext()
            }
            return null
        }

        fun take() {
            pending = null
        }

        /** Position on wall [wallMs]: the file covering it, else this camera's next file. */
        fun openAt(wallMs: Long) {
            close()
            exhausted = false
            val f = fileAt(role, wallMs) ?: nextFile(role, wallMs) ?: run {
                exhausted = true
                return
            }
            open(f, if (wallMs > f.startUtcMs) DvrTimelineMath.offsetInFile(f, wallMs) else 0L)
        }

        private fun openNext() {
            val cur = file
            close()
            val next = cur?.let { nextFile(role, it.startUtcMs + 1) }
            if (next == null) {
                exhausted = true
                return
            }
            open(next, 0L)
        }

        private fun open(f: DvrTimelineMath.CameraFile, offsetMs: Long) {
            file = f
            val path = dvr.recordingFile(f.name)
            if (path == null) {
                reader = null
                return
            }
            reader = runCatching { RecordingSampleReader(path, f.startUtcMs).also { it.open(offsetMs) } }
                .onFailure { Log.w(TAG, "open ${f.name}: ${it.message}") }
                .getOrNull()
        }

        fun close() {
            reader?.close()
            reader = null
            pending = null
        }
    }

    private val queue = LinkedBlockingQueue<Cmd>()
    private val thread = Thread(::run, "oaa-replay").apply { isDaemon = true }
    private val clock = ReplayClock()
    private val cursors = LinkedHashMap<String, Cursor>()
    private var wanted: Set<String> = roles.toSet()
    private var groups: List<DvrTimelineMath.Group> = emptyList()
    private var groupsAt = 0L
    private var playing = false
    private var started = false
    private var speed = 1.0
    private var pausedAt = 0L
    private var group: String? = null
    @Volatile private var floor = 0L

    fun start() = thread.start()

    /** Jump to wall [atMs]; [paused] null keeps the current play / pause state. */
    fun seek(atMs: Long, paused: Boolean?) = queue.put(Cmd.Seek(atMs, paused))
    fun pause() = queue.put(Cmd.Pause)
    fun resume() = queue.put(Cmd.Resume)
    fun setSpeed(speed: Double) = queue.put(Cmd.Speed(speed))
    fun setRoles(roles: Collection<String>) = queue.put(Cmd.Roles(roles.toSet()))

    /** Stop and wait for the thread; returns the first RTP time live frames may use. */
    fun stop(): Long {
        queue.put(Cmd.Stop)
        runCatching { thread.join(STOP_WAIT_MS) }
        return floor
    }

    private fun run() {
        try {
            var waitMs = Long.MAX_VALUE
            while (true) {
                val cmd = if (waitMs == Long.MAX_VALUE) queue.take() else queue.poll(waitMs, TimeUnit.MILLISECONDS)
                if (cmd == Cmd.Stop) break
                if (cmd != null) handle(cmd)
                waitMs = pump()
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            Log.w(TAG, "replay failed", t)
            emit("error", t.message ?: "replay failed")
        } finally {
            cursors.values.forEach { it.close() }
            cursors.clear()
            floor = clock.liveFloor()
        }
    }

    private fun handle(cmd: Cmd) {
        when (cmd) {
            is Cmd.Seek -> doSeek(cmd.atMs, cmd.paused)
            Cmd.Pause -> if (playing) {
                pausedAt = clock.positionAt(now())
                playing = false
                emit("paused")
            }
            Cmd.Resume -> if (!playing && started) {
                clock.reanchor(now(), pausedAt, speed)
                playing = true
                emit("playing")
            }
            is Cmd.Speed -> {
                val s = cmd.speed.coerceIn(MIN_SPEED, MAX_SPEED)
                if (s != speed) {
                    if (playing) clock.reanchor(now(), clock.positionAt(now()), s)
                    speed = s
                    if (started) emit(if (playing) "playing" else "paused")
                }
            }
            is Cmd.Roles -> setWanted(cmd.roles)
            Cmd.Stop -> Unit
        }
    }

    private fun doSeek(atMs: Long, paused: Boolean?) {
        val res = dvr.resolvePlayAt(atMs)
        if (res["ok"] != true) {
            emit("error", res["error"]?.toString() ?: "no recordings")
            return
        }
        if (res["live"] == true) {
            emit("live")
            return
        }
        val wall = (res["atUtcMs"] as? Number)?.toLong() ?: atMs
        refreshGroups(force = true)
        playing = !(paused ?: (started && !playing))
        started = true
        pausedAt = wall
        val t = now()
        clock.reanchor(t, wall, speed)
        cursors.values.forEach { it.close() }
        cursors.clear()
        wanted.forEach { role -> cursors[role] = openCursor(role, wall, t) }
        group = groupAt(wall)?.id
        emit(if (playing) "playing" else "paused")
    }

    private fun setWanted(roles: Set<String>) {
        wanted = roles
        cursors.keys.filter { it !in roles }.forEach { cursors.remove(it)?.close() }
        if (!started) return
        val t = now()
        val pos = if (playing) clock.positionAt(t) else pausedAt
        roles.filter { it !in cursors }.forEach { role -> cursors[role] = openCursor(role, pos, t) }
        emit(if (playing) "playing" else "paused")
    }

    private fun openCursor(role: String, wallMs: Long, nowMs: Long): Cursor = Cursor(role).also {
        it.openAt(wallMs)
        it.prerollUntil = wallMs
        it.showTarget = !playing
        clock.startPreroll(role, nowMs)
    }

    /** Send what is due; returns how long to wait for the next frame (MAX_VALUE: until a command). */
    private fun pump(): Long {
        if (!started) return Long.MAX_VALUE
        for (c in cursors.values) {
            while (true) {
                val s = c.peek() ?: break
                if (s.wallMs >= c.prerollUntil) break
                send(c, s)
            }
            if (c.showTarget) {
                c.showTarget = false
                c.peek()?.takeIf { it.wallMs - c.prerollUntil <= TARGET_SLACK_MS }?.let { send(c, it) }
            }
            c.prerollUntil = Long.MIN_VALUE
        }
        if (!playing) return Long.MAX_VALUE

        val t = now()
        var nextDue = Long.MAX_VALUE
        var nextWall = Long.MAX_VALUE
        for (c in cursors.values) {
            while (true) {
                val s = c.peek() ?: break
                val due = clock.dueAt(s.wallMs)
                if (due > t) {
                    if (due < nextDue) {
                        nextDue = due
                        nextWall = s.wallMs
                    }
                    break
                }
                send(c, s)
            }
        }
        if (nextDue == Long.MAX_VALUE) {
            pausedAt = clock.positionAt(t)
            playing = false
            emit("ended")
            return Long.MAX_VALUE
        }
        if (nextDue - t > GAP_SKIP_MS) {
            clock.reanchor(t, nextWall, speed)
            group = groupAt(nextWall)?.id
            emit("playing")
            return 0L
        }
        val pos = clock.positionAt(t)
        val g = groupAt(pos)?.id
        if (g != null && g != group) {
            group = g
            // A camera that dropped out may be back in the new group.
            cursors.values.filter { it.exhausted }.forEach { c ->
                c.openAt(pos)
                c.prerollUntil = pos
                clock.startPreroll(c.role, t)
            }
            emit("playing")
        }
        return (nextDue - t).coerceIn(1L, MAX_WAIT_MS)
    }

    private fun send(c: Cursor, s: RecordingSampleReader.Sample) {
        c.take()
        write(c.role, s.annexB, clock.rtpFor(c.role, s.wallMs))
    }

    // --- recordings -------------------------------------------------------------

    private fun refreshGroups(force: Boolean = false) {
        val t = now()
        if (!force && t - groupsAt < GROUPS_REFRESH_MS) return
        groups = dvr.timelineGroups()
        groupsAt = t
    }

    private fun groupAt(wallMs: Long): DvrTimelineMath.Group? =
        groups.firstOrNull { wallMs >= it.startUtcMs && wallMs < it.endUtcMs }

    private fun fileAt(role: String, wallMs: Long): DvrTimelineMath.CameraFile? =
        groups.firstNotNullOfOrNull { g -> g.file(role)?.takeIf { wallMs >= it.startUtcMs && wallMs < it.endUtcMs } }

    /** [role]'s first file starting at or after [wallMs]; looks for newly sealed groups once. */
    private fun nextFile(role: String, wallMs: Long): DvrTimelineMath.CameraFile? {
        fun find() = groups.mapNotNull { it.file(role) }.filter { it.startUtcMs >= wallMs }.minByOrNull { it.startUtcMs }
        return find() ?: run {
            refreshGroups()
            find()
        }
    }

    // --- events -----------------------------------------------------------------

    private fun emit(state: String, error: String? = null) {
        val t = now()
        val pos = if (playing) clock.positionAt(t) else pausedAt
        val g = group?.let { id -> groups.firstOrNull { it.id == id } }
        val a = clock.anchor
        val msg = OaaFrames.versioned()
            .put("type", OaaWebRtc.REPLAY_STATE)
            .put("state", state)
            .put("atMs", pos)
            .put("speed", speed)
            .put("roles", JSONArray(g?.files?.map { it.role }.orEmpty()))
        if (started) {
            msg.put(
                "anchor",
                JSONObject()
                    .put("fromRtpMs", a.fromRtpMs)
                    .put("rtpMs", a.rtpMs)
                    .put("wallMs", a.wallMs)
                    .put("speed", a.speed),
            )
        }
        if (g != null) msg.put("group", g.id).put("groupStartUtcMs", g.startUtcMs).put("groupEndUtcMs", g.endUtcMs)
        if (error != null) msg.put("error", error)
        runCatching { onState(msg) }
    }

    companion object {
        private const val TAG = "OaaReplay"
        private const val STOP_WAIT_MS = 2_000L
        private const val MAX_WAIT_MS = 50L
        /** Nothing recorded for this long ahead (car was off): jump to the next recording. */
        private const val GAP_SKIP_MS = 3_000L
        private const val GROUPS_REFRESH_MS = 5_000L
        /** A paused seek shows the first frame at the target only if it is this close. */
        private const val TARGET_SLACK_MS = 1_000L
        const val MIN_SPEED = 0.25
        const val MAX_SPEED = 4.0
    }
}
