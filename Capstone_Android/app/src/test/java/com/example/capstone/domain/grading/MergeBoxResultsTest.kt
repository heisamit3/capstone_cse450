package com.example.capstone.domain.grading

import com.example.capstone.domain.model.Question
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [mergeBoxResults] - the lossy step where per-box results become
 * the one grade row the server stores.
 *
 * Plain JVM: the merge touches no Android API, no OpenCV and no engine, which is
 * what lets the rule that matters most here - a reviewed box is not a zero - be
 * pinned without a device.
 */
class MergeBoxResultsTest {

    private fun question(id: Int, marks: Int = 5) = Question(
        id = id,
        text = "Q$id",
        marks = marks,
        modelAnswer = "answer",
        rubric = null,
        externalAnswerBoxId = "ab_$id"
    )

    private fun scored(
        orderIndex: Int,
        marks: Int,
        certainty: Double = 0.9,
        maxMarks: Int = 5,
        feedback: String = "ok",
        transcription: String = "written"
    ) = BoxResult(
        orderIndex = orderIndex,
        question = question(orderIndex + 1, maxMarks),
        outcome = BoxOutcome.Scored(marks, certainty, transcription, feedback)
    )

    private fun needsReview(
        orderIndex: Int,
        maxMarks: Int = 5,
        reason: String = "illegible",
        transcription: String = ""
    ) = BoxResult(
        orderIndex = orderIndex,
        question = question(orderIndex + 1, maxMarks),
        outcome = BoxOutcome.NeedsReview(reason, transcription, feedback = "")
    )

    // ---- marks -------------------------------------------------------------

    @Test
    fun `marks are summed across scored boxes`() {
        val grade = mergeBoxResults(listOf(scored(0, 3), scored(1, 4)))

        assertThat(grade.obtainedMarks).isEqualTo(7)
        assertThat(grade.totalMarks).isEqualTo(10)
    }

    @Test
    fun `a box needing review contributes no marks but keeps its max in the total`() {
        val grade = mergeBoxResults(listOf(scored(0, 5), needsReview(1)))

        assertThat(grade.obtainedMarks).isEqualTo(5)
        // The ceiling is still 10: the box exists, it just has no mark yet.
        assertThat(grade.totalMarks).isEqualTo(10)
    }

    @Test
    fun `a reviewed box and a box genuinely scored zero are never conflated`() {
        val reviewed = mergeBoxResults(listOf(needsReview(0)))
        val zeroed = mergeBoxResults(listOf(scored(0, marks = 0)))

        // Identical arithmetic, deliberately different meaning.
        assertThat(reviewed.obtainedMarks).isEqualTo(zeroed.obtainedMarks)

        assertThat(reviewed.needsManualReview).isTrue()
        assertThat(zeroed.needsManualReview).isFalse()
        assertThat(reviewed.boxesNeedingReview).hasSize(1)
        assertThat(zeroed.boxesNeedingReview).isEmpty()
        assertThat(reviewed.feedback).contains("not marked")
        assertThat(zeroed.feedback).contains("(0/5)")
        assertThat(zeroed.feedback).doesNotContain("not marked")
    }

    // ---- confidence --------------------------------------------------------

    @Test
    fun `confidence is the minimum, not the mean`() {
        val grade = mergeBoxResults(
            listOf(
                scored(0, 5, certainty = 0.99),
                scored(1, 5, certainty = 0.99),
                scored(2, 5, certainty = 0.20)
            )
        )

        // A mean would report 0.73 and bury the box the model was guessing at.
        assertThat(grade.confidence).isEqualTo(0.20)
    }

    @Test
    fun `one box needing review drops confidence to zero`() {
        val grade = mergeBoxResults(listOf(scored(0, 5, certainty = 1.0), needsReview(1)))

        assertThat(grade.confidence).isEqualTo(0.0)
    }

