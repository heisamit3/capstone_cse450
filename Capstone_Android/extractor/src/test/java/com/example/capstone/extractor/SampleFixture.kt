package com.example.capstone.extractor

import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * The one ArUco fixture that exists anywhere in this project.
 *
 * `sample/sample_page.png` is a **rendered page**, 1242x1756 against a canonical 1240x1754, and
 * `sample/layout.json` is the older mobile-service shape (`layout_id`, `page_w_px`, boxes with
 * `bbox` plus `order_index`). `INTEGRATION_AUDIT.md` section 1.7 flags both as worth keeping
 * and notes that the geometry predates `LEFT_MARGIN = 186` - hence x=168 here, not 186.
 *
 * The recorded output this fixture is checked against comes from `NOTES.md` section 5, which
 * ran the real Python `extract_page` over these exact bytes.
 */
internal object SampleFixture {

    /**
     * The marker constants the generator uses, restated here **by the test, not by the module**.
     *
     * This is the whole design in one place. `MARKER_MARGIN_PX` and `MARKER_SIZE_PX` are
     * `pydantic-settings` fields on both Python sides, and `GET /api/questions/{id}` does not
     * carry them (audit section 2.3, blocking item 1). So somebody has to know them, and the
     * choice is whether that somebody is reachable and testable. Putting them here makes the
     * convention an argument the caller supplies and a test can vary - see [MarkerContractTest].
     */
    const val MARKER_MARGIN_PX = 40
    const val MARKER_SIZE_PX = 60

    const val FIRST_BOX_ID = "ab_syzn1vsmmsrm6jat"
    const val SECOND_BOX_ID = "ab_uub03qhomsrm71en"

    fun pageBytes(): ByteArray = resource("/sample/sample_page.png")

    /**
     * The sample layout, with marker centres computed the way the generator draws them:
     * `m + s / 2` with integer floor. Being geometrically correct instead of bug-compatible
     * costs 0.66 px (audit section 2.3, run J), which is inside the tolerance either way.
     */
    fun layout(
        markerMarginPx: Int = MARKER_MARGIN_PX,
        markerSizePx: Int = MARKER_SIZE_PX,
        markerIdOrder: List<Int> = listOf(0, 1, 2, 3),
    ): Layout {
        val json = JSONObject(String(resource("/sample/layout.json"), Charsets.UTF_8))
        val pageW = json.getInt("page_w_px")
        val pageH = json.getInt("page_h_px")

        val boxesJson = json.getJSONArray("answer_boxes")
        val boxes = (0 until boxesJson.length()).map { i ->
            val box = boxesJson.getJSONObject(i)
            val bbox = box.getJSONArray("bbox")
            AnswerBoxRef(
                externalAnswerBoxId = box.getString("id"),
                pageIndex = box.getInt("page_index"),
                bbox = Bbox(bbox.getInt(0), bbox.getInt(1), bbox.getInt(2), bbox.getInt(3)),
            )
        }

        return Layout(
            externalQuestionId = json.getString("layout_id"),
            pageWidthPx = pageW,
            pageHeightPx = pageH,
            markers = markerCentres(pageW, pageH, markerMarginPx, markerSizePx, markerIdOrder),
            answerBoxes = boxes,
        )
    }

    /**
     * Ports `get_marker_positions` (`doc_renderer.py:35`) into the test, where it belongs.
     *
     * [markerIdOrder] assigns ids to the four corners in the order top-left, top-right,
     * bottom-left, bottom-right. The generator uses `[0, 1, 2, 3]`, which is row-major and
     * **not** clockwise; passing `[0, 1, 3, 2]` is the clockwise mistake audit section 2.3
     * measured at 990 px.
     */
    fun markerCentres(
        pageW: Int,
        pageH: Int,
        markerMarginPx: Int = MARKER_MARGIN_PX,
        markerSizePx: Int = MARKER_SIZE_PX,
        markerIdOrder: List<Int> = listOf(0, 1, 2, 3),
    ): List<MarkerRef> {
        val half = markerSizePx / 2
        val left = (markerMarginPx + half).toDouble()
        val top = (markerMarginPx + half).toDouble()
        val right = (pageW - markerMarginPx - half).toDouble()
        val bottom = (pageH - markerMarginPx - half).toDouble()
        val corners = listOf(
            left to top,
            right to top,
            left to bottom,
            right to bottom,
        )
        return markerIdOrder.mapIndexed { i, id ->
            MarkerRef(id = id, x = corners[i].first, y = corners[i].second)
        }
    }

    private fun resource(path: String): ByteArray {
        val stream = requireNotNull(SampleFixture::class.java.getResourceAsStream(path)) {
            "missing test resource " + path
        }
        return stream.use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            out.toByteArray()
        }
    }
}
