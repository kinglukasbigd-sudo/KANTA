package mk.kanta.app.feature.report

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mk.kanta.app.core.data.model.ContainerCategory
import mk.kanta.app.core.data.model.ContainerKind
import mk.kanta.app.core.data.model.ContainerStatus
import mk.kanta.app.core.data.remote.KantaError
import mk.kanta.app.core.data.remote.KantaResult
import mk.kanta.app.core.data.report.ContainerCandidate
import mk.kanta.app.core.data.report.ContainerDraft
import mk.kanta.app.core.data.report.ReportDraft
import mk.kanta.app.core.data.report.ReportGateway
import mk.kanta.app.core.image.PhotoProcessor
import mk.kanta.app.core.image.ProcessedPhoto
import mk.kanta.app.core.location.GeoMath
import mk.kanta.app.core.location.LatLon
import java.io.File
import java.util.UUID
import javax.inject.Inject

enum class ReportStage {
    Camera,
    Processing,
    Compose,

    /** §4.6 "One is missing": the photo is taken and the Add container sheet is up. */
    Adding,
    Sending,
    Done,
}

/**
 * What the flow was opened for. [Report] is §4.3; [AddContainer] is §4.6's
 * "One is missing" — the same camera and privacy processing, then straight into
 * the Add container flow with no report at the end.
 */
enum class ReportMode(val wire: String) {
    Report("report"),
    AddContainer("add"),
    ;

    companion object {
        fun from(wire: String?) = entries.firstOrNull { it.wire == wire } ?: Report
    }
}

/** The report kinds a user can pick (§4.3). `full` is preset by the Full tile, never a chip. */
enum class ReportKind(val wire: String) {
    Full("full"),
    Damaged("damaged"),
    Destroyed("destroyed"),
    Burning("burning"),
    Missing("missing"),
    DumpedAround("dumped_around"),
    ;

    companion object {
        /** The chip row, in the order §4.3 lists them. */
        val chips = listOf(Damaged, Destroyed, Burning, Missing, DumpedAround)
    }
}

data class AddContainerUiState(
    val loading: Boolean = true,
    /** Where the phone is — the server measures the pin against this (§4.6: 30 m). */
    val device: LatLon? = null,
    /** §4.6: "user drags the pin to the exact spot". Starts where the phone is. */
    val pin: LatLon? = null,
    /** Containers already around, drawn under the pin so a duplicate is visible before it's refused. */
    val nearby: List<ContainerCandidate> = emptyList(),
    val remaining: Int? = null,
    val isAdmin: Boolean = false,
    /** §4.6: allowance used up — offer "Send for review" instead. */
    val limitReached: Boolean = false,
    val kind: ContainerKind? = null,
    val category: ContainerCategory = ContainerCategory.GENERAL,
    /** §4.6: "Is it this one?" — a same-kind container already stands here. */
    val duplicateOf: ContainerCandidate? = null,
    val submitting: Boolean = false,
    val error: KantaError? = null,
) {
    val canSubmit: Boolean get() = kind != null && pin != null && !submitting && !limitReached

    /** Shown under the map ("Pin is 8 m from you") — information only; the server decides. */
    val pinDistanceMetres: Double?
        get() = if (device != null && pin != null) GeoMath.distanceMetres(device, pin) else null
}

data class ReportUiState(
    val stage: ReportStage = ReportStage.Camera,
    val mode: ReportMode = ReportMode.Report,
    val presetFull: Boolean = false,
    /** Opened from the §4.6 area check: its answer is recorded when this flow succeeds. */
    val fromAreaCheck: Boolean = false,
    val photo: ProcessedPhoto? = null,
    /** Face check failed: the photo is refused rather than published unblurred (§8). */
    val photoRejected: Boolean = false,
    val device: LatLon? = null,
    val locationUnavailable: Boolean = false,
    val snapping: Boolean = false,
    val container: ContainerCandidate? = null,
    val kind: ReportKind? = null,
    val note: String = "",
    val pickerOpen: Boolean = false,
    val candidates: List<ContainerCandidate> = emptyList(),
    val add: AddContainerUiState? = null,
    val outcome: SendOutcome? = null,
) {
    val noteLength: Int get() = note.length

    val canSend: Boolean
        get() = stage == ReportStage.Compose &&
            photo != null && container != null && kind != null && device != null

    /** §4.3: for Full, success goes straight on to "Nearest containers with space". */
    val goToAlternatives: Boolean
        get() = stage == ReportStage.Done && presetFull &&
            (outcome is SendOutcome.Sent || outcome is SendOutcome.MergedMeToo)
}

