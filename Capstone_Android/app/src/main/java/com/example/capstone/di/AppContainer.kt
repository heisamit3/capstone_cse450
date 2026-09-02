package com.example.capstone.di

import android.content.Context
import com.example.capstone.BuildConfig
import com.example.capstone.data.local.LocalGradingService
import com.example.capstone.data.local.LocalModelProvider
import com.example.capstone.data.local.TokenManager
import com.example.capstone.data.remote.ApiService
import com.example.capstone.data.repository.AssignmentRepository
import com.example.capstone.data.repository.AuthRepository
import com.example.capstone.domain.grading.GradingService
import com.example.capstone.domain.grading.WorksheetGrader
import com.example.capstone.domain.worksheet.WorksheetSession
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

interface AppContainer {
    val authRepository: AuthRepository
    val assignmentRepository: AssignmentRepository
    val tokenManager: TokenManager

    /**
     * Concrete provider, exposed only so the temporary ModelTestScreen can probe
     * the engine directly. Production code must not use this.
     */
    val localModelProvider: LocalModelProvider

    /**
     * Grading, as an interface. Swapping in a cloud implementation later means
     * changing one line here and nothing else.
     */
    val gradingService: GradingService

    /** Grades a whole worksheet, one answer box at a time, on top of [gradingService]. */
    val worksheetGrader: WorksheetGrader

    /**
     * Holds the crops between the scan screen and the grading screen. Process
     * scoped because the crops are far too large for a SavedStateHandle.
     */
    val worksheetSession: WorksheetSession
}

class DefaultAppContainer(private val context: Context) : AppContainer {
    // Set per build type in app/build.gradle.kts, not here.
    private val baseUrl = BuildConfig.BASE_URL

    override val tokenManager: TokenManager by lazy {
        TokenManager(context)
    }

    private val authInterceptor = Interceptor { chain ->
        val token = runBlocking {
            tokenManager.token.firstOrNull()
        }
        val request = chain.request().newBuilder()
        if (token != null) {
            request.addHeader("Authorization", "Bearer $token")
        }
        chain.proceed(request.build())
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .addInterceptor(authInterceptor)
        // OkHttp defaults to 10s on each of these. Connect stays short so a
        // missing `adb reverse` fails fast and obviously; read and write are
        // longer because worksheet photo uploads are large over USB.
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val retrofitService: ApiService by lazy {
        retrofit.create(ApiService::class.java)
    }

    override val authRepository: AuthRepository by lazy {
        AuthRepository(retrofitService, tokenManager)
    }

    override val assignmentRepository: AssignmentRepository by lazy {
        AssignmentRepository(retrofitService)
    }

    override val localModelProvider: LocalModelProvider by lazy {
        LocalModelProvider(context)
    }

    override val gradingService: GradingService by lazy {
        LocalGradingService(localModelProvider)
    }

    override val worksheetGrader: WorksheetGrader by lazy {
        WorksheetGrader(gradingService)
    }

    // Not lazy by accident: one instance for the process is the whole contract.
    override val worksheetSession: WorksheetSession by lazy {
        WorksheetSession()
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 60L
        const val WRITE_TIMEOUT_SECONDS = 60L
    }
}
