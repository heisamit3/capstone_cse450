package com.example.capstone.data.repository

import com.example.capstone.data.local.TokenManager
import com.example.capstone.data.remote.ApiService
import com.example.capstone.data.remote.ServerError
import com.google.gson.Gson
import retrofit2.Response

class AuthRepository(
    private val apiService: ApiService,
    private val tokenManager: TokenManager
) {
    suspend fun login(email: String, password: String): Result<Unit> {
        return try {
            val response = apiService.login(mapOf("email" to email, "password" to password))
            handleAuthResponse(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun register(email: String, password: String): Result<Unit> {
        return try {
            val response = apiService.register(
                mapOf("email" to email, "password" to password, "role" to "student")
            )
            handleAuthResponse(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun handleAuthResponse(response: Response<com.example.capstone.data.remote.AuthResponse>): Result<Unit> {
        return if (response.isSuccessful) {
            val body = response.body()
            if (body != null) {
                tokenManager.saveToken(body.token)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Empty response body"))
            }
        } else {
            Result.failure(Exception(describeFailure(response)))
        }
    }

    /**
     * Builds a message that always names the HTTP status, so a failure can be
     * acted on without attaching a debugger.
     *
     * The server speaks two error shapes: `{ error }` from HttpError, and
     * `{ error, issues[] }` from a Zod rejection. Zod's `error` is only ever the
     * constant "Validation failed", so the issues are what actually say what was
     * wrong - they are flattened into the message rather than dropped.
     * Anything unrecognised falls back to the raw body, truncated.
     */
    private fun describeFailure(response: Response<*>): String {
        val code = response.code()
        val body = try {
            response.errorBody()?.string().orEmpty()
        } catch (e: Exception) {
            ""
        }

        if (body.isBlank()) return "HTTP $code (empty response body)"

        val parsed = try {
            Gson().fromJson(body, ServerError::class.java)
        } catch (e: Exception) {
            null
        }

        val issues = parsed?.issues.orEmpty()
        val serverMessage = parsed?.error?.takeIf { it.isNotBlank() }

        val detail = when {
            issues.isNotEmpty() -> {
                val rendered = issues.joinToString("; ") { issue ->
                    val field = issue.path?.joinToString(".").orEmpty()
                    val message = issue.message.orEmpty()
                    if (field.isBlank()) message else "$field: $message"
                }
                "${serverMessage.orEmpty()} ($rendered)".trim()
            }
            serverMessage != null -> serverMessage
            else -> body.take(MAX_BODY_CHARS)
        }

        return "HTTP $code: $detail"
    }

    private companion object {
        /** Enough to identify an unexpected body without flooding the UI. */
        const val MAX_BODY_CHARS = 300
    }

    suspend fun logout() {
        tokenManager.clearToken()
    }
}
