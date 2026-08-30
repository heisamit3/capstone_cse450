package com.example.capstone.data.repository

import android.net.Uri
import com.example.capstone.data.remote.ApiService
import com.example.capstone.domain.model.Assignment
import com.example.capstone.domain.model.Grade
import com.example.capstone.domain.model.Question
import com.example.capstone.domain.model.Submission
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class AssignmentRepository(private val apiService: ApiService) {

    suspend fun getAssignments(): Result<List<Assignment>> {
        return try {
            val dtos = apiService.getAssignments()
            Result.success(dtos.map { dto ->
                Assignment(
                    id = dto.id,
                    title = dto.title,
                    description = dto.description,
                    questions = dto.questions?.map { q -> Question(q.id, q.questionText) } ?: emptyList(),
                    isCompleted = dto.submissionStatus == "completed"
                )
            })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAssignmentDetail(id: Int): Result<Assignment> {
        return try {
            val dto = apiService.getAssignment(id)
            Result.success(
                Assignment(
                    id = dto.id,
                    title = dto.title,
                    description = dto.description,
                    questions = dto.questions?.map { q -> Question(q.id, q.questionText) } ?: emptyList(),
                    isCompleted = dto.submissionStatus == "completed"
                )
            )
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

    suspend fun submitAssignment(assignmentId: Int, questionImages: Map<Int, Uri>): Result<Unit> {
        // ... (existing implementation)
        return Result.failure(Exception("Not implemented for Uri"))
    }

    suspend fun submitAssignmentWithBytes(
        assignmentId: Int,
        questionIds: List<Int>,
        imageBytes: ByteArray
    ): Result<Unit> {
        return try {
            val partMap = mutableMapOf<String, okhttp3.RequestBody>()
            partMap["assignment_id"] =
                assignmentId.toString().toRequestBody("text/plain".toMediaTypeOrNull())

            val answersList = questionIds.map { qId ->
                mapOf("question_id" to qId, "answer_text" to "")
            }
            partMap["answers"] =
                Gson().toJson(answersList).toRequestBody("application/json".toMediaTypeOrNull())

            val fileParts = questionIds.map { qId ->
                val requestFile = imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                MultipartBody.Part.createFormData("image_$qId", "capture.jpg", requestFile)
            }

            val response = apiService.submitAssignment(partMap, fileParts)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Upload failed: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
