package com.example.capstone.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.capstone.CapstoneApplication
import com.example.capstone.data.repository.AssignmentRepository
import com.example.capstone.domain.model.Assignment
import com.example.capstone.domain.worksheet.MarkerCorners
import com.example.capstone.domain.worksheet.WorksheetSession
import com.example.capstone.extractor.AnswerCrop
import com.example.capstone.extractor.ExtractionResult
import com.example.capstone.extractor.Layout
import com.example.capstone.extractor.OpenCvNative
import com.example.capstone.extractor.PageExtractor
import com.example.capstone.util.ImagePrep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * What the scan screen is showing.
 *
 * There is no "photo taken, not yet checked" state, and that is deliberate.
 * Extraction runs the instant the photo lands, so the student never confirms a
 * photo that turns out to be unusable - the confirmation they are offered is
 * always a confirmation of crops that actually exist.
 */
sealed interface ScanUiState {

    /** Nothing picked yet. */
    data object Idle : ScanUiState

    /** The assignment or its layout is still loading. */
    data object Loading : ScanUiState

    /** A photo is being decoded, registered and cut. */
    data object Extracting : ScanUiState

    /**
     * Every answer box was found. [preview] is a downscaled copy of the photo
     * for display and [crops] carry [AnswerCrop.imageQuad] in the coordinates of
     * the full-size photo, so the overlay scales by [previewScale].
     */
    data class Extracted(
        val preview: Bitmap,
        val previewScale: Float,
        val crops: List<AnswerCrop>
    ) : ScanUiState

    /**
     * The photo is not usable and the student should take another one.
     * [message] is written to be shown as-is; [detail] is a second line naming
     * the underlying cause, or null when the message already says everything.
     */
    data class Retake(val message: String, val detail: String? = null) : ScanUiState

    /**
     * Scanning cannot proceed for this assignment at all, and another photo will
     * not help - no layout, a layout the extractor refuses, more pages than this
     * screen photographs.
     */
    data class Blocked(val message: String) : ScanUiState
}

/**
 * Picks a worksheet photo from the gallery and extracts its answer boxes.
 *
 * The camera is deliberately not the entry path in this build. Registration
 * needs all four corner markers sharp and in frame at full resolution, and the
 * fastest way to get a photo like that today is to take it with the phone's own
 * camera app - which has the framing aids, the focus tap and the review step
 * this screen does not - and pick the result here. The CameraX code is still in
 * `ScanScreen` and still compiles; it is simply not reachable, and the CAMERA
 * permission is no longer requested for it.
 */
