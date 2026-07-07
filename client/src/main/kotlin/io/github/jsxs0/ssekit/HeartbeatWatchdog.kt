package io.github.jsxs0.ssekit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Detects the silent stall.
 *
 * A server that crashes, loses power, or falls off the network sends no FIN
 * packet. The socket on the phone still looks open; reads simply never
 * return. Nothing errors, nothing calls back. To the app, a machine that no
 * longer exists is "connected" and the conveyor data on screen is quietly
 * minutes old.
 *
 * The contract that fixes it has two halves:
 *  - the server promises a heartbeat event at least every [heartbeatMillis]
 *  - this watchdog declares the stream dead after [missedBeats] silent periods
 *
 * Feed [onSignal] on every sign of life. If the timer ever fires, the stream
 * is gone even though no exception said so.
 */
class HeartbeatWatchdog(
    private val scope: CoroutineScope,
    private val heartbeatMillis: Long,
    private val missedBeats: Int = 3,
    private val onSilentStall: () -> Unit,
) {

    private var patrol: Job? = null

    // onSignal arrives on OkHttp reader threads while stop() runs on the
    // client's dispatcher; synchronize so a stale patrol can never survive.

    /** Any life on the wire resets the countdown: events, heartbeats, the open itself. */
    @Synchronized
    fun onSignal() {
        patrol?.cancel()
        patrol = scope.launch {
            delay(heartbeatMillis * missedBeats)
            onSilentStall()
        }
    }

    @Synchronized
    fun stop() {
        patrol?.cancel()
        patrol = null
    }
}
