package tv.own.owntv.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.ByteArrayInputStream

/**
 * "Prefer SDR when a stream offers both": an HLS stream that lists SDR and HDR variants side by side plays
 * the SDR ones, for a TV whose HDR picture looks washed out. A stream with only one kind is untouched — an
 * HDR-only picture stays HDR and the TV tone-maps it.
 *
 * Media3 reads `VIDEO-RANGE` but keeps it to itself, so the playlist text is read here.
 */
object SdrVariants {

    /** The addresses of the SDR variants, as written in [playlist], when it also has HDR ones; else null. */
    fun sdrUris(playlist: String): Set<String>? {
        val sdr = LinkedHashSet<String>()
        var hdr = false
        var pendingHdr: Boolean? = null
        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF:") ->
                    pendingHdr = RANGE.find(line)?.groupValues?.get(1)?.uppercase() in HDR_RANGES
                line.isEmpty() || line.startsWith("#") -> Unit
                pendingHdr != null -> {
                    if (pendingHdr) hdr = true else sdr += line
                    pendingHdr = null
                }
            }
        }
        return sdr.takeIf { hdr && it.isNotEmpty() }
    }

    /** Media3's own parsers, with the HDR variants dropped from a stream that has both while [preferSdr]. */
    @UnstableApi
    class ParserFactory(private val preferSdr: () -> Boolean) : HlsPlaylistParserFactory {
        private val inner = DefaultHlsPlaylistParserFactory()

        override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> = wrap(inner.createPlaylistParser())

        override fun createPlaylistParser(
            multivariantPlaylist: HlsMultivariantPlaylist,
            previousMediaPlaylist: HlsMediaPlaylist?,
        ): ParsingLoadable.Parser<HlsPlaylist> = wrap(inner.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

        private fun wrap(parser: ParsingLoadable.Parser<HlsPlaylist>) = ParsingLoadable.Parser<HlsPlaylist> { uri, input ->
            if (!preferSdr()) return@Parser parser.parse(uri, input)
            val bytes = input.readBytes()
            val parsed = parser.parse(uri, ByteArrayInputStream(bytes))
            if (parsed !is HlsMultivariantPlaylist) return@Parser parsed
            val keep = sdrUris(String(bytes, Charsets.UTF_8))?.map { UriUtil.resolveToUri(parsed.baseUri, it) }?.toSet()
                ?: return@Parser parsed
            val variants = parsed.variants.filter { it.url in keep }
            if (variants.isEmpty()) return@Parser parsed
            HlsMultivariantPlaylist(
                parsed.baseUri, parsed.tags, variants, parsed.videos, parsed.audios, parsed.subtitles,
                parsed.closedCaptions, parsed.muxedAudioFormat, parsed.muxedCaptionFormats,
                parsed.hasIndependentSegments, parsed.variableDefinitions, parsed.sessionKeyDrmInitData,
                parsed.contentSteeringInfo,
            )
        }
    }

    private val RANGE = Regex("VIDEO-RANGE=\"?([A-Za-z]+)")
    private val HDR_RANGES = setOf("PQ", "HLG")
}
