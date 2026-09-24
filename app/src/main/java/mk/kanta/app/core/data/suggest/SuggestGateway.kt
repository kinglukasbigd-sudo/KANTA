package mk.kanta.app.core.data.suggest

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import mk.kanta.app.core.data.remote.KantaRepository
import mk.kanta.app.core.data.remote.KantaResult
import mk.kanta.app.core.data.remote.dto.NearbySuggestionDto
import mk.kanta.app.core.data.remote.dto.SubmitSuggestionResultDto
import mk.kanta.app.core.data.report.ContainerCandidate
import mk.kanta.app.core.data.report.PhotoUploader
import mk.kanta.app.core.data.report.ReportGateway
import mk.kanta.app.core.data.report.settle
import mk.kanta.app.core.location.LatLon
import mk.kanta.app.core.location.LocationProvider
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** An open suggestion drawn on the pick map, so people see what's already asked for. */
data class SuggestionPin(val id: String, val position: LatLon)

/**
 * The Suggest flow's only door to the outside world (§4.4) — an interface so the
 * ViewModel is unit tested against a fake, like the report flow.
 */
interface SuggestGateway {
    suspend fun currentLocation(): LatLon?

    /** Containers around, from the cache, drawn under the crosshair. */
    suspend fun containersAround(at: LatLon, radiusMetres: Double): List<ContainerCandidate>

    suspend fun openSuggestionPins(): List<SuggestionPin>

    /** §4.4: the open suggestion a new one here would merge into (server's 50 m). */
    suspend fun openSuggestionNear(at: LatLon): KantaResult<NearbySuggestionDto?>

    suspend fun uploadPhoto(id: String, photo: File): KantaResult<String>

    suspend fun submit(at: LatLon, reason: String, note: String?, photoPath: String?): KantaResult<SubmitSuggestionResultDto>
}

@Singleton
class DefaultSuggestGateway @Inject constructor(
    private val repository: KantaRepository,
    private val reports: ReportGateway,
    private val location: LocationProvider,
    private val uploader: PhotoUploader,
) : SuggestGateway {

    override suspend fun currentLocation(): LatLon? = location.current()

    override suspend fun containersAround(at: LatLon, radiusMetres: Double) =
        reports.containersAround(at, radiusMetres)

    override suspend fun openSuggestionPins(): List<SuggestionPin> =
        when (val result = repository.suggestions(limit = 200).settle()) {
            is KantaResult.Success -> result.data
                .filter { it.state == "open" }
                .map { SuggestionPin(it.id, LatLon(it.lat, it.lon)) }
            else -> emptyList()
        }

    override suspend fun openSuggestionNear(at: LatLon) =
        repository.openSuggestionNear(at.lon, at.lat).settle()

    override suspend fun uploadPhoto(id: String, photo: File) =
        uploader.upload(PhotoUploader.SUGGESTIONS, id, photo)

    override suspend fun submit(at: LatLon, reason: String, note: String?, photoPath: String?) =
        repository.submitSuggestion(at.lon, at.lat, reason, note, photoPath).settle()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SuggestModule {
    @Binds
    abstract fun bindSuggestGateway(impl: DefaultSuggestGateway): SuggestGateway

    @Binds
    abstract fun bindSuggestionVotes(impl: SuggestionVoting): SuggestionVotes
}
