package mk.kanta.app.core.data

import kotlinx.coroutines.CancellationException
import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.data.remote.KantaErrorMapper
import mk.kanta.app.core.data.remote.toKantaError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Tests for the pure half of the error mapping (§8: unit tests for status logic
 * and distance rules).
 *
 * These are the rules a user actually feels — "you're 82 m away", "you've used
 * both your containers" — so they are pinned down here rather than trusted to a
 * manual pass through the app.
 */
class KantaErrorMapperTest {

    // -----------------------------------------------------------------------------------------
    // Every code in supabase/README.md maps to its own error
    // -----------------------------------------------------------------------------------------

    @Test
    fun `every documented sql state maps to its error`() {
        val expected = mapOf(
            "KA001" to KantaError.TooFarFromContainer::class,
            "KA002" to KantaError.DailyReportLimit::class,
            "KA003" to KantaError.DuplicateContainer::class,
            "KA004" to KantaError.AddAllowanceUsedUp::class,
            "KA005" to KantaError.TooFarToAdd::class,
            "KA006" to KantaError.DailyRequestLimit::class,
            "KA007" to KantaError.AdminOnly::class,
            "KA008" to KantaError.AlreadyDone::class,
            "KA009" to KantaError.CannotConfirmOwnContainer::class,
            "KA010" to KantaError.TooFarToConfirm::class,
            "KA011" to KantaError.NotSignedIn::class,
            "KA012" to KantaError.ContainerNotFound::class,
            "KA013" to KantaError.ReportNotOpen::class,
            "KA014" to KantaError.SuggestionNotFound::class,
            "KA015" to KantaError.RequestAlreadyReviewed::class,
        )

        expected.forEach { (code, type) ->
            val mapped = KantaErrorMapper.fromSqlState(code)
            assertEquals("code $code", type, mapped::class)
        }
    }

    @Test
    fun `every mapped error carries a message resource`() {
        (1..15).forEach { n ->
            val code = "KA%03d".format(n)
            val error = KantaErrorMapper.fromSqlState(code)
            assertTrue("$code has no message", error.messageRes != 0)
        }
    }

    @Test
    fun `unknown code falls through to Unknown and keeps the code for logs`() {
        val error = KantaErrorMapper.fromSqlState("KA999")
        assertEquals(KantaError.Unknown("KA999"), error)
    }

    @Test
    fun `null code is Unknown rather than a crash`() {
        assertTrue(KantaErrorMapper.fromSqlState(null) is KantaError.Unknown)
    }

    @Test
    fun `codes are matched case insensitively`() {
        assertTrue(KantaErrorMapper.fromSqlState("ka004") is KantaError.AddAllowanceUsedUp)
    }

    // -----------------------------------------------------------------------------------------
    // Distances: the number the server measured must survive into the UI
    // -----------------------------------------------------------------------------------------

    @Test
    fun `distance detail is carried into the error`() {
        val error = KantaErrorMapper.fromSqlState("KA001", "82") as KantaError.TooFarFromContainer
        assertEquals(82, error.metres)
    }

    @Test
    fun `distance survives a unit suffix`() {
        val error = KantaErrorMapper.fromSqlState("KA005", "41 m") as KantaError.TooFarToAdd
        assertEquals(41, error.metres)
    }

    @Test
    fun `missing distance is null rather than zero`() {
        // Zero would read as "you are 0 m away", which is worse than saying nothing.
        val error = KantaErrorMapper.fromSqlState("KA010", null) as KantaError.TooFarToConfirm
        assertNull(error.metres)
    }

    @Test
    fun `non numeric distance detail is null rather than a crash`() {
        val error = KantaErrorMapper.fromSqlState("KA001", "far away") as KantaError.TooFarFromContainer
        assertNull(error.metres)
    }

