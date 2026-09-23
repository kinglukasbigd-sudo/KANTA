package mk.kanta.app.core.data.remote

import io.github.jan.supabase.SupabaseClient
import mk.kanta.app.core.network.SupabaseClientHolder
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mk.kanta.app.core.data.di.IoDispatcher
import mk.kanta.app.core.data.model.ContainerCategory
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.remote.dto.AddAllowanceDto
import mk.kanta.app.core.data.remote.dto.AddContainerResultDto
import mk.kanta.app.core.data.remote.dto.AdminUnverifiedDto
import mk.kanta.app.core.data.remote.dto.ConfirmContainerResultDto
import mk.kanta.app.core.data.remote.dto.ConfirmReportResultDto
import mk.kanta.app.core.data.remote.dto.ContainerDetailDto
import mk.kanta.app.core.data.remote.dto.ContainerMarkerDto
import mk.kanta.app.core.data.remote.dto.ContainerRequestResultDto
import mk.kanta.app.core.data.remote.dto.ImpactDto
import mk.kanta.app.core.data.remote.dto.MunicipalityStatsDto
import mk.kanta.app.core.data.remote.dto.MyReportDto
import mk.kanta.app.core.data.remote.dto.NearbyContainerDto
import mk.kanta.app.core.data.remote.dto.PendingRequestDto
import mk.kanta.app.core.data.remote.dto.PublicReportDto
import mk.kanta.app.core.data.remote.dto.ResolvedReportDto
import mk.kanta.app.core.data.remote.dto.ReviewRequestResultDto
import mk.kanta.app.core.data.remote.dto.SubmitReportResultDto
import mk.kanta.app.core.data.remote.dto.SubmitSuggestionResultDto
import mk.kanta.app.core.data.remote.dto.SuggestionDto
import mk.kanta.app.core.data.remote.dto.UnverifiedContainerDto
import mk.kanta.app.core.data.remote.dto.VoteSuggestionResultDto
import mk.kanta.app.core.location.LatLon
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's single door to the Supabase backend.
 *
 * Every method wraps exactly one RPC from supabase/migrations and returns a
 * `Flow<KantaResult<T>>` that emits [KantaResult.Loading] first, so screens can
 * show a skeleton without inventing their own loading state (§8).
 *
 * Nothing here re-implements a server rule. The 60 m radius, the 10-per-day cap,
 * the 2-container limit and the rest are enforced in SQL; this layer's job is to
 * carry the arguments there and translate the refusal back into something the
 * user can read.
 */
