package com.example.capstone.domain.grading

/**
 * Grades one handwritten answer from a worksheet photo.
 *
 * All app code must depend on this interface, never on a concrete implementation.
 * Today the only implementation is on-device (LiteRT-LM); a cloud-backed
 * implementation is planned and will slot in behind this same contract.
 */
interface GradingService {

    /**
     * Grades [photoPng] and returns a parsed [GradeResult].
     *
     * Never throws: inference or parse failures come back as
     * [GradeResult.needsManualReview] = true.
     *
     * @param photoPng the answer photo, already prepared by ImagePrep (PNG, <= 1024px long edge).
     * @param rubric optional marking rubric; the student-facing API does not always supply one.
     */
    suspend fun grade(
        photoPng: ByteArray,
        questionText: String,
        modelAnswer: String,
        rubric: String?,
        maxMarks: Int
    ): GradeResult

    /**
     * Runs the same grading prompt but returns the model's response completely
     * unmodified — no JSON extraction, no parsing, no trimming. Debug use only.
     */
    suspend fun gradeRaw(
        photoPng: ByteArray,
        questionText: String,
        modelAnswer: String,
        maxMarks: Int
    ): String
}

/**
 * The outcome of grading a single answer.
 *
 * @param transcription what the model read in the handwriting.
 * @param legible false when the model could not read the writing (it is told to
 *   report this rather than guess).
 * @param marks awarded marks, always within 0..maxMarks.
 * @param certainty the model's self-reported confidence, 0.0..1.0.
 * @param needsManualReview true when the grade could not be established and a
 *   teacher must look at it. [marks] carries no meaning when this is true.
 */
data class GradeResult(
    val transcription: String,
    val legible: Boolean,
    val marks: Int,
    val certainty: Double,
    val feedback: String,
    val needsManualReview: Boolean
)
