package com.example.capstone.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.capstone.domain.grading.BoxOutcome
import com.example.capstone.domain.grading.BoxResult
import com.example.capstone.domain.grading.WorksheetGrade
import kotlin.math.roundToInt

/**
 * Marks a scanned worksheet box by box, then sends the result.
 *
 * The list fills in as the model works rather than after it, because on-device
 * inference for a whole worksheet is measured in tens of seconds and a spinner
 * for all of it tells the student nothing about whether anything is working.
 *
 * A box needing review and a box scored zero are rendered differently on
 * purpose, and never collapse into each other: zero means the answer was wrong,
 * review means nobody has established what the answer was.
 */
@Composable
fun WorksheetGradingScreen(
    onDone: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorksheetGradingViewModel = viewModel(factory = WorksheetGradingViewModel.Factory)
) {
    val uiState = viewModel.uiState

    // Navigation from a state change belongs in a LaunchedEffect: called from
    // the composition body it fires again on every recomposition.
    LaunchedEffect(uiState) {
        if (uiState is WorksheetGradingUiState.Submitted) onDone()
    }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("Marking") },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) { Text("Back") }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            when (uiState) {
                is WorksheetGradingUiState.Preparing -> Centered {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Matching the answer boxes to the questions…")
                }

                is WorksheetGradingUiState.Grading -> {
                    Text(
                        text = "Marked ${uiState.results.size} of ${uiState.total}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (uiState.total == 0) 0f
                            else uiState.results.size.toFloat() / uiState.total
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    BoxList(uiState.results, Modifier.weight(1f))
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { viewModel.cancel() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Stop marking")
                    }
                }

                is WorksheetGradingUiState.Cancelled -> {
                    Text(
                        text = "Marking stopped. ${uiState.results.size} " +
                            "${boxWord(uiState.results.size)} marked.",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    BoxList(uiState.results, Modifier.weight(1f))
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.start() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Mark again from the start")
                    }
                }

                is WorksheetGradingUiState.Graded -> Summary(
                    grade = uiState.grade,
                    busy = false,
                    error = null,
                    actionLabel = "Submit",
                    onAction = { viewModel.upload() },
                    modifier = Modifier.weight(1f)
                )

                is WorksheetGradingUiState.Uploading -> Summary(
                    grade = uiState.grade,
                    busy = true,
                    error = null,
                    actionLabel = "Submitting…",
                    onAction = {},
                    modifier = Modifier.weight(1f)
                )

                is WorksheetGradingUiState.UploadFailed -> Summary(
                    grade = uiState.grade,
                    busy = false,
                    error = uiState.detail,
                    actionLabel = "Try submitting again",
                    onAction = { viewModel.upload() },
                    modifier = Modifier.weight(1f)
                )

                // Terminal: onDone() has already fired from the LaunchedEffect.
                is WorksheetGradingUiState.Submitted -> Centered {
                    CircularProgressIndicator()
                }

                is WorksheetGradingUiState.Failed -> Centered {
                    Text(
                        text = uiState.message,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    if (uiState.detail != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = uiState.detail,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onNavigateBack) { Text("Back") }
                }
            }
        }
    }
}

@Composable
private fun Summary(
    grade: WorksheetGrade,
    busy: Boolean,
    error: String?,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${grade.obtainedMarks} / ${grade.totalMarks}",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "Confidence ${(grade.confidence * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodyMedium
        )

        if (grade.needsManualReview) {
            Spacer(Modifier.height(8.dp))
            // Said in full: the mark shown above is not the whole worksheet, and
            // the boxes missing from it were not scored zero.
            Text(
                text = "${grade.boxesNeedingReview.size} " +
                    "${boxWord(grade.boxesNeedingReview.size)} could not be marked here and " +
                    "${if (grade.boxesNeedingReview.size == 1) "is" else "are"} not " +
                    "counted in that total. A teacher will mark " +
                    "${if (grade.boxesNeedingReview.size == 1) "it" else "them"}.",
                style = MaterialTheme.typography.bodyMedium,
                color = REVIEW_COLOR
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    BoxList(grade.boxes, modifier)
    Spacer(Modifier.height(16.dp))

    if (error != null) {
        Text(
            text = error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(8.dp))
    }

    Button(
        onClick = onAction,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text(actionLabel)
        }
    }
}

@Composable
private fun BoxList(results: List<BoxResult>, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(results, key = { it.orderIndex }) { result ->
            BoxCard(result)
        }
    }
}

@Composable
private fun BoxCard(result: BoxResult) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = result.question.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                when (val outcome = result.outcome) {
                    is BoxOutcome.Scored -> Text(
                        text = "${outcome.marks} / ${result.maxMarks}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    // Never "0 / n". This box has no mark, and showing one would
                    // read as an answer that scored nothing.
                    is BoxOutcome.NeedsReview -> Text(
                        text = "– / ${result.maxMarks}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = REVIEW_COLOR
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            when (val outcome = result.outcome) {
                is BoxOutcome.Scored -> {
                    if (outcome.transcription.isNotBlank()) {
                        Text(
                            text = "Read as: ${outcome.transcription}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        text = outcome.feedback,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                is BoxOutcome.NeedsReview -> {
                    Text(
                        text = "Needs a teacher",
                        style = MaterialTheme.typography.labelLarge,
                        color = REVIEW_COLOR
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = outcome.reason,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/** Amber, distinct from both the scored text colour and the error red. */
private val REVIEW_COLOR = Color(0xFFB26A00)

private fun boxWord(count: Int) = if (count == 1) "answer" else "answers"

@Composable
private fun ColumnScope.Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content
    )
}
