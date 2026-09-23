package mk.kanta.app.core.data.report

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import mk.kanta.app.core.data.local.PendingReportDao
import mk.kanta.app.core.data.remote.KantaResult
import mk.kanta.app.core.location.LatLon
import java.io.File

/**
 * Sends queued reports once there is a network (§4.3 "upload with WorkManager").
 *
 * Each report ends in exactly one of three places:
 *  - sent → row and local photo deleted;
 *  - refused for a reason that time will not fix (too far, daily limit, the
 *    container no longer exists) → marked failed, never retried, kept so the
 *    user can be told;
 *  - failed for a reason that might pass (offline, server busy) → retried with
 *    exponential backoff.
 */
@HiltWorker
class UploadReportsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val pendingDao: PendingReportDao,
    private val gateway: ReportGateway,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        var retryLater = false

        for (row in pendingDao.queued()) {
            val photo = File(row.localPhotoPath)
            if (!photo.exists()) {
                // Nothing left to send; keeping the row would retry forever.
                pendingDao.markFailed(row.id, "photo missing")
                continue
            }

            val draft = ReportDraft(
                id = row.id,
                containerId = row.containerId,
                kind = row.kind,
                photo = photo,
                note = row.note,
                device = LatLon(row.lat, row.lon),
            )

            when (val result = gateway.submit(draft)) {
                is KantaResult.Success -> {
                    pendingDao.delete(row.id)
                    photo.delete()
                }
                is KantaResult.Failure ->
                    if (result.error.isRetryable && row.attempts < MAX_ATTEMPTS) {
                        pendingDao.recordRetry(row.id, result.error.toString())
                        retryLater = true
                    } else {
                        pendingDao.markFailed(row.id, result.error.toString())
                    }
                KantaResult.Loading -> Unit
            }
        }

        return if (retryLater) Result.retry() else Result.success()
    }

    private companion object {
        /** About a day of exponential backoff from 30 s. Past that, stop and tell the user. */
        const val MAX_ATTEMPTS = 12
    }
}
