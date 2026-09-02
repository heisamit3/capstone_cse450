package com.example.capstone.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.capstone.domain.model.Assignment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAssignmentClick: (Int) -> Unit,
    onViewResults: () -> Unit,
    onOpenModelTest: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
) {
    val uiState = viewModel.uiState
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("Pending", "Completed")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Assignments") },
                actions = {
                    // TEMPORARY debug button. Remove with ModelTestScreen.
                    TextButton(onClick = onOpenModelTest) {
                        Text("Model")
                    }
                    TextButton(onClick = onViewResults) {
                        Text("Results")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = modifier.padding(innerPadding)) {
            TabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title) }
                    )
                }
            }

            when (uiState) {
                is HomeUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is HomeUiState.Success -> {
                    val list = if (selectedTabIndex == 0) uiState.pendingAssignments else uiState.completedAssignments
                    AssignmentList(assignments = list, onAssignmentClick = onAssignmentClick)
                }
                is HomeUiState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = uiState.message, color = MaterialTheme.colorScheme.error)
                            Button(onClick = { viewModel.loadHomeData() }) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AssignmentList(
    assignments: List<Assignment>,
    onAssignmentClick: (Int) -> Unit
) {
    if (assignments.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No assignments found")
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(assignments) { assignment ->
                AssignmentItem(assignment = assignment, onClick = { onAssignmentClick(assignment.id) })
            }
        }
    }
}

@Composable
fun AssignmentItem(assignment: Assignment, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = assignment.title, style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = assignment.description.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2
            )
        }
    }
}
