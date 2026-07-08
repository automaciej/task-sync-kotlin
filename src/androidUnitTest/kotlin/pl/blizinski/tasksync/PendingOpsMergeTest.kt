package pl.blizinski.tasksync

import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PendingOpsMergeTest {

    private fun op(type: OpType, createdAt: Long = 0L) = PendingOp(
        id = "$type-$createdAt",
        type = type,
        entityLocalId = "E1",
        listLocalId = "L1",
        createdAt = createdAt,
        status = OpStatus.PENDING,
    )

    private val processor = PendingOpsProcessor(
        FakeLocalStore(), FakeNetworkSource(), serializer<FakeContent>(), FakeSyncErrorClassifier(),
    )

    @Test
    fun emptyListMergesToEmpty() {
        assertTrue(processor.merge(emptyList()).isEmpty())
    }

    @Test
    fun createThenDeleteCancelsOut() {
        val result = processor.merge(listOf(op(OpType.CREATE_RECORD, 1), op(OpType.DELETE_RECORD, 2)))
        assertTrue(result.isEmpty(), "CREATE + DELETE should cancel each other out")
    }

    @Test
    fun updateThenDeleteKeepsOnlyDelete() {
        val result = processor.merge(listOf(op(OpType.UPDATE_RECORD, 1), op(OpType.DELETE_RECORD, 2)))
        assertEquals(1, result.size)
        assertEquals(OpType.DELETE_RECORD, result.first().type)
    }

    @Test
    fun multipleUpdatesKeepsLastOnly() {
        val ops = listOf(op(OpType.UPDATE_RECORD, 1), op(OpType.UPDATE_RECORD, 2), op(OpType.UPDATE_RECORD, 3))
        val result = processor.merge(ops)
        assertEquals(1, result.size)
        assertEquals(OpType.UPDATE_RECORD, result.first().type)
        assertEquals(3L, result.first().createdAt, "Should keep the last UPDATE")
    }

    @Test
    fun createThenUpdateKeepsBoth() {
        val result = processor.merge(listOf(op(OpType.CREATE_RECORD, 1), op(OpType.UPDATE_RECORD, 2)))
        assertEquals(2, result.size)
        assertEquals(OpType.CREATE_RECORD, result[0].type)
        assertEquals(OpType.UPDATE_RECORD, result[1].type)
    }

    @Test
    fun createThenCompleteKeepsBoth() {
        val result = processor.merge(listOf(op(OpType.CREATE_RECORD, 1), op(OpType.COMPLETE_RECORD, 2)))
        assertEquals(2, result.size)
        assertEquals(OpType.CREATE_RECORD, result[0].type)
        assertEquals(OpType.COMPLETE_RECORD, result[1].type)
    }

    @Test
    fun singleOpPassesThrough() {
        listOf(OpType.CREATE_RECORD, OpType.UPDATE_RECORD, OpType.COMPLETE_RECORD, OpType.DELETE_RECORD)
            .forEach { type ->
                val result = processor.merge(listOf(op(type)))
                assertEquals(1, result.size)
                assertEquals(type, result.first().type)
            }
    }
}
