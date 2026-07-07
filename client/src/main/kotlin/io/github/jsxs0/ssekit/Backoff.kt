package io.github.jsxs0.ssekit

import kotlin.math.min
import kotlin.random.Random

/**
 * Exponential backoff with FULL jitter.
 *
 * Why jitter is not optional on a factory floor: when the server restarts,
 * every device on the shift loses its stream at the same moment. Without
 * jitter they all redial on the same schedule, in synchronized waves, a
 * thundering herd against a server that is trying to stand back up. Full
 * jitter spreads the herd uniformly across the whole backoff window.
 *
 * Scheme after the AWS Architecture Blog, "Exponential Backoff And Jitter"
 * (https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/).
 */
class Backoff(
    private val baseMillis: Long = 1_000L,
    private val capMillis: Long = 30_000L,
    private val floorMillis: Long = 250L,
    private val random: Random = Random.Default,
) {

    /**
     * @param attempt the upcoming dial attempt, starting at 1
     * @param serverRetryHintMillis the reconnection time the server asked for,
     *   if it sent one. The SSE spec lets a `retry:` field set the base
     *   reconnection time; honoring it is the client's job, nobody else's.
     * @return a delay drawn uniformly from `[floor, min(cap, base * 2^(attempt-1))]`
     */
    fun delayFor(attempt: Int, serverRetryHintMillis: Long? = null): Long {
        val base = serverRetryHintMillis ?: baseMillis
        val shift = min(attempt - 1, MAX_SHIFT).coerceAtLeast(0)
        val ceiling = min(capMillis, base shl shift)
        return random.nextLong(ceiling + 1).coerceAtLeast(floorMillis)
    }

    private companion object {
        // 2^16 seconds is already past any realistic cap; bounding the shift
        // keeps the left-shift from overflowing on absurd attempt numbers.
        const val MAX_SHIFT = 16
    }
}
