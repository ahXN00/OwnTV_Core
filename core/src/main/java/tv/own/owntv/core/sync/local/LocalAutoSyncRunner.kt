package tv.own.owntv.core.sync.local

import android.util.Log
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** What an automatic-sync failure means for the row and the agreement. */
fun autoSyncOutcomeOf(error: Throwable?): AutoSyncOutcome = when {
    error == null -> AutoSyncOutcome.SYNCED
    error is AutoSyncHttpException -> when (AutoSyncRefusal.of(error.code, error.reason)) {
        AutoSyncRefusal.BUSY -> AutoSyncOutcome.SKIPPED
        AutoSyncRefusal.NO_AGREEMENT -> AutoSyncOutcome.TURNED_OFF
        AutoSyncRefusal.UNPAIRED -> AutoSyncOutcome.UNPAIRED
        else -> AutoSyncOutcome.FAILED
    }
    error is IOException -> AutoSyncOutcome.UNREACHABLE
    else -> AutoSyncOutcome.FAILED
}

/**
 * Automatic passes, with nobody watching: hello → the other device's export → merged here → this
 * device's export → merged there. It has its own [events] and never touches the manual sync's
 * progress or opens anything on either screen.
 *
 * Runs only for agreements that are due ([AutoSyncAgreement.isDue]). A device busy with a pass answers
 * "busy" and this one is skipped, not counted. A failure moves only the last try and the failure count;
 * "no agreement" there ends the agreement here, and "unpaired" removes the pairing. A turn-off still to
 * be told to the other side is delivered at the first contact.
 */
class LocalAutoSyncRunner(
    private val store: LocalAutoSyncStore,
    private val paired: PairedDeviceStore,
    private val client: LocalAutoSyncClient,
    private val transfer: AutoSyncTransfer,
    private val locks: AutoSyncLocks,
    private val discovery: LocalSyncDiscovery,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val running = Mutex()

    private val _events = MutableStateFlow<Map<String, AutoSyncEvent>>(emptyMap())

    /** The last pass's result per device, for the rows' status lines. */
    val events: StateFlow<Map<String, AutoSyncEvent>> = _events.asStateFlow()

    /** One round: notices first, then every due agreement. A round already running makes this a no-op. */
    suspend fun runDue(reason: String) {
        if (!running.tryLock()) return
        try {
            deliverNotices()
            val now = clock()
            val due = store.current().values.filter { it.isDue(now) }
            if (due.isNotEmpty()) Log.i(TAG, "auto-sync ($reason): ${due.size} due")
            due.forEach { syncOne(it) }
        } finally {
            running.unlock()
        }
    }

    private suspend fun syncOne(agreement: AutoSyncAgreement) {
        val device = paired.current().firstOrNull { it.id == agreement.deviceId } ?: return
        // Its profile here was deleted: nothing left to sync, and the other side is told.
        if (!transfer.profileExists(agreement)) return store.remove(device.id, tellThem = true)
        if (!locks.tryLock(device.id)) return report(device.id, AutoSyncOutcome.SKIPPED)
        var received = 0
        val error = try {
            val selfId = paired.selfId()
            val at = reach(device, selfId) ?: throw IOException("not found")
            val incoming = transfer.incomingFile()
            try {
                client.export(at.address, at.port, selfId, device.secret, incoming)
                received = transfer.importFrom(agreement, incoming, device.secret)
            } finally {
                incoming.delete()
            }
            val outgoing = transfer.exportFor(agreement, device.secret)
            try {
                client.import(at.address, at.port, selfId, device.secret, outgoing)
            } finally {
                outgoing.parentFile?.deleteRecursively()
            }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        } finally {
            locks.unlock(device.id)
        }
        val outcome = autoSyncOutcomeOf(error)
        when (outcome) {
            AutoSyncOutcome.SYNCED -> store.recordAttempt(device.id, clock(), success = true)
            AutoSyncOutcome.SKIPPED -> Unit
            AutoSyncOutcome.TURNED_OFF -> store.turnedOffByThem(device.id, clock())
            AutoSyncOutcome.UNPAIRED -> {
                store.forget(device.id)
                paired.remove(device.id)
            }
            AutoSyncOutcome.UNREACHABLE, AutoSyncOutcome.FAILED -> store.recordAttempt(device.id, clock(), success = false)
        }
        if (error != null && outcome != AutoSyncOutcome.SKIPPED) Log.w(TAG, "auto-sync with a paired device: $outcome", error)
        report(device.id, outcome, received)
    }

    private data class Reached(val address: String, val port: Int)

    /**
     * Where [device] answers now: its last address first (a short hello, and the id must be its own),
     * else a few seconds of looking for its announcement by id. Each answer refreshes the address.
     */
    private suspend fun reach(device: PairedDevice, selfId: String): Reached? {
        hello(device, device.address, device.autoPort, selfId)?.let { return it }
        val found = withTimeoutOrNull(FIND_TIMEOUT_MS) {
            discovery.discover().firstOrNull { it.deviceId == device.id }
        } ?: return null
        return hello(device, found.address, found.port, selfId)?.also { paired.updateAddress(device.id, it.address, it.port) }
    }

    private suspend fun hello(device: PairedDevice, address: String, port: Int, selfId: String): Reached? = try {
        val hello = client.hello(address, port, selfId, device.secret)
        if (hello.deviceId != device.id) null
        else {
            store.saveTheirSections(device.id, hello.sections)
            Reached(address, port)
        }
    } catch (e: IOException) {
        null
    }

    /** Tells each device this one turned off since the last contact; dropped once it is heard (or it no longer knows us). */
    private suspend fun deliverNotices() {
        val selfId = paired.selfId()
        store.pendingNotices().forEach { id ->
            val device = paired.current().firstOrNull { it.id == id } ?: return@forEach store.noticeDelivered(id)
            val delivered = try {
                client.notice(device.address, device.autoPort, selfId, device.secret)
                true
            } catch (e: AutoSyncHttpException) {
                // It refused for who we are, not for being away: there is nobody left to tell.
                e.code == 401 || e.code == 403
            } catch (e: IOException) {
                false
            }
            if (delivered) store.noticeDelivered(id)
        }
    }

    private fun report(deviceId: String, outcome: AutoSyncOutcome, received: Int = 0) {
        _events.update { it + (deviceId to AutoSyncEvent(deviceId, outcome, clock(), received)) }
    }

    private companion object {
        const val TAG = "LocalAutoSync"
        const val FIND_TIMEOUT_MS = 5_000L
    }
}