@Singleton
class KantaRepository @Inject constructor(
    private val supabase: SupabaseClientHolder,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Resolved per call, so an unconfigured build fails as an error, not a crash. */
    private val client: SupabaseClient get() = supabase.client

    // =========================================================================================
    // Map (anon-readable — §2: browsing needs no account)
    // =========================================================================================

    /** `containers_in_bbox` — the markers for the current viewport (§3.4). */
    fun containersInBbox(
        minLon: Double,
        minLat: Double,
        maxLon: Double,
        maxLat: Double,
    ): Flow<KantaResult<List<ContainerMarkerDto>>> = rpcList("containers_in_bbox") {
        put("min_lon", minLon)
        put("min_lat", minLat)
        put("max_lon", maxLon)
        put("max_lat", maxLat)
    }

    /**
     * `nearest_containers` — §5.2 "Nearest containers with space".
     * Defaults mirror the spec: big containers only, same category, within 600 m.
     */
    fun nearestContainers(
        lon: Double,
        lat: Double,
        category: ContainerCategory = ContainerCategory.GENERAL,
        kind: ContainerKind = ContainerKind.BIG,
        limit: Int = 10,
        maxMetres: Int = 600,
    ): Flow<KantaResult<List<NearbyContainerDto>>> = rpcList("nearest_containers") {
        put("p_lon", lon)
        put("p_lat", lat)
        put("p_category", category.wire)
        put("p_limit", limit)
        put("p_max_m", maxMetres)
        put("p_kind", kind.wire)
    }

    /** `nearest_container_to` — snaps a new report to what the user is standing at (§4.3). */
    fun nearestContainerTo(
        lon: Double,
        lat: Double,
        maxMetres: Int = 60,
    ): Flow<KantaResult<NearbyContainerDto?>> = rpcFirstOrNull("nearest_container_to") {
        put("p_lon", lon)
        put("p_lat", lat)
        put("p_max_m", maxMetres)
    }

    /**
     * `container_detail` — everything the detail sheet needs in one call (§4.1).
     * [from] is where the phone is; with it the server can say whether
     * "Yes, it's here" is allowed (§4.6). Without it that answer is simply no.
     */
    fun containerDetail(
        containerId: String,
        from: LatLon? = null,
    ): Flow<KantaResult<ContainerDetailDto?>> =
        rpcFirstOrNull("container_detail") {
            put("p_container_id", containerId)
            if (from != null) {
                put("p_lon", from.lon)
                put("p_lat", from.lat)
            }
        }

    // =========================================================================================
    // Reports (§4.3, §5.1)
    // =========================================================================================

    /**
     * `submit_report`. The server may turn this into a me-too instead of a new
     * report; [SubmitReportResultDto.mergedMeToo] says which happened so the UI
     * can be honest about it.
     */
    fun submitReport(
        containerId: String,
        kind: String,
        photoPath: String,
        note: String?,
        lon: Double,
        lat: Double,
    ): Flow<KantaResult<SubmitReportResultDto>> = rpcFirst("submit_report") {
        put("p_container_id", containerId)
        put("p_kind", kind)
        put("p_photo_path", photoPath)
        put("p_note", note ?: "")
        put("p_lon", lon)
        put("p_lat", lat)
    }

    /** `confirm_report` — "Me too, still full" / "It's been emptied" (§4.1). */
    fun confirmReport(
        reportId: String,
        kind: String,
        photoPath: String? = null,
    ): Flow<KantaResult<ConfirmReportResultDto>> = rpcFirst("confirm_report") {
        put("p_report_id", reportId)
        put("p_kind", kind)
        put("p_photo_path", photoPath ?: "")
    }

    /**
     * The photo timeline on the container detail sheet (§4.1).
     *
     * `reports_public` is a view, not an RPC, so this is a plain select — the
     * view is what strips user_id (§6), and anon may read it.
     */
    fun reportsForContainer(containerId: String): Flow<KantaResult<List<PublicReportDto>>> =
        resultFlow {
            client.postgrest.from("reports_public")
                .select {
                    filter { eq("container_id", containerId) }
                    order("created_at", Order.DESCENDING)
                    limit(50)
                }
                .decodeList<PublicReportDto>()
        }

    /** `my_reports` — the profile list (§4.5 screen 11). */
    fun myReports(limit: Int = 100): Flow<KantaResult<List<MyReportDto>>> =
        rpcList("my_reports") { put("p_limit", limit) }

    /** `my_resolved_since` — drives the "Fixed!" notification worker (§5.4). */
    fun myResolvedSince(sinceIso: String): Flow<KantaResult<List<ResolvedReportDto>>> =
        rpcList("my_resolved_since") { put("p_since", sinceIso) }

    // =========================================================================================
    // Suggestions (§4.4, §5.3)
    // =========================================================================================

    /** `submit_suggestion`. Merges into an open suggestion within 50 m. */
    fun submitSuggestion(
        lon: Double,
        lat: Double,
        reason: String,
        note: String? = null,
        photoPath: String? = null,
    ): Flow<KantaResult<SubmitSuggestionResultDto>> = rpcFirst("submit_suggestion") {
        put("p_lon", lon)
        put("p_lat", lat)
        put("p_reason", reason)
        put("p_note", note ?: "")
        put("p_photo_path", photoPath ?: "")
    }

    /** `vote_suggestion` — one vote per user (§5.3). */
    fun voteSuggestion(suggestionId: String): Flow<KantaResult<VoteSuggestionResultDto>> =
        rpcFirst("vote_suggestion") { put("p_suggestion_id", suggestionId) }

    /** `suggestions_list` — sorted by votes, optional municipality filter (§4.5). */
    fun suggestions(
        municipalityId: Int? = null,
        limit: Int = 50,
    ): Flow<KantaResult<List<SuggestionDto>>> = rpcList("suggestions_list") {
        if (municipalityId != null) put("p_municipality_id", municipalityId)
        put("p_limit", limit)
    }

    // =========================================================================================
    // Stats (§5.4)
    // =========================================================================================

    /** `my_impact` — the impact counter. */
    fun myImpact(): Flow<KantaResult<ImpactDto>> = rpcFirst("my_impact") {}

    /** `municipality_stats` — the ranking, with the previous window for trend arrows. */
    fun municipalityStats(days: Int = 30): Flow<KantaResult<List<MunicipalityStatsDto>>> =
        rpcList("municipality_stats") { put("p_days", days) }

    // =========================================================================================
    // §4.6 Map your street
    // =========================================================================================

    /** `my_add_allowance` — "You can add 2 containers" / "1 more". */
    fun myAddAllowance(): Flow<KantaResult<AddAllowanceDto>> = rpcFirst("my_add_allowance") {}

    /**
     * `add_container`. [deviceLon]/[deviceLat] are where the phone actually is;
     * the server rejects the call if the pin is more than 30 m away, so passing
     * the pin twice would defeat the rule.
     */
    fun addContainer(
        lon: Double,
        lat: Double,
        kind: ContainerKind,
        category: ContainerCategory,
        photoPath: String,
        deviceLon: Double,
        deviceLat: Double,
        confirmDifferent: Boolean = false,
    ): Flow<KantaResult<AddContainerResultDto>> = rpcFirst("add_container") {
        put("p_lon", lon)
        put("p_lat", lat)
        put("p_kind", kind.wire)
        put("p_category", category.wire)
        put("p_photo_path", photoPath)
        put("p_device_lon", deviceLon)
        put("p_device_lat", deviceLat)
        put("p_confirm_different", confirmDifferent)
    }

    /** `confirm_container_exists` — the one-tap "Yes, it's here". */
    fun confirmContainerExists(
        containerId: String,
        lon: Double,
        lat: Double,
    ): Flow<KantaResult<ConfirmContainerResultDto>> = rpcFirst("confirm_container_exists") {
        put("p_container_id", containerId)
        put("p_lon", lon)
        put("p_lat", lat)
    }

    /** `submit_container_request` — for users who used up their 2 adds. */
    fun submitContainerRequest(
        lon: Double,
        lat: Double,
        kind: ContainerKind,
        category: ContainerCategory,
        photoPath: String,
        note: String? = null,
    ): Flow<KantaResult<ContainerRequestResultDto>> = rpcFirst("submit_container_request") {
        put("p_lon", lon)
        put("p_lat", lat)
        put("p_kind", kind.wire)
        put("p_category", category.wire)
        put("p_photo_path", photoPath)
        put("p_note", note ?: "")
    }

    /** `submit_area_check` — records that this 150 m radius was walked. */
    fun submitAreaCheck(
        lon: Double,
        lat: Double,
        result: String,
    ): Flow<KantaResult<String>> = rpcScalar("submit_area_check") {
        put("p_lon", lon)
        put("p_lat", lat)
        put("p_result", result)
    }

    /** `should_prompt_area_check` — never ask more often than §4.6 allows. */
    fun shouldPromptAreaCheck(lon: Double, lat: Double): Flow<KantaResult<Boolean>> =
        rpcScalar("should_prompt_area_check") {
            put("p_lon", lon)
            put("p_lat", lat)
        }

    /** `unverified_nearby` — dashed containers inside the check radius. */
    fun unverifiedNearby(
        lon: Double,
        lat: Double,
        maxMetres: Int = 150,
    ): Flow<KantaResult<List<UnverifiedContainerDto>>> = rpcList("unverified_nearby") {
        put("p_lon", lon)
        put("p_lat", lat)
        put("p_max_m", maxMetres)
    }

    // =========================================================================================
    // §4.6 Admin. The server checks role = 'admin' on every one of these.
    // =========================================================================================

    fun adminPendingRequests(limit: Int = 100): Flow<KantaResult<List<PendingRequestDto>>> =
        rpcList("admin_pending_requests") { put("p_limit", limit) }

    fun adminReviewRequest(
        requestId: String,
        approve: Boolean,
    ): Flow<KantaResult<ReviewRequestResultDto>> = rpcFirst("admin_review_request") {
        put("p_id", requestId)
        put("p_approve", approve)
    }

    fun adminUnverifiedContainers(limit: Int = 200): Flow<KantaResult<List<AdminUnverifiedDto>>> =
        rpcList("admin_unverified_containers") { put("p_limit", limit) }

    fun adminVerifyContainer(containerId: String): Flow<KantaResult<Boolean>> =
        rpcScalar("admin_verify_container") { put("p_id", containerId) }

    fun adminDeleteContainer(containerId: String): Flow<KantaResult<Boolean>> =
        rpcScalar("admin_delete_container") { put("p_id", containerId) }

    /** `admin_coverage` — GeoJSON of where checks happened, as a raw string. */
    fun adminCoverage(days: Int = 90): Flow<KantaResult<String>> =
        rpcRaw("admin_coverage") { put("p_days", days) }

    // =========================================================================================
    // Account (§8: delete account removes the profile and anonymises reports)
    // =========================================================================================

    fun deleteMyAccount(): Flow<KantaResult<Boolean>> = rpcScalar("delete_my_account") {}

    // =========================================================================================
    // Plumbing
    //
    // One place decides what Loading/Success/Failure means, so no call site can
    // forget to map an exception and leak a raw Postgres string into the UI.
    // =========================================================================================

    private inline fun <reified T> rpcList(
        function: String,
        crossinline params: JsonObjectBuilder.() -> Unit,
    ): Flow<KantaResult<List<T>>> = resultFlow {
        client.postgrest.rpc(function, jsonParams(params)).decodeList<T>()
    }

    private inline fun <reified T> rpcFirstOrNull(
        function: String,
        crossinline params: JsonObjectBuilder.() -> Unit,
    ): Flow<KantaResult<T?>> = resultFlow {
        client.postgrest.rpc(function, jsonParams(params)).decodeList<T>().firstOrNull()
    }

    /**
     * For RPCs declared `returns table(...)` that always yield exactly one row.
     * An empty result means the function silently did nothing, which is a bug on
     * the SQL side rather than something the user caused — surfaced as Unknown.
     */
    private inline fun <reified T> rpcFirst(
        function: String,
        crossinline params: JsonObjectBuilder.() -> Unit,
    ): Flow<KantaResult<T>> = resultFlow {
        client.postgrest.rpc(function, jsonParams(params)).decodeList<T>().firstOrNull()
            ?: error("$function returned no rows")
    }

    /** For RPCs returning a bare scalar (boolean, uuid, int). */
    private inline fun <reified T> rpcScalar(
        function: String,
        crossinline params: JsonObjectBuilder.() -> Unit,
    ): Flow<KantaResult<T>> = resultFlow {
        client.postgrest.rpc(function, jsonParams(params)).decodeAs<T>()
    }

    /** For RPCs returning jsonb we hand straight to a map layer. */
    private inline fun rpcRaw(
        function: String,
        crossinline params: JsonObjectBuilder.() -> Unit,
    ): Flow<KantaResult<String>> = resultFlow {
        client.postgrest.rpc(function, jsonParams(params)).data
    }

    private inline fun jsonParams(builder: JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject(builder)

    private inline fun <T> resultFlow(
        crossinline block: suspend () -> T,
    ): Flow<KantaResult<T>> = flow {
        emit(KantaResult.Loading)
        if (!supabase.isConfigured) {
            emit(KantaResult.Failure(KantaError.BackendNotConfigured))
            return@flow
        }
        emit(KantaResult.Success(block()))
    }.catch { throwable ->
        emit(KantaResult.Failure(throwable.toKantaError()))
    }.flowOn(io)
}

/** Wire values, so an enum rename cannot silently change what the database receives. */
private val ContainerKind.wire: String
    get() = when (this) {
        ContainerKind.BIG -> "big"
        ContainerKind.SMALL -> "small"
    }

private val ContainerCategory.wire: String
    get() = when (this) {
        ContainerCategory.GENERAL -> "general"
        ContainerCategory.GLASS -> "glass"
        ContainerCategory.PAPER -> "paper"
        ContainerCategory.PLASTIC -> "plastic"
        ContainerCategory.MIXED_RECYCLING -> "mixed_recycling"
    }
