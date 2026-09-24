package mk.kanta.app.core.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mk.kanta.app.core.data.model.ContainerCategory
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.model.ContainerStatus

/**
 * Wire shapes for the Supabase RPCs in supabase/migrations.
 *
 * One DTO per RPC return type, named after the function. They stay deliberately
 * dumb — snake_case fields, primitives only — and convert to domain types through
 * the `to…()` helpers, so a column rename in SQL cannot silently reshape the app.
 */

// -------------------------------------------------------------------------------------------
// Shared converters
// -------------------------------------------------------------------------------------------

internal fun String.toContainerKind(): ContainerKind = when (this) {
    "big" -> ContainerKind.BIG
    "small" -> ContainerKind.SMALL
    else -> ContainerKind.BIG
}

internal fun String.toContainerCategory(): ContainerCategory = when (this) {
    "general" -> ContainerCategory.GENERAL
    "glass" -> ContainerCategory.GLASS
    "paper" -> ContainerCategory.PAPER
    "plastic" -> ContainerCategory.PLASTIC
    "mixed_recycling" -> ContainerCategory.MIXED_RECYCLING
    else -> ContainerCategory.GENERAL
}

internal fun String.toContainerStatus(): ContainerStatus = when (this) {
    "ok" -> ContainerStatus.OK
    "full" -> ContainerStatus.FULL
    "broken" -> ContainerStatus.BROKEN
    "destroyed" -> ContainerStatus.DESTROYED
    "missing" -> ContainerStatus.MISSING
    else -> ContainerStatus.OK
}

// -------------------------------------------------------------------------------------------
// Map reads
// -------------------------------------------------------------------------------------------

/** `containers_in_bbox` — the lightweight row the map draws (§3.4, §6). */
@Serializable
data class ContainerMarkerDto(
    val id: String,
    val code: String,
    val kind: String,
    val category: String,
    val status: String,
    val verified: Boolean,
    val lon: Double,
    val lat: Double,
)

/** `nearest_containers` / `nearest_container_to` (§5.2, §4.3). */
@Serializable
data class NearbyContainerDto(
    val id: String,
    val code: String,
    val kind: String,
    val category: String,
    val status: String,
    val verified: Boolean,
    val lon: Double,
    val lat: Double,
    @SerialName("distance_m") val distanceM: Double,
)

/** `container_detail` (§4.1). */
@Serializable
data class ContainerDetailDto(
    val id: String,
    val code: String,
    val kind: String,
    val category: String,
    val status: String,
    @SerialName("status_since") val statusSince: String,
    val verified: Boolean,
    @SerialName("municipality_id") val municipalityId: Int? = null,
    @SerialName("municipality_name") val municipalityName: String? = null,
    val lon: Double,
    val lat: Double,
    @SerialName("open_reports") val openReports: Long,
    /** §5.1: "1 person says it's full" before the marker turns orange. */
    @SerialName("unconfirmed_full") val unconfirmedFull: Long,
    /** §4.6: the caller added this container ("Added by you"). */
    @SerialName("added_by_me") val addedByMe: Boolean = false,
    @SerialName("i_confirmed") val iConfirmed: Boolean = false,
    /**
     * §4.6: whether "Yes, it's here" would be accepted — decided by the server
     * (50 m, not the adder, not already confirmed), never re-derived here.
     */
    @SerialName("can_confirm_exists") val canConfirmExists: Boolean = false,
)

// -------------------------------------------------------------------------------------------
// Reports
// -------------------------------------------------------------------------------------------

/** `submit_report` (§4.3). [mergedMeToo] is true when this became a me-too. */
@Serializable
data class SubmitReportResultDto(
    @SerialName("report_id") val reportId: String,
    @SerialName("merged_me_too") val mergedMeToo: Boolean,
    @SerialName("me_too_count") val meTooCount: Long,
    val status: String,
)

/** `confirm_report` (§5.1). */
@Serializable
data class ConfirmReportResultDto(
    @SerialName("report_id") val reportId: String,
    @SerialName("report_state") val reportState: String,
    val status: String,
)

