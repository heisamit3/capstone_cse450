package com.example.capstone.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.capstone.CapstoneApplication
import com.example.capstone.data.repository.AssignmentRepository
import kotlinx.coroutines.launch

sealed interface SubmitUiState {
    object Idle : SubmitUiState
    object Loading : SubmitUiState
    object Success : SubmitUiState
    data class Error(val message: String) : SubmitUiState
}

class SubmitViewModel(
    private val assignmentRepository: AssignmentRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    val assignmentId: Int = checkNotNull(savedStateHandle.get<String>("assignmentId")?.toInt())
    
    // We'll store the photo bytes in memory for the submission flow
    var photoBytes by mutableStateOf<ByteArray?>(null)
    
    var uiState: SubmitUiState by mutableStateOf(SubmitUiState.Idle)
        private set

    fun submit(questionIds: List<Int>) {
        val bytes = photoBytes ?: return
        viewModelScope.launch {
            uiState = SubmitUiState.Loading
            val result = assignmentRepository.submitAssignmentWithBytes(assignmentId, questionIds, bytes)
            uiState = if (result.isSuccess) {
                SubmitUiState.Success
            } else {
                SubmitUiState.Error(result.exceptionOrNull()?.message ?: "Submission failed")
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as CapstoneApplication)
                SubmitViewModel(
                    assignmentRepository = application.container.assignmentRepository,
                    savedStateHandle = this.createSavedStateHandle()
                )
            }
        }
    }
}
