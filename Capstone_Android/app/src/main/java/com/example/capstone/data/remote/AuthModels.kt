package com.example.capstone.data.remote

data class UserDto(
    val id: Int,
    val email: String,
    val role: String
)

data class AuthResponse(
    val user: UserDto,
    val token: String
)

data class ErrorResponse(
    val error: String
)

/**
 * Both server error shapes in one type: `{ error }` from HttpError and
 * `{ error, issues[] }` from a Zod rejection. Every field is nullable because
 * a failure response is exactly when the shape cannot be trusted.
 */
data class ServerError(
    val error: String? = null,
    val issues: List<ServerErrorIssue>? = null
)

data class ServerErrorIssue(
    val message: String? = null,
    val path: List<String>? = null
)