    @Test
    fun `duplicate container carries the existing id for the Is it this one sheet`() {
        val id = "0f7c6b3e-1a2b-4c5d-8e9f-0a1b2c3d4e5f"
        val error = KantaErrorMapper.fromSqlState("KA003", id) as KantaError.DuplicateContainer
        assertEquals(id, error.existingContainerId)
    }

    @Test
    fun `blank duplicate detail becomes null`() {
        val error = KantaErrorMapper.fromSqlState("KA003", "") as KantaError.DuplicateContainer
        assertNull(error.existingContainerId)
    }

    // -----------------------------------------------------------------------------------------
    // Extracting the code from a real PostgREST error body
    // -----------------------------------------------------------------------------------------

    @Test
    fun `code is extracted from a postgrest error body`() {
        val body = """{"code":"KA002","details":null,"hint":null,"message":"daily report limit reached"}"""
        assertTrue(RuntimeException(body).toKantaError() is KantaError.DailyReportLimit)
    }

    @Test
    fun `code and details are extracted together`() {
        val body = """{"code":"KA001","details":"82","hint":null,"message":"too far: 82 m"}"""
        val error = RuntimeException(body).toKantaError() as KantaError.TooFarFromContainer
        assertEquals(82, error.metres)
    }

    @Test
    fun `a note mentioning KA does not trigger a false match`() {
        // The regex is anchored to KA + exactly three digits, so free text is safe.
        val body = """{"code":"P0001","message":"note was: KANTA is great, see KA12 street"}"""
        assertTrue(RuntimeException(body).toKantaError() is KantaError.Unknown)
    }

    // -----------------------------------------------------------------------------------------
    // Transport failures
    // -----------------------------------------------------------------------------------------

    @Test
    fun `unknown host is offline`() {
        assertEquals(KantaError.Offline, UnknownHostException("no dns").toKantaError())
    }

    @Test
    fun `io exception is offline`() {
        assertEquals(KantaError.Offline, IOException("socket closed").toKantaError())
    }

    @Test
    fun `timeout is server unavailable`() {
        assertEquals(KantaError.ServerUnavailable, SocketTimeoutException("timeout").toKantaError())
    }

    @Test
    fun `http 503 is server unavailable`() {
        assertEquals(
            KantaError.ServerUnavailable,
            RuntimeException("HTTP 503 Service Unavailable").toKantaError(),
        )
    }

    @Test
    fun `expired jwt is treated as not signed in`() {
        assertEquals(
            KantaError.NotSignedIn,
            RuntimeException("401 Unauthorized: JWT expired").toKantaError(),
        )
    }

    @Test
    fun `offline and server errors are retryable but rule violations are not`() {
        assertTrue(KantaError.Offline.isRetryable)
        assertTrue(KantaError.ServerUnavailable.isRetryable)
        // Retrying an identical request that broke a rule just fails the same way.
        assertTrue(!KantaError.DailyReportLimit.isRetryable)
        assertTrue(!KantaError.TooFarFromContainer(80).isRetryable)
    }

    @Test
    fun `unknown throwable keeps type and message for the bug report`() {
        val error = IllegalStateException("weird").toKantaError() as KantaError.Unknown
        val technical = requireNotNull(error.technical)
        assertTrue(technical.contains("IllegalStateException"))
        assertTrue(technical.contains("weird"))
    }

    @Test
    fun `null message does not crash the mapper`() {
        assertTrue(RuntimeException().toKantaError() is KantaError.Unknown)
    }

    // -----------------------------------------------------------------------------------------
    // Cancellation must not be swallowed
    // -----------------------------------------------------------------------------------------

    @Test
    fun `cancellation is rethrown not mapped`() {
        // Mapping it would turn a cancelled coroutine into a visible error toast
        // and break structured concurrency.
        try {
            CancellationException("scope closed").toKantaError()
            fail("expected CancellationException to be rethrown")
        } catch (expected: CancellationException) {
            assertEquals("scope closed", expected.message)
        }
    }
}
