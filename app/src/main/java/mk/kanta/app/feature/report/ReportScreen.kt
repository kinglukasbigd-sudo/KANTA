package mk.kanta.app.feature.report

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mk.kanta.app.R
import mk.kanta.app.core.designsystem.KantaTheme
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.component.KantaEmptyState
import mk.kanta.app.core.designsystem.component.KantaIcons
import mk.kanta.app.core.designsystem.component.KantaPrimaryButton
import mk.kanta.app.core.designsystem.component.KantaSkeleton
import androidx.compose.ui.unit.dp

/**
 * The report flow (spec §4.3): camera → processing → compose → done.
 *
 * [onFullSent] fires for a successful Full report, which §4.3 sends straight on to
 * "Nearest containers with space"; every other ending uses [onClose].
 */
@Composable
fun ReportScreen(
    onClose: () -> Unit,
    onFullSent: () -> Unit,
    viewModel: ReportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var cameraGranted by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var asked by remember { mutableStateOf(false) }

    // Camera AND location in one prompt: the report needs both — the photo, and
    // the phone's position that the server checks against the container (§4.3).
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        asked = true
        cameraGranted = result[Manifest.permission.CAMERA] == true || granted(Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) {
        val needed = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ).filterNot(::granted)
        if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray()) else asked = true
    }

    // Back from the compose sheet returns to the camera (retake), not out of the flow.
    BackHandler(enabled = state.stage == ReportStage.Compose || state.stage == ReportStage.Adding) {
        viewModel.retake()
    }

    // §4.3: a successful Full report goes straight on to the alternatives.
    LaunchedEffect(state.goToAlternatives) {
        if (state.goToAlternatives) {
            kotlinx.coroutines.delay(SUCCESS_HOLD_MS)
            onFullSent()
        }
    }

    when {
        !cameraGranted && asked -> CameraPermissionNeeded(
            onAllow = { permissions.launch(arrayOf(Manifest.permission.CAMERA)) },
            onClose = onClose,
        )

        !cameraGranted -> Box(Modifier.fillMaxSize()) // waiting for the system dialog

        else -> when (state.stage) {
            ReportStage.Camera -> CameraCapture(
                onCaptured = viewModel::onPhotoCaptured,
                onClose = onClose,
                rejectedMessage = if (state.photoRejected) stringResource(R.string.report_photo_rejected) else null,
            )

            ReportStage.Processing -> ProcessingScreen()

            ReportStage.Compose, ReportStage.Sending -> ReportComposeContent(
                state = state,
                onRetake = viewModel::retake,
                onSelectKind = viewModel::selectKind,
                onNoteChange = viewModel::onNoteChange,
                onChangeContainer = viewModel::openPicker,
                onAddContainer = viewModel::openAddContainer,
                onSend = viewModel::send,
                onDismissOutcome = viewModel::dismissOutcome,
            )

            // §4.6 "One is missing": the photo stays in view behind the Add sheet,
            // so the user can see what they are placing.
            ReportStage.Adding -> PhotoBackdrop(state.photo?.file)

            ReportStage.Done -> ReportDoneScreen(state = state, onDone = onClose)
        }
    }

    if (state.pickerOpen) {
        ContainerPickerSheet(
            candidates = state.candidates,
            selectedId = state.container?.id,
            device = state.device,
            onPick = viewModel::pickContainer,
            onAddNew = viewModel::openAddContainer,
            onDismiss = viewModel::closePicker,
        )
    }

    state.add?.let { add ->
        AddContainerSheet(
            state = add,
            onKind = viewModel::addKind,
            onCategory = viewModel::addCategory,
            onSubmit = { viewModel.submitAdd(confirmDifferent = false) },
            onConfirmDifferent = { viewModel.submitAdd(confirmDifferent = true) },
            onUseExisting = viewModel::useDuplicate,
            onSendForReview = viewModel::sendForReview,
            onPinMoved = viewModel::movePin,
            onDismiss = viewModel::closeAddContainer,
        )
    }
}

@Composable
private fun PhotoBackdrop(file: java.io.File?) {
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        if (file != null) {
            coil3.compose.AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                alpha = 0.55f,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Face detection and re-encoding take a moment. Said plainly, because "blurring
 * faces" is also a promise the user should see being kept (§8).
 */
@Composable
private fun ProcessingScreen() {
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Column(
            modifier = Modifier.fillMaxSize().padding(Spacing.xxl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            KantaSkeleton(width = 200.dp, height = 14.dp)
            Text(
                text = stringResource(R.string.report_processing),
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = Spacing.l),
            )
        }
    }
}

@Composable
private fun CameraPermissionNeeded(onAllow: () -> Unit, onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.Center) {
            KantaEmptyState(
                title = stringResource(R.string.report_camera_needed_title),
                message = stringResource(R.string.report_camera_needed_message),
                icon = KantaIcons.Camera,
                action = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        KantaPrimaryButton(stringResource(R.string.report_allow_camera), onClick = onAllow)
                        androidx.compose.material3.TextButton(onClick = onClose) {
                            Text(stringResource(R.string.action_cancel), color = KantaTheme.colors.onSurfaceMuted)
                        }
                    }
                },
            )
        }
    }
}

/** How long the success check stays on screen before a Full report moves on. */
private const val SUCCESS_HOLD_MS = 1_400L
