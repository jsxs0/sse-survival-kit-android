package io.github.jsxs0.ssekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The reconnect policy, specified as data. No sockets, no timers, no mocks:
 * the reducer is a pure function, so its whole contract fits in plain asserts.
 */
class ReduceTest {

    private val open = SseState.Open(attempt = 2, lastEventId = "41", sinceMillis = 0L)

    @Test
    fun `silent stall preserves the resume id`() {
        val next = reduce(open, SseSignal.WentDown(Disconnect.SilentStall, atMillis = 5_000L))

        val wait = assertIs<SseState.RetryWait>(next)
        assertEquals("41", wait.lastEventId)
        assertEquals(Disconnect.SilentStall, wait.cause)
    }

    @Test
    fun `a quick flap keeps escalating the backoff`() {
        // Open for only five seconds: same trouble spell, attempt goes 2 -> 3.
        val next = reduce(open, SseSignal.WentDown(Disconnect.ServerClosed, atMillis = 5_000L))

        assertEquals(3, assertIs<SseState.RetryWait>(next).attempt)
    }

    @Test
    fun `only stability earns a backoff reset`() {
        // Open past the stable threshold: this drop is fresh trouble, attempt 1.
        val next = reduce(open, SseSignal.WentDown(Disconnect.ServerClosed, atMillis = 90_000L))

        assertEquals(1, assertIs<SseState.RetryWait>(next).attempt)
    }

    @Test
    fun `redial resumes when an event id was ever seen`() {
        val wait = SseState.RetryWait(attempt = 3, Disconnect.SilentStall, lastEventId = "41")

        val next = reduce(wait, SseSignal.RetryElapsed)

        assertEquals(SseState.Resuming(attempt = 3, lastEventId = "41"), next)
    }

    @Test
    fun `redial connects fresh when no event id was ever seen`() {
        val wait = SseState.RetryWait(attempt = 1, Disconnect.TransportError(null), lastEventId = null)

        val next = reduce(wait, SseSignal.RetryElapsed)

        assertEquals(SseState.Connecting(attempt = 1), next)
    }

    @Test
    fun `network back skips the rest of the wait`() {
        val wait = SseState.RetryWait(attempt = 2, Disconnect.TransportError(null), lastEventId = "7")

        val next = reduce(wait, SseSignal.NetworkBack)

        assertIs<SseState.Resuming>(next)
    }

    @Test
    fun `network back is ignored while the stream is healthy`() {
        assertEquals(open, reduce(open, SseSignal.NetworkBack))
    }

    @Test
    fun `a drop echo while already waiting changes nothing`() {
        // Cancelling a stalled EventSource fires onFailure; the echo must not
        // escalate the attempt or restart the wait.
        val wait = SseState.RetryWait(attempt = 2, Disconnect.SilentStall, lastEventId = "41")

        val next = reduce(wait, SseSignal.WentDown(Disconnect.TransportError(null), atMillis = 6_000L))

        assertEquals(wait, next)
    }

    @Test
    fun `rejected credentials are terminal`() {
        val next = reduce(open, SseSignal.WentDown(Disconnect.AuthRejected(401), atMillis = 5_000L))

        assertIs<SseState.Failed>(next)
    }

    @Test
    fun `event ids are tracked only while open`() {
        val next = reduce(open, SseSignal.EventArrived(id = "42"))

        assertEquals("42", assertIs<SseState.Open>(next).lastEventId)
    }
}
