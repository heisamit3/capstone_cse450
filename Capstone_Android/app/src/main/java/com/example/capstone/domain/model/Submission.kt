package com.example.capstone.domain.model

data class Submission(
    val id: Int,
    val assignmentId: Int,
    val status: String,
    val submittedAt: String
)
