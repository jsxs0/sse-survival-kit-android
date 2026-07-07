# SSE Survival Kit for Android

Reference code for the DroidKaigi 2026 session
**"Server-Sent Events on Android: Real-Time Streams That Survive the Factory Floor"**
by Jasveen Sandral.

Every code slide in that talk is an excerpt from this repository.

## Contents

```
client/   Kotlin, plain JVM module (OkHttp + coroutines, no Android dependency)
  SseState.kt           the connection state machine: six states, one pure reducer
  Backoff.kt            exponential backoff with full jitter, honors server retry hints
  HeartbeatWatchdog.kt  detects half-open TCP connections that no API reports
  SseClient.kt          okhttp-sse wrapper: callbackFlow, Last-Event-ID resume, fast reconnect
  FlowRecipes.kt        backpressure: conflate() for gauges, DROP_OLDEST buffer for logs
  DataAge.kt            shows the age of the data on screen ("last update 00:07 ago")
  ReduceTest.kt         the reconnect policy, tested with plain asserts

server/   Ruby, a test server on the Rage framework
  One SSE endpoint with monotonic ids, heartbeat events, Last-Event-ID replay
  from a ring buffer, and switches to cause failures on purpose.
```

## What SSE gives you, and what you build

The SSE spec (WHATWG HTML, `text/event-stream`) guarantees a format:
`data:`, `id:`, `event:`, `retry:`, and comment lines. On Android, the rest
is your code:

| The wire gives you | Your client must build |
|---|---|
| `id:` on every event | tracking it, and sending `Last-Event-ID` on reconnect |
| `retry:` hints | a reconnect loop (okhttp-sse has none, and it never passes `retry:` to your listener) |
| heartbeat events | a watchdog that reports the stream dead after missed beats |
| a one-way stream | backpressure rules: latest value for gauges, bounded buffers for logs |
| nothing | a random backoff delay, so many devices do not reconnect at the same time |

## Run the server

```bash
cd server
bundle install
bundle exec rage s
```

Watch the raw wire:

```bash
curl -N http://localhost:3000/telemetry
```

Cause failures on purpose while a client is connected:

```bash
curl -X POST http://localhost:3000/chaos/silent     # stop all output, keep the socket open (watchdog test)
curl -X POST http://localhost:3000/chaos/burst      # send faster than the device can render (backpressure test)
curl -X POST http://localhost:3000/chaos/kill       # close every stream at once (reconnect + resume test)
curl -X POST http://localhost:3000/chaos/normal     # back to normal
curl       http://localhost:3000/chaos              # current mode
```

All telemetry values are generated. No real factory data is used.

## Use the client

```kotlin
val client = SseClient(
    url = "http://10.0.2.2:3000/telemetry",
    heartbeatMillis = 5_000L,
    networkBackSignals = networkBackFlow, // see below
)

// Connection badge
lifecycleScope.launch {
    client.state.collect { badge.render(it) }
}

// Gauges: only the newest value matters
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

## Background

The server-side halves of these patterns come from four merged pull requests
to the SSE layer of the [Rage](https://github.com/rage-rb/rage) framework:
[#245](https://github.com/rage-rb/rage/pull/245),
[#248](https://github.com/rage-rb/rage/pull/248),
[#264](https://github.com/rage-rb/rage/pull/264),
[#267](https://github.com/rage-rb/rage/pull/267).
The test server in this repository runs on the framework those changes shipped in.

## License

MIT
