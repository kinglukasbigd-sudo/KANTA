package mk.kanta.app.feature.suggest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.data.remote.KantaResult
import mk.kanta.app.core.data.remote.dto.NearbySuggestionDto
import mk.kanta.app.core.data.remote.dto.SubmitSuggestionResultDto
import mk.kanta.app.core.data.report.ContainerCandidate
import mk.kanta.app.core.data.suggest.SuggestGateway
import mk.kanta.app.core.data.suggest.SuggestionPin
import mk.kanta.app.core.data.suggest.SuggestionVotes
import mk.kanta.app.core.data.suggest.VoteOverlay
import mk.kanta.app.core.image.PhotoPrivacyException
import mk.kanta.app.core.image.PhotoProcessor
import mk.kanta.app.core.image.ProcessedPhoto
import mk.kanta.app.core.location.LatLon
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The Suggest flow (§4.4) against fakes: pick → nearby? → details → send. */
@OptIn(ExperimentalCoroutinesApi::class)
class SuggestViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val here = LatLon(41.9965, 21.4314)
    private lateinit var gateway: FakeGateway
    private lateinit var votes: FakeVotes
    private lateinit var processor: FakeProcessor

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        gateway = FakeGateway()
        votes = FakeVotes()
        processor = FakeProcessor()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = SuggestViewModel(gateway, processor, votes)

    @Test fun `the pick map opens where the user is, pin in the middle`() = runTest(dispatcher) {
        val vm = viewModel(); advanceUntilIdle()
        assertEquals(here, vm.state.value.start)
        assertEquals(here, vm.state.value.pin)
        assertEquals(SuggestStage.Pick, vm.state.value.stage)
    }

    @Test fun `without a location it opens on Skopje centre`() = runTest(dispatcher) {
        gateway.location = null
        val vm = viewModel(); advanceUntilIdle()
        assertEquals(LatLon.SKOPJE_CENTRE, vm.state.value.start)
    }

    @Test fun `a free spot goes straight to the details`() = runTest(dispatcher) {
        val vm = viewModel(); advanceUntilIdle()
        vm.placeHere(); advanceUntilIdle()
        assertEquals(SuggestStage.Details, vm.state.value.stage)
        assertNull(vm.state.value.nearby)
    }

    @Test fun `an open suggestion within 50 m is offered first`() = runTest(dispatcher) {
        gateway.nearby = KantaResult.Success(nearbyDto(votes = 4, iVoted = false))
        val vm = viewModel(); advanceUntilIdle()
        vm.placeHere(); advanceUntilIdle()

        with(vm.state.value) {
            assertEquals(SuggestStage.Nearby, stage)
            assertEquals(SuggestReason.AlwaysFull, nearby?.reason)
            assertEquals(23, nearby?.distanceMetres)
        }
    }

    @Test fun `vote for this one instead is one tap and ends the flow`() = runTest(dispatcher) {
        gateway.nearby = KantaResult.Success(nearbyDto(votes = 4, iVoted = false))
        val vm = viewModel(); advanceUntilIdle()
        vm.placeHere(); advanceUntilIdle()

        vm.voteInstead()

        assertEquals(listOf("s-1" to 4), votes.voted)
        assertEquals(SuggestOutcome.Voted(5), vm.state.value.outcome)
        assertEquals(SuggestStage.Done, vm.state.value.stage)
        assertTrue(gateway.submitted.isEmpty())
    }

    @Test fun `already voted there means nothing is sent twice`() = runTest(dispatcher) {
        gateway.nearby = KantaResult.Success(nearbyDto(votes = 4, iVoted = true))
        val vm = viewModel(); advanceUntilIdle()
        vm.placeHere(); advanceUntilIdle()

        vm.voteInstead()

        assertTrue(votes.voted.isEmpty())
        assertEquals(SuggestOutcome.AlreadyVoted, vm.state.value.outcome)
    }

    @Test fun `offline check still lets the user carry on`() = runTest(dispatcher) {
        gateway.nearby = KantaResult.Failure(KantaError.Offline)
        val vm = viewModel(); advanceUntilIdle()
        vm.placeHere(); advanceUntilIdle()
        assertEquals(SuggestStage.Details, vm.state.value.stage)
    }

    @Test fun `a reason is needed before sending`() = runTest(dispatcher) {
        val vm = viewModel(); advanceUntilIdle()
        vm.placeHere(); advanceUntilIdle()
        assertFalse(vm.state.value.canSend)
        vm.selectReason(SuggestReason.NoContainer)
        assertTrue(vm.state.value.canSend)
    }

    @Test fun `sending uses the dragged spot, not the phone`() = runTest(dispatcher) {
        val vm = viewModel(); advanceUntilIdle()
        val spot = LatLon(here.lat + 0.001, here.lon)
        vm.movePin(spot)
        vm.placeHere(); advanceUntilIdle()
        vm.selectReason(SuggestReason.NewBuilding)
        vm.onNoteChange("  new block on the corner  ")

        vm.send(); advanceUntilIdle()

        val sent = gateway.submitted.single()
        assertEquals(spot, sent.at)
        assertEquals("new_building", sent.reason)
        assertEquals("new block on the corner", sent.note)
        assertNull(sent.photoPath)
        assertEquals(SuggestOutcome.Sent(1), vm.state.value.outcome)
        assertEquals(listOf(spot), votes.suggested)
    }

    @Test fun `a photo is uploaded first and its path sent`() = runTest(dispatcher) {
        val vm = viewModel(); advanceUntilIdle()
        vm.placeHere(); advanceUntilIdle()
        vm.selectReason(SuggestReason.Dumping)
        vm.openCamera()
        vm.onPhotoCaptured(File.createTempFile("raw", ".jpg")); advanceUntilIdle()
        assertEquals(SuggestStage.Details, vm.state.value.stage)

        vm.send(); advanceUntilIdle()

        assertEquals("suggestions/u/x.jpg", gateway.submitted.single().photoPath)
    }

    @Test fun `a photo whose faces could not be checked is refused`() = runTest(dispatcher) {
        processor.fail = true
        val vm = viewModel(); advanceUntilIdle()
        vm.placeHere(); advanceUntilIdle()
        vm.openCamera()

        vm.onPhotoCaptured(File.createTempFile("raw", ".jpg")); advanceUntilIdle()

        assertEquals(SuggestStage.Camera, vm.state.value.stage)
        assertTrue(vm.state.value.photoRejected)
        assertNull(vm.state.value.photo)
    }

    @Test fun `a failed upload sends nothing and says why`() = runTest(dispatcher) {
        gateway.upload = KantaResult.Failure(KantaError.Offline)
        val vm = viewModel(); advanceUntilIdle()
        vm.placeHere(); advanceUntilIdle()
        vm.selectReason(SuggestReason.Dumping)
        vm.openCamera()
        vm.onPhotoCaptured(File.createTempFile("raw", ".jpg")); advanceUntilIdle()

        vm.send(); advanceUntilIdle()

        assertTrue(gateway.submitted.isEmpty())
        assertEquals(SuggestStage.Details, vm.state.value.stage)
        assertEquals(KantaError.Offline, vm.state.value.error)
    }

    @Test fun `a suggestion that raced into a merge says so`() = runTest(dispatcher) {
        gateway.submitResult = KantaResult.Success(SubmitSuggestionResultDto("s-9", merged = true, votes = 3))
        val vm = viewModel(); advanceUntilIdle()
        vm.placeHere(); advanceUntilIdle()
        vm.selectReason(SuggestReason.AlwaysFull)

        vm.send(); advanceUntilIdle()

        assertEquals(SuggestOutcome.Merged(3), vm.state.value.outcome)
    }

    @Test fun `an optimistic vote shows at once and the server's count wins`() {
        val card = SuggestionUi(
            id = "s-1", position = here, reason = SuggestReason.NoContainer, note = null, photoPath = null,
            votes = 4, state = SuggestionState.Open, municipalityId = 1, createdAt = "", iVoted = false,
        )
        val optimistic = card.with(VoteOverlay(voted = true, votes = 5, pending = true))
        assertTrue(optimistic.iVoted); assertTrue(optimistic.voting); assertEquals(5, optimistic.votes)
        assertFalse(optimistic.canVote)

        val confirmed = card.with(VoteOverlay(voted = true, votes = 7, pending = false))
        assertEquals(7, confirmed.votes); assertFalse(confirmed.voting)
    }

    @Test fun `sent and placed suggestions take no votes`() {
        val sent = SuggestionUi(
            id = "s-1", position = here, reason = null, note = null, photoPath = null,
            votes = 4, state = SuggestionState.Sent, municipalityId = null, createdAt = "", iVoted = false,
        )
        assertFalse(sent.canVote)
    }

    // ---------------------------------------------------------------------------------------

    private fun nearbyDto(votes: Int, iVoted: Boolean) = NearbySuggestionDto(
        id = "s-1", lon = here.lon, lat = here.lat, reason = "always_full", note = null,
        votes = votes, distanceM = 23.4, createdAt = "2026-09-24T10:00:00Z", iVoted = iVoted,
    )

    private data class Submitted(val at: LatLon, val reason: String, val note: String?, val photoPath: String?)

    private inner class FakeGateway : SuggestGateway {
        var location: LatLon? = here
        var nearby: KantaResult<NearbySuggestionDto?> = KantaResult.Success(null)
        var upload: KantaResult<String> = KantaResult.Success("suggestions/u/x.jpg")
        var submitResult: KantaResult<SubmitSuggestionResultDto> =
            KantaResult.Success(SubmitSuggestionResultDto("s-new", merged = false, votes = 1))
        val submitted = mutableListOf<Submitted>()

        override suspend fun currentLocation() = location
        override suspend fun containersAround(at: LatLon, radiusMetres: Double) = emptyList<ContainerCandidate>()
        override suspend fun openSuggestionPins() = emptyList<SuggestionPin>()
        override suspend fun openSuggestionNear(at: LatLon) = nearby
        override suspend fun uploadPhoto(id: String, photo: File) = upload
        override suspend fun submit(at: LatLon, reason: String, note: String?, photoPath: String?) =
            submitResult.also { submitted += Submitted(at, reason, note, photoPath) }
    }

    private class FakeVotes : SuggestionVotes {
        val voted = mutableListOf<Pair<String, Int>>()
        val suggested = mutableListOf<LatLon>()
        override fun vote(suggestionId: String, currentVotes: Int) { voted += suggestionId to currentVotes }
        override fun notifyChanged() = Unit
        override fun notifySuggested(at: LatLon) { suggested += at }
    }

    private class FakeProcessor : PhotoProcessor {
        var fail = false
        override suspend fun process(raw: File): ProcessedPhoto {
            if (fail) throw PhotoPrivacyException("face check failed")
            return ProcessedPhoto(File.createTempFile("processed", ".jpg"), 1600, 1200, 180_000, facesBlurred = 0)
        }
    }
}
