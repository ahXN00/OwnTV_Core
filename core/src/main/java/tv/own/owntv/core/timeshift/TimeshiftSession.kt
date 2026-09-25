package tv.own.owntv.core.timeshift

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * One channel's timeshift buffer on disk (N4): a folder of short pieces, the oldest dropped as the
 * newest arrive, and any number of readers following it — each reader is a player watching from some
 * piece onwards, at the live edge or behind it.
 *
 * **Readers block, they never see "the end".** A reader that reaches the piece still being written
 * waits for more, exactly as a network socket waits for the provider; that is what lets a paused player
 * resume from the saved copy and lets the live edge play with no added delay. Only [close] ends a reader.
 *
 * Thread-safe: one writer (the downloader) and blocking readers (the local server's threads) share
 * [lock]. Wall times are the viewer's clock: a piece starts where the previous one ended, re-anchored to
 * arrival after a gap, so a burst of bytes never compresses minutes of television into a second.
 */
class TimeshiftSession(
    /** Unguessable, and part of every URL, so a stale URL can never reach a newer buffer. */
    val token: String,
    /** Which channel this is the buffer of (the controller's key). */
    val channelKey: String,
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Container(val extension: String, val mimeType: String) {
        TS("ts", "video/mp2t"),
        FMP4("mp4", "video/mp4"),
    }

    class Piece(
        val index: Long,
        val file: File,
        val wallStartMs: Long,
        val arrivedAtMs: Long,
        val gapBefore: Boolean,
    ) {
        @Volatile var bytes: Long = 0
        @Volatile var durationMs: Long = 0
        @Volatile var complete: Boolean = false
    }

    private val lock = Object()
    private val pieces = ArrayDeque<Piece>()
    private var nextIndex = 0L
    private var out: OutputStream? = null
    private var pendingGap = false
    @Volatile private var closed = false

    @Volatile var container: Container = Container.TS
        private set
    private var initFile: File? = null
    private var initBytes: ByteArray? = null

    private val readers = AtomicInteger(0)
    /** Players reading this buffer right now. Zero for a while means nobody is watching (see the manager). */
    val readerCount: Int get() = readers.get()
    @Volatile var lastReaderLeftMs: Long = clock()
        private set

    /** Called on the reader threads when the count changes, so the manager can react without polling. */
    @Volatile var onReadersChanged: (() -> Unit)? = null

    init {
        dir.mkdirs()
    }

    // --- Writer side (the downloader) --------------------------------------------------------------

    /**
     * What the pieces are, and for fragmented MP4 the initialisation segment every reader is given first.
     * A different init mid-buffer (the provider changed codec) cannot be joined to the old pieces, so the
     * buffer starts over.
     */
    fun setContainer(value: Container, init: ByteArray? = null) = synchronized(lock) {
        val changed = container != value || (init != null && initBytes != null && !initBytes!!.contentEquals(init))
        if (changed && pieces.isNotEmpty()) dropAllLocked()
        container = value
        if (init != null && (initBytes == null || changed)) {
            initBytes = init
            initFile = File(dir, "init.mp4").also { it.writeBytes(init) }
        }
        lock.notifyAll()
    }

    /** The connection broke; the next piece follows a hole. */
    fun markGap() = synchronized(lock) { pendingGap = true }

    fun beginPiece() = synchronized(lock) {
        finishOpenPieceLocked(durationMs = null)
        val now = clock()
        val previous = pieces.lastOrNull()
        val gap = pendingGap || previous == null
        val wallStart = if (gap || previous == null) now else previous.wallStartMs + previous.durationMs
        val index = nextIndex++
        val piece = Piece(
            index = index,
            file = File(dir, index.toString().padStart(10, '0') + "." + container.extension),
            wallStartMs = wallStart,
            arrivedAtMs = now,
            gapBefore = pendingGap && previous != null,
        )
        pendingGap = false
        out = BufferedOutputStream(piece.file.outputStream(), WRITE_BUFFER)
        pieces.addLast(piece)
        lock.notifyAll()
    }

    fun write(bytes: ByteArray, offset: Int, length: Int) {
        val stream = synchronized(lock) { out } ?: return
        stream.write(bytes, offset, length)
    }

    /** Make what has been written visible to readers. Called after every network read. */
    fun flush() = synchronized(lock) {
        val stream = out ?: return
        stream.flush()
        pieces.lastOrNull()?.let { it.bytes = it.file.length() }
        lock.notifyAll()
    }

    /** Close the running piece at [durationMs] of television; null measures it on the wall clock. */
    fun endPiece(durationMs: Long?) = synchronized(lock) {
        finishOpenPieceLocked(durationMs)
        lock.notifyAll()
    }

    private fun finishOpenPieceLocked(durationMs: Long?) {
        val stream = out ?: return
        runCatching { stream.flush(); stream.close() }
        out = null
        pieces.lastOrNull()?.let { piece ->
            piece.bytes = piece.file.length()
            // Closed without a measured length (a broken connection): the wall clock is all there is.
            piece.durationMs = durationMs ?: (clock() - piece.arrivedAtMs).coerceAtLeast(0)
            piece.complete = true
        }
    }

    /** Drop what the window and the storage floor no longer allow. Returns how many pieces went. */
    fun trim(windowMs: Long, usableBytes: Long = dir.usableSpace): Int = synchronized(lock) {
        val list = pieces.toList()
        val drop = TimeshiftRules.piecesToDrop(
            durationsMs = list.map { durationOfLocked(it) },
            sizes = list.map { it.bytes },
            windowMs = windowMs,
            usableBytes = usableBytes,
        )
        repeat(drop) { pieces.removeFirst().file.delete() }
        drop
    }

    // --- Timeline ----------------------------------------------------------------------------------

    private fun durationOfLocked(piece: Piece): Long =
        if (piece.complete) piece.durationMs else (clock() - piece.arrivedAtMs).coerceAtLeast(0)

    val isEmpty: Boolean get() = synchronized(lock) { pieces.isEmpty() }

    /** The piece a player at the live edge starts from — the one being written. */
    fun livePieceIndex(): Long? = synchronized(lock) { pieces.lastOrNull()?.index }

    /** The wall-clock instant the oldest saved picture belongs to. */
    fun oldestWallMs(): Long? = synchronized(lock) { pieces.firstOrNull()?.wallStartMs }

    /** The wall-clock instant of the newest saved picture. */
    fun liveEdgeWallMs(): Long? = synchronized(lock) { pieces.lastOrNull()?.let { it.wallStartMs + durationOfLocked(it) } }

    /**
     * "Live" for this buffer: its newest picture, but never later than now. A provider answers a new
     * connection with a burst of what it already had — often 20–30 s of a `.ts` channel, and the three
     * segments an HLS player starts with — so the saved copy runs that far *ahead* of the clock. An
     * ordinary player plays that burst too and is still called live, so the copy measures behind-live
     * from the clock, not from its own edge.
     */
    fun playableEdgeWallMs(): Long? = liveEdgeWallMs()?.coerceAtMost(clock())

    /** Where piece [index] starts on the wall clock, or null once it has been dropped. */
    fun wallStartOf(index: Long): Long? = synchronized(lock) { pieces.firstOrNull { it.index == index }?.wallStartMs }

    /** The piece holding wall-clock instant [wallMs]; the oldest when that has already been dropped. */
    fun pieceAt(wallMs: Long): Long? = synchronized(lock) {
        pieces.lastOrNull { it.wallStartMs <= wallMs }?.index ?: pieces.firstOrNull()?.index
    }

    /** Wall-clock spans that hold nothing (the connection was down), oldest first. */
    fun gaps(): List<LongRange> = synchronized(lock) {
        val list = pieces.toList()
        list.zipWithNext().mapNotNull { (a, b) ->
            val end = a.wallStartMs + durationOfLocked(a)
            if (b.gapBefore && b.wallStartMs > end) end until b.wallStartMs else null
        }
    }

    /** Wait until the first piece exists, or the session closed. False when closed. */
    fun awaitFirstPiece(timeoutMs: Long): Boolean = synchronized(lock) {
        val until = clock() + timeoutMs
        while (!closed && (pieces.isEmpty() || pieces.last().bytes == 0L && !pieces.last().complete)) {
            val left = until - clock()
            if (left <= 0) return false
            lock.wait(minOf(left, WAIT_SLICE_MS))
        }
        !closed
    }

    // --- Reader side (the local server) ------------------------------------------------------------

    /**
     * A stream of this buffer from piece [fromIndex] onwards — null is the live edge, resolved now, so a
     * player that reconnects by itself rejoins the edge rather than where it first started — blocking at
     * the live edge. For fragmented MP4 the initialisation segment comes first. A piece already dropped
     * starts at the oldest instead.
     */
    fun open(fromIndex: Long?): InputStream = Reader(fromIndex)

    /**
     * Where the newest reader's stream starts on the wall clock. The player's position counts from
     * there, whichever way it (re)opened the buffer — which makes "what is on screen" exact even after an
     * engine reconnects on its own.
     */
    @Volatile var readingFromWallMs: Long? = null
        private set

    private inner class Reader(fromIndex: Long?) : InputStream() {
        private var index = synchronized(lock) {
            val first = pieces.firstOrNull()?.index ?: 0L
            val live = pieces.lastOrNull()?.let { newest ->
                val edge = minOf(newest.wallStartMs + durationOfLocked(newest), clock())
                pieces.lastOrNull { it.wallStartMs <= edge }?.index ?: newest.index
            }
            val start = (fromIndex ?: live ?: 0L).coerceAtLeast(first)
            readingFromWallMs = pieces.firstOrNull { it.index == start }?.wallStartMs ?: clock()
            start
        }
        private var input: FileInputStream? = null
        private var readInPiece = 0L
        private var init: ByteArray? = synchronized(lock) { if (container == Container.FMP4) initBytes else null }
        private var initPos = 0
        private var done = false

        init {
            readers.incrementAndGet()
            onReadersChanged?.invoke()
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            init?.let { head ->
                val n = minOf(len, head.size - initPos)
                System.arraycopy(head, initPos, b, off, n)
                initPos += n
                if (initPos >= head.size) init = null
                return n
            }
            while (!done) {
                val stream = input ?: openPiece() ?: return -1
                val n = stream.read(b, off, len)
                if (n > 0) {
                    readInPiece += n
                    return n
                }
                // At the end of what is on disk: the next piece, or wait for this one to grow.
                synchronized(lock) {
                    val piece = pieces.firstOrNull { it.index == index }
                    when {
                        closed -> return -1
                        piece == null -> advance() // dropped under us while we were reading it
                        piece.complete && readInPiece >= piece.bytes -> advance()
                        piece.bytes > readInPiece -> Unit // grew since our read — read again
                        else -> lock.wait(WAIT_SLICE_MS)
                    }
                }
            }
            return -1
        }

        /** Open piece [index], or the oldest if it is gone; waits while it does not exist yet. */
        private fun openPiece(): FileInputStream? = synchronized(lock) {
            while (!closed && !done) {
                val first = pieces.firstOrNull()
                if (first != null && index < first.index) index = first.index
                val piece = pieces.firstOrNull { it.index == index }
                if (piece != null) {
                    readInPiece = 0
                    val stream = runCatching { FileInputStream(piece.file) }.getOrNull()
                    if (stream != null) {
                        input = stream
                        return stream
                    }
                    index++ // dropped between the lookup and the open
                    continue
                }
                lock.wait(WAIT_SLICE_MS)
            }
            null
        }

        private fun advance() {
            runCatching { input?.close() }
            input = null
            index++
        }

        override fun close() {
            if (done) return
            done = true
            runCatching { input?.close() }
            input = null
            if (readers.decrementAndGet() == 0) lastReaderLeftMs = clock()
            onReadersChanged?.invoke()
        }
    }

    // --- Lifetime ----------------------------------------------------------------------------------

    val isClosed: Boolean get() = closed

    /** Stop everything and delete the folder. Readers get end-of-stream. */
    fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        runCatching { out?.close() }
        out = null
        dropAllLocked()
        initFile?.delete()
        runCatching { dir.deleteRecursively() } // the walk throws if the folder vanishes under it
        lock.notifyAll()
    }

    private fun dropAllLocked() {
        runCatching { out?.close() }
        out = null
        pieces.forEach { it.file.delete() }
        pieces.clear()
    }

    private companion object {
        const val WRITE_BUFFER = 64 * 1024
        const val WAIT_SLICE_MS = 250L
    }
}
