package com.example.capstone.data.local

import android.os.SystemClock
import android.util.Log
import com.example.capstone.domain.grading.GradeResult
import com.example.capstone.domain.grading.GradingService
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/**
 * On-device implementation of [GradingService], backed by the shared LiteRT-LM
 * engine held by [LocalModelProvider].
 *
 * Grading is entirely the model's job: there is no token-overlap heuristic and
 * no cloud fallback. When the model's output cannot be parsed the result comes
 * back with [GradeResult.needsManualReview] set — no marks are invented.
 */
class LocalGradingService(
    private val modelProvider: LocalModelProvider
) : GradingService {

    override suspend fun gradeRaw(
        photoPng: ByteArray,
        questionText: String,
        modelAnswer: String,
        maxMarks: Int
    ): String = runPrompt(
        photoPng = photoPng,
        prompt = buildPrompt(questionText, modelAnswer, rubric = null, maxMarks = maxMarks),
        label = "gradeRaw"
    )

    override suspend fun grade(
        photoPng: ByteArray,
        questionText: String,
        modelAnswer: String,
        rubric: String?,
        maxMarks: Int
    ): GradeResult {
        val basePrompt = buildPrompt(questionText, modelAnswer, rubric, maxMarks)

        // Exactly two attempts: the initial call, then one retry with a stricter
        // reminder. No third try.
        val attempts = listOf(basePrompt, basePrompt + "\n\n" + RETRY_REMINDER)

        for ((index, prompt) in attempts.withIndex()) {
            val attemptNo = index + 1
            val raw = try {
                runPrompt(photoPng, prompt, "grade#$attemptNo")
            } catch (ce: CancellationException) {
                // Never swallow cancellation.
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "grade#$attemptNo inference failed", t)
                continue
            }

            val parsed = parseGrade(raw, maxMarks)
            if (parsed != null) {
                Log.i(TAG, "grade#$attemptNo parsed ok marks=${parsed.marks} legible=${parsed.legible}")
                return parsed
            }
            Log.w(TAG, "grade#$attemptNo could not be parsed")
        }

        Log.w(TAG, "grading gave up after ${attempts.size} attempts; flagging for manual review")
        return manualReviewResult()
    }

    /**
     * One inference round trip. Creates a FRESH conversation so no context bleeds
     * between questions, and closes it via [use] even on failure.
     */
    private suspend fun runPrompt(photoPng: ByteArray, prompt: String, label: String): String =
        modelProvider.withInference { engine ->
            val startedAt = SystemClock.elapsedRealtime()
            val conversationConfig = ConversationConfig(
                // Deterministic grading, not creative writing.
                samplerConfig = SamplerConfig(topK = TOP_K, topP = TOP_P, temperature = TEMPERATURE)
            )
            engine.createConversation(conversationConfig).use { conversation ->
                // Image FIRST, text LAST - required content order for Gemma 3n.
                val contents = Contents.of(
                    Content.ImageBytes(photoPng),
                    Content.Text(prompt)
                )
                val raw = conversation.sendMessage(contents).textOrEmpty()
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                Log.i(TAG, "$label elapsedMs=$elapsedMs pngBytes=${photoPng.size} rawResponse=$raw")
                raw
            }
        }

    private fun buildPrompt(
        questionText: String,
        modelAnswer: String,
        rubric: String?,
        maxMarks: Int
    ): String = buildString {
        appendLine("You are marking one handwritten answer from a photo of a student worksheet.")
        appendLine()
        appendLine("Question: $questionText")
        appendLine("Correct answer: $modelAnswer")
        if (!rubric.isNullOrBlank()) {
            appendLine("Marking rubric: $rubric")
        }
        appendLine("Maximum marks: $maxMarks")
        appendLine()
        appendLine("Do this:")
        appendLine("1. Read the handwriting in the image and transcribe the student's answer exactly as written.")
        appendLine(
            "2. If the handwriting is unclear, cut off, or you are not certain what it says, " +
                "set \"legible\" to false and do NOT guess. Never invent an answer you cannot actually read."
        )
        appendLine("3. Compare the transcription with the correct answer and award a whole number of marks from 0 to $maxMarks.")
        appendLine("4. Write one short sentence of feedback addressed to the student.")
        appendLine("5. Set \"certainty\" to how confident you are in this mark, from 0.0 to 1.0.")
        appendLine()
        appendLine("Reply with ONLY this JSON object. No preamble, no explanation, no markdown code fences:")
        append(JSON_TEMPLATE)
    }

    companion object {
        private const val TAG = "LocalGradingService"

        private val gson = Gson()

        /**
         * Extracts the first balanced `{...}` block and parses it.
         *
         * Returns null when the response holds no JSON object, when the JSON is
         * malformed, or when the marks field is absent — a missing mark is a parse
         * failure, never a silent zero.
         */
        internal fun parseGrade(raw: String, maxMarks: Int): GradeResult? {
            val json = extractFirstJsonObject(raw) ?: run {
                Log.w(TAG, "no JSON object found in response")
                return null
            }

            val dto = try {
                gson.fromJson(json, GradeDto::class.java)
            } catch (t: Throwable) {
                // Gson throws JsonSyntaxException / JsonParseException / NumberFormatException
                // on malformed input; none of them may escape grading.
                Log.w(TAG, "Gson could not parse extracted JSON: $json", t)
                return null
            } ?: return null

            // No marks means we do not know the grade. Do not fabricate one.
            val rawMarks = dto.marks ?: run {
                Log.w(TAG, "parsed JSON has no marks field")
                return null
            }

            // The model reports legible=false when it could not read the handwriting.
            // Whatever mark it attached to that is guesswork, so a human has to look.
            val legible = dto.legible ?: false

            return GradeResult(
                transcription = dto.transcription.orEmpty(),
                legible = legible,
                marks = rawMarks.roundToInt().coerceIn(0, maxMarks),
                certainty = (dto.certainty ?: 0.0).coerceIn(0.0, 1.0),
                feedback = dto.feedback.orEmpty(),
                needsManualReview = !legible
            )
        }

        /**
         * Scans for the first brace-balanced JSON object, ignoring braces that sit
         * inside string literals. Survives markdown fences, chatty preambles and
         * trailing junk, which a greedy regex would not.
         */
        internal fun extractFirstJsonObject(raw: String): String? {
            val start = raw.indexOf('{')
            if (start < 0) return null

            var depth = 0
            var inString = false
            var escaped = false

            for (i in start until raw.length) {
                val c = raw[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return raw.substring(start, i + 1)
                    }
                }
            }
            return null
        }

        /**
         * Marks carry no meaning here; [GradeResult.needsManualReview] is the signal.
         * Zero is a placeholder for a non-null Int, not a grade the model gave.
         */
        internal fun manualReviewResult() = GradeResult(
            transcription = "",
            legible = false,
            marks = 0,
            certainty = 0.0,
            feedback = "Automatic grading could not be completed. This answer needs manual review.",
            needsManualReview = true
        )

        /** Mirrors the JSON contract in [JSON_TEMPLATE]. All fields nullable: the model may omit any of them. */
        private data class GradeDto(
            @SerializedName("transcription") val transcription: String? = null,
            @SerializedName("legible") val legible: Boolean? = null,
            // Double, not Int: models happily emit 2.0 or "2", both of which would
            // blow up an Int field.
            @SerializedName("marks") val marks: Double? = null,
            @SerializedName("certainty") val certainty: Double? = null,
            @SerializedName("feedback") val feedback: String? = null
        )

        private const val TEMPERATURE = 0.1
        private const val TOP_K = 5
        private const val TOP_P = 0.95

        private const val JSON_TEMPLATE =
            "{\"transcription\":\"...\",\"legible\":true,\"marks\":0,\"certainty\":0.0,\"feedback\":\"...\"}"

        private const val RETRY_REMINDER =
            "Your previous reply could not be parsed. Reply with the single JSON object and nothing " +
                "else. It must start with { and end with }."
    }
}
