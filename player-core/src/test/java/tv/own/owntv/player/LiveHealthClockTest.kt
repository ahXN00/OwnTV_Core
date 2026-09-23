package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mpv live reconnect budget used to be cleared by the first advancing poll, so a channel that
 * played for two seconds after every reconnect never ran out of attempts and spun for ever. These pin
 * the replacement rule: only an unbroken run of [LivePreviewEngine.HEALTHY_MS] earns the budget back.
 */
class LiveHealthClockTest {

    @Test
    fun `a short burst of playback does not count as healthy`() {
        val clock = LiveHealthClock(healthyMs = 60_000L)
        assertFalse(clock.onProgress(0L))
        assertFalse(clock.onProgress(2_000L))
        assertFalse(clock.onProgress(59_999L))
    }

    @Test
    fun `an unbroken run of the healthy window counts`() {
        val clock = LiveHealthClock(healthyMs = 60_000L)
        clock.onProgress(10_000L)
        assertTrue(clock.onProgress(70_000L))
    }

    @Test
    fun `a stall in between starts the window over`() {
        val clock = LiveHealthClock(healthyMs = 60_000L)
        clock.onProgress(0L)
        clock.onProgress(50_000L)
        clock.reset()
        assertFalse("the run before the stall must not carry over", clock.onProgress(61_000L))
        assertTrue(clock.onProgress(121_000L))
    }

    @Test
    fun `repeated quick deaths never earn the budget back`() {
        // Each reconnect starts a new watchdog (a new clock) and dies a few seconds in.
        repeat(10) { attempt ->
            val clock = LiveHealthClock(healthyMs = 60_000L)
            val start = attempt * 10_000L
            assertFalse(clock.onProgress(start))
            assertFalse(clock.onProgress(start + 5_000L))
        }
    }

    @Test
    fun `the default window is the live engine's`() {
        val clock = LiveHealthClock()
        clock.onProgress(0L)
        assertFalse(clock.onProgress(LivePreviewEngine.HEALTHY_MS - 1))
        assertTrue(clock.onProgress(LivePreviewEngine.HEALTHY_MS))
    }

    @Test
    fun `retry and restore replay every field play accepts`() {
        // HUD Retry once dropped two of play()'s fields and reopened a DASH-declared channel as the
        // wrong container. Both recovery paths now replay one TunedRequest; this fails the moment play()
        // gains a parameter that value does not carry.
        val engine = LivePreviewEngine::class.java
        // A value-class parameter mangles the JVM name (play-4a1nqsM); the $default bridge is skipped.
        val playParams = engine.declaredMethods
            .filter { (it.name == "play" || it.name.startsWith("play-")) && !it.name.endsWith("\$default") }
            .filter { it.parameterTypes.firstOrNull() == String::class.java }
            .maxOf { it.parameterCount }
        val request = Class.forName("tv.own.owntv.player.LivePreviewEngine\$TunedRequest", false, engine.classLoader)
        val fields = request.declaredFields.count { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        assertEquals(playParams, fields)
    }
}
