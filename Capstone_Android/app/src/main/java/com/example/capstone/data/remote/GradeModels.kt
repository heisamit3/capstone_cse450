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
