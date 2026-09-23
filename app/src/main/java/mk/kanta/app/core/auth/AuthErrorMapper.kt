package mk.kanta.app.core.auth

import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.data.remote.KantaErrorMapper
import java.util.concurrent.CancellationException

/**
 * Maps Supabase Auth failures to [KantaError] (brief: "wrong code, expired code,
 * no internet").
 *
 * Supabase answers a wrong code and an expired code with the SAME response —
 * `otp_expired`, "Token has expired or is invalid" — so the server alone cannot
 * tell them apart. We can: we know when the code was sent and how long codes
 * live. Past the expiry window it expired; inside it, it was mistyped. That keeps
 * the two friendly messages honest instead of guessing.
 *
 * Matching is on the error text rather than on supabase-kt's exception classes,
 * for the same reason as [KantaErrorMapper]: it survives client upgrades that
 * reshape the exception hierarchy.
 */
object AuthErrorMapper {

    /** Must match Authentication → Email → OTP expiry in Supabase (README: 3600 s). */
    const val OTP_EXPIRY_SECONDS = 3_600L

    fun fromThrowable(throwable: Throwable, secondsSinceCodeSent: Long? = null): KantaError {
        if (throwable is CancellationException) throw throwable
        val text = generateSequence(throwable) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")
        return classify(text, secondsSinceCodeSent) ?: KantaErrorMapper.fromThrowable(throwable)
    }

    /** The pure half. Returns null when the text is not an auth-specific failure. */
    fun classify(text: String, secondsSinceCodeSent: Long?): KantaError? {
        val t = text.lowercase()
        return when {
            "otp_expired" in t || "expired or is invalid" in t || "token has expired" in t ->
                if (secondsSinceCodeSent != null && secondsSinceCodeSent >= OTP_EXPIRY_SECONDS) {
                    KantaError.CodeExpired
                } else {
                    KantaError.WrongCode
                }

            "over_email_send_rate_limit" in t ||
                "over_request_rate_limit" in t ||
                "rate limit" in t ||
                "only request this after" in t -> KantaError.TooManyCodeRequests

            "email_address_invalid" in t ||
                "validate email" in t ||
                "invalid format" in t -> KantaError.InvalidEmail

            else -> null
        }
    }
}
