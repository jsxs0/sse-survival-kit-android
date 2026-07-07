package io.github.jsxs0.ssekit

/**
 * Every state a factory-floor SSE connection can be in.
 *
 * The client is a state machine, not a pile of callbacks: exactly one state at
 * any moment, and every transition passes through [reduce]. The UI renders the
 * state as-is (a connection badge is one `collect` away), and tests drive the
 * machine with plain values, no sockets required.
 */
sealed interface SseState {

    /** No connection requested yet. */
    data object Idle : SseState

    /** Dialing for the first time: there is no event id to resume from. */
    data class Connecting(val attempt: Int) : SseState

    /**
     * Dialing again after a drop, sending `Last-Event-ID` so the server can
     * replay what this device missed. Resuming is not Connecting: the UI can
     * keep showing (visibly stale) data instead of a spinner.
     */
    data class Resuming(val attempt: Int, val lastEventId: String) : SseState

    /**
     * Stream is live. [lastEventId] tracks the newest `id:` seen on the wire;
     * [attempt] remembers which dial attempt succeeded so that a stream that
     * dies seconds after opening keeps escalating its backoff.
     */
    data class Open(val attempt: Int, val lastEventId: String?, val sinceMillis: Long) : SseState

    /** Waiting out a backoff delay before dialing again. */
    data class RetryWait(val attempt: Int, val cause: Disconnect, val lastEventId: String?) : SseState

    /** Terminal: reconnecting cannot fix this (credentials rejected). */
    data class Failed(val cause: Disconnect) : SseState
}

/** Why the stream went down. The cause decides the next transition. */
sealed interface Disconnect {

    /** Socket error, HTTP error status, or read timeout. Retryable. */
    data class TransportError(val error: Throwable?, val httpCode: Int? = null) : Disconnect

    /** The server finished the stream on purpose. Still retryable: monitoring streams are shifts, not sessions. */
    data object ServerClosed : Disconnect

    /**
     * The watchdog stopped hearing heartbeats. The socket still looks open,
     * nothing raised, data simply stopped arriving: the half-open TCP trap.
     * Retryable, and the most dangerous cause on this list because no API
     * will ever report it. Only the heartbeat contract makes it visible.
     */
    data object SilentStall : Disconnect

    /** 401 or 403: retrying with the same credentials cannot help. Fatal. */
    data class AuthRejected(val httpCode: Int) : Disconnect
}

/** Everything that can happen to a connection, funneled from any thread. */
sealed interface SseSignal {

    /** The caller asked for the stream. */
    data object Start : SseSignal

    /** The response arrived with 200 and `text/event-stream`. */
    data class Opened(val atMillis: Long) : SseSignal

    /** An event arrived; [id] is the `id:` field if the server sent one. */
    data class EventArrived(val id: String?) : SseSignal

    /** The connection is gone, one way or another. */
    data class WentDown(val cause: Disconnect, val atMillis: Long) : SseSignal

    /** The backoff delay ran out. */
    data object RetryElapsed : SseSignal

    /** The platform reported connectivity is back: skip the rest of the wait. */
    data object NetworkBack : SseSignal
}

/**
 * The whole reconnect policy in one pure function.
 *
 * Timers, sockets, and randomness live outside; this is just data in, data
 * out, which is why the policy is unit-testable line by line.
 *
 * @param stableOpenMillis how long a stream must stay open before a drop is
 *   treated as fresh trouble (attempt 1) instead of the same trouble spell
 *   continuing to escalate. Flapping must not reset your backoff; only
 *   stability earns a reset.
 */
fun reduce(state: SseState, signal: SseSignal, stableOpenMillis: Long = 60_000L): SseState = when (signal) {

    SseSignal.Start ->
        if (state is SseState.Idle) SseState.Connecting(attempt = 1) else state

    is SseSignal.Opened ->
        if (state is SseState.Connecting || state is SseState.Resuming)
            SseState.Open(state.attempt, state.lastEventId, signal.atMillis)
        else state

    is SseSignal.EventArrived ->
        if (state is SseState.Open && signal.id != null) state.copy(lastEventId = signal.id) else state

    is SseSignal.WentDown -> when {
        // Already down (or done): cancelling a stalled source fires onFailure,
        // and that echo must not escalate the attempt or restart the timer.
        state is SseState.RetryWait || state is SseState.Failed -> state
        signal.cause is Disconnect.AuthRejected ->
            SseState.Failed(signal.cause)
        state is SseState.Open && signal.atMillis - state.sinceMillis >= stableOpenMillis ->
            SseState.RetryWait(attempt = 1, signal.cause, state.lastEventId)
        else ->
            SseState.RetryWait(state.attempt + 1, signal.cause, state.lastEventId)
    }

    SseSignal.RetryElapsed, SseSignal.NetworkBack ->
        if (state is SseState.RetryWait) state.redial() else state
}

/** RetryWait becomes Resuming when there is an id to resume from, Connecting when there is not. */
private fun SseState.RetryWait.redial(): SseState =
    lastEventId?.let { SseState.Resuming(attempt, it) } ?: SseState.Connecting(attempt)

/** The newest event id this connection has seen, whatever state it is in. */
val SseState.lastEventId: String?
    get() = when (this) {
        is SseState.Open -> lastEventId
        is SseState.Resuming -> lastEventId
        is SseState.RetryWait -> lastEventId
        else -> null
    }

/** The dial attempt the machine is on; zero when idle or failed. */
val SseState.attempt: Int
    get() = when (this) {
        is SseState.Connecting -> attempt
        is SseState.Resuming -> attempt
        is SseState.Open -> attempt
        is SseState.RetryWait -> attempt
        else -> 0
    }
