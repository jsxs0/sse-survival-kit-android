package io.github.jsxs0.ssekit

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow

/**
 * Honest UI: on a factory dashboard, a frozen chart looks exactly like a
 * stopped machine. When the stream drops, the operator must SEE staleness,
 * not admire a confident screen of old numbers. Render the age next to the
 * data ("last update 00:07 ago") and let the state badge tell the rest.
 */
class DataAgeTracker(private val clock: () -> Long = System::currentTimeMillis) {

    private val lastArrivalMillis = MutableStateFlow<Long?>(null)

    /** Call on every update that reaches the screen. */
    fun touch() {
        lastArrivalMillis.value = clock()
    }

    /** Age of the data on screen, refreshed every [tickMillis]; null until the first update. */
    fun ages(tickMillis: Long = 1_000L): Flow<Long?> =
        combine(lastArrivalMillis, ticker(tickMillis)) { arrival, now ->
            arrival?.let { now - it }
        }

    private fun ticker(intervalMillis: Long): Flow<Long> = flow {
        while (true) {
            emit(clock())
            delay(intervalMillis)
        }
    }
}

/** 427 seconds becomes "07:07". */
fun formatAge(ageMillis: Long): String {
    val totalSeconds = ageMillis / 1_000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
