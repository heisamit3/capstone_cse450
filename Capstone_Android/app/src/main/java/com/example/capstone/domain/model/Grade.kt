package com.example.capstone.domain.model

data class Grade(
    val id: Int,
    val submissionId: Int,
    val assignmentId: Int,
    val grade: String,
    val feedback: String
)
