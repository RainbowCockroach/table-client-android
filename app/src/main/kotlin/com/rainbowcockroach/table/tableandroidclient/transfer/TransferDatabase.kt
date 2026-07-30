package com.rainbowcockroach.table.tableandroidclient.transfer

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The queue table of DESIGN §3.
 *
 * Enums are stored by name rather than through a converter: the record already needs a
 * mapping function, and a name in the file survives reordering the enum.
 */
@Entity(tableName = "transfers")
internal data class TransferEntity(
    @PrimaryKey val id: String,
    val direction: String,
    val name: String,
    val size: Long,
    val state: String,
    val remoteId: String?,
    val sha256: String?,
    val sourceUri: String?,
    val bytesDone: Long,
    val failureMessage: String?,
    val failureRetryable: Boolean,
    val publishedName: String?,
    val publishedUri: String?,
    val createdAt: Long,
)

@Dao
internal interface TransferDao {

    @Query("SELECT * FROM transfers ORDER BY createdAt, id")
    fun observeAll(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers ORDER BY createdAt, id")
    suspend fun all(): List<TransferEntity>

    @Query("SELECT * FROM transfers WHERE id = :id")
    suspend fun get(id: String): TransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(transfer: TransferEntity)

    @Query("DELETE FROM transfers WHERE id = :id")
    suspend fun delete(id: String)
}

/** Added with the completion notification, which opens the file the download landed in. */
private val ADD_PUBLISHED_URI = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE transfers ADD COLUMN publishedUri TEXT")
    }
}

@Database(entities = [TransferEntity::class], version = 2, exportSchema = false)
internal abstract class TransferDatabase : RoomDatabase() {

    abstract fun transfers(): TransferDao

    companion object {
        // Migrated rather than rebuilt: an upgrade must not drop transfers still in flight.
        fun open(context: Context): TransferDatabase =
            Room.databaseBuilder(context, TransferDatabase::class.java, "transfers.db")
                .addMigrations(ADD_PUBLISHED_URI)
                .build()
    }
}

/** The durable [TransferStore]: the queue as it survives process death (rule 14). */
class RoomTransferStore internal constructor(private val database: TransferDatabase) : TransferStore {

    constructor(context: Context) : this(TransferDatabase.open(context))

    private val dao = database.transfers()

    override val transfers: Flow<List<TransferRecord>> =
        dao.observeAll().map { rows -> rows.map { it.toRecord() } }

    override suspend fun all(): List<TransferRecord> = dao.all().map { it.toRecord() }

    override suspend fun get(id: String): TransferRecord? = dao.get(id)?.toRecord()

    override suspend fun put(record: TransferRecord) = dao.put(record.toEntity())

    override suspend fun update(
        id: String,
        change: (TransferRecord) -> TransferRecord,
    ): TransferRecord? = database.withTransaction {
        val current = dao.get(id)?.toRecord() ?: return@withTransaction null
        change(current).also { dao.put(it.toEntity()) }
    }

    override suspend fun delete(id: String) = dao.delete(id)
}

private fun TransferEntity.toRecord() = TransferRecord(
    id = id,
    direction = TransferDirection.valueOf(direction),
    name = name,
    size = size,
    state = TransferState.valueOf(state),
    remoteId = remoteId,
    sha256 = sha256,
    sourceUri = sourceUri,
    bytesDone = bytesDone,
    failure = failureMessage?.let { TransferFailure(it, failureRetryable) },
    publishedName = publishedName,
    publishedUri = publishedUri,
    createdAt = createdAt,
)

private fun TransferRecord.toEntity() = TransferEntity(
    id = id,
    direction = direction.name,
    name = name,
    size = size,
    state = state.name,
    remoteId = remoteId,
    sha256 = sha256,
    sourceUri = sourceUri,
    bytesDone = bytesDone,
    failureMessage = failure?.message,
    failureRetryable = failure?.retryable ?: false,
    publishedName = publishedName,
    publishedUri = publishedUri,
    createdAt = createdAt,
)