/**
 * The report flow (spec §4.3): camera → processing → compose → send.
 *
 * Every outside dependency sits behind [ReportGateway] and [PhotoProcessor], so the
 * whole state machine is unit tested against fakes.
 */
@HiltViewModel
class ReportViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val gateway: ReportGateway,
    private val processor: PhotoProcessor,
) : ViewModel() {

    private val presetFull: Boolean = savedState.get<Boolean>("presetFull") ?: false
    private val mode: ReportMode = ReportMode.from(savedState.get<String>("mode"))

    /** §4.6 "One on the map is not here": the marker the user tapped. */
    private val presetContainerId: String? = savedState.get<String>("containerId")

    private val _state = MutableStateFlow(
        ReportUiState(
            mode = mode,
            presetFull = presetFull,
            fromAreaCheck = savedState.get<Boolean>("fromAreaCheck") ?: false,
            kind = when {
                presetFull -> ReportKind.Full
                // §4.6: "with kind 'missing' preselected". Still a chip the user can change.
                else -> ReportKind.entries.firstOrNull { it.wire == savedState.get<String>("presetKind") }
            },
        ),
    )
    val state: StateFlow<ReportUiState> = _state.asStateFlow()

    /** One id per photo: its storage name, so a retried upload overwrites rather than duplicates. */
    private var draftId: String = UUID.randomUUID().toString()

    // -----------------------------------------------------------------------------------------
    // Camera → processing
    // -----------------------------------------------------------------------------------------

    /**
     * Processing and locating run side by side: by the time the faces are blurred
     * the container is usually already snapped, so the compose sheet opens
     * complete rather than filling in piece by piece.
     */
    fun onPhotoCaptured(raw: File) {
        draftId = UUID.randomUUID().toString()
        _state.update { it.copy(stage = ReportStage.Processing, photoRejected = false, outcome = null) }

        viewModelScope.launch {
            val located = async { locateAndSnap() }
            val processed = runCatching { processor.process(raw) }

            processed.onSuccess { photo ->
                _state.update { it.copy(photo = photo) }
                located.await()
                if (mode == ReportMode.AddContainer) {
                    _state.update { it.copy(stage = ReportStage.Adding) }
                    openAddContainer()
                } else {
                    _state.update { it.copy(stage = ReportStage.Compose) }
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                raw.delete()
                located.cancel()
                // Any processing failure — an unreadable file or a face check that
                // could not run (PhotoPrivacyException) — rejects the photo. It is
                // never published unprocessed; the user retakes it.
                _state.update { it.copy(stage = ReportStage.Camera, photoRejected = true) }
            }
        }
    }

    fun retake() {
        _state.value.photo?.file?.delete()
        _state.update {
            it.copy(stage = ReportStage.Camera, photo = null, outcome = null, photoRejected = false, add = null)
        }
    }

    private suspend fun locateAndSnap() {
        _state.update { it.copy(snapping = true) }
        val device = gateway.currentLocation()
        if (device == null) {
            // The server measures the phone against the container (§4.3: 60 m),
            // so without a fix there is nothing it could accept.
            _state.update { it.copy(snapping = false, locationUnavailable = true) }
            return
        }
        _state.update { it.copy(device = device, locationUnavailable = false) }

        // §4.6: the user already pointed at the container on the map.
        presetContainerId?.let { id ->
            gateway.containerById(id, device)?.let { chosen ->
                _state.update { it.copy(snapping = false, container = chosen) }
                return
            }
        }

        val snapped = when (val result = gateway.nearestContainer(device)) {
            is KantaResult.Success -> result.data
            // Offline: the Room cache knows the containers around, so the report can
            // still be composed and queued (§4.3).
            else -> gateway.containersAround(device, SendOutcomes.MAX_REPORT_DISTANCE_M).firstOrNull()
        }
        _state.update { it.copy(snapping = false, container = snapped) }
    }

    // -----------------------------------------------------------------------------------------
    // Compose
    // -----------------------------------------------------------------------------------------

    fun selectKind(kind: ReportKind) {
        if (presetFull) return // §4.3: "For Full the status is already set."
        _state.update { it.copy(kind = kind, outcome = null) }
    }

    fun onNoteChange(text: String) = _state.update { it.copy(note = text.take(MAX_NOTE)) }

    fun openPicker() {
        val device = _state.value.device ?: return
        viewModelScope.launch {
            val candidates = gateway.containersAround(device, PICKER_RADIUS_M)
            _state.update { it.copy(pickerOpen = true, candidates = candidates) }
        }
    }

    fun closePicker() = _state.update { it.copy(pickerOpen = false) }

    fun pickContainer(candidate: ContainerCandidate) =
        _state.update { it.copy(container = candidate, pickerOpen = false, outcome = null) }

    // -----------------------------------------------------------------------------------------
    // Add container (§4.6) — reached from "Not on the map" and from "too far"
    // -----------------------------------------------------------------------------------------

    fun openAddContainer() {
        val device = _state.value.device
        _state.update {
            it.copy(add = AddContainerUiState(device = device, pin = device), pickerOpen = false)
        }
        viewModelScope.launch {
            if (device != null) {
                val nearby = gateway.containersAround(device, PICKER_RADIUS_M)
                updateAdd { it.copy(nearby = nearby) }
            }
        }
        viewModelScope.launch {
            when (val result = gateway.addAllowance()) {
                is KantaResult.Success -> updateAdd {
                    it.copy(
                        loading = false,
                        remaining = result.data.remaining,
                        isAdmin = result.data.isAdmin,
                        limitReached = !result.data.isAdmin && result.data.remaining <= 0,
                    )
                }
                is KantaResult.Failure -> updateAdd { it.copy(loading = false, error = result.error) }
                KantaResult.Loading -> Unit
            }
        }
    }

    /**
     * Closing the sheet. In the report flow that returns to the compose sheet; in
     * "One is missing" there is nothing underneath, so it goes back to the camera.
     */
    fun closeAddContainer() {
        if (mode == ReportMode.AddContainer) retake() else _state.update { it.copy(add = null) }
    }

    /** The map under the pin settled (§4.6 "drags the pin to the exact spot"). */
    fun movePin(to: LatLon) = updateAdd { it.copy(pin = to, error = null, duplicateOf = null) }

    fun addKind(kind: ContainerKind) = updateAdd {
        // Small cans have no recycling category (§3.4); reset it so a stale
        // "glass" cannot ride along.
        it.copy(kind = kind, category = if (kind == ContainerKind.SMALL) ContainerCategory.GENERAL else it.category, error = null)
    }

    fun addCategory(category: ContainerCategory) = updateAdd { it.copy(category = category) }

    fun submitAdd(confirmDifferent: Boolean = false) {
        val draft = containerDraft(confirmDifferent) ?: return
        viewModelScope.launch {
            updateAdd { it.copy(submitting = true, error = null, duplicateOf = null) }
            when (val result = gateway.addContainer(draft)) {
                is KantaResult.Success -> if (mode == ReportMode.AddContainer) {
                    // "One is missing" ends here: the container IS the contribution.
                    _state.update {
                        it.copy(
                            add = null,
                            stage = ReportStage.Done,
                            outcome = SendOutcome.ContainerAdded(verified = result.data.verified),
                        )
                    }
                    recordAreaCheck(AREA_ADDED)
                } else {
                    val added = ContainerCandidate(
                        id = result.data.containerId,
                        code = result.data.code,
                        kind = draft.kind,
                        category = draft.category,
                        status = ContainerStatus.OK,
                        verified = result.data.verified,
                        position = draft.pin,
                        distanceMetres = 0.0,
                    )
                    _state.update { it.copy(container = added, add = null, outcome = null) }
                }
                is KantaResult.Failure -> when (val error = result.error) {
                    is KantaError.DuplicateContainer -> {
                        val existing = findCandidate(error.existingContainerId, draft.device)
                        updateAdd { it.copy(submitting = false, duplicateOf = existing, error = if (existing == null) error else null) }
                    }
                    KantaError.AddAllowanceUsedUp -> updateAdd { it.copy(submitting = false, limitReached = true) }
                    else -> updateAdd { it.copy(submitting = false, error = error) }
                }
                KantaResult.Loading -> Unit
            }
        }
    }

    /** "Is it this one?" → yes: report on the existing container instead of adding. */
    fun useDuplicate() {
        val existing = _state.value.add?.duplicateOf ?: return
        if (mode == ReportMode.AddContainer) {
            // It was on the map after all — which is exactly what the check wanted to know.
            _state.update { it.copy(add = null, stage = ReportStage.Done, outcome = SendOutcome.AlreadyOnMap) }
            recordAreaCheck(AREA_ALL_PRESENT)
            return
        }
        _state.update { it.copy(container = existing, add = null, outcome = null) }
    }

    /** §4.6: allowance used up — send for admin review; it does not go on the map. */
    fun sendForReview() {
        val draft = containerDraft(confirmDifferent = false) ?: return
        viewModelScope.launch {
            updateAdd { it.copy(submitting = true, error = null) }
            when (val result = gateway.requestContainer(draft, _state.value.note.ifBlank { null })) {
                is KantaResult.Success -> {
                    _state.update {
                        it.copy(add = null, stage = ReportStage.Done, outcome = SendOutcome.RequestSent)
                    }
                    recordAreaCheck(AREA_ADDED)
                }
                is KantaResult.Failure -> updateAdd { it.copy(submitting = false, error = result.error) }
                KantaResult.Loading -> Unit
            }
        }
    }

    private fun containerDraft(confirmDifferent: Boolean): ContainerDraft? {
        val s = _state.value
        val add = s.add ?: return null
        val kind = add.kind ?: return null
        val device = s.device ?: return null
        return ContainerDraft(
            // The pin starts where the phone is and the user drags it onto the
            // container (§4.6). The server checks it against the phone: 30 m.
            pin = add.pin ?: device,
            device = device,
            kind = kind,
            category = add.category,
            photo = s.photo?.file ?: return null,
            photoId = draftId,
            confirmDifferent = confirmDifferent,
        )
    }

    private suspend fun findCandidate(id: String?, around: LatLon): ContainerCandidate? {
        if (id == null) return null
        return (_state.value.candidates + gateway.containersAround(around, PICKER_RADIUS_M))
            .firstOrNull { it.id == id }
    }

    private inline fun updateAdd(transform: (AddContainerUiState) -> AddContainerUiState) =
        _state.update { current -> current.add?.let { current.copy(add = transform(it)) } ?: current }

    // -----------------------------------------------------------------------------------------
    // Send
    // -----------------------------------------------------------------------------------------

    fun send() {
        val s = _state.value
        if (!s.canSend) return
        val container = s.container ?: return
        val device = s.device ?: return
        val photo = s.photo ?: return
        val kind = s.kind ?: return

        SendOutcomes.precheck(container.distanceMetres)?.let { tooFar ->
            _state.update { it.copy(outcome = tooFar) }
            return
        }

        val draft = ReportDraft(
            id = draftId,
            containerId = container.id,
            kind = kind.wire,
            photo = photo.file,
            note = s.note.trim().ifBlank { null },
            device = device,
        )

        viewModelScope.launch {
            _state.update { it.copy(stage = ReportStage.Sending, outcome = null) }
            val outcome = SendOutcomes.from(gateway.submit(draft))
            if (outcome == SendOutcome.Queued) gateway.enqueue(draft)
            _state.update {
                it.copy(
                    stage = if (outcome.isFinal) ReportStage.Done else ReportStage.Compose,
                    outcome = outcome,
                )
            }
            // §4.6 "One on the map is not here": the check is answered once the
            // server has the report. A queued one records nothing yet — it may
            // still be refused when it finally sends.
            if (kind == ReportKind.Missing &&
                (outcome is SendOutcome.Sent || outcome is SendOutcome.MergedMeToo)
            ) {
                recordAreaCheck(AREA_REPORTED_MISSING)
            }
        }
    }

    /**
     * §4.6: the area check this flow was opened from is answered by the flow's
     * success. Best effort: the report or container already landed, which is the
     * part that matters; a lost check only means the user may be asked again.
     */
    private fun recordAreaCheck(result: String) {
        val s = _state.value
        if (!s.fromAreaCheck) return
        val at = s.device ?: return
        // NonCancellable: "Done" closes the screen (and this scope) at once; the
        // one small write should still reach the server.
        viewModelScope.launch { withContext(NonCancellable) { gateway.submitAreaCheck(at, result) } }
    }

    fun dismissOutcome() = _state.update { it.copy(outcome = null) }

    override fun onCleared() {
        // The processed photo is ours to clean up — abandoned, sent, or merged.
        // Once queued, the upload worker owns it and deletes it after sending.
        if (_state.value.outcome != SendOutcome.Queued) _state.value.photo?.file?.delete()
    }

    companion object {
        const val MAX_NOTE = 280

        /** area_checks.result values (§6). */
        const val AREA_ALL_PRESENT = "all_present"
        const val AREA_ADDED = "added"
        const val AREA_REPORTED_MISSING = "reported_missing"

        /** Wider than the 60 m report rule so the picker can show why a far one won't work. */
        const val PICKER_RADIUS_M = 120.0
    }
}
