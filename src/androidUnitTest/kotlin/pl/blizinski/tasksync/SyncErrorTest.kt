package pl.blizinski.tasksync

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncErrorTest {

    private fun error(occurredAt: Long) = SyncError(
        occurredAt = occurredAt,
        kind = SyncErrorKind.PULL_FAILED,
        message = "error-$occurredAt",
    )

    @Test
    fun cleanSyncClearsAccumulatedErrors() {
        val previous = listOf(error(1), error(2))
        assertEquals(emptyList(), accumulateRecentErrors(previous, emptyList(), max = 5))
    }

    @Test
    fun newErrorsArePrependedAndCappedAtMax() {
        val previous = listOf(error(1), error(2), error(3))
        val new = listOf(error(4), error(5))
        val result = accumulateRecentErrors(previous, new, max = 4)
        assertEquals(listOf(error(4), error(5), error(1), error(2)), result)
    }

    @Test
    fun emptyPreviousAndEmptyNewStaysEmpty() {
        assertEquals(emptyList(), accumulateRecentErrors<SyncError>(emptyList(), emptyList(), max = 5))
    }

    @Test
    fun newErrorsWithNoPreviousHistoryAreKeptUpToMax() {
        val new = listOf(error(1), error(2), error(3))
        assertEquals(listOf(error(1), error(2)), accumulateRecentErrors(emptyList(), new, max = 2))
    }
}
