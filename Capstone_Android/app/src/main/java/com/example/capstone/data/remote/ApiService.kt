package com.example.capstone.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    @POST("auth/login")
    suspend fun login(@Body request: Map<String, String>): Response<AuthResponse>

    @POST("auth/register")
    suspend fun register(@Body request: Map<String, String>): Response<AuthResponse>

    @GET("assignments")
    suspend fun getAssignments(@Query("status") status: String? = null): List<AssignmentDto>

    @GET("assignments/{id}")
    suspend fun getAssignment(@Path("id") id: Int): AssignmentDto

    @GET("submissions/me")
    suspend fun getMySubmissions(): List<SubmissionDto>

    @GET("submissions/me/{id}")
    suspend fun getSubmission(@Path("id") id: Int): SubmissionDto

    @Multipart
    @POST("submissions")
    suspend fun submitAssignment(
        @PartMap parts: Map<String, @JvmSuppressWildcards RequestBody>,
        @Part files: List<MultipartBody.Part>
    ): Response<SubmissionDto>

    /**
     * Records what the phone graded, against a submission that already exists.
     *
     * Keyed on the submission id, which means the multipart upload's response
     * body is load bearing and cannot be discarded - see
     * [com.example.capstone.data.repository.AssignmentRepository.submitAnswers].
     *
     * Answers 201 the first time and 200 on a repeat; both are successful, so
     * callers must check [Response.isSuccessful] rather than the code.
     */
    @POST("submissions/{id}/grade")
    suspend fun submitGrade(
        @Path("id") submissionId: Int,
        @Body request: SubmissionGradeRequest
    ): Response<GradeDto>
}
