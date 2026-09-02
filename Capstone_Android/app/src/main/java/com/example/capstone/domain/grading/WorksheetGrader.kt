package com.example.capstone.domain.grading

import com.example.capstone.domain.worksheet.ResolvedAnswer
import com.example.capstone.util.ImagePrep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Grades one worksheet, one answer box at a time.
 *
 * Emits a [BoxResult] per box as soon as that box is done, so a screen can fill
 * in while the rest is still running. The flow is cold and strictly sequential:
 * collecting it starts the run, cancelling the collection stops it between
 * boxes, and nothing is graded twice.
 *
 * ### Why there is no concurrency here
 *
 * There is exactly one LiteRT-LM engine per process and
 * `LocalModelProvider.withInference` already serialises every decode behind a
 * mutex; `LocalGradingService` already opens a fresh `Conversation` per call so
 * no context leaks between questions. Running boxes in parallel would therefore
 * buy nothing - the calls would queue on that mutex anyway - while holding
 * several decoded bitmaps alive at once on a device that has just been asked to
 * keep a 0.5B multimodal model resident. So: one at a time, deliberately.
 *
 * The crop PNGs are held for the whole run, because they are the upload payload
 * and they are small (one answer box at canonical page resolution). The decoded
 * bitmaps are not: [ImagePrep.toGradingPng] decodes, transforms and re-encodes
 * inside one call, so only the box currently being graded ever has a bitmap.
 */
class WorksheetGrader(private val gradingService: GradingService) {

    /**
     * Grades [answers] in [com.example.capstone.extractor.AnswerCrop.orderIndex]
     * order - the teacher's document order - regardless of the order they arrive
     * in.
     *
     * Every box emits exactly one result. A box that cannot be graded at all
     * emits [BoxOutcome.NeedsReview] rather than being skipped: a skipped box
     * would leave the worksheet looking complete while one answer silently never
     * got looked at.
     */
    fun grade(answers: List<ResolvedAnswer>): Flow<BoxResult> = flow {
        for (answer in answers.sortedBy { it.crop.orderIndex }) {
            emit(gradeOne(answer))
        }
    }.flowOn(Dispatchers.Default)

    private suspend fun gradeOne(answer: ResolvedAnswer): BoxResult {
        val question = answer.question
        val orderIndex = answer.crop.orderIndex

        // A question with no mark ceiling or no model answer cannot be graded
        // against anything. That is a wiring or teacher-data problem, not a
        // student one, and it must not turn into a zero.
        if (!question.isGradeable) {
            return BoxResult(
                orderIndex = orderIndex,
                question = question,
                outcome = BoxOutcome.NeedsReview(
                    reason = "This question has no marking information yet, " +
                        "so it cannot be marked on the device.",
                    transcription = "",
                    feedback = ""
                )
            )
        }

        val png = ImagePrep.toGradingPng(answer.crop.png)
            ?: return BoxResult(
                orderIndex = orderIndex,
                question = question,
                outcome = BoxOutcome.NeedsReview(
                    reason = "The cropped answer could not be prepared for reading.",
                    transcription = "",
                    feedback = ""
                )
            )

        // grade() is documented never to throw; a failed inference or an
        // unparseable reply comes back as needsManualReview.
        val result = gradingService.grade(
            photoPng = png,
            questionText = question.text,
            modelAnswer = question.modelAnswer!!,
            rubric = question.rubric,
            maxMarks = question.marks!!
        )

        val outcome = if (result.needsManualReview) {
            BoxOutcome.NeedsReview(
                reason = if (!result.legible) {
                    "The handwriting in this box could not be read confidently."
                } else {
                    "The model did not return a usable mark for this box."
                },
                transcription = result.transcription,
                feedback = result.feedback
            )
        } else {
            BoxOutcome.Scored(
                marks = result.marks,
                certainty = result.certainty,
                transcription = result.transcription,
                feedback = result.feedback
            )
        }

        return BoxResult(orderIndex = orderIndex, question = question, outcome = outcome)
    }
}