    @Test
    fun `all boxes confident gives the lowest of them, not one`() {
        val grade = mergeBoxResults(listOf(scored(0, 5, certainty = 0.8), scored(1, 5, certainty = 0.6)))

        assertThat(grade.confidence).isEqualTo(0.6)
    }

    // ---- the review flag ---------------------------------------------------

    @Test
    fun `every box scored means no manual review`() {
        val grade = mergeBoxResults(listOf(scored(0, 1), scored(1, 2), scored(2, 3)))

        assertThat(grade.needsManualReview).isFalse()
        assertThat(grade.boxesNeedingReview).isEmpty()
    }

    @Test
    fun `a single unreadable box out of many flags the whole submission`() {
        val grade = mergeBoxResults(
            listOf(scored(0, 5), scored(1, 5), scored(2, 5), needsReview(3), scored(4, 5))
        )

        assertThat(grade.needsManualReview).isTrue()
        assertThat(grade.boxesNeedingReview.map { it.orderIndex }).containsExactly(3)
        assertThat(grade.obtainedMarks).isEqualTo(20)
    }

    // ---- ordering and feedback --------------------------------------------

    @Test
    fun `boxes come back in orderIndex order however they arrive`() {
        val grade = mergeBoxResults(listOf(scored(2, 1), scored(0, 1), scored(1, 1)))

        assertThat(grade.boxes.map { it.orderIndex }).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `feedback is one line per box, in order, each naming its question`() {
        val grade = mergeBoxResults(
            listOf(
                scored(0, 3, feedback = "Close."),
                needsReview(1, reason = "Could not read it.")
            )
        )

        val lines = grade.feedback.lines()
        assertThat(lines).hasSize(2)
        assertThat(lines[0]).isEqualTo("Q1 (3/5): Close.")
        assertThat(lines[1]).isEqualTo("Q2 (not marked - needs review): Could not read it.")
    }

    // ---- degenerate input --------------------------------------------------

    @Test
    fun `no boxes is a flagged worksheet, not a zero-mark one`() {
        val grade = mergeBoxResults(emptyList())

        assertThat(grade.needsManualReview).isTrue()
        assertThat(grade.confidence).isEqualTo(0.0)
        assertThat(grade.obtainedMarks).isEqualTo(0)
        assertThat(grade.totalMarks).isEqualTo(0)
        assertThat(grade.boxes).isEmpty()
    }

    @Test
    fun `every box needing review scores zero and flags, with nothing scored`() {
        val grade = mergeBoxResults(listOf(needsReview(0), needsReview(1)))

        assertThat(grade.obtainedMarks).isEqualTo(0)
        assertThat(grade.totalMarks).isEqualTo(10)
        assertThat(grade.needsManualReview).isTrue()
        assertThat(grade.boxesNeedingReview).hasSize(2)
    }

    // ---- what reaches the server ------------------------------------------

    @Test
    fun `transcription survives both outcomes for the upload`() {
        val grade = mergeBoxResults(
            listOf(
                scored(0, 5, transcription = "56"),
                needsReview(1, transcription = "5b?")
            )
        )

        // answers[].transcription is the only place per-question detail can go:
        // the grade row itself carries no question_id.
        assertThat(grade.boxes.map { it.transcription }).containsExactly("56", "5b?").inOrder()
    }

    @Test
    fun `obtained marks never exceed total marks, which the server rejects with a 400`() {
        val grade = mergeBoxResults(listOf(scored(0, 5, maxMarks = 5), scored(1, 2, maxMarks = 3)))

        assertThat(grade.obtainedMarks).isAtMost(grade.totalMarks)
    }

    @Test
    fun `confidence stays inside the range the server accepts`() {
        val grade = mergeBoxResults(listOf(scored(0, 1, certainty = 0.0), scored(1, 1, certainty = 1.0)))

        assertThat(grade.confidence).isAtLeast(0.0)
        assertThat(grade.confidence).isAtMost(1.0)
    }
}
