package com.example.capstone.data.repository

import com.example.capstone.data.remote.ApiService
import com.example.capstone.data.remote.AssignmentDto
import com.example.capstone.data.remote.GradedAnswerRequest
import com.example.capstone.data.remote.LayoutDto
import com.example.capstone.data.remote.QuestionDto
import com.example.capstone.data.remote.SubmissionGradeRequest
import com.example.capstone.domain.model.Assignment
import com.example.capstone.domain.model.Grade
import com.example.capstone.domain.model.Question
import com.example.capstone.domain.model.Submission
import com.example.capstone.extractor.AnswerBoxRef
import com.example.capstone.extractor.Bbox
import com.example.capstone.extractor.Layout
import com.example.capstone.extractor.MarkerRef
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * One answer box's image, ready to upload.
 *
 * The bytes are the extractor's rectified crop, at the resolution it was cut at
 * - not the downscaled copy the model was shown. The crop is what the student
 * actually wrote; the downscale is an inference detail, and a teacher opening
 * this later should get the better of the two.
 */
data class AnswerUpload(
    val questionId: Int,
    val png: ByteArray,
    val fileName: String,
    /**
     * Declared rather than assumed. The server's `fileFilter` rejects anything
     * whose mimetype does not start with `image/` and stores the rest as sent,
     * so mislabelling JPEG bytes as PNG would file them under the wrong
     * extension for whoever opens them later.
     */
    val mimeType: String = "image/png"
) {
    // png is a ByteArray, so the generated equals/hashCode would compare it by
    // identity. Two uploads of the same bytes are not equal; compare questionId.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

class AssignmentRepository(private val apiService: ApiService) {

    suspend fun getAssignments(): Result<List<Assignment>> {
        return try {
            val dtos = apiService.getAssignments()
            Result.success(dtos.map { dto -> dto.toDomain() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAssignmentDetail(id: Int): Result<Assignment> {
        return try {
            val dto = apiService.getAssignment(id)
            Result.success(dto.toDomain())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMySubmissions(): Result<List<Submission>> {
        return try {
            val dtos = apiService.getMySubmissions()
            Result.success(dtos.map { dto ->
                Submission(
                    id = dto.id,
                    assignmentId = dto.assignmentId,
                    status = dto.status ?: "pending",
                    submittedAt = dto.submittedAt
                )
            })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMyGrades(): Result<List<Grade>> {
        return try {
            val submissions = apiService.getMySubmissions()
            val allGrades = mutableListOf<Grade>()
            submissions.forEach { sub ->
                sub.grades?.forEach { g ->
                    allGrades.add(Grade(g.id, g.submissionId, g.assignmentId, g.grade, g.feedback))
                }
            }
            Result.success(allGrades)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Uploads one image per question and returns the **submission id**.
     *
     * The id is the whole reason this returns an Int. `POST /submissions/{id}/grade`
     * is keyed on it, and the previous version of this method threw the response
     * body away and returned `Result<Unit>`, which made the grade route
     * unreachable from the app no matter what else was built.
     *
     * The server's shape is one file part named `image_<question_id>` per
     * question, plus an `answers` JSON array naming those same question ids. It
     * rejects the request if any answer has no image, if a question id repeats,
     * or if a question id does not belong to the assignment - so [answers] must
     * be exactly one entry per question being submitted.
     *
     * `answer_text` is sent empty here on purpose. The transcription is not
     * known until grading has run, and it is written afterwards by
     * [submitGrade], which is the only path that has one.
     */
    suspend fun submitAnswers(
        assignmentId: Int,
        answers: List<AnswerUpload>
    ): Result<Int> {
        return try {
            val parts = mutableMapOf<String, okhttp3.RequestBody>()
            parts["assignment_id"] =
                assignmentId.toString().toRequestBody("text/plain".toMediaTypeOrNull())

            val answersList = answers.map { upload ->
                mapOf("question_id" to upload.questionId, "answer_text" to "")
            }
            parts["answers"] =
                Gson().toJson(answersList).toRequestBody("application/json".toMediaTypeOrNull())

            val fileParts = answers.map { upload ->
                MultipartBody.Part.createFormData(
                    "image_${upload.questionId}",
                    upload.fileName,
                    upload.png.toRequestBody(upload.mimeType.toMediaTypeOrNull())
                )
            }

            val response = apiService.submitAssignment(parts, fileParts)
            val body = response.body()
            when {
                !response.isSuccessful ->
                    Result.failure(Exception("Upload failed: ${response.code()} ${response.message()}"))
                // A 2xx with no parsable body is not a success we can build on:
                // without the id there is nothing to attach a grade to.
                body == null ->
                    Result.failure(Exception("Upload succeeded but returned no submission id"))
                else -> Result.success(body.id)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Records a finished grade against [submissionId].
     *
     * Must follow [submitAnswers]: the server rejects a transcription for a
     * question that has no answer row yet. 201 and 200 are both success - the
     * first created the grade row, the second updated one that already existed.
     */
    suspend fun submitGrade(
        submissionId: Int,
        obtainedMarks: Int,
        feedback: String,
        confidence: Double,
        transcriptions: List<GradedAnswerRequest>
    ): Result<Unit> {
        return try {
            val response = apiService.submitGrade(
                submissionId,
                SubmissionGradeRequest(
                    obtainedMarks = obtainedMarks,
                    feedback = feedback,
                    // The only value this app is entitled to write. "frontier_api"
                    // belongs to a cloud grader that does not exist yet, and
                    // "teacher_override" is the override route's alone.
                    confidence = confidence,
                    gradedBy = GRADED_BY_LOCAL_MODEL,
                    answers = transcriptions
                )
            )
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(
                    Exception("Grade upload failed: ${response.code()} ${response.message()}")
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private companion object {
        const val GRADED_BY_LOCAL_MODEL = "local_model"
    }
}

/**
 * Wire shape to domain shape.
 *
 * One mapper, used by both the list and the detail endpoint, because they serve
 * the same type with different subsets selected. Fields the list endpoint does
 * not select arrive null and stay null - nothing here invents a default for
 * them, because a fabricated mark ceiling or a fabricated model answer would be
 * graded against exactly as if it were real.
 */
private fun AssignmentDto.toDomain(): Assignment = Assignment(
    id = id,
    title = title,
    description = description,
    questions = questions?.map { it.toDomain() } ?: emptyList(),
    isCompleted = submissionStatus == "completed",
    externalQuestionId = externalQuestionId,
    // Both halves or neither. A layout with no external question id cannot be
    // paired with a crop, and the extractor requires the id to build one.
    layout = if (externalQuestionId != null) layout?.toExtractorLayout(externalQuestionId)
    else null
)

private fun QuestionDto.toDomain(): Question = Question(
    id = id,
    text = questionText,
    marks = marks,
    modelAnswer = modelAnswer,
    rubric = rubric,
    externalAnswerBoxId = externalAnswerBoxId
)

/**
 * Adapts the served layout onto the extractor's own [Layout].
 *
 * This function is the whole of the app side of the marker convention, and the
 * extractor is written so that it has to be: no page geometry is hardcoded in
 * that module, every number is an argument, so a wrong value is stated in one
 * place rather than assumed in many. Every number here came off the wire.
 *
 * Nothing is repaired on the way through. A malformed centre or bbox is passed
 * along as it arrived, for LayoutValidator to refuse: a layout quietly patched
 * into plausibility still registers four markers and still reports success,
 * and every crop it then produces is silently displaced.
 */
private fun LayoutDto.toExtractorLayout(externalQuestionId: String): Layout = Layout(
    externalQuestionId = externalQuestionId,
    pageWidthPx = pageWidthPx,
    pageHeightPx = pageHeightPx,
    // Stored JSON keys marker ids as strings. Order here carries no meaning -
    // correspondence with a detected marker is by id - but a key that is not an
    // integer, or a centre that is not a pair, is dropped so the validator sees
    // a marker set that is short rather than one that is wrong.
    markers = markers.centres.mapNotNull { (id, centre) ->
        val markerId = id.toIntOrNull()
        if (markerId == null || centre.size != 2) {
            null
        } else {
            MarkerRef(id = markerId, x = centre[0].toDouble(), y = centre[1].toDouble())
        }
    },
    // Array order is the teacher document order and is load bearing. Mapped in
    // place, never sorted; LayoutValidator checks it against the geometry.
    answerBoxes = answerBoxes.map { box ->
        AnswerBoxRef(
            externalAnswerBoxId = box.id,
            pageIndex = box.pageIndex,
            bbox = Bbox(
                x = box.bbox.getOrElse(0) { 0 },
                y = box.bbox.getOrElse(1) { 0 },
                w = box.bbox.getOrElse(2) { 0 },
                h = box.bbox.getOrElse(3) { 0 }
            )
        )
    }
)
