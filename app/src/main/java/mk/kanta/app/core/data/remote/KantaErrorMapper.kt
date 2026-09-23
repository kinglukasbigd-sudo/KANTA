package mk.kanta.app.core.data.remote

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

/**
 * Turns whatever the network layer threw into a [KantaError] the UI can render.
 *
 * Deliberately split in two:
 *
 *  * [fromSqlState] is pure — a code and a detail string in, a [KantaError] out.
 *    All the real logic lives here, so it is unit-testable without Supabase, a
 *    network, or an Android runtime.
 *  * [fromThrowable] is the thin, untestable-by-nature adapter that digs the
 *    SQLSTATE out of an exception before delegating.
 *
 * Why parse the message rather than read a typed field: PostgREST returns the
 * SQLSTATE as `"code"` in its JSON error body, and supabase-kt surfaces that body
 * in the exception message. Matching on the code text keeps this working across
 * client versions that reshape their exception hierarchy, and the regex is
 * anchored to the `KA` prefix these migrations own, so it cannot match a stray
 * word in a user's note.
 */
object KantaErrorMapper {

    /** `KA` followed by exactly three digits — the code space 0002_tables.sql defines. */
    private val CODE_REGEX = Regex("""\bKA(\d{3})\b""")

    /**
     * PostgREST's `details` field, which the RPCs use to pass the measured
     * distance or the conflicting container id.
     */
    private val DETAIL_REGEX = Regex(""""details?"\s*:\s*"([^"]*)"""")

    /**
     * The pure mapping. [code] is a SQLSTATE such as `KA001`; [detail] is the
     * RPC's `DETAIL` payload, which carries a distance in metres or a UUID.
     */
    fun fromSqlState(code: String?, detail: String? = null): KantaError = when (code?.uppercase()) {
        "KA001" -> KantaError.TooFarFromContainer(detail?.toMetres())
        "KA002" -> KantaError.DailyReportLimit
        "KA003" -> KantaError.DuplicateContainer(detail?.takeIf { it.isNotBlank() })
        "KA004" -> KantaError.AddAllowanceUsedUp
        "KA005" -> KantaError.TooFarToAdd(detail?.toMetres())
        "KA006" -> KantaError.DailyRequestLimit
        "KA007" -> KantaError.AdminOnly
        "KA008" -> KantaError.AlreadyDone
        "KA009" -> KantaError.CannotConfirmOwnContainer
        "KA010" -> KantaError.TooFarToConfirm(detail?.toMetres())
        "KA011" -> KantaError.NotSignedIn
        "KA012" -> KantaError.ContainerNotFound
        "KA013" -> KantaError.ReportNotOpen
        "KA014" -> KantaError.SuggestionNotFound
        "KA015" -> KantaError.RequestAlreadyReviewed
        else -> KantaError.Unknown(code)
    }

    /**
     * Adapter for a real failure. Cancellation is rethrown rather than mapped:
     * a coroutine that was cancelled did not fail, and swallowing it here would
     * break structured concurrency.
     */
    fun fromThrowable(throwable: Throwable): KantaError {
        if (throwable is CancellationException) throw throwable

        val message = throwable.message.orEmpty()

        CODE_REGEX.find(message)?.let { match ->
            return fromSqlState(
                code = "KA${match.groupValues[1]}",
                detail = DETAIL_REGEX.find(message)?.groupValues?.getOrNull(1),
            )
        }

        // No Kanta code: classify by transport instead.
        return when {
            throwable is UnknownHostException -> KantaError.Offline
            throwable is SocketTimeoutException -> KantaError.ServerUnavailable
            throwable is IOException -> KantaError.Offline
            containsUnauthorized(message) -> KantaError.NotSignedIn
            containsServerFailure(message) -> KantaError.ServerUnavailable
            else -> KantaError.Unknown(throwable.toTechnicalString())
        }
    }

    /**
     * The RPCs put the rounded distance in DETAIL, but a future change could send
     * "82 m" or a decimal. Take the leading number and ignore the rest rather than
     * losing the figure entirely.
     */
    private fun String.toMetres(): Int? =
        Regex("""\d+""").find(this)?.value?.toIntOrNull()

    private fun containsUnauthorized(message: String): Boolean =
        message.contains("401") ||
            message.contains("JWT", ignoreCase = true) ||
            message.contains("Unauthorized", ignoreCase = true)

    private fun containsServerFailure(message: String): Boolean =
        message.contains("500") ||
            message.contains("502") ||
            message.contains("503") ||
            message.contains("504")

    private fun Throwable.toTechnicalString(): String =
        "${this::class.simpleName}: ${message.orEmpty().take(200)}"
}

/** Convenience so call sites read `catch (t: Throwable) { t.toKantaError() }`. */
fun Throwable.toKantaError(): KantaError = KantaErrorMapper.fromThrowable(this)
