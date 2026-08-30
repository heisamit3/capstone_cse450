package com.example.capstone.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.capstone.CapstoneApplication
import com.example.capstone.data.repository.AuthRepository
import kotlinx.coroutines.launch

sealed interface RegisterUiState {
    object Idle : RegisterUiState
    object Loading : RegisterUiState
    data class Success(val message: String) : RegisterUiState
    data class Error(val message: String) : RegisterUiState
}

class RegisterViewModel(private val authRepository: AuthRepository) : ViewModel() {
    var email by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set
    var confirmPassword by mutableStateOf("")
        private set

    var uiState: RegisterUiState by mutableStateOf(RegisterUiState.Idle)
        private set

    fun updateEmail(value: String) {
        email = value
    }

    fun updatePassword(value: String) {
        password = value
    }

    fun updateConfirmPassword(value: String) {
        confirmPassword = value
    }

    fun register() {
        if (email.isBlank() || password.isBlank()) {
            uiState = RegisterUiState.Error("Email and password are required")
            return
        }

        if (password != confirmPassword) {
            uiState = RegisterUiState.Error("Passwords do not match")
            return
        }

        // Mirrors the server's Zod schema (auth.schemas.ts). Checking here turns a
        // 400 "Validation failed" round trip into an immediate, specific message.
        if (!EMAIL_PATTERN.matches(email.trim())) {
            uiState = RegisterUiState.Error("Enter a valid email address")
            return
        }

        if (password.length < MIN_PASSWORD_LENGTH) {
            uiState = RegisterUiState.Error(
                "Password must be at least $MIN_PASSWORD_LENGTH characters"
            )
            return
        }

        viewModelScope.launch {
            uiState = RegisterUiState.Loading
            val result = authRepository.register(email.trim(), password)
            uiState = if (result.isSuccess) {
                RegisterUiState.Success("Registration successful")
            } else {
                RegisterUiState.Error(result.exceptionOrNull()?.message ?: "Registration failed")
            }
        }
    }

    companion object {
        /** Server-side minimum, from `registerSchema` in auth.schemas.ts. */
        const val MIN_PASSWORD_LENGTH = 8

        private val EMAIL_PATTERN = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as CapstoneApplication)
                RegisterViewModel(authRepository = application.container.authRepository)
            }
        }
    }
}
