package pl.blizinski.tasksync.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import pl.blizinski.tasksync.OpStatus

@Dao
interface SyncedRecordsDao {

    @Query("SELECT * FROM synced_records WHERE listLocalId = :listLocalId AND isDeleted = 0")
    fun observeByList(listLocalId: String): Flow<List<SyncedRecordEntity>>

    @Query("SELECT * FROM synced_records WHERE localId = :localId")
    suspend fun getByLocalId(localId: String): SyncedRecordEntity?

    @Query("SELECT * FROM synced_records WHERE remoteId = :remoteId")
    suspend fun getByRemoteId(remoteId: String): SyncedRecordEntity?

    @Query("SELECT * FROM synced_records WHERE listLocalId = :listLocalId AND isDeleted = 0")
    suspend fun getAllForList(listLocalId: String): List<SyncedRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: SyncedRecordEntity)

    @Query("UPDATE synced_records SET remoteId = :remoteId WHERE localId = :localId")
    suspend fun updateRemoteId(localId: String, remoteId: String)

    @Query("""
        UPDATE synced_records
        SET contentJson = :contentJson, isCompleted = :isCompleted,
            lastSyncedAt = :lastSyncedAt, remoteUpdatedAt = :remoteUpdatedAt,
            lastSyncedContentJson = :lastSyncedContentJson
        WHERE localId = :localId
    """)
    suspend fun updateSyncedState(
        localId: String,
        contentJson: String,
        isCompleted: Boolean,
        lastSyncedAt: Long,
        remoteUpdatedAt: Long?,
        lastSyncedContentJson: String,
    )

    @Query("UPDATE synced_records SET listLocalId = :newListLocalId WHERE localId = :localId")
    suspend fun updateListId(localId: String, newListLocalId: String)

    @Query("UPDATE synced_records SET isDeleted = 1 WHERE localId = :localId")
    suspend fun softDelete(localId: String)

    @Query("DELETE FROM synced_records WHERE localId = :localId")
    suspend fun hardDelete(localId: String)
}

@Dao
interface SyncedListsDao {

    @Query("SELECT * FROM synced_lists WHERE isDeleted = 0 ORDER BY position ASC")
    fun observeAll(): Flow<List<SyncedListEntity>>

    @Query("SELECT * FROM synced_lists WHERE localId = :localId")
    suspend fun getByLocalId(localId: String): SyncedListEntity?

    @Query("SELECT * FROM synced_lists WHERE remoteId = :remoteId")
    suspend fun getByRemoteId(remoteId: String): SyncedListEntity?

    @Query("SELECT * FROM synced_lists WHERE isDeleted = 0 ORDER BY position ASC")
    suspend fun getAll(): List<SyncedListEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(list: SyncedListEntity)

    @Query("DELETE FROM synced_lists WHERE localId = :localId")
    suspend fun hardDelete(localId: String)
}

@Dao
interface PendingOpsDao {

    @Query("SELECT * FROM pending_ops ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<PendingOpEntity>>

    @Query("SELECT * FROM pending_ops ORDER BY createdAt ASC")
    suspend fun getAll(): List<PendingOpEntity>

    @Query("SELECT * FROM pending_ops WHERE entityLocalId = :entityLocalId ORDER BY createdAt ASC")
    suspend fun getByEntity(entityLocalId: String): List<PendingOpEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(op: PendingOpEntity)

    @Query("DELETE FROM pending_ops WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM pending_ops WHERE entityLocalId = :entityLocalId")
    suspend fun deleteByEntity(entityLocalId: String)

    @Query("UPDATE pending_ops SET listLocalId = :newListLocalId WHERE entityLocalId = :entityLocalId")
    suspend fun updateListIdForEntity(entityLocalId: String, newListLocalId: String)

    @Query("""
        UPDATE pending_ops
        SET attemptCount = attemptCount + 1, status = :status
        WHERE id = :id
    """)
    suspend fun recordAttempt(id: String, status: OpStatus)

    @Query("SELECT COUNT(*) FROM pending_ops WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_ops WHERE status = 'FAILED'")
    fun observeFailedCount(): Flow<Int>
}
