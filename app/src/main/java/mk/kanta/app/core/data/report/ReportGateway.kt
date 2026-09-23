package mk.kanta.app.core.data.report

import mk.kanta.app.core.data.model.ContainerCategory
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.model.ContainerStatus
import mk.kanta.app.core.data.remote.KantaResult
import mk.kanta.app.core.data.remote.dto.AddAllowanceDto
import mk.kanta.app.core.data.remote.dto.AddContainerResultDto
import mk.kanta.app.core.data.remote.dto.ContainerRequestResultDto
import mk.kanta.app.core.data.remote.dto.SubmitReportResultDto
import mk.kanta.app.core.location.LatLon
import java.io.File

/** A container the report can be filed against, with its distance from the user. */
data class ContainerCandidate(
    val id: String,
    val code: String,
    val kind: ContainerKind,
    val category: ContainerCategory,
    val status: ContainerStatus,
    val verified: Boolean,
    val position: LatLon,
    val distanceMetres: Double,
)

/** Everything needed to send one report, online now or from the queue later. */
data class ReportDraft(
    /** Also the storage file name, so a retried upload overwrites instead of duplicating. */
    val id: String,
    val containerId: String,
    /** full · damaged · destroyed · burning · missing · dumped_around (§6). */
    val kind: String,
    val photo: File,
    val note: String?,
    /** Where the PHONE is. The server measures this against the container (§4.3: 60 m). */
    val device: LatLon,
)

/** Adding a container (§4.6). The pin is where the container stands; device is where the phone is. */
data class ContainerDraft(
    val pin: LatLon,
    val device: LatLon,
    val kind: ContainerKind,
    val category: ContainerCategory,
    val photo: File,
    /** Photo storage id, shared with the report so one photo is uploaded once. */
    val photoId: String,
    val confirmDifferent: Boolean = false,
)

/**
 * The report screen's only door to the outside world.
 *
 * An interface on purpose: the ViewModel is unit tested against a fake, so the
 * flow's states — snapped, too far, merged, queued, sent — are pinned down without
 * a network, a camera or a GPS fix.
 */
interface ReportGateway {

    /** Where the phone is now, or null (no permission / no fix). */
    suspend fun currentLocation(): LatLon?

    /** §4.3: "auto-detected nearest container", within the 60 m a report allows. */
    suspend fun nearestContainer(at: LatLon): KantaResult<ContainerCandidate?>

    /** Everything the mini-map picker can offer around [at], nearest first. */
    suspend fun containersAround(at: LatLon, radiusMetres: Double): List<ContainerCandidate>

    /**
     * A container the user already chose on the map (§4.6 "One on the map is not
     * here" → tap it), with its distance from [from]. Null if it is not cached.
     */
    suspend fun containerById(id: String, from: LatLon): ContainerCandidate?

    /** Uploads the photo (idempotently) and files the report. */
    suspend fun submit(draft: ReportDraft): KantaResult<SubmitReportResultDto>

    /** §4.3 offline: keep it on the device and send it when a network returns. */
    suspend fun enqueue(draft: ReportDraft)

    /** §4.6: "You can add 2 containers" / "1 more". */
    suspend fun addAllowance(): KantaResult<AddAllowanceDto>

    suspend fun addContainer(draft: ContainerDraft): KantaResult<AddContainerResultDto>

    /** §4.6: when the allowance is used up, send for admin review instead. */
    suspend fun requestContainer(draft: ContainerDraft, note: String?): KantaResult<ContainerRequestResultDto>

    /**
     * §4.6: record that the 150 m around [at] was checked. [result] is one of
     * `all_present`, `added`, `reported_missing` — the values area_checks accepts.
     */
    suspend fun submitAreaCheck(at: LatLon, result: String): KantaResult<Unit>
}