class ScanViewModel(
    private val context: Context,
    private val assignmentRepository: AssignmentRepository,
    private val worksheetSession: WorksheetSession,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val assignmentId: Int = checkNotNull(savedStateHandle.get<String>("assignmentId")?.toInt())

    var uiState: ScanUiState by mutableStateOf(ScanUiState.Loading)
        private set

    private var assignment: Assignment? = null

    init {
        loadAssignment()
    }

    fun loadAssignment() {
        viewModelScope.launch {
            uiState = ScanUiState.Loading
            val result = assignmentRepository.getAssignmentDetail(assignmentId)
            val loaded = result.getOrNull()
            if (loaded == null) {
                uiState = ScanUiState.Blocked(
                    "Could not load this assignment: " +
                        (result.exceptionOrNull()?.message ?: "unknown error")
                )
                return@launch
            }
            assignment = loaded
            uiState = blockingReason(loaded)?.let { ScanUiState.Blocked(it) } ?: ScanUiState.Idle
        }
    }

    /**
     * Everything about this assignment that makes scanning impossible before a
     * photo is even taken. Checked up front so the student is not asked for a
     * photo that could never have worked.
     */
    private fun blockingReason(assignment: Assignment): String? {
        val layout = assignment.layout
            ?: return "This assignment has no printed worksheet layout, so a photo " +
                "of it cannot be matched to its answer boxes."

        if (layout.answerBoxes.isEmpty()) {
            return "This worksheet has no answer boxes to read."
        }

        val pages = layout.answerBoxes.map { it.pageIndex }.distinct().sorted()
        if (pages != listOf(FIRST_PAGE)) {
            return "This worksheet has answers on ${pages.size} pages " +
                "(${pages.joinToString()}). This screen reads a single page."
        }
        return null
    }

    fun onImagePicked(uri: Uri) {
        val assignment = this.assignment ?: return
        val layout = assignment.layout ?: return

        viewModelScope.launch {
            uiState = ScanUiState.Extracting
            uiState = withContext(Dispatchers.IO) { extract(uri, layout) }
        }
    }

    fun reset() {
        uiState = if (assignment == null) ScanUiState.Loading else ScanUiState.Idle
    }

    /** Called when the student accepts the crops; hands them to the grading screen. */
    fun keepCrops(crops: List<AnswerCrop>) {
        worksheetSession.put(assignmentId, crops)
    }

    /**
     * Reads the picked image, registers it and cuts the crops.
     *
     * Runs entirely off the main thread and holds the full-resolution PNG for as
     * short a time as it can: a 12 MP photo is tens of megabytes as PNG, and the
     * decoded Mat inside the extractor is tens more. The reference is dropped
     * before the preview bitmap is built.
     */
    private fun extract(uri: Uri, layout: Layout): ScanUiState {
        val source = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (t: Throwable) {
            Log.w(TAG, "could not read the picked image", t)
            null
        } ?: return ScanUiState.Retake(
            message = "That image could not be opened.",
            detail = "Pick the photo again, or choose a different one."
        )

        // Orientation only. Downscaling here would throw away the marker detail
        // registration depends on and the stroke detail the crops are for.
        var full: ByteArray? = ImagePrep.toRegistrationPng(source)
            ?: return ScanUiState.Retake(
                message = "That file is not an image this app can read.",
                detail = "${source.size} bytes, not a decodable photo."
            )

        return try {
            OpenCvNative.load()

            val bytes = full!!
            val result = PageExtractor().extractPage(layout, FIRST_PAGE, bytes)
            when (result) {
                is ExtractionResult.Success -> {
                    val preview = decodePreview(bytes)
                    // The photo is no longer needed; the crops are.
                    full = null
                    if (preview == null) {
                        ScanUiState.Retake(
                            message = "The photo could not be displayed.",
                            detail = "Try taking it again."
                        )
                    } else {
                        ScanUiState.Extracted(
                            preview = preview.bitmap,
                            previewScale = preview.scale,
                            crops = result.crops
                        )
                    }
                }

                is ExtractionResult.MarkersNotFound -> ScanUiState.Retake(
                    message = MarkerCorners.sentence(layout, result.missingIds),
                    detail = "${result.found} of ${layout.markers.size} corner markers were " +
                        "found. Take the photo again with the whole sheet, including all " +
                        "four corners, inside the frame."
                )

                is ExtractionResult.RegistrationFailed -> ScanUiState.Retake(
                    message = "The corners were found but the page could not be squared up.",
                    detail = result.reason
                )

                is ExtractionResult.Undecodable -> ScanUiState.Retake(
                    message = "That file is not an image this app can read.",
                    detail = result.cause.message ?: result.cause.javaClass.simpleName
                )

                // Not the student's photo and not fixable by taking another one:
                // the geometry the server sent cannot be used.
                is ExtractionResult.InvalidLayout -> ScanUiState.Blocked(
                    "This worksheet's printed layout is not usable: ${result.reason}"
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "extraction failed", t)
            ScanUiState.Retake(
                message = "Something went wrong reading that photo.",
                detail = "${t.javaClass.simpleName}: ${t.message}"
            )
        } finally {
            full = null
        }
    }

    /**
     * A display-sized copy of the photo, plus the factor that maps full-size
     * image pixels onto it - which is what the box overlay needs, because
     * [AnswerCrop.imageQuad] is in full-size pixels.
     *
     * `inSampleSize` only ever halves, so the real scale is recovered from the
     * decoded width rather than assumed from the requested one.
     */
    private fun decodePreview(bytes: ByteArray): Preview? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > PREVIEW_LONG_EDGE) {
            sample *= 2
        }

        val bitmap = try {
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "out of memory decoding the preview", e)
            null
        } ?: return null

        return Preview(bitmap, bitmap.width.toFloat() / bounds.outWidth.toFloat())
    }

    private data class Preview(val bitmap: Bitmap, val scale: Float)

    companion object {
        private const val TAG = "ScanViewModel"

        /** This screen photographs one page. See [blockingReason]. */
        private const val FIRST_PAGE = 0

        /** Long edge of the on-screen copy of the photo. */
        private const val PREVIEW_LONG_EDGE = 1600

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        as CapstoneApplication
                    )
                ScanViewModel(
                    context = application,
                    assignmentRepository = application.container.assignmentRepository,
                    worksheetSession = application.container.worksheetSession,
                    savedStateHandle = this.createSavedStateHandle()
                )
            }
        }
    }
}
