package com.example.capstone.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubmitScreen(
    onSubmissionSuccess: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SubmitViewModel = viewModel(factory = SubmitViewModel.Factory),
    assignmentViewModel: AssignmentDetailViewModel = viewModel(factory = AssignmentDetailViewModel.Factory)
) {
    val uiState = viewModel.uiState
    val assignmentState = assignmentViewModel.uiState
    val photoBytes = viewModel.photoBytes

    val bitmap = remember(photoBytes) {
        photoBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Submit Worksheet") },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text("Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Captured Worksheet",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No image data found")
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            if (uiState is SubmitUiState.Error) {
                Text(text = uiState.message, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(8.dp))
            }

            val isAssignmentLoaded = assignmentState is AssignmentDetailUiState.Success
            
            Button(
                onClick = { 
                    if (assignmentState is AssignmentDetailUiState.Success) {
                        viewModel.submit(assignmentState.assignment.questions.map { it.id })
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState !is SubmitUiState.Loading && isAssignmentLoaded && photoBytes != null
            ) {
                if (uiState is SubmitUiState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Upload and Submit")
                }
            }

            if (uiState is SubmitUiState.Success) {
                onSubmissionSuccess()
            }
        }
    }
}
