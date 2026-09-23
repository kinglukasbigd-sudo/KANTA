package mk.kanta.app.feature.report

import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import mk.kanta.app.R
import mk.kanta.app.core.designsystem.KantaShape
import mk.kanta.app.core.designsystem.Spacing
import mk.kanta.app.core.designsystem.rememberKantaHaptics
import java.io.File
import java.util.UUID

/**
 * The in-app camera (§4.3: "in-app camera opens instantly (CameraX, big shutter,
 * torch toggle)"). §2: "in-app camera only (no gallery picks for reports)" — there
 * is deliberately no gallery button, because a report is evidence of what is there
 * now, not a photo from last week.
 */
@Composable
fun CameraCapture(
    onCaptured: (File) -> Unit,
    onClose: () -> Unit,
    rejectedMessage: String?,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = rememberKantaHaptics()

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // COMPATIBLE renders through a TextureView, which composes cleanly with
            // the Compose overlays drawn on top of it.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            // §4.3 "3 taps": the shutter should feel instant.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val shutterLabel = stringResource(R.string.camera_shutter)

    LaunchedEffect(lifecycleOwner) {
        val cameraProvider = awaitCameraProvider(context)
        provider = cameraProvider
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        cameraProvider.unbindAll()
        camera = cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageCapture,
        )
    }
    DisposableEffect(Unit) { onDispose { provider?.unbindAll() } }

    LaunchedEffect(torchOn, camera) { camera?.cameraControl?.enableTorch(torchOn) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Close, top-left.
        CameraRoundButton(
            onClick = onClose,
            contentDescription = stringResource(R.string.camera_close),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(Spacing.l),
        ) { Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White) }

        // Torch, top-right — only on phones that have one.
        if (camera?.cameraInfo?.hasFlashUnit() == true) {
            CameraRoundButton(
                onClick = {
                    haptics.tick()
                    torchOn = !torchOn
                },
                contentDescription = stringResource(if (torchOn) R.string.camera_torch_off else R.string.camera_torch_on),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(Spacing.l),
            ) {
                Icon(
                    imageVector = if (torchOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                    contentDescription = null,
                    tint = Color.White,
                )
            }
        }

        // Why the last photo was refused (§8: faces could not be checked).
        (error ?: rejectedMessage)?.let { message ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 88.dp, start = Spacing.xl, end = Spacing.xl),
                shape = KantaShape.card,
                color = Color.Black.copy(alpha = 0.72f),
            ) {
                Text(
                    text = message,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(Spacing.l),
                )
            }
        }

        // The big round shutter.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = Spacing.xxxl)
                .size(84.dp)
                .border(BorderStroke(4.dp, Color.White), CircleShape)
                .padding(8.dp)
                .clip(CircleShape)
                .background(if (capturing) Color.White.copy(alpha = 0.5f) else Color.White)
                .semantics {
                    role = Role.Button
                    contentDescription = shutterLabel
                }
                .clickable(enabled = !capturing && camera != null) {
                    haptics.tick()
                    capturing = true
                    error = null
                    takePhoto(
                        context = context,
                        imageCapture = imageCapture,
                        onSaved = { file ->
                            capturing = false
                            onCaptured(file)
                        },
                        onError = {
                            capturing = false
                            error = context.getString(R.string.camera_capture_failed)
                        },
                    )
                },
        )
    }
}

@Composable
private fun CameraRoundButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(Spacing.minTouchTarget)
            .semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.45f),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/** ProcessCameraProvider's future, awaited without pulling in the Guava coroutine adapter. */
private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { continuation.resumeWith(Result.success(it)) }
                    .onFailure { continuation.resumeWith(Result.failure(it)) }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

/**
 * Captures to a private cache file. The raw file still carries EXIF — orientation
 * is needed for one more step — and is deleted by the photo processor after it has
 * produced the stripped, blurred copy.
 */
private fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    onSaved: (File) -> Unit,
    onError: (ImageCaptureException) -> Unit,
) {
    val file = File(context.cacheDir, "raw_${UUID.randomUUID()}.jpg")
    imageCapture.takePicture(
        ImageCapture.OutputFileOptions.Builder(file).build(),
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) = onSaved(file)
            override fun onError(exception: ImageCaptureException) {
                file.delete()
                onError(exception)
            }
        },
    )
}
