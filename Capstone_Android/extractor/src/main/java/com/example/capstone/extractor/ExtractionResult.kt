package com.example.capstone.extractor

/**
 * The outcome of extracting one page.
 *
 * There is no success-with-an-error-string case, and that is the point. The Python service
 * returns HTTP 200 with an empty crop list and an `error` field when only two markers were
 * found (`NOTES.md` section 2.2 step 3), which reads as success to anything that does not
 * think to look. Here a caller cannot get at crops without having handled every way in which
 * there are none.
 */
sealed interface ExtractionResult {

    /** Crops in [Layout.answerBoxes] order, one per box on the requested page. */
    data class Success(val crops: List<AnswerCrop>) : ExtractionResult

    /**
     * Fewer than all of the layout's markers were detected. [found] counts the layout's own
     * marker ids that were seen; markers carrying ids the layout does not name are ignored.
     */
    data class MarkersNotFound(val found: Int, val missingIds: List<Int>) : ExtractionResult

    /** The layout could not be used. [reason] names the box or marker and the numbers. */
    data class InvalidLayout(val reason: String) : ExtractionResult

    /** The bytes were not a decodable image. */
    data class Undecodable(val cause: Throwable) : ExtractionResult

    /**
     * Every marker was found, but the canonical-to-image solve degenerated - an empty
     * homography, or detected centres that are collinear or coincident.
     *
     * The Python has no such case and cannot distinguish it: a degenerate solve there still
     * reports 4/4 and "homography" and produces crops from a garbage matrix.
     */
    data class RegistrationFailed(val reason: String) : ExtractionResult
}

/**
 * A point in image pixels.
 *
 * Deliberately not `org.opencv.core.Point`. OpenCV is an `implementation` dependency of this
 * module, so it is absent from the compile classpath of anything that depends on it; a result
 * type carrying an OpenCV class would be unusable by the app that asked for it.
 */
data class PointPx(val x: Double, val y: Double)

/**
 * One rectified answer-box crop.
 *
 * [orderIndex] is the box position in [Layout.answerBoxes], captured at extraction rather than
 * re-derived later, which is what audit section 2.4 recommends. [externalQuestionId] and
 * [externalAnswerBoxId] are kept as a pair because a bare answer-box id is a globally unique
 * key only by accident: it is unique today only because a collision crashes the teacher server
 * rather than coexisting (audit section 2.5, run I).
 *
 * Note that [png] is a ByteArray, so the generated equals/hashCode compare it by identity, not
 * by content. Two crops holding the same bytes are not equal. Compare the ids.
 */
data class AnswerCrop(
    val externalQuestionId: String,
    val externalAnswerBoxId: String,
    val pageIndex: Int,
    val orderIndex: Int,
    val png: ByteArray,
    /**
     * Where this crop was cut from, in the pixels of the image that was passed in: the inset
     * rectangle's four corners as TL, TR, BR, BL. Not axis aligned - a photo taken at an angle
     * gives a genuine quadrilateral, which is the point of rectifying rather than cropping.
     *
     * It is here so a UI can show the student what was read off their photo. Nothing in the
     * extraction path consumes it.
     */
    val imageQuad: List<PointPx>,
)
