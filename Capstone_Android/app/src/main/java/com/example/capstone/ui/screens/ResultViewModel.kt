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
import com.example.capstone.data.repository.AssignmentRepository
import com.example.capstone.domain.model.Grade
import kotlinx.coroutines.launch

sealed interface ResultUiState {
    object Loading : ResultUiState
    data class Success(val grades: List<Grade>) : ResultUiState
    data class Error(val message: String) : ResultUiState
}

class ResultViewModel(private val assignmentRepository: AssignmentRepository) : ViewModel() {
    var uiState: ResultUiState by mutableStateOf(ResultUiState.Loading)
        private set

    init {
        loadGrades()
    }

    fun loadGrades() {
        viewModelScope.launch {
            uiState = ResultUiState.Loading
            val result = assignmentRepository.getMyGrades()
            uiState = if (result.isSuccess) {
                ResultUiState.Success(result.getOrThrow())
            } else {
                ResultUiState.Error("Failed to load grades and feedback")
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as CapstoneApplication)
                ResultViewModel(assignmentRepository = application.container.assignmentRepository)
            }
        }
    }
}
