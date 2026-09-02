package com.example.capstone.data.remote

import com.google.gson.annotations.SerializedName

/**
 * The printed-page geometry for one assignment, exactly as the server stores it
 * on the `layouts` row and serves it inside the student `GET /assignments/{id}`
 * response.
 *
 * Present only for assignments created by the server import route, which pulls
 * this from the teacher worksheet system. Null for anything created locally, so
 * every field here is downstream of a null check on [AssignmentDto.layout].
 *
 * The app does not compute any of this. The marker centres in particular are
 * derived from constants that live on the teacher server and can be changed per
 * deployment; a mismatch does not fail loudly, it silently displaces every crop
 * while still reporting four markers found. Deriving them here would bake the
 * assumption into every installed APK instead of into one row we can fix.
 */
data class LayoutDto(
    @SerializedName("page_w_px")
    val pageWidthPx: Int,
    @SerializedName("page_h_px")
    val pageHeightPx: Int,
    @SerializedName("aruco_dict")
    val arucoDict: String,
    val markers: MarkerContractDto,
    /**
     * In served array order, which is the teacher document order and therefore
     * reading order. Position is meaningful; do not re-sort.
     */
    @SerializedName("answer_boxes")
    val answerBoxes: List<LayoutAnswerBoxDto>,
    /**
     * Shape version of [markers] and [answerBoxes]. A client that does not know
     * the version it is handed should refuse the layout rather than guess.
     */
    @SerializedName("layout_version")
    val layoutVersion: Int
)

/**
 * Where the four registration markers sit on the canonical page.
 *
 * [centres] is keyed by marker id as a string, because that is how it is stored
 * as JSON. Ids are row-major: 0 top-left, 1 top-right, 2 BOTTOM-left,
 * 3 bottom-right - not clockwise.
 */
data class MarkerContractDto(
    @SerializedName("aruco_dict")
    val arucoDict: String,
    @SerializedName("marker_size_px")
    val markerSizePx: Int,
    @SerializedName("marker_margin_px")
    val markerMarginPx: Int,
    val centres: Map<String, List<Int>>,
    /**
     * "served" when the teacher API published the marker contract itself,
     * "computed_from_constants" when the server had to fall back to hardcoded
     * values. The second is unchecked and can be silently wrong.
     */
    val source: String
)

/**
 * One answer box rectangle on the canonical page.
 *
 * [bbox] is `[x, y, w, h]` and is the OUTER border box of the drawn rectangle:
 * it includes a 2 px border, 8 px padding, and a caption line about 13 px tall
 * along the top. Insetting for the writing area is the caller's job and must
 * happen in canonical page space, before any perspective transform.
 */
data class LayoutAnswerBoxDto(
    val id: String,
    val label: String,
    val points: Int,
    val bbox: List<Int>,
    @SerializedName("page_index")
    val pageIndex: Int,
    @SerializedName("order_index")
    val orderIndex: Int
)
