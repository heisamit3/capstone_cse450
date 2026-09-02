package com.example.capstone.data.remote

import com.google.gson.annotations.SerializedName

/**
 * A question as the server serves it.
 *
 * Two endpoints serve this type and they serve different subsets:
 *
 * - `GET /assignments` (student) selects only `id` and `question_text`.
 * - `GET /assignments/{id}` (student) additionally selects `marks`,
 *   `model_answer`, `rubric` and `external_answer_box_id`.
 *
 * So everything beyond [id] and [questionText] is nullable, and null means
 * "this endpoint did not send it" rather than "the server has no value". The
 * distinction matters for [modelAnswer], where the server also uses an empty
 * string to mean "imported from the teacher worksheet system, teacher has not
 * supplied the answer yet". Both are ungradeable; only one is a wiring bug.
 */
data class QuestionDto(
    val id: Int,
    @SerializedName("question_text")
    val questionText: String,
    /** Marks available for this question. Absent from the list endpoint. */
    val marks: Int? = null,
    @SerializedName("model_answer")
    val modelAnswer: String? = null,
    val rubric: String? = null,
    /**
     * The teacher worksheet system's answer box id, when this question was
     * imported from it. Null for locally created questions.
     *
     * Only ever meaningful together with the assignment it came back on: on the
     * teacher side these ids are a global primary key today only because a
     * collision crashes his insert rather than coexisting. Match on the pair.
     */
    @SerializedName("external_answer_box_id")
    val externalAnswerBoxId: String? = null
)

data class AssignmentDto(
    val id: Int,
    val title: String,
    // Nullable in Postgres and in the Prisma schema. Gson writes null straight
    // into a non-null Kotlin field, so declaring it non-null here does not
    // prevent the null - it only moves the crash to the first read.
    val description: String?,
    @SerializedName("total_marks")
    val totalMarks: Int,
    val questions: List<QuestionDto>?,
    @SerializedName("submission_status")
    val submissionStatus: String?, // "pending" or "completed"
    /**
     * The teacher worksheet system question id this assignment was imported
     * from. Served by the detail endpoint only; null for anything created
     * locally. Half of the join key - see [QuestionDto.externalAnswerBoxId].
     */
    @SerializedName("external_question_id")
    val externalQuestionId: String? = null,
    /**
     * Printed-page geometry. Served only by `GET /assignments/{id}` (student),
     * and only for assignments imported from the teacher worksheet system.
     */
    val layout: LayoutDto? = null
)

data class SubmissionAnswerDto(
    val id: Int,
    @SerializedName("question_id")
    val questionId: Int,
    @SerializedName("answer_text")
    val answerText: String,
    @SerializedName("answer_image_path")
    val answerImagePath: String
)

data class SubmissionDto(
    val id: Int,
    @SerializedName("assignment_id")
    val assignmentId: Int,
    @SerializedName("student_id")
    val studentId: Int,
    @SerializedName("submitted_at")
    val submittedAt: String,
    val answers: List<SubmissionAnswerDto>?,
    val grades: List<GradeDto>?,
    val status: String? // "pending" or "completed"
)
