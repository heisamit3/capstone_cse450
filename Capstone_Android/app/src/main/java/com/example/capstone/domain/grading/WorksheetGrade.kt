package com.example.capstone.domain.grading

import com.example.capstone.domain.model.Question

/**
 * The result of grading one answer box.
 *
 * [orderIndex] is the crop's position in the teacher's document order, carried
 * through from extraction so the UI can fill boxes in reading order and so a
 * result can be matched back to the box it came from without re-deriving
 * anything.
 */
data class BoxResult(
    val orderIndex: Int,
    val question: Question,
    val outcome: BoxOutcome
) {
    /** Marks available for this box. */
    val maxMarks: Int get() = question.marks ?: 0

    /** What goes in `answers[].transcription` on the upload. */
    val transcription: String
        get() = when (outcome) {
            is BoxOutcome.Scored -> outcome.transcription
            is BoxOutcome.NeedsReview -> outcome.transcription
        }
}

/**
 * What happened to one box.
 *
 * Two cases, and the split is the whole point of this file. A box the model
 * scored zero and a box nobody could score are both worth zero marks on the
 * submission, and they must never look the same anywhere else: one says the
 * student's answer was wrong, the other says the app could not read it. Merging
 * them into "0" would silently convert an unreadable photo into a failed
 * answer.
 */
sealed interface BoxOutcome {

    /** The model read the answer and awarded a mark. [marks] is meaningful. */
    data class Scored(
        val marks: Int,
        val certainty: Double,
        val transcription: String,
        val feedback: String
    ) : BoxOutcome

    /**
     * A teacher has to look at this box. There is no mark.
     *
     * [reason] is written for a student to read. [transcription] is whatever the
     * model claimed to read, which may be empty and must not be trusted as an
     * answer - it is carried so the teacher reviewing this has the same
     * information the app had.
     */
    data class NeedsReview(
        val reason: String,
        val transcription: String,
        val feedback: String
    ) : BoxOutcome
}

/**
 * Every box on one worksheet, merged into the single row the server stores.
 *
 * `Grade` on the server is per-submission, not per-question: one
 * `obtained_marks`, one `feedback`, one `confidence`, and no `question_id`
 * (`CLAUDE.md` §2.6). So the merge is lossy by construction, and the per-box
 * detail survives in exactly two places - `answers[].transcription` on the
 * upload, and [boxes] here, which the screen renders.
 */
data class WorksheetGrade(
    /** In [BoxResult.orderIndex] order. */
    val boxes: List<BoxResult>,
    /** Sum over scored boxes. Boxes needing review contribute nothing. */
    val obtainedMarks: Int,
    /** Sum of every box's [BoxResult.maxMarks], scored or not. */
    val totalMarks: Int,
    /** The least confident box. See [mergeBoxResults] for what a review box contributes. */
    val confidence: Double,
    /** Per-box feedback, joined, one line per box. */
    val feedback: String,
    /** True when any box needs a teacher. */
    val needsManualReview: Boolean
) {
    /** Boxes a teacher has to look at, in order. */
    val boxesNeedingReview: List<BoxResult>
        get() = boxes.filter { it.outcome is BoxOutcome.NeedsReview }
}

/**
 * Merges per-box results into the one grade row the server accepts.
 *
 * The four rules, and why each is what it is:
 *
 * - **Marks are summed, and a box needing review adds zero.** There is no other
 *   arithmetic available: the submission has one mark total. The zero is not a
 *   judgement about the answer, which is why [WorksheetGrade.needsManualReview]
 *   travels with it and why [WorksheetGrade.boxes] keeps every box's own
 *   outcome intact.
 * - **Confidence is the minimum, not the mean.** A mean lets nine confident
 *   boxes bury one the model was guessing at, which is the exact case a
 *   confidence number exists to surface. A box needing review contributes 0.0,
 *   because the honest confidence in a grade that could not be established is
 *   none.
 * - **Feedback is joined one line per box**, each line naming its question, so
 *   the single string the server stores can still be read back apart. Review
 *   boxes are labelled as such rather than given a mark.
 * - **Any box needing review flags the submission.** Not a majority, not a
 *   threshold: one unreadable answer is enough for a teacher to want the paper.
 *
 * An empty [boxes] is not a zero-mark worksheet - it is a worksheet nothing was
 * graded on. It comes back flagged, with confidence 0.
 */
fun mergeBoxResults(boxes: List<BoxResult>): WorksheetGrade {
    val ordered = boxes.sortedBy { it.orderIndex }

    if (ordered.isEmpty()) {
        return WorksheetGrade(
            boxes = emptyList(),
            obtainedMarks = 0,
            totalMarks = 0,
            confidence = 0.0,
            feedback = "No answers were graded.",
            needsManualReview = true
        )
    }

    var obtained = 0
    var total = 0
    var confidence = 1.0
    var flagged = false
    val lines = mutableListOf<String>()

    for (box in ordered) {
        total += box.maxMarks
        val label = "Q${box.question.id}"
        when (val outcome = box.outcome) {
            is BoxOutcome.Scored -> {
                obtained += outcome.marks
                confidence = minOf(confidence, outcome.certainty)
                lines += "$label (${outcome.marks}/${box.maxMarks}): ${outcome.feedback}"
            }

            is BoxOutcome.NeedsReview -> {
                flagged = true
                // A box with no established grade has no confidence to average in.
                confidence = 0.0
                lines += "$label (not marked - needs review): ${outcome.reason}"
            }
        }
    }

    return WorksheetGrade(
        boxes = ordered,
        obtainedMarks = obtained,
        totalMarks = total,
        confidence = confidence,
        feedback = lines.joinToString("\n"),
        needsManualReview = flagged
    )
}
