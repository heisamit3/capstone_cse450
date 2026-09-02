package com.example.capstone.extractor

/**
 * The geometry this module extracts against.
 *
 * Every number here is supplied by the caller. **No page geometry is hardcoded anywhere in
 * this module** - not the marker margin, not the marker size, not an id-to-corner ordering.
 * That is deliberate, and it is the single most important property of this file.
 *
 * `INTEGRATION_AUDIT.md` §2.3 measured the cost of getting it wrong, against real markers and
 * a real perspective warp: a marker margin off by 10 px displaces every crop by ~11 px, and
 * assuming a clockwise id order instead of the generator's row-major one displaces them by
 * 990 px with a mirrored homography. **Both still detect 4/4 markers and report success.**
 * The Python service hides those constants inside itself and cannot tell you it was wrong.
 * Here they are arguments, so a mismatch is the caller's to state and the caller's to check.
 *
 * `GET /api/questions/{id}` does not carry the marker contract today (audit §2.3, blocking
 * item 1), so whoever adapts that response is where the convention lives.
 */

/** A rectangle in canonical page pixels: the `[x, y, w, h]` form the teacher API serves. */
data class Bbox(val x: Int, val y: Int, val w: Int, val h: Int) {
    internal val right: Int get() = x + w
    internal val bottom: Int get() = y + h
    override fun toString(): String = "[$x, $y, $w, $h]"
}

/**
 * The canonical centre of one ArUco marker, in page pixels.
 *
 * Correspondence with a detected marker is by [id] and only by [id]. Position in
 * [Layout.markers] carries no meaning.
 */
data class MarkerRef(val id: Int, val x: Double, val y: Double)

/** One answer box, as `answer_boxes[]` serves it. */
data class AnswerBoxRef(
    val externalAnswerBoxId: String,
    val pageIndex: Int,
    val bbox: Bbox,
)

/**
 * One finalized question's page geometry.
 *
 * [answerBoxes] **array order is reading order** and is preserved verbatim into
 * [AnswerCrop.orderIndex]. `AnswerBoxOut` omits `order_index` (audit §2.4), so the array as
 * served is the only ordering signal there is; re-deriving it from geometry would be
 * inventing an answer. [LayoutValidator] does assert that sorting by `(pageIndex, bbox.y)`
 * reproduces that order, and rejects the layout if it does not.
 */
data class Layout(
    val externalQuestionId: String,
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val markers: List<MarkerRef>,
    val answerBoxes: List<AnswerBoxRef>,
)

/**
 * How much of a `bbox` is chrome rather than handwriting.
 *
 * `bbox` is the outer border-box of `.answer-box-node`: 2 px dashed border and 8 px padding
 * on every edge, plus an ~13 px caption line inside the top padding (audit §2.6, read off
 * `_PRINT_CSS_TEMPLATE`). The inset is applied to the canonical rectangle **before** the
 * homography - see [PageExtractor]. Insetting the warped quad afterwards would take a
 * different amount off each edge, because a photo's scale varies across the page.
 */
data class Inset(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    internal val horizontal: Int get() = left + right
    internal val vertical: Int get() = top + bottom

    internal fun applyTo(bbox: Bbox): Bbox = Bbox(
        x = bbox.x + left,
        y = bbox.y + top,
        w = bbox.w - horizontal,
        h = bbox.h - vertical,
    )

    override fun toString(): String = "($left, $top, $right, $bottom)"

    companion object {
        /** Strips the border, the padding and the caption line. Audit §2.6's recommendation. */
        val ANSWER_BOX = Inset(left = 10, top = 23, right = 10, bottom = 10)

        /** No inset: the crop is the `bbox` as served. Used to compare against recorded output. */
        val NONE = Inset(0, 0, 0, 0)
    }
}

/**
 * Which transform maps canonical page space onto the image.
 *
 * Ports `_compute_transform` (`v-2.1.1/backend/services/extractor.py:45`). The Python's third
 * modality, `tablet`, is not here: it has no image and no markers, so it is not extraction.
 */
enum class Modality { PHOTO, SCANNER }
