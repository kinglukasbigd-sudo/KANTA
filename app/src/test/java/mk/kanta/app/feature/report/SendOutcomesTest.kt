package mk.kanta.app.feature.report

import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.data.remote.KantaResult
import mk.kanta.app.core.data.remote.dto.SubmitReportResultDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §4.3's distance and merge rules, one branch at a time. */
class SendOutcomesTest {

    private fun ok(merged: Boolean, meToo: Long) =
        KantaResult.Success(SubmitReportResultDto("r-1", merged, meToo, "full"))

    // --- Distance -----------------------------------------------------------------------

    @Test fun `within 60 m passes the local precheck`() = assertNull(SendOutcomes.precheck(59.4))

    @Test fun `exactly 60 m passes, matching the server's inclusive rule`() = assertNull(SendOutcomes.precheck(60.0))

    @Test fun `over 60 m is refused locally with the distance`() =
        assertEquals(SendOutcome.TooFar(82), SendOutcomes.precheck(81.6))

    @Test fun `unknown distance is left to the server`() = assertNull(SendOutcomes.precheck(null))

    @Test fun `server too far keeps its measured distance`() =
        assertEquals(SendOutcome.TooFar(74), SendOutcomes.from(KantaResult.Failure(KantaError.TooFarFromContainer(74))))

    // --- Merge into me-too ----------------------------------------------------------------

    @Test fun `a new report is Sent`() = assertEquals(SendOutcome.Sent, SendOutcomes.from(ok(merged = false, meToo = 0)))

    @Test fun `a merge reports how many neighbours came first`() =
        assertEquals(SendOutcome.MergedMeToo(3), SendOutcomes.from(ok(merged = true, meToo = 3)))

    @Test fun `a merge never claims fewer than the one original reporter`() =
        assertEquals(SendOutcome.MergedMeToo(1), SendOutcomes.from(ok(merged = true, meToo = 0)))

    // --- Everything else ------------------------------------------------------------------

    @Test fun `daily limit is RateLimited`() =
        assertEquals(SendOutcome.RateLimited, SendOutcomes.from(KantaResult.Failure(KantaError.DailyReportLimit)))

    @Test fun `offline is queued`() =
        assertEquals(SendOutcome.Queued, SendOutcomes.from(KantaResult.Failure(KantaError.Offline)))

    @Test fun `a server timeout is queued too`() =
        assertEquals(SendOutcome.Queued, SendOutcomes.from(KantaResult.Failure(KantaError.ServerUnavailable)))

    @Test fun `anything else is a plain failure carrying the error`() =
        assertEquals(
            SendOutcome.Failed(KantaError.ContainerNotFound),
            SendOutcomes.from(KantaResult.Failure(KantaError.ContainerNotFound)),
        )

    @Test fun `only the endings are final`() {
        assertTrue(SendOutcome.Sent.isFinal)
        assertTrue(SendOutcome.MergedMeToo(2).isFinal)
        assertTrue(SendOutcome.Queued.isFinal)
        assertTrue(SendOutcome.RequestSent.isFinal)
        assertFalse(SendOutcome.TooFar(80).isFinal)
        assertFalse(SendOutcome.RateLimited.isFinal)
        assertFalse(SendOutcome.Failed(KantaError.Offline).isFinal)
    }
}
