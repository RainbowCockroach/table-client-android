package com.rainbowcockroach.table.tableandroidclient.transfer

import com.rainbowcockroach.table.tableandroidclient.api.TableClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** DESIGN §3: 2 uploads / 2 downloads. */
private const val MAX_CONCURRENT_PER_DIRECTION = 2

/**
 * How many times a retryable failure is worth re-running before the queue calls it dead.
 *
 * WorkManager would otherwise back off and retry a hopeless transfer forever; the user's
 * Retry button starts a fresh count.
 */
const val MAX_ATTEMPTS = 8

private const val PROGRESS_WRITE_INTERVAL_MILLIS = 500L

/** What the worker reports back to WorkManager. */
enum class RunOutcome { DONE, RETRY, GAVE_UP }

/** One attempt at moving one file, in whichever direction the record names. */
fun interface TransferAttempt {
    fun run(client: TableClient, record: TransferRecord, report: TransferReport): TransferResult
}

/**
 * How a running attempt writes back into the queue.
 *
 * Called from the transfer thread, so every method is blocking — the protocol layer is
 * plain blocking Kotlin (DESIGN §1) and must not have to know about coroutines.
 */
interface TransferReport {
    fun bytes(done: Long)

    /**
     * The upload session this attempt will use, or null when it is lost (rules 3, 14).
     * Durable before the first byte goes out, so a resumed worker keeps the same session.
     */
    fun session(session: UploadSession?)
}

/**
 * Runs one queued record to a verdict and records what happened.
 *
 * Retry and backoff belong to the caller — every attempt here is a single pass that
 * resumes from whatever the server and the local temp file already hold.
 */
class TransferRunner(
    private val store: TransferStore,
    private val clientFor: suspend () -> TableClient?,
    private val attempt: TransferAttempt,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val clock: () -> Long = System::currentTimeMillis,
    concurrency: Int = MAX_CONCURRENT_PER_DIRECTION,
) {
    private val slots = TransferDirection.entries.associateWith { Semaphore(concurrency) }

    /** [runAttempt] is zero-based, as WorkManager counts it. */
    suspend fun run(id: String, runAttempt: Int = 0): RunOutcome {
        val queued = store.get(id) ?: return RunOutcome.DONE
        if (queued.state == TransferState.DONE) return RunOutcome.DONE

        // Building a client rejects a bad host URL and, per rule 13, a plain http:// one.
        val client = try {
            clientFor() ?: return failWith(id, "no server configured", runAttempt)
        } catch (rejected: Exception) {
            return failWith(id, rejected.message ?: rejected.toString(), runAttempt)
        }

        return slots.getValue(queued.direction).withPermit {
            val record = store.update(id) { it.copy(state = TransferState.RUNNING, failure = null) }
                ?: return@withPermit RunOutcome.DONE
            val report = StoreReport(record.id, record.size)
            val result = withContext(Dispatchers.IO) {
                // A worker that throws is retried by WorkManager forever; a verdict is not.
                runCatching { attempt.run(client, record, report) }
                    .getOrElse { permanent(it.message ?: it.toString()) }
            }
            finish(id, result, report.reported, runAttempt)
        }
    }

    private suspend fun failWith(id: String, message: String, runAttempt: Int): RunOutcome =
        finish(id, permanent(message), reported = 0L, runAttempt = runAttempt)

    private suspend fun finish(
        id: String,
        result: TransferResult,
        reported: Long,
        runAttempt: Int,
    ): RunOutcome = when (result) {
        is TransferResult.Done -> {
            store.update(id) {
                it.copy(
                    state = TransferState.DONE,
                    bytesDone = it.size,
                    failure = null,
                    publishedName = result.published?.name,
                    publishedUri = result.published?.uri,
                )
            }
            RunOutcome.DONE
        }

        is TransferResult.Failed -> {
            val failure = result.failure.takeUnless { it.retryable && runAttempt + 1 >= maxAttempts }
                ?: TransferFailure(
                    "${result.failure.message} — gave up after $maxAttempts attempts",
                    retryable = false,
                )
            store.update(id) {
                it.copy(state = TransferState.FAILED, bytesDone = reported, failure = failure)
            }
            if (failure.retryable) RunOutcome.RETRY else RunOutcome.GAVE_UP
        }
    }

    private inner class StoreReport(private val id: String, private var size: Long) : TransferReport {

        var reported: Long = 0L
            private set

        private var lastWriteAt = 0L
        private var lastPhase: TransferState? = null

        override fun bytes(done: Long) {
            reported = done
            // Every byte is there but the transfer is not done: hashing, acking and — for an
            // upload — the server's own rehash are what is left.
            val phase = if (done >= size) TransferState.VERIFYING else TransferState.RUNNING
            val now = clock()
            if (phase == lastPhase && now - lastWriteAt < PROGRESS_WRITE_INTERVAL_MILLIS) return
            lastPhase = phase
            lastWriteAt = now
            write { it.copy(state = phase, bytesDone = done) }
        }

        override fun session(session: UploadSession?) {
            size = session?.size ?: size
            write {
                it.copy(
                    remoteId = session?.id,
                    sha256 = session?.sha256 ?: it.sha256,
                    size = session?.size ?: it.size,
                )
            }
        }

        private fun write(change: (TransferRecord) -> TransferRecord) =
            runBlocking { store.update(id, change) }
    }
}