/** `reports_public` — the photo timeline on the container sheet (§4.1). */
@Serializable
data class PublicReportDto(
    val id: String,
    @SerialName("container_id") val containerId: String,
    val kind: String,
    @SerialName("photo_path") val photoPath: String,
    val note: String? = null,
    val state: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("resolved_at") val resolvedAt: String? = null,
    @SerialName("me_too_count") val meTooCount: Long,
    @SerialName("resolved_count") val resolvedCount: Long,
    @SerialName("age_hours") val ageHours: Double,
)

/** `my_reports` (§4.5 screen 11; widened in 0017). */
@Serializable
data class MyReportDto(
    @SerialName("report_id") val reportId: String,
    @SerialName("container_id") val containerId: String,
    @SerialName("container_code") val containerCode: String,
    @SerialName("container_kind") val containerKind: String = "big",
    @SerialName("container_lon") val containerLon: Double? = null,
    @SerialName("container_lat") val containerLat: Double? = null,
    @SerialName("municipality_id") val municipalityId: Int? = null,
    val kind: String,
    val state: String,
    @SerialName("photo_path") val photoPath: String,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("resolved_at") val resolvedAt: String? = null,
    @SerialName("me_too_count") val meTooCount: Long,
    /** §5.4 before/after: the photo that came with the resolving confirmation. */
    @SerialName("resolved_photo_path") val resolvedPhotoPath: String? = null,
)

/** `my_suggestions` (0017): made or voted for by the user. */
@Serializable
data class MySuggestionDto(
    val id: String,
    val lon: Double,
    val lat: Double,
    val reason: String,
    val votes: Int,
    val state: String,
    @SerialName("municipality_id") val municipalityId: Int? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("i_authored") val iAuthored: Boolean,
)

/** `my_resolved_since` — drives the "Fixed!" notification worker (§5.4). */
@Serializable
data class ResolvedReportDto(
    @SerialName("report_id") val reportId: String,
    @SerialName("container_id") val containerId: String,
    @SerialName("container_code") val containerCode: String,
    val kind: String,
    @SerialName("resolved_at") val resolvedAt: String,
    @SerialName("hours_to_resolve") val hoursToResolve: Double,
    @SerialName("before_photo") val beforePhoto: String? = null,
    @SerialName("after_photo") val afterPhoto: String? = null,
)

// -------------------------------------------------------------------------------------------
// Suggestions
// -------------------------------------------------------------------------------------------

/** `submit_suggestion` (§4.4). [merged] true means it joined an existing pin within 50 m. */
@Serializable
data class SubmitSuggestionResultDto(
    @SerialName("suggestion_id") val suggestionId: String,
    val merged: Boolean,
    val votes: Int,
)

/** `vote_suggestion` (§5.3). */
@Serializable
data class VoteSuggestionResultDto(
    @SerialName("suggestion_id") val suggestionId: String,
    val votes: Int,
)

/** `suggestions_list` (§4.5 screen 9). */
@Serializable
data class SuggestionDto(
    val id: String,
    val lon: Double,
    val lat: Double,
    val reason: String,
    val note: String? = null,
    @SerialName("photo_path") val photoPath: String? = null,
    val votes: Int,
    val state: String,
    @SerialName("municipality_id") val municipalityId: Int? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("i_voted") val iVoted: Boolean,
)

/**
 * `open_suggestion_near` (0016): the open suggestion within the 50 m merge radius
 * that a new one would join — §4.4 "Vote for this one instead".
 */
@Serializable
data class NearbySuggestionDto(
    val id: String,
    val lon: Double,
    val lat: Double,
    val reason: String,
    val note: String? = null,
    val votes: Int,
    @SerialName("distance_m") val distanceM: Double,
    @SerialName("created_at") val createdAt: String,
    @SerialName("i_voted") val iVoted: Boolean,
)

// -------------------------------------------------------------------------------------------
// Stats
// -------------------------------------------------------------------------------------------

/** `my_impact` (§5.4). */
@Serializable
data class ImpactDto(
    val emptied: Long,
    val repaired: Long,
    @SerialName("containers_placed") val containersPlaced: Long,
)

