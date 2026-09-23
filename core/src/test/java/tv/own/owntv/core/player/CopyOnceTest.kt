package tv.own.owntv.core.player

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CopyOnceTest {

    @Test fun copiesOnceEvenWhenManyReadersArriveTogether() = runBlocking {
        val copies = AtomicInteger()
        var flag = false
        val gate = CopyOnce(isDone = { flag }, markDone = { flag = true }, copy = { copies.incrementAndGet() })
        (1..20).map { async { gate.ensure() } }.awaitAll()
        gate.ensure()
        assertEquals(1, copies.get())
        assertEquals(true, flag)
    }

    @Test fun anInstallThatAlreadyMovedNeverCopiesAgain() = runBlocking {
        val copies = AtomicInteger()
        CopyOnce(isDone = { true }, markDone = { error("must not mark again") }, copy = { copies.incrementAndGet() }).ensure()
        assertEquals(0, copies.get())
    }

    @Test fun aFailedCopyIsRetriedOnTheNextStart() = runBlocking {
        var flag = false
        val first = CopyOnce(isDone = { flag }, markDone = { flag = true }, copy = { error("crash mid-copy") })
        runCatching { first.ensure() }
        assertEquals(false, flag)
        val copies = AtomicInteger()
        CopyOnce(isDone = { flag }, markDone = { flag = true }, copy = { copies.incrementAndGet() }).ensure()
        assertEquals(1, copies.get())
    }
}
