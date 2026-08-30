package com.example.capstone.domain.model

data class Question(
    val id: Int,
    val text: String
)

data class Assignment(
    val id: Int,
    val title: String,
    /** Optional: the server column is nullable. */
    val description: String?,
    val questions: List<Question> = emptyList(),
    val isCompleted: Boolean = false
)
