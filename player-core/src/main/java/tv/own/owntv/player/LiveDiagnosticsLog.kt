package tv.own.owntv.player

import android.content.Context
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import tv.own.owntv.core.CoreBuildInfo
import tv.own.owntv.core.player.PlayerBudget

/**
 * Bounded ring buffer + rolling file for Live TV (ExoPlayer) playback diagnostics. A hang can happen at any
 * time, and this device's vendor PQ/AI logging floods logcat fast enough that OwnTV's own lines don't survive
 * to a bugreport — so this keeps its own bounded record independent of Logcat.
 *
 * Enabled by default in debug builds, disabled by default in release. Callers must redact URLs/credentials
 * before passing text in — this class does not inspect or strip anything itself.
 */
object LiveDiagnosticsLog {
    const val TAG = "OwnTV-LivePreviewEngine"

    @Volatile var enabled: Boolean = CoreBuildInfo.debug || CoreBuildInfo.diagnosticBuild

    private const val MAX_EVENTS = 1000

    /**
     * Rolling-file cap, device-tiered like [PlayerBudget]'s memory budget.
     *
     * Rolling the file means reading all of it back and rewriting the half worth keeping, so the cap is
     * also the size of the read/write burst on the [writer] thread. On a 2 GB TV, halving it halves that
     * burst; on anything larger the extra history is worth having when a hang has to be diagnosed after
     * the fact.
     */
    private const val MAX_FILE_BYTES = 256 * 1024L
    private const val MAX_FILE_BYTES_LOW_SPEC = 128 * 1024L

    @Volatile private var maxFileBytes = MAX_FILE_BYTES
    private val ring = ArrayDeque<String>()
    private val lock = Any()
    // Immutable and thread-safe: events arrive from the main, player and mpv event threads at once, and
    // the SimpleDateFormat this replaced could print a garbled timestamp when two of them overlapped.
    private val timeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US)
    // File I/O used to run on whichever thread logged the line — often the main thread. One thread, so
    // lines still land in the order they were logged.
    private val writer = Executors.newSingleThreadScheduledExecutor { Thread(it, "owntv-diag-log").apply { isDaemon = true } }
    @Volatile private var logFile: File? = null

    // Writer thread only. Opening, sizing and closing the file for every line cost a stat, an open and a
    // close several times a second while a channel plays; one buffered writer, flushed shortly after the
    // first unflushed line, does the same work in one write.
    private var out: java.io.Writer? = null
    /** Characters in the file — close enough to bytes for a soft cap, without encoding every line twice. */
    private var written = 0L
    private var flushPending = false
    private const val FLUSH_DELAY_MS = 1_000L

    /** Call once with an app [Context] so events can be flushed to [file]. Safe to call repeatedly. */
    fun init(context: Context) {
        if (logFile != null) return
        maxFileBytes = if (PlayerBudget.of(context).lowSpec) MAX_FILE_BYTES_LOW_SPEC else MAX_FILE_BYTES
        runCatching {
            val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
            logFile = File(dir, "live_diagnostics.log")
        }
    }

    /** Record one diagnostic line. Always kept in the in-memory ring; also written to Logcat + the rolling
     *  file when [enabled]. [message] must already be redacted of URLs/credentials by the caller. */
    fun event(message: String) {
        val line = "${timeFmt.format(LocalDateTime.now())} $message"
        synchronized(lock) {
            ring.addLast(line)
            while (ring.size > MAX_EVENTS) ring.pollFirst()
        }
        if (enabled) {
            android.util.Log.i(TAG, message)
            writeLine(line)
        }
    }

    private fun writeLine(line: String) {
        val f = logFile ?: return
        writer.execute {
            runCatching {
                if (out == null) open(f)
                if (written > maxFileBytes) {
                    out?.close()
                    val kept = f.readLines().takeLast(MAX_EVENTS / 2)
                    f.writeText(kept.joinToString("\n") + "\n")
                    open(f)
                }
                out?.apply { write(line); write("\n") }
                written += line.length + 1
                if (!flushPending) {
                    flushPending = true
                    writer.schedule({ flushPending = false; runCatching { out?.flush() } }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
                }
            }.onFailure { runCatching { out?.close() }; out = null }
        }
    }

    private fun open(f: File) {
        out = java.io.BufferedWriter(java.io.FileWriter(f, true))
        written = f.length()
    }

    /** Oldest-first snapshot of the in-memory ring buffer (e.g. for an in-app export action). */
    fun snapshot(): String = synchronized(lock) { ring.joinToString("\n") }

    /** Path of the rolling diagnostic file on disk, once [init] has run. */
    fun file(): File? = logFile
}
