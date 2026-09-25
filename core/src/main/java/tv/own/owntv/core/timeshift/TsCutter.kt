package tv.own.owntv.core.timeshift

/**
 * Cuts a live MPEG-TS byte stream into timeshift pieces that each play on their own (N4, P16a).
 *
 * A piece must be a valid place to *start* a stream, because a rewind opens the buffer at a piece
 * boundary. So a cut is made only where a decoder can begin — on a video packet flagged as a random
 * access point (a keyframe) — and every new piece opens with the most recent PAT and PMT, the two
 * tables a demuxer needs before it can find any stream at all. A piece's length is measured with the
 * stream's own clock (PCR), so a burst of bytes (an HLS segment fetched in half a second) still counts
 * as the six seconds of television it is.
 *
 * Pure: bytes in, packets and cut decisions out through [Sink]. No I/O, no clock of its own except the
 * [wallClock] fallback for a stream that carries no PCR at all.
 */
class TsCutter(
    private val targetMs: Long = TARGET_PIECE_MS,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    /** Where the packets go. [cut] closes the running piece and opens the next. */
    interface Sink {
        fun write(bytes: ByteArray, offset: Int, length: Int)
        /** The running piece is complete and lasted [durationMs]; the next packet starts a new one. */
        fun cut(durationMs: Long)
    }

    private val carry = ByteArray(PACKET)
    private var carried = 0

    private var patPacket: ByteArray? = null
    private var pmtPacket: ByteArray? = null
    private var pmtPid = -1
    private var videoPid = -1
    /** Any PES stream, for a radio channel that has no video to cut on. */
    private var firstPesPid = -1
    private var sawRandomAccess = false

    private var pieceStartPcrMs = -1L
    private var lastPcrMs = -1L
    private var pieceStartWallMs = -1L

    /** True once a 0x47 sync pattern has been found — false for a stream that is not TS at all. */
    var synced = false
        private set

    /** Bytes thrown away while hunting for sync. A caller gives up on a stream that is all garbage. */
    var discardedBytes = 0L
        private set

    fun feed(bytes: ByteArray, offset: Int, length: Int, sink: Sink) {
        var pos = offset
        val end = offset + length
        // Finish a packet left over from the previous call.
        if (carried > 0) {
            val need = PACKET - carried
            val take = minOf(need, end - pos)
            System.arraycopy(bytes, pos, carry, carried, take)
            carried += take
            pos += take
            if (carried < PACKET) return
            carried = 0
            if (carry[0] == SYNC) {
                synced = true
                packet(carry, 0, sink)
            } else {
                discardedBytes += PACKET
            }
        }
        while (pos < end) {
            if (bytes[pos] != SYNC) {
                pos++; discardedBytes++
                continue
            }
            if (end - pos < PACKET) {
                System.arraycopy(bytes, pos, carry, 0, end - pos)
                carried = end - pos
                return
            }
            // A lone 0x47 inside payload is common; only a sync byte one packet later confirms it —
            // unless the packet ends exactly at the end of this chunk, where there is nothing to check.
            if (!synced && pos + PACKET < end && bytes[pos + PACKET] != SYNC) {
                pos++; discardedBytes++
                continue
            }
            synced = true
            packet(bytes, pos, sink)
            pos += PACKET
        }
    }

    private fun packet(b: ByteArray, p: Int, sink: Sink) {
        val pid = ((b[p + 1].toInt() and 0x1F) shl 8) or (b[p + 2].toInt() and 0xFF)
        val pusi = b[p + 1].toInt() and 0x40 != 0
        val afc = (b[p + 3].toInt() shr 4) and 0x03
        var randomAccess = false
        var payloadStart = p + 4
        if (afc and 0x02 != 0) {
            val afLength = b[p + 4].toInt() and 0xFF
            if (afLength > 0) {
                val flags = b[p + 5].toInt() and 0xFF
                randomAccess = flags and 0x40 != 0
                if (flags and 0x10 != 0 && afLength >= 7) {
                    val base = ((b[p + 6].toLong() and 0xFF) shl 25) or
                        ((b[p + 7].toLong() and 0xFF) shl 17) or
                        ((b[p + 8].toLong() and 0xFF) shl 9) or
                        ((b[p + 9].toLong() and 0xFF) shl 1) or
                        ((b[p + 10].toLong() and 0xFF) shr 7)
                    onPcr(base / 90)
                }
            }
            payloadStart = p + 5 + afLength
        }
        val hasPayload = afc and 0x01 != 0 && payloadStart < p + PACKET

        if (pid == PAT_PID && pusi && hasPayload) {
            patPacket = b.copyOfRange(p, p + PACKET)
            parsePat(b, payloadStart, p + PACKET)
        } else if (pid == pmtPid && pusi && hasPayload) {
            pmtPacket = b.copyOfRange(p, p + PACKET)
            parsePmt(b, payloadStart, p + PACKET)
        } else if (pusi && firstPesPid < 0 && pid != PAT_PID && pid != NULL_PID && hasPayload &&
            payloadStart + 3 <= p + PACKET && b[payloadStart].toInt() == 0 && b[payloadStart + 1].toInt() == 0 &&
            b[payloadStart + 2].toInt() == 1
        ) {
            firstPesPid = pid
        }

        if (isCutPoint(pid, pusi, randomAccess)) {
            sink.cut(pieceDurationMs())
            startPiece()
            // Every piece is a valid start: the tables first, then the keyframe itself.
            patPacket?.let { sink.write(it, 0, PACKET) }
            pmtPacket?.let { sink.write(it, 0, PACKET) }
        } else if (pieceStartWallMs < 0) {
            startPiece()
        }
        sink.write(b, p, PACKET)
    }

    private fun isCutPoint(pid: Int, pusi: Boolean, randomAccess: Boolean): Boolean {
        if (pieceStartWallMs < 0) return false // the very first packet opens the first piece, no cut
        if (patPacket == null || pmtPacket == null) return false
        val elapsed = pieceDurationMs()
        if (videoPid >= 0) {
            if (pid != videoPid) return false
            if (randomAccess) sawRandomAccess = true
            if (randomAccess) return elapsed >= targetMs
            // A stream that never flags keyframes: cut on a picture start once a piece has run long,
            // so the buffer still moves. The first frames after such a cut may show blocks.
            return !sawRandomAccess && pusi && elapsed >= targetMs * NO_KEYFRAME_FACTOR
        }
        // Radio: no video, every audio frame is a valid start.
        return pid == firstPesPid && pusi && elapsed >= targetMs
    }

    private fun startPiece() {
        pieceStartPcrMs = lastPcrMs
        pieceStartWallMs = wallClock()
    }

    private fun onPcr(ms: Long) {
        // A PCR that jumps (a reconnect, a wrap, a provider reset) restarts the piece's clock rather
        // than producing a negative or hour-long piece.
        if (lastPcrMs >= 0 && (ms < lastPcrMs || ms - lastPcrMs > PCR_JUMP_MS)) {
            pieceStartPcrMs = -1L
        }
        lastPcrMs = ms
        if (pieceStartPcrMs < 0 && pieceStartWallMs >= 0) {
            // The piece began without a usable clock; start counting from here, crediting the wall time so far.
            pieceStartPcrMs = ms - (wallClock() - pieceStartWallMs).coerceAtLeast(0)
        }
    }

    /** How long the running piece has lasted: stream clock when there is one, wall clock otherwise. */
    fun pieceDurationMs(): Long =
        if (pieceStartPcrMs >= 0 && lastPcrMs >= pieceStartPcrMs) lastPcrMs - pieceStartPcrMs
        else (wallClock() - pieceStartWallMs).coerceAtLeast(0)

    private fun parsePat(b: ByteArray, payloadStart: Int, end: Int) {
        val section = payloadStart + 1 + (b[payloadStart].toInt() and 0xFF) // skip pointer_field
        if (section + 8 > end) return
        val sectionLength = ((b[section + 1].toInt() and 0x0F) shl 8) or (b[section + 2].toInt() and 0xFF)
        val entriesEnd = minOf(section + 3 + sectionLength - 4, end)
        var i = section + 8
        while (i + 4 <= entriesEnd) {
            val program = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            val pid = ((b[i + 2].toInt() and 0x1F) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (program != 0) {
                pmtPid = pid
                return
            }
            i += 4
        }
    }

    private fun parsePmt(b: ByteArray, payloadStart: Int, end: Int) {
        val section = payloadStart + 1 + (b[payloadStart].toInt() and 0xFF)
        if (section + 12 > end) return
        val sectionLength = ((b[section + 1].toInt() and 0x0F) shl 8) or (b[section + 2].toInt() and 0xFF)
        val infoLength = ((b[section + 10].toInt() and 0x0F) shl 8) or (b[section + 11].toInt() and 0xFF)
        val esEnd = minOf(section + 3 + sectionLength - 4, end)
        var i = section + 12 + infoLength
        while (i + 5 <= esEnd) {
            val type = b[i].toInt() and 0xFF
            val pid = ((b[i + 1].toInt() and 0x1F) shl 8) or (b[i + 2].toInt() and 0xFF)
            val esInfo = ((b[i + 3].toInt() and 0x0F) shl 8) or (b[i + 4].toInt() and 0xFF)
            if (type in VIDEO_STREAM_TYPES) {
                videoPid = pid
                return
            }
            i += 5 + esInfo
        }
    }

    companion object {
        const val PACKET = 188
        private const val SYNC: Byte = 0x47
        private const val PAT_PID = 0
        private const val NULL_PID = 0x1FFF

        /** Short pieces: a rewind can land within two seconds of where it was aimed, and the live edge
         *  starts at most one piece back. */
        const val TARGET_PIECE_MS = 2_000L

        private const val NO_KEYFRAME_FACTOR = 3

        /** A PCR step bigger than this is a discontinuity, not time passing. */
        private const val PCR_JUMP_MS = 10_000L

        /** MPEG-1/2, MPEG-4 Part 2, H.264, HEVC, VVC, AVS2/AVS3, VC-1 (private 0xEA). */
        private val VIDEO_STREAM_TYPES = setOf(0x01, 0x02, 0x10, 0x1B, 0x24, 0x33, 0x42, 0xD2, 0xD4, 0xEA)
    }
}
