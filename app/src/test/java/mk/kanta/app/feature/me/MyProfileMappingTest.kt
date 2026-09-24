package mk.kanta.app.feature.me

import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.remote.dto.MyReportDto
import mk.kanta.app.core.data.remote.dto.MySuggestionDto
import mk.kanta.app.core.location.LatLon
import mk.kanta.app.core.util.hoursBetween
import mk.kanta.app.core.util.isoToMillis
import mk.kanta.app.feature.suggest.SuggestReason
import mk.kanta.app.feature.suggest.SuggestionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §4.5 screen 11: what the server's rows become on the profile. */
class MyProfileMappingTest {

    private fun dto(state: String, resolvedAt: String? = null, resolvedPhoto: String? = null) = MyReportDto(
        reportId = "r-1", containerId = "c-1", containerCode = "SK-00023", containerKind = "small",
        containerLon = 21.4076, containerLat = 42.0016, municipalityId = 2,
        kind = "full", state = state, photoPath = "reports/u/r-1.jpg",
        createdAt = "2026-09-20T08:00:00+00:00", resolvedAt = resolvedAt, meTooCount = 2,
        resolvedPhotoPath = resolvedPhoto,
    )

    @Test fun `an open report keeps its start time for the live timer`() {
        val ui = dto("open").toUi()
        assertEquals(MyReportState.Open, ui.state)
        assertEquals(isoToMillis("2026-09-20T08:00:00+00:00"), ui.createdAtMillis)
        assertNull(ui.resolvedAtMillis)
    }

    @Test fun `a resolved report knows how long it took, and has its after photo`() {
        val ui = dto("resolved", resolvedAt = "2026-09-21T15:00:00+00:00", resolvedPhoto = "reports/v/after.jpg").toUi()
        assertEquals(MyReportState.Resolved, ui.state)
        assertEquals(31.0, hoursBetween(ui.createdAtMillis!!, ui.resolvedAtMillis!!), 0.01)
        assertEquals("reports/v/after.jpg", ui.resolvedPhotoPath)
    }

    @Test fun `resolved on neighbours' word has no after photo`() =
        assertNull(dto("resolved", resolvedAt = "2026-09-21T15:00:00+00:00", resolvedPhoto = "").toUi().resolvedPhotoPath)

    @Test fun `expired is its own state`() = assertEquals(MyReportState.Expired, dto("expired").toUi().state)

    @Test fun `tap goes to the container's real position`() {
        val ui = dto("open").toUi()
        assertEquals(LatLon(42.0016, 21.4076), ui.position)
        assertEquals(ContainerKind.SMALL, ui.containerKind)
        assertEquals(2, ui.meToo)
    }

    @Test fun `authored and voted suggestions are told apart`() {
        val mine = MySuggestionDto("s-1", 21.4, 42.0, "always_full", 3, "open", 1, "2026-09-20T08:00:00Z", iAuthored = true).toUi()
        val voted = MySuggestionDto("s-2", 21.4, 42.0, "new_building", 5, "sent", null, "2026-09-20T08:00:00Z", iAuthored = false).toUi()
        assertTrue(mine.authored); assertEquals(SuggestReason.AlwaysFull, mine.reason)
        assertFalse(voted.authored); assertEquals(SuggestionState.Sent, voted.state)
    }

    @Test fun `an impact of nothing yet is recognised`() {
        assertTrue(ImpactUi(0, 0, 0).isEmpty)
        assertFalse(ImpactUi(1, 0, 0).isEmpty)
    }

    @Test fun `a malformed timestamp is null, not a crash`() = assertNull(isoToMillis("yesterday"))
}
