package tv.own.owntv.core.util

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.core.stalker.StalkerEpgLoader

class ErrorMessagesTest {
    @Test
    fun `known host resolution failure is classified for localized presentation`() {
        assertEquals(
            FriendlySyncFailure.Unreachable,
            classifySyncFailure("Unable to resolve host example.test", online = true),
        )
    }

    /**
     * Both guide sources that can answer perfectly and carry nothing. Before this, the portal's
     * message fell through to [FriendlySyncFailure.Unknown] and was shown as raw English in every
     * language, and the XMLTV one had no message at all so the row showed no error whatsoever.
     */
    @Test
    fun `a guide that answered with nothing is classified rather than shown raw`() {
        assertEquals(
            FriendlySyncFailure.GuideEmpty,
            classifySyncFailure(StalkerEpgLoader.PortalHasNoGuideException().message, online = true),
        )
        assertEquals(
            FriendlySyncFailure.GuideEmpty,
            classifySyncFailure(EpgRepository.NoProgrammesInWindowException().message, online = true),
        )
    }

    @Test
    fun `unmapped external failure preserves its raw text`() {
        val raw = "Provider-specific failure code X17"

        assertEquals(
            FriendlySyncFailure.Unknown(raw),
            classifySyncFailure(raw, online = true),
        )
    }
}
