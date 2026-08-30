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
import com.example.capstone.domain.model.Assignment
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

sealed interface HomeUiState {
    object Loading : HomeUiState
    data class Success(
        val pendingAssignments: List<Assignment>,
        val completedAssignments: List<Assignment>
    ) : HomeUiState
    data class Error(val message: String) : HomeUiState
}

class HomeViewModel(private val assignmentRepository: AssignmentRepository) : ViewModel() {
    var uiState: HomeUiState by mutableStateOf(HomeUiState.Loading)
        private set

    init {
        loadHomeData()
    }

    fun loadHomeData() {
        viewModelScope.launch {
            uiState = HomeUiState.Loading
            
            val assignmentsResult = async { assignmentRepository.getAssignments() }
            val submissionsResult = async { assignmentRepository.getMySubmissions() }

            val assignments = assignmentsResult.await()
            val submissions = submissionsResult.await()

            if (assignments.isSuccess && submissions.isSuccess) {
                val allAssignments = assignments.getOrThrow()
                val mySubmissions = submissions.getOrThrow()
                
                val submittedIds = mySubmissions.map { it.assignmentId }.toSet()
                
                val pending = allAssignments.filter { it.id !in submittedIds }
                val completed = allAssignments.filter { it.id in submittedIds }
                
                uiState = HomeUiState.Success(pending, completed)
            } else {
                // Name the actual failure: without it a 401, a refused connection
                // and a parse error are indistinguishable on screen.
                val cause = assignments.exceptionOrNull() ?: submissions.exceptionOrNull()
                uiState = HomeUiState.Error(
                    "Failed to load assignments: " +
                        (cause?.message ?: cause?.javaClass?.simpleName ?: "unknown error")
                )
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as CapstoneApplication)
                HomeViewModel(assignmentRepository = application.container.assignmentRepository)
            }
        }
    }
}
