package mk.kanta.app.core.data.report

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import mk.kanta.app.core.data.di.IoDispatcher
import mk.kanta.app.core.data.local.ContainerDao
import mk.kanta.app.core.data.local.PendingReportDao
import mk.kanta.app.core.data.local.PendingReportEntity
import mk.kanta.app.core.data.model.ContainerCategory
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.model.ContainerStatus
import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.data.remote.KantaRepository
import mk.kanta.app.core.data.remote.KantaResult
import mk.kanta.app.core.data.remote.dto.AddAllowanceDto
import mk.kanta.app.core.data.remote.dto.AddContainerResultDto
import mk.kanta.app.core.data.remote.dto.ContainerRequestResultDto
import mk.kanta.app.core.data.remote.dto.SubmitReportResultDto
import mk.kanta.app.core.data.remote.toKantaError
import mk.kanta.app.core.location.GeoMath
import mk.kanta.app.core.location.LatLon
import mk.kanta.app.core.location.LocationProvider
import mk.kanta.app.core.network.SupabaseClientHolder
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultReportGateway @Inject constructor(
    private val repository: KantaRepository,
    private val supabase: SupabaseClientHolder,
    private val location: LocationProvider,
    private val containerDao: ContainerDao,
    private val pendingDao: PendingReportDao,
    private val workManager: WorkManager,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ReportGateway {

    override suspend fun currentLocation(): LatLon? = location.current()

    override suspend fun nearestContainer(at: LatLon): KantaResult<ContainerCandidate?> =
        repository.nearestContainerTo(at.lon, at.lat, maxMetres = 60).settle().map { dto ->
            dto?.let {
                ContainerCandidate(
                    id = it.id,
                    code = it.code,
                    kind = it.kind.toKind(),
                    category = it.category.toCategory(),
                    status = it.status.toStatus(),
                    verified = it.verified,
                    position = LatLon(it.lat, it.lon),
                    distanceMetres = it.distanceM,
                )
            }
        }

    override suspend fun containersAround(at: LatLon, radiusMetres: Double): List<ContainerCandidate> {
        // From the Room cache, so the picker works with a weak signal — the user is
        // standing next to the container, often not somewhere with good reception.
        val (southWest, northEast) = GeoMath.boundingBox(at, radiusMetres)
        return containerDao.observeInBbox(southWest.lon, southWest.lat, northEast.lon, northEast.lat)
            .first()
            .map {
                val position = LatLon(it.lat, it.lon)
                ContainerCandidate(
                    id = it.id,
                    code = it.code,
                    kind = it.kind.toKind(),
                    category = it.category.toCategory(),
                    status = it.status.toStatus(),
                    verified = it.verified,
                    position = position,
                    distanceMetres = GeoMath.distanceMetres(at, position),
                )
            }
            .filter { it.distanceMetres <= radiusMetres }
            .sortedBy { it.distanceMetres }
    }

    override suspend fun submit(draft: ReportDraft): KantaResult<SubmitReportResultDto> {
        val path = when (val upload = uploadPhoto(draft.id, draft.photo)) {
            is KantaResult.Success -> upload.data
            is KantaResult.Failure -> return upload
            KantaResult.Loading -> return KantaResult.Failure(KantaError.Unknown("upload loading"))
        }
        return repository.submitReport(
            containerId = draft.containerId,
            kind = draft.kind,
            photoPath = path,
            note = draft.note,
            lon = draft.device.lon,
            lat = draft.device.lat,
        ).settle()
    }

    override suspend fun enqueue(draft: ReportDraft) {
        pendingDao.upsert(
            PendingReportEntity(
                id = draft.id,
                containerId = draft.containerId,
                kind = draft.kind,
                localPhotoPath = draft.photo.path,
                note = draft.note,
                lon = draft.device.lon,
                lat = draft.device.lat,
            ),
        )
        scheduleUpload(workManager)
    }

    override suspend fun addAllowance(): KantaResult<AddAllowanceDto> =
        repository.myAddAllowance().settle()

    override suspend fun addContainer(draft: ContainerDraft): KantaResult<AddContainerResultDto> {
        val path = when (val upload = uploadPhoto(draft.photoId, draft.photo)) {
            is KantaResult.Success -> upload.data
            is KantaResult.Failure -> return upload
            KantaResult.Loading -> return KantaResult.Failure(KantaError.Unknown("upload loading"))
        }
        return repository.addContainer(
            lon = draft.pin.lon,
            lat = draft.pin.lat,
            kind = draft.kind,
            category = draft.category,
            photoPath = path,
            deviceLon = draft.device.lon,
            deviceLat = draft.device.lat,
            confirmDifferent = draft.confirmDifferent,
        ).settle()
    }

    override suspend fun requestContainer(
        draft: ContainerDraft,
        note: String?,
    ): KantaResult<ContainerRequestResultDto> {
        val path = when (val upload = uploadPhoto(draft.photoId, draft.photo)) {
            is KantaResult.Success -> upload.data
            is KantaResult.Failure -> return upload
            KantaResult.Loading -> return KantaResult.Failure(KantaError.Unknown("upload loading"))
        }
        return repository.submitContainerRequest(
            lon = draft.pin.lon,
            lat = draft.pin.lat,
            kind = draft.kind,
            category = draft.category,
            photoPath = path,
            note = note,
        ).settle()
    }

    // -----------------------------------------------------------------------------------------

    /**
     * Uploads to `reports/{uid}/{id}.jpg` (§6 storage paths) with upsert, so the
     * same photo sent twice — a retry, or a report after an add — lands on the same
     * object. One photo, one file, however many calls use it.
     */
    private suspend fun uploadPhoto(id: String, file: File): KantaResult<String> {
        if (!supabase.isConfigured) return KantaResult.Failure(KantaError.BackendNotConfigured)
        return withContext(io) {
            try {
                val uid = supabase.client.auth.currentUserOrNull()?.id
                    ?: return@withContext KantaResult.Failure(KantaError.NotSignedIn)
                val path = "reports/$uid/$id.jpg"
                supabase.client.storage.from("photos").upload(path, file.readBytes()) {
                    upsert = true
                    contentType = ContentType.Image.JPEG
                }
                KantaResult.Success(path)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                KantaResult.Failure(t.toKantaError())
            }
        }
    }

    companion object {
        const val UPLOAD_WORK = "kanta-report-upload"

        /**
         * One unique chain, run when there is a network. APPEND_OR_REPLACE so a
         * report queued while the worker is mid-run gets a follow-up pass rather
         * than being skipped by the run already in flight.
         */
        fun scheduleUpload(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<UploadReportsWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork(UPLOAD_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

/** First non-Loading value: a repository Flow collapsed into one answer. */
internal suspend fun <T> Flow<KantaResult<T>>.settle(): KantaResult<T> =
    first { it !is KantaResult.Loading }

private fun String.toKind() = if (this == "small") ContainerKind.SMALL else ContainerKind.BIG

private fun String.toCategory() = when (this) {
    "glass" -> ContainerCategory.GLASS
    "paper" -> ContainerCategory.PAPER
    "plastic" -> ContainerCategory.PLASTIC
    "mixed_recycling" -> ContainerCategory.MIXED_RECYCLING
    else -> ContainerCategory.GENERAL
}

private fun String.toStatus() = when (this) {
    "full" -> ContainerStatus.FULL
    "broken" -> ContainerStatus.BROKEN
    "destroyed" -> ContainerStatus.DESTROYED
    "missing" -> ContainerStatus.MISSING
    else -> ContainerStatus.OK
}
