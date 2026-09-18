package com.solitech.bitcoincorenode.core.node

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Follows `debug.log`, like `tail -F`.
 *
 * ## Why not just poll getblockchaininfo
 *
 * Because during the first stretch of a cold start there is no RPC server yet.
 * Core binds the HTTP port only after it has loaded the block index, and on a
 * phone with a few gigabytes of chainstate that can be tens of seconds. During
 * that window `getblockchaininfo` is not slow — it is refused, and the only
 * evidence the node is alive and making progress is what it writes here.
 *
 * ## The two things that make this non-trivial
 *
 * **Rotation.** Core rotates debug.log (`shrinkdebugfile=1` truncates it at
 * startup once it passes 10 MB). A naive tailer holding a file offset keeps
 * reading past the new end of a shorter file and returns garbage forever. So
 * we detect shrinkage and seek back to zero.
 *
 * **Partial lines.** Reads land mid-line constantly. Emitting a half-line
 * means the string matching in [NodeSupervisor.interpretLogLine] misses the
 * pattern it was waiting for. So we buffer until a newline arrives.
 */
class DebugLogTailer(
    private val logFile: File,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 400,
    private val onLine: (String) -> Unit,
) {
    private var job: Job? = null

    fun start() {
        stop()
        job = scope.launch(Dispatchers.IO) {
            var position = 0L
            var pending = StringBuilder()

            // Start at the end of any pre-existing log. Replaying an old log
            // would drive the startup UI through last week's state machine.
            if (logFile.exists()) position = logFile.length()

            val buffer = ByteArray(BUFFER_BYTES)

            while (isActive) {
                ensureActive()
                try {
                    if (!logFile.exists()) {
                        position = 0
                        pending = StringBuilder()
                        delay(pollIntervalMs)
                        continue
                    }

                    val length = logFile.length()
                    if (length < position) {
                        // Truncated or rotated. Anything buffered belongs to a
                        // file that no longer exists.
                        position = 0
                        pending = StringBuilder()
                    }
                    if (length == position) {
                        delay(pollIntervalMs)
                        continue
                    }

                    RandomAccessFile(logFile, "r").use { raf ->
                        raf.seek(position)
                        var remaining = length - position
                        while (remaining > 0 && isActive) {
                            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                            val read = raf.read(buffer, 0, toRead)
                            if (read <= 0) break
                            position += read
                            remaining -= read

                            pending.append(String(buffer, 0, read, StandardCharsets.UTF_8))

                            var newlineAt = pending.indexOf("\n")
                            while (newlineAt >= 0) {
                                val line = pending.substring(0, newlineAt).trimEnd('\r')
                                pending.delete(0, newlineAt + 1)
                                if (line.isNotBlank()) onLine(line)
                                newlineAt = pending.indexOf("\n")
                            }

                            // A single unterminated line this long is not a log
                            // line, it is a runaway. Drop it rather than grow
                            // the buffer without bound.
                            if (pending.length > MAX_PENDING) pending = StringBuilder()
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The log can vanish underneath us mid-read during a
                    // restart. Not worth surfacing; just resync next tick.
                    delay(pollIntervalMs * 2)
                }
                delay(pollIntervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val BUFFER_BYTES = 8 * 1024
        private const val MAX_PENDING = 64 * 1024
    }
}

/**
 * Pulls structured facts out of Core's log lines.
 *
 * Deliberately narrow. Parsing a log is inherently fragile — the format is not
 * an API and upstream can change it in any release — so this extracts only
 * things that have been stable for many years, and every caller treats a null
 * as "no information", never as an error. Anything load-bearing comes from RPC.
 */
object LogParser {

    // 2026-07-06T19:37:11Z UpdateTip: new best=00000... height=840123 ... progress=0.999812
    private val UPDATE_TIP = Regex("""UpdateTip: new best=(\w+) height=(\d+).*?progress=([\d.]+)""")
    private val PROGRESS = Regex("""progress=([\d.]+)""")
    private val HEIGHT = Regex("""height=(\d+)""")

    data class TipUpdate(val hash: String, val height: Long, val progress: Double)

    fun parseTipUpdate(line: String): TipUpdate? {
        val m = UPDATE_TIP.find(line) ?: return null
        return TipUpdate(
            hash = m.groupValues[1],
            height = m.groupValues[2].toLongOrNull() ?: return null,
            progress = m.groupValues[3].toDoubleOrNull() ?: return null,
        )
    }

    fun parseProgress(line: String): Double? =
        PROGRESS.find(line)?.groupValues?.get(1)?.toDoubleOrNull()

    fun parseHeight(line: String): Long? =
        HEIGHT.find(line)?.groupValues?.get(1)?.toLongOrNull()

    /** Lines worth showing the user without them opening the raw log. */
    fun isNoteworthy(line: String): Boolean =
        "Error" in line || "Warning" in line || "ERROR" in line ||
            "init message" in line || "Shutdown" in line || "Corrupt" in line
}
