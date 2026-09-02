package com.example.capstone.data.remote

import com.google.gson.annotations.SerializedName

data class GradeDto(
    val id: Int,
    @SerializedName("submission_id")
    val submissionId: Int,
    @SerializedName("assignment_id")
    val assignmentId: Int,
    val grade: String,
    val feedback: String
)

/**
 * The body of `POST /submissions/{id}/grade`.
 *
 * The server grades nothing here; it validates this and stores it. Field names
 * and constraints are the server's `createSubmissionGradeSchema`:
 *
 * - [obtainedMarks] must be a non-negative int and must not exceed the
 *   assignment's `total_marks`, else 400.
 * - [feedback] is **required**, not optional.
 * - [confidence] must be within 0..1.
 * - [gradedBy] accepts only `local_model` or `frontier_api`. It does not accept
 *   `teacher_override`; only `PATCH /grades/{id}/override` ever writes that.
 * - every [answers] entry's `question_id` must belong to this submission's
 *   assignment, must not repeat, and must already have an answer row on the
 *   submission - so this call has to follow the multipart upload, never
 *   precede it.
 *
 * The call is idempotent by handler, not by schema: `Grade.submission_id` has no
 * unique constraint, so the server does find-then-update and answers 201 the
 * first time, 200 after that. Retrying a failed upload cannot leave two grades.
 */
data class SubmissionGradeRequest(
    @SerializedName("obtained_marks")
    val obtainedMarks: Int,
    val feedback: String,
    val confidence: Double,
    @SerializedName("graded_by")
    val gradedBy: String,
    val answers: List<GradedAnswerRequest>
)

/**
 * One graded answer. [transcription] overwrites that answer's `answer_text` on
 * the server, which is the only place per-question detail can live: the grade
 * row itself has no `question_id`.
 */
data class GradedAnswerRequest(
    @SerializedName("question_id")
    val questionId: Int,
    val transcription: String
)
