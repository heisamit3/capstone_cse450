package com.example.capstone.extractor

import kotlin.math.abs

/**
 * Checks a [Layout] before any of it is trusted.
 *
 * Everything here is cheap and none of it needs OpenCV. It exists because the failure it
 * guards against is silent: audit section 2.3 measured a wrong marker margin producing an
 * 11 px systematic displacement and a swapped id pair producing 990 px, both of which still
 * detect 4/4 markers and report success. A layout that cannot be right is worth refusing
 * before a photo is even decoded.
 */
internal object LayoutValidator {

    /** The ids the ArUco contract requires. An id set, not a geometry constant. */
    private val REQUIRED_MARKER_IDS = setOf(0, 1, 2, 3)

    /** Half a square pixel. Below this, three centres are a line as far as a solver cares. */
    private const val COLLINEAR_AREA_EPSILON = 0.5

    /**
     * Returns a reason naming what is wrong, or null when the layout is usable.
     *
     * [inset] participates because "the inset leaves positive area" is a property of the pair,
     * not of the layout alone.
     */
    fun validate(layout: Layout, inset: Inset): String? =
        validateMarkers(layout) ?: validateBoxes(layout, inset) ?: validateReadingOrder(layout)

    private fun validateMarkers(layout: Layout): String? {
        if (layout.pageWidthPx <= 0 || layout.pageHeightPx <= 0) {
            return "page size " + layout.pageWidthPx + "x" + layout.pageHeightPx +
                " is not positive"
        }
        if (layout.markers.size != 4) {
            return "expected exactly 4 markers, got " + layout.markers.size
        }
        val ids = layout.markers.map { it.id }
        if (ids.toSet() != REQUIRED_MARKER_IDS) {
            return "marker ids must be exactly " + REQUIRED_MARKER_IDS.sorted() +
                ", got " + ids.sorted()
        }
        // Three collinear centres make the four-point solve rank deficient. findHomography
        // would still return a matrix, and it would be meaningless.
        for (i in 0..1) {
            for (j in i + 1..2) {
                for (k in j + 1..3) {
                    val a = layout.markers[i]
                    val b = layout.markers[j]
                    val c = layout.markers[k]
                    val area = triangleArea(a, b, c)
                    if (area < COLLINEAR_AREA_EPSILON) {
                        return "canonical marker centres " + a.id + ", " + b.id + ", " + c.id +
                            " are collinear (triangle area " + formatArea(area) + " px^2)"
                    }
                }
            }
        }
        return null
    }

    private fun validateBoxes(layout: Layout, inset: Inset): String? {
        val seen = mutableSetOf<String>()
        for (box in layout.answerBoxes) {
            val id = box.externalAnswerBoxId
            if (!seen.add(id)) return "duplicate answer box id " + id
            if (box.pageIndex < 0) {
                return "answer box " + id + ": page_index " + box.pageIndex + " is negative"
            }

            val bbox = box.bbox
            if (bbox.w <= 0 || bbox.h <= 0) {
                return "answer box " + id + ": bbox " + bbox.w + "x" + bbox.h +
                    " has non-positive area"
            }
            if (bbox.x < 0 || bbox.y < 0) {
                return "answer box " + id + ": bbox " + bbox + " starts outside the page"
            }
            if (bbox.right > layout.pageWidthPx) {
                return "answer box " + id + ": bbox " + bbox + " extends past page width " +
                    layout.pageWidthPx
            }
            if (bbox.bottom > layout.pageHeightPx) {
                return "answer box " + id + ": bbox " + bbox + " extends past page height " +
                    layout.pageHeightPx
            }
            if (bbox.w <= inset.horizontal || bbox.h <= inset.vertical) {
                return "answer box " + id + ": bbox " + bbox.w + "x" + bbox.h +
                    " leaves no area after inset " + inset
            }
        }

        for (i in layout.answerBoxes.indices) {
            for (j in i + 1 until layout.answerBoxes.size) {
                val a = layout.answerBoxes[i]
                val b = layout.answerBoxes[j]
                if (a.pageIndex == b.pageIndex && overlaps(a.bbox, b.bbox)) {
                    return "answer boxes " + a.externalAnswerBoxId + " and " +
                        b.externalAnswerBoxId + " overlap on page " + a.pageIndex
                }
            }
        }
        return null
    }

    /**
     * Audit section 2.4 free consistency check, promoted to a hard failure.
     *
     * Array order is reading order and nothing else records it, so the one cross-check
     * available is that geometry agrees: page_index is monotonically non-decreasing along the
     * array, and bbox.y increases within a page. If it ever does not, something upstream
     * reordered the array and the ordering this module would capture is already wrong.
     */
    private fun validateReadingOrder(layout: Layout): String? {
        val sorted = layout.answerBoxes.sortedWith(compareBy({ it.pageIndex }, { it.bbox.y }))
        for (i in layout.answerBoxes.indices) {
            if (sorted[i].externalAnswerBoxId != layout.answerBoxes[i].externalAnswerBoxId) {
                return "reading order is not (page_index, bbox.y) sorted: " +
                    sorted[i].externalAnswerBoxId + " sorts into index " + i +
                    ", but the array has " + layout.answerBoxes[i].externalAnswerBoxId + " there"
            }
        }
        return null
    }

    /** Strict interior intersection: boxes that meet edge to edge touch, they do not overlap. */
    private fun overlaps(a: Bbox, b: Bbox): Boolean =
        a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom

    private fun triangleArea(a: MarkerRef, b: MarkerRef, c: MarkerRef): Double =
        abs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)) / 2.0

    private fun formatArea(area: Double): String =
        (kotlin.math.round(area * 100.0) / 100.0).toString()
}
