package mk.kanta.app.core.auth

import kotlinx.coroutines.CancellationException
import mk.kanta.app.core.data.remote.KantaError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

/**
 * Supabase answers a wrong code and an expired code identically (`otp_expired`),
 * so these tests pin down the elapsed-time rule that tells them apart.
 */
class AuthErrorMapperTest {

    private val otpBody = """{"code":403,"error_code":"otp_expired","msg":"Token has expired or is invalid"}"""

    @Test
    fun `otp_expired soon after sending means the code was mistyped`() =
        assertEquals(KantaError.WrongCode, AuthErrorMapper.classify(otpBody, secondsSinceCodeSent = 40))

    @Test
    fun `otp_expired after the expiry window means it expired`() =
        assertEquals(
            KantaError.CodeExpired,
            AuthErrorMapper.classify(otpBody, secondsSinceCodeSent = AuthErrorMapper.OTP_EXPIRY_SECONDS),
        )

    @Test
    fun `unknown send time defaults to wrong code`() =
        assertEquals(KantaError.WrongCode, AuthErrorMapper.classify(otpBody, secondsSinceCodeSent = null))

    @Test
    fun `plain message without the code field is still recognised`() =
        assertEquals(KantaError.WrongCode, AuthErrorMapper.classify("Token has expired or is invalid", 10))

    @Test
    fun `email rate limit`() = assertEquals(
        KantaError.TooManyCodeRequests,
        AuthErrorMapper.classify("""{"error_code":"over_email_send_rate_limit","msg":"email rate limit exceeded"}""", null),
    )

    @Test
    fun `per address cooldown message`() = assertEquals(
        KantaError.TooManyCodeRequests,
        AuthErrorMapper.classify("For security purposes, you can only request this after 42 seconds.", null),
    )

    @Test
    fun `invalid email from the server`() = assertEquals(
        KantaError.InvalidEmail,
        AuthErrorMapper.classify("Unable to validate email address: invalid format", null),
    )

    @Test
    fun `unrelated text is not an auth error`() = assertNull(AuthErrorMapper.classify("HTTP 503", null))

    @Test
    fun `no internet falls through to Offline`() =
        assertEquals(KantaError.Offline, AuthErrorMapper.fromThrowable(UnknownHostException("xyz.supabase.co")))

    @Test
    fun `dropped connection falls through to Offline`() =
        assertEquals(KantaError.Offline, AuthErrorMapper.fromThrowable(IOException("connection reset")))

    @Test
    fun `auth error wrapped in a cause is still found`() {
        val wrapped = RuntimeException("request failed", RuntimeException(otpBody))
        assertEquals(KantaError.WrongCode, AuthErrorMapper.fromThrowable(wrapped, secondsSinceCodeSent = 5))
    }

    @Test
    fun `cancellation is rethrown, never shown as an error`() {
        try {
            AuthErrorMapper.fromThrowable(CancellationException("left the screen"))
            fail("expected rethrow")
        } catch (expected: CancellationException) {
            assertEquals("left the screen", expected.message)
        }
    }
}
