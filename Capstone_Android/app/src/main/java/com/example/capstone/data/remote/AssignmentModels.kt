package com.example.capstone.data.remote

import com.google.gson.annotations.SerializedName

data class QuestionDto(
    val id: Int,
    @SerializedName("question_text")
    val questionText: String
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
    val submissionStatus: String? // "pending" or "completed"
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
