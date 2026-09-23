package mk.kanta.app.feature.report

import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.data.remote.KantaResult
import mk.kanta.app.core.data.remote.dto.SubmitReportResultDto
import kotlin.math.roundToInt

/** What happened when the user pressed Send (§4.3). */
sealed interface SendOutcome {

    /** A new report was filed. */
    data object Sent : SendOutcome

    /**
     * §4.3: "If an open report of the same kind already exists on that container →
     * becomes a 'me too' confirmation automatically (tell the user)."
     * [neighbours] is how many people had already reported it.
     */
    data class MergedMeToo(val neighbours: Int) : SendOutcome

    /** §4.3: more than 60 m away. Offer "Move closer" or "Add a new container here". */
    data class TooFar(val metres: Int?) : SendOutcome

    /** §6: the 10-a-day limit. */
    data object RateLimited : SendOutcome

    /** §4.3: offline — kept on the phone, sent later. */
    data object Queued : SendOutcome

    /** §4.6: allowance used up, so a container request went to review instead. */
    data object RequestSent : SendOutcome

    /**
     * §4.6 "One is missing": the container was added. [verified] is true for an
     * admin (on the map for good at once), false for everyone else (dashed until
     * two neighbours confirm it).
     */
    data class ContainerAdded(val verified: Boolean) : SendOutcome

    /** §4.6 "Is it this one?" → yes: nothing was missing after all. */
    data object AlreadyOnMap : SendOutcome

    data class Failed(val error: KantaError) : SendOutcome

    /** True when the flow is over and the user should see the success screen. */
    val isFinal: Boolean
        get() = this is Sent || this is MergedMeToo || this is Queued || this is RequestSent ||
            this is ContainerAdded || this is AlreadyOnMap
}

/**
 * The pure rules for turning a send attempt into a [SendOutcome]. Kept apart from
 * the ViewModel so every branch is unit tested directly.
 */
object SendOutcomes {

    /** §4.3 / SQL submit_report: the server's radius. The client checks it too, to save an upload. */
    const val MAX_REPORT_DISTANCE_M = 60.0

    /**
     * Refuse locally what the server would refuse anyway. Beyond saving an upload,
     * this matters offline: a queued report that is too far would sit in the queue
     * and fail hours later, long after the user could have walked closer.
     */
    fun precheck(distanceMetres: Double?): SendOutcome? =
        if (distanceMetres != null && distanceMetres > MAX_REPORT_DISTANCE_M) {
            SendOutcome.TooFar(distanceMetres.roundToInt())
        } else {
            null
        }

    fun from(result: KantaResult<SubmitReportResultDto>): SendOutcome = when (result) {
        is KantaResult.Success ->
            if (result.data.mergedMeToo) {
                // me_too_count includes this user when a confirmation was just added;
                // the people who reported before them are the reporter plus everyone
                // else, which is that same number. Never fewer than the one reporter.
                SendOutcome.MergedMeToo(neighbours = result.data.meTooCount.toInt().coerceAtLeast(1))
            } else {
                SendOutcome.Sent
            }

        is KantaResult.Failure -> fromError(result.error)

        KantaResult.Loading -> SendOutcome.Failed(KantaError.Unknown("still loading"))
    }

    fun fromError(error: KantaError): SendOutcome = when (error) {
        is KantaError.TooFarFromContainer -> SendOutcome.TooFar(error.metres)
        KantaError.DailyReportLimit -> SendOutcome.RateLimited
        // A dropped connection or a server that timed out are both "try again
        // later", which is exactly what the queue does. Retrying a report the
        // server did in fact receive is harmless: the same reporter re-reporting
        // is a no-op in submit_report.
        KantaError.Offline, KantaError.ServerUnavailable -> SendOutcome.Queued
        else -> SendOutcome.Failed(error)
    }
}
