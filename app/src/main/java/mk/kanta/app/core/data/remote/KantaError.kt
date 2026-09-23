package mk.kanta.app.core.data.remote

import androidx.annotation.StringRes
import mk.kanta.app.R

/**
 * Every way a Kanta backend call can fail, as something the UI can act on.
 *
 * The SQL raises stable SQLSTATEs (`KA001`…`KA015`, see supabase/README.md) rather
 * than English sentences, so the message the user reads comes from `strings.xml`
 * in their own language (§8: "No hard-coded strings; all three languages").
 *
 * Several errors carry the number the server measured — [TooFarFromContainer]'s
 * distance, for instance — because "Move closer, you're 82 m away" is a far more
 * useful thing to read than "too far".
 */
sealed interface KantaError {

    /** The string resource to show the user. */
    @get:StringRes
    val messageRes: Int

    /** True when retrying the identical request could plausibly succeed. */
    val isRetryable: Boolean get() = false

    // --- Location rules ----------------------------------------------------------------

    /** §4.3: a report must be filed within 60 m of the container. */
    data class TooFarFromContainer(val metres: Int?) : KantaError {
        override val messageRes = R.string.error_too_far_from_container
    }

    /** §4.6: the pin must be within 30 m of where the user is standing. */
    data class TooFarToAdd(val metres: Int?) : KantaError {
        override val messageRes = R.string.error_too_far_to_add
    }

    /** §4.6: confirming a container requires being within 50 m of it. */
    data class TooFarToConfirm(val metres: Int?) : KantaError {
        override val messageRes = R.string.error_too_far_to_confirm
    }

    // --- Limits ------------------------------------------------------------------------

    /** §6: 10 reports per user per day. */
    data object DailyReportLimit : KantaError {
        override val messageRes = R.string.error_daily_report_limit
    }

    /** §4.6: 2 containers per account, for life. */
    data object AddAllowanceUsedUp : KantaError {
        override val messageRes = R.string.error_add_allowance_used_up
    }

    /** §4.6: 3 container requests per user per day. */
    data object DailyRequestLimit : KantaError {
        override val messageRes = R.string.error_daily_request_limit
    }

    // --- Conflicts ---------------------------------------------------------------------

    /**
     * §4.6: a container of the same kind already sits within 10 m (big) or 5 m (small).
     * [existingContainerId] is what the "Is it this one?" sheet shows.
     */
    data class DuplicateContainer(val existingContainerId: String?) : KantaError {
        override val messageRes = R.string.error_duplicate_container
    }

    /** A confirmation or vote the user already cast. Harmless, but worth saying. */
    data object AlreadyDone : KantaError {
        override val messageRes = R.string.error_already_done
    }

    /** §4.6: you cannot vouch for a container you added yourself. */
    data object CannotConfirmOwnContainer : KantaError {
        override val messageRes = R.string.error_cannot_confirm_own
    }

    // --- Auth and permissions ----------------------------------------------------------

    /** §2: report / suggest / vote all require an account. */
    data object NotSignedIn : KantaError {
        override val messageRes = R.string.error_not_signed_in
    }

    /** §4.6: admin-only RPC called by a normal account. */
    data object AdminOnly : KantaError {
        override val messageRes = R.string.error_admin_only
    }

    // --- Missing rows ------------------------------------------------------------------

    data object ContainerNotFound : KantaError {
        override val messageRes = R.string.error_container_not_found
    }

    data object ReportNotOpen : KantaError {
        override val messageRes = R.string.error_report_not_open
    }

    data object SuggestionNotFound : KantaError {
        override val messageRes = R.string.error_suggestion_not_found
    }

    data object RequestAlreadyReviewed : KantaError {
        override val messageRes = R.string.error_request_already_reviewed
    }

    // --- Transport ---------------------------------------------------------------------

    /** No connection. §4.3: the report is queued and sent later, so this is not fatal. */
    data object Offline : KantaError {
        override val messageRes = R.string.error_offline
        override val isRetryable = true
    }

    /** The request reached the server but it failed or took too long. */
    data object ServerUnavailable : KantaError {
        override val messageRes = R.string.error_server_unavailable
        override val isRetryable = true
    }

    /**
     * local.properties has no Supabase credentials, so there is no backend to
     * talk to. A developer-setup problem rather than a user one, but it has to
     * surface somewhere rather than crashing the screen.
     */
    data object BackendNotConfigured : KantaError {
        override val messageRes = R.string.error_backend_not_configured
    }

    /**
     * Anything we did not anticipate. [technical] is kept for logs and bug reports
     * and is never shown to the user.
     */
    data class Unknown(val technical: String?) : KantaError {
        override val messageRes = R.string.error_unknown
        override val isRetryable = true
    }
}
