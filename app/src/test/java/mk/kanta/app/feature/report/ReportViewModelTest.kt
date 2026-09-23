package mk.kanta.app.feature.report

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mk.kanta.app.core.data.model.ContainerCategory
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.model.ContainerStatus
import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.data.remote.KantaResult
import mk.kanta.app.core.data.remote.dto.AddAllowanceDto
import mk.kanta.app.core.data.remote.dto.AddContainerResultDto
import mk.kanta.app.core.data.remote.dto.ContainerRequestResultDto
import mk.kanta.app.core.data.remote.dto.SubmitReportResultDto
import mk.kanta.app.core.data.report.ContainerCandidate
import mk.kanta.app.core.data.report.ContainerDraft
import mk.kanta.app.core.data.report.ReportDraft
import mk.kanta.app.core.data.report.ReportGateway
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

/**
 * The report flow's state machine (§4.3), driven end to end against fakes:
 * camera → processing → compose → send, and every way a send can end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var gateway: FakeGateway
    private lateinit var processor: FakeProcessor

    private val here = LatLon(41.9965, 21.4314)
    private val sk412 = candidate("c-412", "SK-00412", distance = 12.0)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        gateway = FakeGateway()
        processor = FakeProcessor()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(full: Boolean) =
        ReportViewModel(SavedStateHandle(mapOf("presetFull" to full)), gateway, processor)

    private fun rawFile() = File.createTempFile("raw", ".jpg")

    // --- Capture → compose ------------------------------------------------------------

    @Test fun `capturing snaps the nearest container and opens compose`() = runTest(dispatcher) {
        gateway.nearest = KantaResult.Success(sk412)
        val vm = viewModel(full = false)

        vm.onPhotoCaptured(rawFile())
        assertEquals(ReportStage.Processing, vm.state.value.stage)
        advanceUntilIdle()

        with(vm.state.value) {
            assertEquals(ReportStage.Compose, stage)
            assertEquals("SK-00412", container?.code)
            assertEquals(here, device)
        }
    }

    @Test fun `offline snap falls back to the cached containers around`() = runTest(dispatcher) {
        gateway.nearest = KantaResult.Failure(KantaError.Offline)
        gateway.around = listOf(sk412)
        val vm = viewModel(full = false)

        vm.onPhotoCaptured(rawFile()); advanceUntilIdle()

        assertEquals("SK-00412", vm.state.value.container?.code)
    }

    @Test fun `a failed face check sends the user back to retake, never onward`() = runTest(dispatcher) {
        processor.fail = true
        val vm = viewModel(full = false)

        vm.onPhotoCaptured(rawFile()); advanceUntilIdle()

        with(vm.state.value) {
            assertEquals(ReportStage.Camera, stage)
            assertTrue(photoRejected)
            assertNull(photo)
        }
    }

    @Test fun `no location fix means nothing can be sent`() = runTest(dispatcher) {
        gateway.location = null
        val vm = viewModel(full = true)

        vm.onPhotoCaptured(rawFile()); advanceUntilIdle()

        assertTrue(vm.state.value.locationUnavailable)
        assertFalse(vm.state.value.canSend)
    }

    // --- Kind -----------------------------------------------------------------------------

    @Test fun `Full is preset and needs no chip`() = runTest(dispatcher) {
        gateway.nearest = KantaResult.Success(sk412)
        val vm = viewModel(full = true)
        vm.onPhotoCaptured(rawFile()); advanceUntilIdle()

        assertEquals(ReportKind.Full, vm.state.value.kind)
        assertTrue(vm.state.value.canSend)

        vm.selectKind(ReportKind.Burning) // §4.3: "the status is already set"
        assertEquals(ReportKind.Full, vm.state.value.kind)
    }

    @Test fun `Report needs a chip before it can send`() = runTest(dispatcher) {
        gateway.nearest = KantaResult.Success(sk412)
        val vm = viewModel(full = false)
        vm.onPhotoCaptured(rawFile()); advanceUntilIdle()

        assertFalse(vm.state.value.canSend)
        vm.selectKind(ReportKind.Damaged)
        assertTrue(vm.state.value.canSend)
    }

    @Test fun `the note is capped at 280`() {
        val vm = viewModel(full = false)
        vm.onNoteChange("x".repeat(400))
        assertEquals(280, vm.state.value.note.length)
    }

    // --- Send -------------------------------------------------------------------------

    @Test fun `a new Full report ends in success and goes on to the alternatives`() = runTest(dispatcher) {
        gateway.nearest = KantaResult.Success(sk412)
        gateway.submitResult = KantaResult.Success(SubmitReportResultDto("r-1", false, 0, "ok"))
        val vm = viewModel(full = true)
        vm.onPhotoCaptured(rawFile()); advanceUntilIdle()

        vm.send(); advanceUntilIdle()

        with(vm.state.value) {
            assertEquals(ReportStage.Done, stage)
            assertEquals(SendOutcome.Sent, outcome)
            assertTrue(goToAlternatives)
        }
        assertEquals("full", gateway.submitted.single().kind)
        assertEquals(here, gateway.submitted.single().device)
    }

    @Test fun `a merge says how many neighbours were first`() = runTest(dispatcher) {
        gateway.nearest = KantaResult.Success(sk412)
        gateway.submitResult = KantaResult.Success(SubmitReportResultDto("r-1", true, 2, "full"))
        val vm = viewModel(full = false)
        vm.onPhotoCaptured(rawFile()); advanceUntilIdle()
        vm.selectKind(ReportKind.Damaged)

        vm.send(); advanceUntilIdle()

        assertEquals(SendOutcome.MergedMeToo(2), vm.state.value.outcome)
        assertFalse(vm.state.value.goToAlternatives) // only Full goes on
    }

    @Test fun `too far by the server keeps the user in compose with the distance`() = runTest(dispatcher) {
        gateway.nearest = KantaResult.Success(sk412)
        gateway.submitResult = KantaResult.Failure(KantaError.TooFarFromContainer(71))
        val vm = viewModel(full = true)
        vm.onPhotoCaptured(rawFile()); advanceUntilIdle()

        vm.send(); advanceUntilIdle()

        assertEquals(ReportStage.Compose, vm.state.value.stage)
        assertEquals(SendOutcome.TooFar(71), vm.state.value.outcome)
    }

    @Test fun `too far by the local check never uploads anything`() = runTest(dispatcher) {
        gateway.nearest = KantaResult.Success(sk412)
        gateway.around = listOf(candidate("c-9", "SK-00009", distance = 95.0))
        val vm = viewModel(full = true)
        vm.onPhotoCaptured(rawFile()); advanceUntilIdle()
        vm.openPicker(); advanceUntilIdle()
        vm.pickContainer(gateway.around.single())

        vm.send(); advanceUntilIdle()

        assertEquals(SendOutcome.TooFar(95), vm.state.value.outcome)
        assertTrue(gateway.submitted.isEmpty())
    }

    @Test fun `offline queues the report and ends the flow`() = runTest(dispatcher) {
        gateway.nearest = KantaResult.Success(sk412)
        gateway.submitResult = KantaResult.Failure(KantaError.Offline)
        val vm = viewModel(full = true)
        vm.onPhotoCaptured(rawFile()); advanceUntilIdle()

        vm.send(); advanceUntilIdle()

        with(vm.state.value) {
            assertEquals(ReportStage.Done, stage)
            assertEquals(SendOutcome.Queued, outcome)
            // Offline there are no alternatives to fetch.
            assertFalse(goToAlternatives)
        }
        assertEquals(1, gateway.queued.size)
        assertEquals("c-412", gateway.queued.single().containerId)
    }

    @Test fun `the daily limit keeps the user in compose`() = runTest(dispatcher) {
        gateway.nearest = KantaResult.Success(sk412)
        gateway.submitResult = KantaResult.Failure(KantaError.DailyReportLimit)
        val vm = viewModel(full = true)
        vm.onPhotoCaptured(rawFile()); advanceUntilIdle()

        vm.send(); advanceUntilIdle()

        assertEquals(ReportStage.Compose, vm.state.value.stage)
        assertEquals(SendOutcome.RateLimited, vm.state.value.outcome)
        assertTrue(gateway.queued.isEmpty())
    }

    // --- Add container (§4.6) -----------------------------------------------------------

    @Test fun `allowance used up offers review, which ends the flow`() = runTest(dispatcher) {
        gateway.nearest = KantaResult.Success(null)
        gateway.allowance = KantaResult.Success(AddAllowanceDto(isAdmin = false, containersAdded = 2, remaining = 0))
        val vm = viewModel(full = true)
        vm.onPhotoCaptured(rawFile()); advanceUntilIdle()

        vm.openAddContainer(); advanceUntilIdle()
        assertTrue(vm.state.value.add!!.limitReached)

        vm.addKind(ContainerKind.BIG)
        vm.sendForReview(); advanceUntilIdle()

        assertEquals(ReportStage.Done, vm.state.value.stage)
        assertEquals(SendOutcome.RequestSent, vm.state.value.outcome)
        assertTrue(gateway.added.isEmpty())
    }

    @Test fun `admins are never limited`() = runTest(dispatcher) {
        gateway.allowance = KantaResult.Success(AddAllowanceDto(isAdmin = true, containersAdded = 40, remaining = 999))
        val vm = viewModel(full = true)
        vm.onPhotoCaptured(rawFile()); advanceUntilIdle()

        vm.openAddContainer(); advanceUntilIdle()

        assertFalse(vm.state.value.add!!.limitReached)
    }

    @Test fun `adding a container selects it for the report`() = runTest(dispatcher) {
        gateway.nearest = KantaResult.Success(null)
        val vm = viewModel(full = true)
        vm.onPhotoCaptured(rawFile()); advanceUntilIdle()

        vm.openAddContainer(); advanceUntilIdle()
        vm.addKind(ContainerKind.BIG)
        vm.addCategory(ContainerCategory.GLASS)
        vm.submitAdd(); advanceUntilIdle()

        with(vm.state.value) {
            assertNull(add)
            assertEquals("SK-00500", container?.code)
            assertTrue(canSend)
        }
        assertEquals(ContainerCategory.GLASS, gateway.added.single().category)
    }

    @Test fun `a duplicate asks, and "it's different" retries with the flag`() = runTest(dispatcher) {
        gateway.nearest = KantaResult.Success(null)
        gateway.around = listOf(sk412)
        gateway.addResults += KantaResult.Failure(KantaError.DuplicateContainer("c-412"))
        val vm = viewModel(full = true)
        vm.onPhotoCaptured(rawFile()); advanceUntilIdle()
        vm.openAddContainer(); advanceUntilIdle()
        vm.addKind(ContainerKind.BIG)

        vm.submitAdd(); advanceUntilIdle()
        assertEquals("SK-00412", vm.state.value.add?.duplicateOf?.code)

        vm.submitAdd(confirmDifferent = true); advanceUntilIdle()
        assertEquals(listOf(false, true), gateway.added.map { it.confirmDifferent })
        assertEquals("SK-00500", vm.state.value.container?.code)
    }

    @Test fun `a duplicate can be used instead of adding`() = runTest(dispatcher) {
        gateway.nearest = KantaResult.Success(null)
        gateway.around = listOf(sk412)
        gateway.addResults += KantaResult.Failure(KantaError.DuplicateContainer("c-412"))
        val vm = viewModel(full = true)
        vm.onPhotoCaptured(rawFile()); advanceUntilIdle()
        vm.openAddContainer(); advanceUntilIdle()
        vm.addKind(ContainerKind.BIG)
        vm.submitAdd(); advanceUntilIdle()

        vm.useDuplicate()

        assertNull(vm.state.value.add)
        assertEquals("SK-00412", vm.state.value.container?.code)
    }

    @Test fun `choosing a small can clears a recycling category`() = runTest(dispatcher) {
        val vm = viewModel(full = true)
        vm.onPhotoCaptured(rawFile()); advanceUntilIdle()
        vm.openAddContainer(); advanceUntilIdle()
        vm.addKind(ContainerKind.BIG)
        vm.addCategory(ContainerCategory.PAPER)

        vm.addKind(ContainerKind.SMALL)

        assertEquals(ContainerCategory.GENERAL, vm.state.value.add!!.category)
    }

    // ---------------------------------------------------------------------------------------

    private fun candidate(id: String, code: String, distance: Double) = ContainerCandidate(
        id = id,
        code = code,
        kind = ContainerKind.BIG,
        category = ContainerCategory.GENERAL,
        status = ContainerStatus.OK,
        verified = true,
        position = here,
        distanceMetres = distance,
    )

    private inner class FakeGateway : ReportGateway {
        var location: LatLon? = here
        var nearest: KantaResult<ContainerCandidate?> = KantaResult.Success(null)
        var around: List<ContainerCandidate> = emptyList()
        var submitResult: KantaResult<SubmitReportResultDto> =
            KantaResult.Success(SubmitReportResultDto("r-1", false, 0, "ok"))
        var allowance: KantaResult<AddAllowanceDto> =
            KantaResult.Success(AddAllowanceDto(isAdmin = false, containersAdded = 0, remaining = 2))
        val addResults = ArrayDeque<KantaResult<AddContainerResultDto>>()

        val submitted = mutableListOf<ReportDraft>()
        val queued = mutableListOf<ReportDraft>()
        val added = mutableListOf<ContainerDraft>()

        override suspend fun currentLocation() = location
        override suspend fun nearestContainer(at: LatLon) = nearest
        override suspend fun containersAround(at: LatLon, radiusMetres: Double) = around
        override suspend fun submit(draft: ReportDraft) = submitResult.also { submitted += draft }
        override suspend fun enqueue(draft: ReportDraft) { queued += draft }
        override suspend fun addAllowance() = allowance
        override suspend fun addContainer(draft: ContainerDraft): KantaResult<AddContainerResultDto> {
            added += draft
            return addResults.removeFirstOrNull()
                ?: KantaResult.Success(AddContainerResultDto("c-500", "SK-00500", verified = false, remaining = 1))
        }
        override suspend fun requestContainer(draft: ContainerDraft, note: String?) =
            KantaResult.Success(ContainerRequestResultDto("req-1", remainingToday = 2))
    }

    private class FakeProcessor : PhotoProcessor {
        var fail = false
        override suspend fun process(raw: File): ProcessedPhoto {
            if (fail) throw PhotoPrivacyException("face check failed")
            return ProcessedPhoto(File.createTempFile("processed", ".jpg"), 1600, 1200, 180_000, facesBlurred = 1)
        }
    }
}
