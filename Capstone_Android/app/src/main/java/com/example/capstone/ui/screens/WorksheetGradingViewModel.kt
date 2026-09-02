package com.example.capstone.ui.screens

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
import com.example.capstone.data.remote.GradedAnswerRequest
import com.example.capstone.data.repository.AnswerUpload
import com.example.capstone.data.repository.AssignmentRepository
import com.example.capstone.domain.grading.BoxResult
import com.example.capstone.domain.grading.WorksheetGrade
import com.example.capstone.domain.grading.WorksheetGrader
import com.example.capstone.domain.grading.mergeBoxResults
import com.example.capstone.domain.worksheet.QuestionResolver
import com.example.capstone.domain.worksheet.ResolvedAnswer
import com.example.capstone.domain.worksheet.Resolution
import com.example.capstone.domain.worksheet.ResolverCreation
import com.example.capstone.domain.worksheet.WorksheetSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What the grading screen is showing.
 *
 * [Grading] and [Graded] both carry the results so far, so the screen renders
 * the same list either way and a box that is already done does not move or
 * change when the next one finishes.
 */
sealed interface WorksheetGradingUiState {

    data object Preparing : WorksheetGradingUiState

    /** Marking is running. [results] grows one box at a time. */
    data class Grading(
        val results: List<BoxResult>,
        val total: Int
    ) : WorksheetGradingUiState

    /** Every box is marked; nothing has been sent yet. */
    data class Graded(val grade: WorksheetGrade) : WorksheetGradingUiState

    /** The submission and the grade are being uploaded. */
    data class Uploading(val grade: WorksheetGrade) : WorksheetGradingUiState

    /** The server has both the answer images and the grade. */
    data class Submitted(val grade: WorksheetGrade) : WorksheetGradingUiState

    /** Marking stopped before it finished, at the student's request. */
    data class Cancelled(val results: List<BoxResult>) : WorksheetGradingUiState

    /**
     * Nothing can proceed. [message] is for the student; [detail] carries the
     * underlying reason, which for a resolution failure names the exact ids and
     * is worth showing rather than swallowing.
     */
    data class Failed(val message: String, val detail: String? = null) :
        WorksheetGradingUiState

    /**
     * Marking finished but the upload did not. [grade] is intact, so retrying
     * re-sends the same marks rather than re-running the model.
     */
    data class UploadFailed(val grade: WorksheetGrade, val detail: String) :
        WorksheetGradingUiState
}

/**
 * Marks every answer box on a scanned worksheet, then uploads the result.
 *
 * The run is a `Flow<BoxResult>` collected here: one box at a time, in the
 * teacher's document order, with each result rendered as it arrives rather than
 * after the last one. Cancelling cancels the collection, which stops the run
 * between boxes - the box in flight finishes its inference, because
 * `LocalGradingService` holds the engine mutex for the duration and interrupting
 * that is not something the LiteRT-LM API offers.
 *
 * Upload is two calls and the order is forced by the server: `POST /submissions`
 * creates the answer rows and returns the id, and only then does
 * `POST /submissions/{id}/grade` have somewhere to attach a mark and a
 * transcription.
 */
