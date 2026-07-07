package io.github.jsxs0.ssekit

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.conflate

/**
 * Backpressure recipes.
 *
 * SSE has no application-level flow control: the server pushes, TCP buffers,
 * and when a mid-range handheld renders slower than a production line emits
 * telemetry, something has to give. Decide what gives ON PURPOSE, per stream
 * semantics, instead of letting some buffer decide for you.
 */

/**
 * Gauges, meters, positions: only the newest value is true. If the collector
 * is busy rendering, skip straight to the latest reading and drop the rest.
 */
fun <T> Flow<T>.latestOnly(): Flow<T> = conflate()

/**
 * Event and alarm logs: history matters, so keep a bounded window and shed
 * the OLDEST entries first when the device cannot keep up. The bound is the
 * point: an unbounded queue on a shift-long stream is a slow OutOfMemoryError.
 */
fun <T> Flow<T>.boundedLog(capacity: Int = 128): Flow<T> =
    buffer(capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)
