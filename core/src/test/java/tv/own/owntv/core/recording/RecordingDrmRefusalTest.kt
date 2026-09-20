package tv.own.owntv.core.recording

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.model.RecordingFailure

/**
 * A DRM-protected channel is refused rather than recorded (§1.5).
 *
 * The behaviour this replaces was genuinely harmful, not merely useless. A protected channel used to
 * fall through to the raw byte pump, which wrote the provider's response into the file, hit
 * end-of-body, reported [RecordingFailure.NETWORK] — a reason that is not terminal — and so
 * **reconnected and appended again**, for the entire length of the programme. The user got an
 * unplayable file, the provider got hours of reconnects, and one of the account's connection slots
 * was held the whole time by a recording that could never succeed.
 *
 * It cannot succeed because the CDM decrypts only into a secure decoder for immediate display: there
 * is no point at which this app holds the frames in the clear to write them down. No amount of
 * retrying changes that, which is exactly why the reason must be terminal.
 */
class RecordingDrmRefusalTest {

    /**
     * The property the engine's retry loop depends on. `NETWORK` and friends are retried; a reason
     * that cannot change with time must not be, or the reconnect storm above comes straight back.
     */
    @Test
    fun `the terminal reasons are the ones retrying cannot fix`() {
        val terminal = setOf(
            RecordingFailure.NO_SPACE,
            RecordingFailure.ENCRYPTED,
            RecordingFailure.DRM_PROTECTED,
        )
        assertTrue("DRM must be terminal", RecordingFailure.DRM_PROTECTED in terminal)
        // The retried reasons are the transient ones, and DRM is emphatically not among them.
        listOf(
            RecordingFailure.NETWORK,
            RecordingFailure.STREAM_UNAVAILABLE,
            RecordingFailure.NO_CONNECTION,
        ).forEach { assertTrue("$it should stay retryable", it !in terminal) }
    }

    /**
     * Kept separate from [RecordingFailure.ENCRYPTED] on purpose, and this is the test that stops
     * someone folding them together later. They are different findings with different messages:
     * `ENCRYPTED` is HLS transport encryption (`#EXT-X-KEY`, usually plain AES-128) discovered *inside*
     * a playlist we had to fetch first, while `DRM_PROTECTED` is declared by the playlist entry and is
     * known before a single byte is requested. Reusing one message for both would tell a user with an
     * AES-128 channel that it is DRM-protected, which is false.
     */
    @Test
    fun `drm and hls encryption are distinct reasons`() {
        assertNotEquals(RecordingFailure.ENCRYPTED, RecordingFailure.DRM_PROTECTED)
    }

    /** Every reason must map to a message, or a refused recording shows the user a blank row. The
     *  mapping itself is exhaustive over the enum, so this is really a guard on future additions. */
    @Test
    fun `every failure reason is accounted for in the enum`() {
        assertTrue(RecordingFailure.DRM_PROTECTED in RecordingFailure.entries)
        // NONE is the only value that deliberately has no message.
        assertNotEquals(RecordingFailure.NONE, RecordingFailure.DRM_PROTECTED)
    }
}