class WorksheetGradingViewModel(
    private val assignmentRepository: AssignmentRepository,
    private val worksheetGrader: WorksheetGrader,
    private val worksheetSession: WorksheetSession,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val assignmentId: Int =
        checkNotNull(savedStateHandle.get<String>("assignmentId")?.toInt())

    var uiState: WorksheetGradingUiState by mutableStateOf(WorksheetGradingUiState.Preparing)
        private set

    private var answers: List<ResolvedAnswer> = emptyList()
    private var gradingJob: Job? = null

    init {
        start()
    }

    fun start() {
        gradingJob?.cancel()
        gradingJob = viewModelScope.launch {
            uiState = WorksheetGradingUiState.Preparing

            val resolved = resolve() ?: return@launch
            answers = resolved

            val collected = mutableListOf<BoxResult>()
            uiState = WorksheetGradingUiState.Grading(emptyList(), resolved.size)

            try {
                worksheetGrader.grade(resolved).collect { result ->
                    collected += result
                    uiState = WorksheetGradingUiState.Grading(
                        collected.toList(),
                        resolved.size
                    )
                }
            } catch (e: CancellationException) {
                // The student stopped it. Keep what was marked, mark nothing else.
                uiState = WorksheetGradingUiState.Cancelled(collected.toList())
                throw e
            }

            uiState = WorksheetGradingUiState.Graded(mergeBoxResults(collected))
        }
    }

    /** Stops marking between boxes. What has been marked so far is kept. */
    fun cancel() {
        gradingJob?.cancel()
    }

    /**
     * Uploads the answer images, then the grade.
     *
     * Available from [WorksheetGradingUiState.Graded] and from
     * [WorksheetGradingUiState.UploadFailed] - a retry re-sends, it never
     * re-marks. Repeating it after a partial failure is safe: a second
     * `POST /submissions` creates a second submission, but the grade attaches to
     * whichever id this call returned, and the grade route itself updates rather
     * than duplicating.
     */
    fun upload() {
        val grade = when (val state = uiState) {
            is WorksheetGradingUiState.Graded -> state.grade
            is WorksheetGradingUiState.UploadFailed -> state.grade
            else -> return
        }

        viewModelScope.launch {
            uiState = WorksheetGradingUiState.Uploading(grade)

            val uploads = answers.map { answer ->
                AnswerUpload(
                    questionId = answer.question.id,
                    png = answer.crop.png,
                    fileName = "${answer.crop.externalAnswerBoxId}.png"
                )
            }

            val submission = assignmentRepository.submitAnswers(assignmentId, uploads)
            val submissionId = submission.getOrNull()
            if (submissionId == null) {
                uiState = WorksheetGradingUiState.UploadFailed(
                    grade,
                    submission.exceptionOrNull()?.message ?: "The answers could not be uploaded."
                )
                return@launch
            }

            val result = assignmentRepository.submitGrade(
                submissionId = submissionId,
                obtainedMarks = grade.obtainedMarks,
                feedback = grade.feedback,
                confidence = grade.confidence,
                transcriptions = grade.boxes.map { box ->
                    GradedAnswerRequest(
                        questionId = box.question.id,
                        transcription = box.transcription
                    )
                }
            )

            uiState = if (result.isSuccess) {
                // The crops have been sent; holding megabytes of them past that
                // point serves nothing.
                worksheetSession.clear()
                WorksheetGradingUiState.Submitted(grade)
            } else {
                WorksheetGradingUiState.UploadFailed(
                    grade,
                    result.exceptionOrNull()?.message
                        ?: "The answers were uploaded but the grade was not."
                )
            }
        }
    }

    /**
     * Joins the scanned crops to this assignment's questions, or sets a failure
     * state and returns null.
     *
     * Every failure here is named rather than generic, because each one has a
     * different cause and a different remedy: no crops means the scan was lost,
     * an unavailable resolver means the assignment was never imported from the
     * worksheet system, and a failed resolution means the photo and the
     * assignment disagree about which boxes exist.
     */
    private suspend fun resolve(): List<ResolvedAnswer>? {
        val crops = worksheetSession.crops(assignmentId)
        if (crops.isNullOrEmpty()) {
            uiState = WorksheetGradingUiState.Failed(
                "The scanned worksheet is no longer available.",
                "Scan the worksheet again."
            )
            return null
        }

        val assignmentResult = assignmentRepository.getAssignmentDetail(assignmentId)
        val assignment = assignmentResult.getOrNull()
        if (assignment == null) {
            uiState = WorksheetGradingUiState.Failed(
                "Could not load this assignment.",
                assignmentResult.exceptionOrNull()?.message
            )
            return null
        }

        val resolver = when (val creation = QuestionResolver.forAssignment(assignment)) {
            is ResolverCreation.Available -> creation.resolver
            is ResolverCreation.Unavailable -> {
                Log.w(TAG, creation.reason)
                uiState = WorksheetGradingUiState.Failed(
                    "This worksheet cannot be marked on the device.",
                    creation.reason
                )
                return null
            }
        }

        return when (val resolution = resolver.resolve(crops)) {
            is Resolution.Resolved -> resolution.answers
            is Resolution.Failed -> {
                Log.w(TAG, resolution.message)
                uiState = WorksheetGradingUiState.Failed(
                    "The photo does not match this assignment's answer boxes.",
                    resolution.message
                )
                null
            }
        }
    }

    companion object {
        private const val TAG = "WorksheetGrading"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        as CapstoneApplication
                    )
                WorksheetGradingViewModel(
                    assignmentRepository = application.container.assignmentRepository,
                    worksheetGrader = application.container.worksheetGrader,
                    worksheetSession = application.container.worksheetSession,
                    savedStateHandle = this.createSavedStateHandle()
                )
            }
        }
    }
}
