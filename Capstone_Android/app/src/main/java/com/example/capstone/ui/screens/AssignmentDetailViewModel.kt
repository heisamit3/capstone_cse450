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
import com.example.capstone.domain.model.Assignment
import kotlinx.coroutines.launch

sealed interface AssignmentDetailUiState {
    object Loading : AssignmentDetailUiState
    data class Success(val assignment: Assignment) : AssignmentDetailUiState
    data class Error(val message: String) : AssignmentDetailUiState
}

class AssignmentDetailViewModel(
    private val assignmentRepository: AssignmentRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val assignmentId: Int = checkNotNull(savedStateHandle.get<String>("assignmentId")?.toInt())
    
    var uiState: AssignmentDetailUiState by mutableStateOf(AssignmentDetailUiState.Loading)
        private set

    init {
        loadAssignment()
    }

    fun loadAssignment() {
        viewModelScope.launch {
            uiState = AssignmentDetailUiState.Loading
            val result = assignmentRepository.getAssignmentDetail(assignmentId)
            uiState = if (result.isSuccess) {
                AssignmentDetailUiState.Success(result.getOrThrow())
            } else {
                AssignmentDetailUiState.Error("Failed to load assignment details")
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as CapstoneApplication)
                val savedStateHandle = this.createSavedStateHandle()
                AssignmentDetailViewModel(
                    assignmentRepository = application.container.assignmentRepository,
                    savedStateHandle = savedStateHandle
                )
            }
        }
    }
}