/** `municipality_stats` (§5.4). Nullable medians mean "nothing resolved yet". */
@Serializable
data class MunicipalityStatsDto(
    @SerialName("municipality_id") val municipalityId: Int,
    @SerialName("name_mk") val nameMk: String,
    @SerialName("name_sq") val nameSq: String,
    @SerialName("name_en") val nameEn: String,
    @SerialName("median_hours") val medianHours: Double? = null,
    @SerialName("resolved_count") val resolvedCount: Long,
    @SerialName("open_count") val openCount: Long,
    @SerialName("active_reporters") val activeReporters: Long,
    @SerialName("prev_median_hours") val prevMedianHours: Double? = null,
)

// -------------------------------------------------------------------------------------------
// §4.6 Map your street
// -------------------------------------------------------------------------------------------

/** `my_add_allowance` — "You can add 2 containers" / "1 more" (§4.6). */
@Serializable
data class AddAllowanceDto(
    @SerialName("is_admin") val isAdmin: Boolean,
    @SerialName("containers_added") val containersAdded: Int,
    val remaining: Int,
)

/** `add_container` (§4.6). */
@Serializable
data class AddContainerResultDto(
    @SerialName("container_id") val containerId: String,
    val code: String,
    val verified: Boolean,
    val remaining: Int,
)

/** `confirm_container_exists` (§4.6). */
@Serializable
data class ConfirmContainerResultDto(
    @SerialName("container_id") val containerId: String,
    val verified: Boolean,
    @SerialName("confirmation_count") val confirmationCount: Long,
)

/** `submit_container_request` (§4.6). */
@Serializable
data class ContainerRequestResultDto(
    @SerialName("request_id") val requestId: String,
    @SerialName("remaining_today") val remainingToday: Int,
)

/** `unverified_nearby` — the one-tap "Yes, it's here" list (§4.6 step 3). */
@Serializable
data class UnverifiedContainerDto(
    val id: String,
    val code: String,
    val kind: String,
    val category: String,
    val status: String,
    val lon: Double,
    val lat: Double,
    @SerialName("distance_m") val distanceM: Double,
    @SerialName("i_confirmed") val iConfirmed: Boolean,
    @SerialName("is_mine") val isMine: Boolean,
    /** The server's answer to "may I show 'Yes, it's here'?" (0015). */
    @SerialName("can_confirm") val canConfirm: Boolean = false,
)

// -------------------------------------------------------------------------------------------
// §4.6 Admin
// -------------------------------------------------------------------------------------------

/** `admin_pending_requests` (§4.6). */
@Serializable
data class PendingRequestDto(
    val id: String,
    val lon: Double,
    val lat: Double,
    val kind: String,
    val category: String,
    @SerialName("photo_path") val photoPath: String,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String,
)

/** `admin_review_request` (§4.6). */
@Serializable
data class ReviewRequestResultDto(
    @SerialName("request_id") val requestId: String,
    val state: String,
    @SerialName("container_id") val containerId: String? = null,
)

/** `admin_unverified_containers` (§4.6). */
@Serializable
data class AdminUnverifiedDto(
    val id: String,
    val code: String,
    val kind: String,
    val category: String,
    val lon: Double,
    val lat: Double,
    @SerialName("added_by") val addedBy: String? = null,
    @SerialName("confirmation_count") val confirmationCount: Long,
    @SerialName("created_at") val createdAt: String,
)

// -------------------------------------------------------------------------------------------
// Profile
// -------------------------------------------------------------------------------------------

/** The `profiles` row the signed-in user may read and partly update (§6 RLS). */
@Serializable
data class ProfileDto(
    val id: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("municipality_id") val municipalityId: Int? = null,
    val lang: String? = null,
    @SerialName("trust_score") val trustScore: Int = 0,
    val role: String = "user",
    @SerialName("containers_added") val containersAdded: Int = 0,
    @SerialName("last_area_check_at") val lastAreaCheckAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    val isAdmin: Boolean get() = role == "admin"

    /** §5.1: a trusted reporter's single `full` report is enough to flip the marker. */
    val isTrusted: Boolean get() = trustScore >= 5
}

/** Only the columns a user is granted UPDATE on (§6). */
@Serializable
data class ProfileUpdateDto(
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("municipality_id") val municipalityId: Int? = null,
    val lang: String? = null,
)
