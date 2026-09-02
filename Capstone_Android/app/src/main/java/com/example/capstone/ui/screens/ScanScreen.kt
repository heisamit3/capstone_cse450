package com.example.capstone.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.capstone.extractor.AnswerCrop
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.min

/**
 * Photograph a worksheet, and see what was read off it before committing to it.
 *
 * The entry path is the gallery, not the camera. Registration needs four sharp
 * corner markers at full resolution; the phone's own camera app is better at
 * producing that photo than an in-app preview with one capture button, so this
 * screen takes the result of that rather than competing with it. The CameraX
 * composables below still compile and are deliberately left in place - camera
 * capture comes back once framing guidance exists to justify it - but nothing
 * reaches them, and this screen no longer asks for the CAMERA permission.
 *
 * Extraction runs the moment a photo is picked. There is no "use this photo?"
 * step in front of it, because a student cannot tell by looking whether the
 * corners will register; the only confirmation worth offering is one shown on
 * top of boxes that were actually found.
 */
@Composable
fun ScanScreen(
    onCropsReady: (Int) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScanViewModel = viewModel(factory = ScanViewModel.Factory)
) {
    val uiState = viewModel.uiState

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.onImagePicked(uri)
    }

    fun pick() = pickImage.launch(
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    )

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("Scan Worksheet") },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) { Text("Back") }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            when (uiState) {
                is ScanUiState.Loading -> Centered { CircularProgressIndicator() }

                is ScanUiState.Idle -> PickPrompt(onPick = ::pick)

                is ScanUiState.Extracting -> Centered {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Reading the worksheet…")
                }

                is ScanUiState.Extracted -> ExtractedPhoto(
                    state = uiState,
                    onContinue = {
                        viewModel.keepCrops(uiState.crops)
                        onCropsReady(viewModel.assignmentId)
                    },
                    onRetake = ::pick
                )

                // Another photo can fix this one, so the only action offered is
                // taking another photo. Continuing is not on the screen at all.
                is ScanUiState.Retake -> Problem(
                    message = uiState.message,
                    detail = uiState.detail,
                    actionLabel = "Choose another photo",
                    onAction = ::pick
                )

                // Another photo cannot fix this one.
                is ScanUiState.Blocked -> Problem(
                    message = uiState.message,
                    detail = null,
                    actionLabel = "Back",
                    onAction = onNavigateBack
                )
            }
        }
    }
}

@Composable
private fun PickPrompt(onPick: () -> Unit) {
    Centered {
        Text(
            text = "Photograph the whole worksheet, with all four corner markers " +
                "inside the frame, then choose that photo here.",
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPick) { Text("Choose worksheet photo") }
    }
}

@Composable
private fun ExtractedPhoto(
    state: ScanUiState.Extracted,
    onContinue: () -> Unit,
    onRetake: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Found ${state.crops.size} " +
                if (state.crops.size == 1) "answer box." else "answer boxes.",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(12.dp))

        BoxOverlayImage(
            preview = state.preview,
            previewScale = state.previewScale,
            crops = state.crops,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = onContinue) { Text("Continue") }
            OutlinedButton(onClick = onRetake) { Text("Choose another photo") }
        }
    }
}

/**
 * The photo with the extracted boxes drawn on it.
 *
 * The quads come from the extractor in the pixels of the full-size photo, so
 * they are scaled twice: once by [previewScale], which maps full-size pixels
 * onto the downscaled preview bitmap, and once by the letterbox fit that
 * `ContentScale.Fit` applies to place that bitmap in the available space. Both
 * factors are computed here rather than assumed, so the outlines sit on the
 * boxes at any aspect ratio.
 *
 * They are drawn as quadrilaterals, not rectangles: a photo taken at an angle
 * produces genuinely skewed boxes, and squaring them off in the overlay would
 * show the student something the extractor did not do.
 */
@Composable
private fun BoxOverlayImage(
    preview: Bitmap,
    previewScale: Float,
    crops: List<AnswerCrop>,
    modifier: Modifier = Modifier
) {
    val image = remember(preview) { preview.asImageBitmap() }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Image(
            bitmap = image,
            contentDescription = "Worksheet photo with the answer boxes that were found",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (image.width == 0 || image.height == 0) return@Canvas

            val fit = min(size.width / image.width, size.height / image.height)
            val offsetX = (size.width - image.width * fit) / 2f
            val offsetY = (size.height - image.height * fit) / 2f
            // Full-size photo pixels -> preview bitmap pixels -> canvas pixels.
            val scale = previewScale * fit

            for (crop in crops) {
                if (crop.imageQuad.size < 4) continue
                val path = Path().apply {
                    crop.imageQuad.forEachIndexed { index, point ->
                        val x = offsetX + point.x.toFloat() * scale
                        val y = offsetY + point.y.toFloat() * scale
                        if (index == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(path, color = OVERLAY_COLOR, style = Stroke(width = OVERLAY_STROKE))
                // A dot on the first corner, so a box drawn upside down by a
                // mirrored solve is visible rather than merely plausible.
                val first = crop.imageQuad.first()
                drawCircle(
                    color = OVERLAY_COLOR,
                    radius = OVERLAY_STROKE * 2f,
                    center = Offset(
                        offsetX + first.x.toFloat() * scale,
                        offsetY + first.y.toFloat() * scale
                    )
                )
            }
        }
    }
}

private val OVERLAY_COLOR = Color(0xFF00C853)
private const val OVERLAY_STROKE = 4f

@Composable
private fun Problem(
    message: String,
    detail: String?,
    actionLabel: String,
    onAction: () -> Unit
) {
    Centered {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        if (detail != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content
    )
}

// ============================================================================
// Camera capture. Retained, compiled, and currently unreachable - see the
// ScanScreen KDoc. Nothing above calls either composable, and the manifest's
// CAMERA permission is no longer requested at runtime by this screen.
// ============================================================================

@Composable
private fun CameraPreview(
    modifier: Modifier = Modifier,
    onCapture: (ByteArray) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val executor = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(Unit) {
        val cameraProvider = suspendCoroutine<ProcessCameraProvider> { continuation ->
            ProcessCameraProvider.getInstance(context).also { future ->
                future.addListener(
                    { continuation.resume(future.get()) },
                    ContextCompat.getMainExecutor(context)
                )
            }
        }

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageCapture
        )
    }

    Column(modifier = modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                val outputFile =
                    context.cacheDir.resolve("capture-${System.currentTimeMillis()}.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

                imageCapture.takePicture(
                    outputOptions,
                    executor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(
                            outputFileResults: ImageCapture.OutputFileResults
                        ) {
                            val raw = outputFile.readBytes()
                            onCapture(raw)
                        }

                        override fun onError(exception: ImageCaptureException) = Unit
                    }
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text("Capture photo")
        }
    }
}
