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
}
