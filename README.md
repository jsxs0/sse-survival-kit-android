# SSE Survival Kit for Android

Reference implementation for the DroidKaigi 2026 session
**"Server-Sent Events on Android: Real-Time Streams That Survive the Factory Floor"**
by Jasveen Sandral.

Everything shown on a slide in that talk is an excerpt from this repository.
Nothing here is pseudocode.

## What is in the box

```
client/   Kotlin, plain JVM module (okhttp + coroutines, no Android dependency)
  SseState.kt           the connection state machine: one pure reducer, six states
  Backoff.kt            exponential backoff with FULL jitter, honors server retry hints
  HeartbeatWatchdog.kt  detects the silent stall (half-open TCP) no API will report
  SseClient.kt          okhttp-sse wrapper: callbackFlow, Last-Event-ID resume, fast-path reconnect
  FlowRecipes.kt        backpressure: conflate() for gauges, DROP_OLDEST buffer for logs
  DataAge.kt            honest UI: "last update 00:07 ago", stale must LOOK stale
  ReduceTest.kt         the reconnect policy, specified as plain asserts

server/   Ruby, a chaos server on the Rage framework
  One SSE endpoint with monotonic ids, heartbeat events, Last-Event-ID replay
  from a ring buffer, and chaos toggles to break things on purpose.
```

## The contract this code implements

The SSE spec (WHATWG HTML, `text/event-stream`) guarantees you a format and
a vocabulary: `data:`, `id:`, `event:`, `retry:`, comment lines. On Android,
everything else is yours:

| The wire gives you | The client must build |
|---|---|
| `id:` on every event | tracking it, and sending `Last-Event-ID` back on reconnect |
| `retry:` reconnection hints | a reconnect loop at all (okhttp-sse has none, and it never surfaces `retry:` to your listener) |
| heartbeat events | a watchdog that declares death after missed beats |
| a one-way firehose | backpressure semantics: latest-wins for gauges, bounded logs for history |
| nothing | jitter, so a fleet of devices does not stampede a recovering server |

## Run the chaos server

```bash
cd server
bundle install
bundle exec rage s
```

Then watch the raw wire (this is the whole SSE format, readable by eye):

```bash
curl -N http://localhost:3000/telemetry
```

Break things on purpose while a client is connected:

```bash
curl -X POST http://localhost:3000/chaos/silent     # stop all output, keep the socket open (watchdog demo)
curl -X POST http://localhost:3000/chaos/burst      # outpace the device (backpressure demo)
curl -X POST http://localhost:3000/chaos/kill       # sever every stream mid-shift (reconnect + resume demo)
curl -X POST http://localhost:3000/chaos/normal     # back to a healthy shift
curl       http://localhost:3000/chaos              # current mode
```

All telemetry is synthetic and generated on the fly. No real factory was
harmed, or even consulted, in the making of this data.

## Use the client

```kotlin
val client = SseClient(
    url = "http://10.0.2.2:3000/telemetry",
    heartbeatMillis = 5_000L,
    networkBackSignals = networkBackFlow, // see below
)

// State badge: render the machine's honesty.
lifecycleScope.launch {
    client.state.collect { badge.render(it) }
}

// Gauges: only the newest value is true.
lifecycleScope.launch {
    client.events().latestOnly().collect { gauge.render(it) }
}
```

Wiring `networkBackSignals` on Android:

```kotlin
val networkBackFlow: Flow<Unit> = callbackFlow {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(Unit) }
    }
    cm.registerDefaultNetworkCallback(callback)
    awaitClose { cm.unregisterNetworkCallback(callback) }
}
```

## Provenance

The server-side halves of these patterns come from four merged pull requests
hardening the SSE internals of the [Rage](https://github.com/rage-rb/rage)
framework: [#245](https://github.com/rage-rb/rage/pull/245),
[#248](https://github.com/rage-rb/rage/pull/248),
[#264](https://github.com/rage-rb/rage/pull/264),
[#267](https://github.com/rage-rb/rage/pull/267).
The chaos server runs on the very framework those fixes shipped in.

## License

MIT
