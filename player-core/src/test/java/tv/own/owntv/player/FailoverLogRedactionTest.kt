package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.network.HostPool
import java.io.File

/**
 * AC17 / R13: no raw server address in the error log or in the logcat lines host failover added. The
 * code names a server only through `HostPool.describe` ("server k of N (•••)").
 */
class FailoverLogRedactionTest {

    private val files = listOf(
        "../core/src/main/java/tv/own/owntv/core/network/HostPool.kt",
        "../core/src/main/java/tv/own/owntv/core/network/HostSelector.kt",
        "../core/src/main/java/tv/own/owntv/core/network/HostFailover.kt",
        "../core/src/main/java/tv/own/owntv/core/network/HostFailure.kt",
        "../core/src/main/java/tv/own/owntv/core/network/HostPreflight.kt",
        "../core/src/main/java/tv/own/owntv/core/network/StreamingHttpClient.kt",
        "../core/src/main/java/tv/own/owntv/core/repository/SourceTester.kt",
        "src/main/java/tv/own/owntv/player/LiveTuneController.kt",
        "src/main/java/tv/own/owntv/player/ServerRound.kt",
        "src/main/java/tv/own/owntv/player/VodFailover.kt",
        "src/main/java/tv/own/owntv/player/ReleaseGate.kt",
    )

    /** A logging call on the line… */
    private val logCall = Regex("""(Log\.[diwe]\(|engineLog\(|LiveDiagnosticsLog\.event\(|recordLadderEvent\(|\blog\()""")

    /** …interpolating one of the names failover gives a server or a stored address. */
    private val rawServer = Regex("""\$\{?(server|failedServer|failed|next\.server|step\.host|candidate|activeUrl|backupUrls|origin|it\.host)\b""")

    @Test
    fun `failover log lines never print a server`() {
        val offenders = files.flatMap { path ->
            val f = File(path)
            assertTrue("missing $path", f.exists())
            f.readLines().mapIndexedNotNull { i, line ->
                "$path:${i + 1}: ${line.trim()}".takeIf { logCall.containsMatchIn(line) && rawServer.containsMatchIn(line) }
            }
        }
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `describe names the position and masks the host`() {
        val pool = listOf("http://primary.example.top", "http://backup.example.top")
        val d = HostPool.describe(pool, pool[1])
        assertTrue(d.startsWith("server 2 of 2"))
        assertFalse(d.contains("backup.example.top"))
    }
}
