package com.example.capstone.ui.screens

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.capstone.CapstoneApplication
import com.example.capstone.data.local.EngineState
import com.example.capstone.data.local.LocalModelProvider
import com.example.capstone.data.local.ModelSpec
import com.example.capstone.data.local.SpecAvailability
import com.example.capstone.domain.grading.GradingService
import com.example.capstone.util.ImagePrep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * TEMPORARY debug view model backing [ModelTestScreen].
 *
 * Talks to [LocalModelProvider] directly for the raw engine probes, which is a
 * deliberate exception for this throwaway screen. Real grading goes through the
 * [GradingService] interface.
 */
/** What produced the text currently in the raw output pane. */
enum class OutputSource {
    /** No probe has completed since the last one was started. */
    NONE,

    /** Verbatim bytes returned by the model. Nothing was added or removed. */
    MODEL,

    /** Text the app composed - a file report, a formatted GradeResult. */
    HARNESS,

    /** A stack trace from a failed probe. */
    ERROR
}

class ModelTestViewModel(
    private val context: Context,
    private val modelProvider: LocalModelProvider,
    private val gradingService: GradingService
) : ViewModel() {

    var status by mutableStateOf("Idle. Pick an image, then load the engine.")
        private set

    /**
     * Whatever the last completed probe produced, byte for byte.
     *
     * `null` means no probe has completed - it is NOT the same as a probe
     * that completed and returned an empty string, and the two must stay
     * distinguishable or an empty model response looks like an idle screen.
     */
    var rawOutput by mutableStateOf<String?>(null)
        private set

    /**
     * Where [rawOutput] came from. The pane renders harness-composed text and
     * verbatim model text identically, so the source has to be stated rather
     * than left to be inferred.
     */
    var outputSource by mutableStateOf(OutputSource.NONE)
        private set

    var elapsedMs by mutableStateOf<Long?>(null)
        private set

    /**
     * The model that was active when the last probe finished.
     *
     * Recorded at completion rather than read live, so a result stays labelled
     * with the model that actually produced it even after a later switch.
     */
    var resultSpec by mutableStateOf<ModelSpec?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    /** The prepared PNG (EXIF-corrected, <= 1024px) that is actually sent to the model. */
    var imagePng by mutableStateOf<ByteArray?>(null)
        private set

    /** The active model. The provider owns this; the screen observes it. */
    val activeSpec: StateFlow<ModelSpec> = modelProvider.activeSpec

    /** Engine load state, so the screen can spin and lock out probes during a swap. */
    val engineState: StateFlow<EngineState> = modelProvider.engineState

    /**
     * Every registry entry with the result of a filesystem check, so entries
     * whose file was never pushed can be shown disabled with a reason instead
     * of silently missing. Empty until the first refresh completes.
     */
    var modelOptions by mutableStateOf<List<SpecAvailability>>(emptyList())
        private set

    init {
        refreshModelOptions()
    }

    /** Re-runs the per-entry file check. Disk I/O, so it hops to IO itself. */
    fun refreshModelOptions() {
        viewModelScope.launch {
            modelOptions = modelProvider.availability()
        }
    }

    /**
     * Switches models.
     *
     * Selecting the model already in use is a no-op in the provider - no
     * release, no reload - and is reported as such. Otherwise the current
     * engine is closed before the replacement is built; the replacement is only
     * built when one was loaded beforehand.
     */
    fun onSpecSelected(spec: ModelSpec) {
        val previous = modelProvider.spec
        val wasLoaded = modelProvider.isEngineReady()
        launchOperation("Switch to ${spec.name}", OutputSource.HARNESS) {
            modelProvider.useSpec(spec)
            refreshModelOptions()
            buildString {
                if (spec == previous) {
                    appendLine("${spec.displayName} was already active. Nothing was reloaded.")
                } else {
                    appendLine("switched: ${previous.displayName} -> ${spec.displayName}")
                    appendLine(
                        if (wasLoaded) {
                            "Previous engine closed, then the replacement was loaded."
                        } else {
                            "No engine was loaded. The next probe loads this one."
                        }
                    )
                }
                appendLine("file: ${spec.fileName}")
                appendLine("supportsVision: ${spec.supportsVision}")
                appendLine("minRamGb: ${spec.minRamGb ?: "unspecified"}")
                appendLine("maxNumTokens: ${spec.maxNumTokens}  imageTokens: ${spec.imageTokens}")
                appendLine("approxSizeMb: ${spec.approxSizeMb}")
                append("engine loaded: ${modelProvider.isEngineReady()}")
            }
        }
    }

    fun onImagePicked(uri: Uri) = launchOperation("Prepare image", OutputSource.HARNESS) {
        val prepared = withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Could not open the selected image")
            ImagePrep.toGradingPng(bytes) to bytes.size
        }
        val (png, originalSize) = prepared
        if (png == null) {
            imagePng = null
            error("ImagePrep could not decode the selected image ($originalSize bytes)")
        }
        imagePng = png
        "Image ready.\noriginal: $originalSize bytes\nprepared PNG: ${png.size} bytes"
    }

    fun checkModelFile() = launchOperation("Check model file", OutputSource.HARNESS) {
        // stat() and File.length() are disk I/O: keep them off the main thread.
        withContext(Dispatchers.IO) {
            val status = modelProvider.inspectModel()
            buildString {
                appendLine("model: ${modelProvider.spec.displayName}")
                appendLine("usable: ${status.usable}")
                appendLine("path: ${status.path}")
                appendLine("exists: ${status.exists}  isFile: ${status.isFile}  readable: ${status.readable}")
                appendLine("dir exists: ${status.parentExists}  dir traversable: ${status.parentTraversable}")
                appendLine("sizeBytes: ${status.sizeBytes}")
                appendLine(
                    "expectedBytes: ${status.expectedBytes ?: "unrecorded (exact-size check skipped)"}" +
                        "  match: ${status.sizeMatchesExpected}"
                )
                appendLine(
                    "sizeMB: ${String.format(Locale.US, "%.1f", status.sizeBytes / 1024.0 / 1024.0)}"
                )
                appendLine("engine already loaded: ${modelProvider.isEngineReady()}")
                append("diagnosis: ${status.diagnosis}")
            }
        }
    }

    fun loadEngine() = launchOperation("Load engine", OutputSource.HARNESS) {
        modelProvider.initialize()
        "Engine initialized. See Logcat tag LocalModelProvider for the init duration."
    }

    fun textOnlyTest() = launchOperation("Text only test", OutputSource.MODEL) {
        modelProvider.runRawPrompt("Reply with exactly the two characters: OK")
    }

    fun describeImage() = launchOperation("Describe image", OutputSource.MODEL) {
        modelProvider.runRawPrompt(
            prompt = DESCRIBE_PROMPT,
            imagePng = requireImage()
        )
    }

    /**
     * Pure OCR probe: can the model read the handwriting at all?
     *
     * This deliberately does NOT go through [GradingService]. Every grading
     * entry point builds a prompt containing the expected answer and the
     * marks available, and a model handed the answer key can reproduce it
     * without reading a single pen stroke - which makes the result worthless
     * as evidence about transcription. Nothing about the question, the
     * expected answer, marks or correctness may enter [TRANSCRIBE_PROMPT].
     */
    fun transcribeHandwriting() =
        launchOperation("Transcribe handwriting (OCR only)", OutputSource.MODEL) {
            modelProvider.runRawPrompt(
                prompt = TRANSCRIBE_PROMPT,
                imagePng = requireImage()
            )
        }

    /**
     * Exercises the full parsed path, [GradingService.grade]. Unlike the
     * transcribe probe this one SHOULD see the model answer - that is the
     * thing under test. Its output is a summary the harness formats from a
     * parsed GradeResult, not model text.
     */
    fun gradeIt() = launchOperation("Grade it", OutputSource.HARNESS) {
        val result = gradingService.grade(
            photoPng = requireImage(),
            questionText = TEST_QUESTION,
            modelAnswer = TEST_MODEL_ANSWER,
            rubric = null,
            maxMarks = TEST_MAX_MARKS
        )
        buildString {
            appendLine("question: $TEST_QUESTION")
            appendLine("expected: $TEST_MODEL_ANSWER   maxMarks: $TEST_MAX_MARKS")
            appendLine("---- parsed GradeResult ----")
            appendLine("transcription: ${result.transcription}")
            appendLine("legible: ${result.legible}")
            appendLine("marks: ${result.marks} / $TEST_MAX_MARKS")
            appendLine("certainty: ${result.certainty}")
            appendLine("feedback: ${result.feedback}")
            appendLine("needsManualReview: ${result.needsManualReview}")
            append("(raw response is in Logcat, tag LocalGradingService)")
        }
    }

    private fun requireImage(): ByteArray =
        imagePng ?: error("Pick an image first")

    /**
     * Runs one probe, timing it and routing any failure into the output pane
     * instead of crashing the screen. Ignores taps while another probe runs.
     */
    private fun launchOperation(
        label: String,
        source: OutputSource,
        block: suspend () -> String
    ) {
        if (busy) return
        busy = true
        status = "$label: running..."
        rawOutput = null
        outputSource = OutputSource.NONE
        elapsedMs = null
        resultSpec = null

        viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val output = block()
                elapsedMs = SystemClock.elapsedRealtime() - startedAt
                // Stored exactly as returned: no trim, no default, no fallback.
                rawOutput = output
                outputSource = source
                status = "$label: done"
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                elapsedMs = SystemClock.elapsedRealtime() - startedAt
                rawOutput = t.stackTraceToString()
                outputSource = OutputSource.ERROR
                status = "$label: FAILED - ${t.message ?: t.javaClass.simpleName}"
            } finally {
                // Whichever model was active when this finished is the one that
                // produced the result above.
                resultSpec = modelProvider.spec
                busy = false
            }
        }
    }

    companion object {
        // Used ONLY by the "Grade it" probe. Never by the OCR probe.
        const val TEST_QUESTION = "What is 7 x 8?"
        const val TEST_MODEL_ANSWER = "56"
        const val TEST_MAX_MARKS = 5

        /**
         * Pure transcription. Carries no question, no expected answer, no
         * marks and no notion of correctness, so whatever comes back can only
         * have come from the pixels.
         */
        const val TRANSCRIBE_PROMPT =
            "Read this image. Write out exactly what is handwritten on it, " +
                "character for character. Do not solve anything. Do not judge " +
                "correctness. Do not explain.\n" +
                "If you cannot read it, write exactly: ILLEGIBLE\n" +
                "Output only the transcribed text."

        /** Plain description. No grading context. */
        const val DESCRIBE_PROMPT = "Describe what you see in this image."

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as CapstoneApplication)
                ModelTestViewModel(
                    context = application,
                    modelProvider = application.container.localModelProvider,
                    gradingService = application.container.gradingService
                )
            }
        }
    }
}
