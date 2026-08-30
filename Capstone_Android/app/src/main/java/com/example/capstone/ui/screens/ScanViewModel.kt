package com.example.capstone.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

sealed interface ScanUiState {
    object Idle : ScanUiState
    data class Captured(val bytes: ByteArray) : ScanUiState
}

class ScanViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    val assignmentId: Int = checkNotNull(savedStateHandle.get<String>("assignmentId")?.toInt())
    
    var uiState: ScanUiState by mutableStateOf(ScanUiState.Idle)
        private set

    fun onImageCaptured(bytes: ByteArray) {
        uiState = ScanUiState.Captured(bytes)
    }

    fun reset() {
        uiState = ScanUiState.Idle
    }
}
