package io.github.jsxs0.ssekit

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/** One parsed `text/event-stream` event, as delivered by the server. */
data class ServerEvent(val id: String?, val type: String?, val data: String)

/** Raised into the collector when the machine reaches [SseState.Failed]. */
class SseAuthException(val httpCode: Int) :
    Exception("SSE stream rejected with HTTP $httpCode; reconnecting cannot help")

/**
 * An SSE consumer built to survive a production shift.
 *
 * okhttp-sse gives you parsing and callbacks. It does NOT give you
 * reconnection: `onFailure` is the end of the road, and the wire-level
 * `retry:` field is consumed by the parser without ever reaching your
 * listener. Everything above parsing, the state machine, the backoff, the
 * watchdog, the `Last-Event-ID` resume, is the client's responsibility.
 * This class is that responsibility, written down once.
 *
 * @param heartbeatMillis the heartbeat cadence the server promised
 * @param networkBackSignals emit here when the platform reports connectivity
 *   returned (a `ConnectivityManager.NetworkCallback.onAvailable` on Android);
 *   the client then skips the rest of its backoff wait and redials at once
 */
class SseClient(
    private val url: String,
    baseClient: OkHttpClient = OkHttpClient(),
    private val heartbeatMillis: Long = 15_000L,
    private val missedBeats: Int = 3,
    private val backoff: Backoff = Backoff(),
    private val stableOpenMillis: Long = 60_000L,
    private val networkBackSignals: Flow<Unit> = emptyFlow(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _state = MutableStateFlow<SseState>(SseState.Idle)

    /** Render this. A connection badge on the dashboard is one `collect` away. */
    val state: StateFlow<SseState> = _state.asStateFlow()

    // The read timeout is the stall backstop, so it must be derived from the
    // heartbeat contract. OkHttp's 10-second default kills a healthy, quiet
    // stream between heartbeats; zero trusts a dead one forever.
    private val client: OkHttpClient = baseClient.newBuilder()
        .readTimeout(heartbeatMillis * (missedBeats + 1), TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    // okhttp-sse never surfaces the spec's `retry:` field, so the server
    // sends its hint as a normal event instead (`event: retry-hint`).
    @Volatile
    private var serverRetryHintMillis: Long? = null

    /**
     * The stream. Collect it and the machine starts; cancel the collector and
     * everything (socket, timers, watchdog) is torn down.
     */
    fun events(): Flow<ServerEvent> = callbackFlow {
        val signals = Channel<SseSignal>(Channel.UNLIMITED)
        var source: EventSource? = null
        var retryTimer: Job? = null

        val watchdog = HeartbeatWatchdog(this, heartbeatMillis, missedBeats) {
            // No exception, no closed socket: just silence past the contract.
            signals.trySend(SseSignal.WentDown(Disconnect.SilentStall, clock()))
        }

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                watchdog.onSignal()
                signals.trySend(SseSignal.Opened(clock()))
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                watchdog.onSignal() // every event is proof of life
                signals.trySend(SseSignal.EventArrived(id))
                when (type) {
                    HEARTBEAT_TYPE -> Unit // liveness only, not data
                    RETRY_HINT_TYPE -> data.toLongOrNull()?.takeIf { it > 0 }?.let { serverRetryHintMillis = it }
                    // Past the flow's buffer, monitoring semantics say drop;
                    // pair with latestOnly() or boundedLog() downstream.
                    else -> trySend(ServerEvent(id, type, data))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                signals.trySend(SseSignal.WentDown(Disconnect.ServerClosed, clock()))
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val code = response?.code
                val cause = when (code) {
                    401, 403 -> Disconnect.AuthRejected(code)
                    else -> Disconnect.TransportError(t, code)
                }
                signals.trySend(SseSignal.WentDown(cause, clock()))
            }
        }

        fun dial(lastEventId: String?) {
            source = EventSources.createFactory(client)
                .newEventSource(request(lastEventId), listener)
        }

        // Single-consumer pump: all mutation happens here, in one coroutine.
        // OkHttp threads and timers only ever drop signals into the channel.
        // The pump also owns its teardown, so the last writer to `source` and
        // `retryTimer` is the coroutine that cleans them up.
        val pump = launch {
            try {
                for (signal in signals) {
                    val next = reduce(_state.value, signal, stableOpenMillis)
                    if (next == _state.value) continue
                    _state.value = next

                    when (next) {
                        is SseState.Connecting -> {
                            retryTimer?.cancel()
                            dial(lastEventId = null)
                        }

                        is SseState.Resuming -> {
                            retryTimer?.cancel()
                            dial(lastEventId = next.lastEventId)
                        }

                        is SseState.RetryWait -> {
                            watchdog.stop()
                            source?.cancel()
                            retryTimer?.cancel()
                            retryTimer = launch {
                                delay(backoff.delayFor(next.attempt, serverRetryHintMillis))
                                signals.trySend(SseSignal.RetryElapsed)
                            }
                        }

                        is SseState.Open -> Unit // data is already flowing

                        is SseState.Failed -> {
                            val cause = next.cause as Disconnect.AuthRejected
                            close(SseAuthException(cause.httpCode))
                        }

                        SseState.Idle -> Unit
                    }
                }
            } finally {
                watchdog.stop()
                retryTimer?.cancel()
                source?.cancel()
            }
        }

        launch {
            networkBackSignals.collect { signals.trySend(SseSignal.NetworkBack) }
        }

        signals.trySend(SseSignal.Start)

        awaitClose {
            pump.cancel()
            // Ready for the next collector: the machine parks in Idle.
            _state.value = SseState.Idle
        }
    }

    private fun request(lastEventId: String?): Request =
        Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .apply {
                if (lastEventId != null)
                    header("Last-Event-ID", lastEventId)
            }
            .build()

    private companion object {
        const val HEARTBEAT_TYPE = "heartbeat"
        const val RETRY_HINT_TYPE = "retry-hint"
    }
}
