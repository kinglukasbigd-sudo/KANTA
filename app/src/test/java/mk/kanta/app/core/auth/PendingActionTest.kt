package mk.kanta.app.core.auth

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pending intent is written to disk so it survives the app being killed while
 * the user reads their email (§4.2 "never lose their intent"). Every variant must
 * round-trip exactly, or a user would sign in and land somewhere else.
 */
class PendingActionTest {

    // Same configuration AuthGate uses.
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    private fun roundTrip(action: PendingAction): PendingAction {
        val encoded = json.encodeToString(StoredPendingAction.serializer(), StoredPendingAction(action, 1_000))
        return json.decodeFromString(StoredPendingAction.serializer(), encoded).action
    }

    @Test fun `full report survives`() =
        assertEquals(PendingAction.OpenReport(presetFull = true), roundTrip(PendingAction.OpenReport(true)))

    @Test fun `other report survives`() =
        assertEquals(PendingAction.OpenReport(presetFull = false), roundTrip(PendingAction.OpenReport(false)))

    @Test fun `suggest survives`() = assertEquals(PendingAction.OpenSuggest, roundTrip(PendingAction.OpenSuggest))

    @Test fun `me too survives with every id`() {
        val action = PendingAction.ConfirmReport(containerId = "c-1", reportId = "r-9", kind = "me_too")
        assertEquals(action, roundTrip(action))
    }

    @Test fun `emptied survives`() {
        val action = PendingAction.ConfirmReport(containerId = "c-1", reportId = "r-9", kind = "resolved")
        assertEquals(action, roundTrip(action))
    }

    @Test fun `vote survives`() = assertEquals(PendingAction.Vote("s-3"), roundTrip(PendingAction.Vote("s-3")))

    @Test fun `stored json is stable and readable`() {
        val encoded = json.encodeToString(
            StoredPendingAction.serializer(),
            StoredPendingAction(PendingAction.Vote("s-3"), 42),
        )
        // The discriminator names are the on-disk contract; renaming a Kotlin class
        // must not orphan an intent stored by the previous app version.
        assertEquals("""{"action":{"type":"vote","suggestionId":"s-3"},"requestedAtMillis":42}""", encoded)
    }
}
