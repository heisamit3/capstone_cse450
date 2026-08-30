package com.example.capstone

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.capstone.ui.screens.*
import com.example.capstone.ui.theme.CapstoneTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CapstoneTheme {
                CapstoneApp()
            }
        }
    }
}

@Composable
fun CapstoneApp() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val application = context.applicationContext as CapstoneApplication
    val token by application.container.tokenManager.token.collectAsState(initial = null)

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "login",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("login") {
                LoginScreen(
                    onLoginSuccess = {
                        navController.navigate("home") {
                            popUpTo("login") { inclusive = true }
                        }
                    },
                    onNavigateToRegister = {
                        navController.navigate("register")
                    }
                )
            }
            composable("register") {
                RegisterScreen(
                    onRegisterSuccess = {
                        navController.navigate("home") {
                            popUpTo("login") { inclusive = true }
                        }
                    },
                    onNavigateToLogin = { navController.popBackStack() }
                )
            }
            composable("home") {
                HomeScreen(
                    onAssignmentClick = { assignmentId ->
                        navController.navigate("assignment_detail/$assignmentId")
                    },
                    onViewResults = {
                        navController.navigate("results")
                    },
                    // TEMPORARY: debug entry point for the on-device model.
                    onOpenModelTest = {
                        navController.navigate("model_test")
                    }
                )
            }
            // TEMPORARY debug route. Remove with ModelTestScreen.
            composable("model_test") {
                ModelTestScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("results") {
                ResultScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("assignment_detail/{assignmentId}") {
                AssignmentDetailScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onStartScan = { assignmentId ->
                        navController.navigate("scan/$assignmentId")
                    }
                )
            }
            composable("scan/{assignmentId}") {
                ScanScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onImageCaptured = { assignmentId, bytes ->
                        navController.currentBackStackEntry?.savedStateHandle?.set("photo_bytes", bytes)
                        navController.navigate("submit/$assignmentId")
                    }
                )
            }
            composable("submit/{assignmentId}") {
                val bytes = navController.previousBackStackEntry?.savedStateHandle?.get<ByteArray>("photo_bytes")
                SubmitScreen(
                    onSubmissionSuccess = {
                        navController.navigate("home") {
                            popUpTo("home") { inclusive = true }
                        }
                    },
                    onNavigateBack = { navController.popBackStack() },
                    viewModel = viewModel<SubmitViewModel>(factory = SubmitViewModel.Factory).apply {
                        if (bytes != null) this.photoBytes = bytes
                    }
                )
            }
        }
    }
}
