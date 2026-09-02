package com.example.capstone.extractor

import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect
import kotlin.math.abs
import kotlin.math.round

/**
 * The registration core, ported from `v-2.1.1/backend/services/extractor.py` lines 22 to 74:
 * `_decode_image`, `_detect_aruco_markers`, `_compute_transform` and `_transform_bbox`.
 *
 * `_crop_region` is deliberately not ported - see [PageExtractor].
 * `_check_qr` is deliberately not ported: the generated QR symbols sit at 1.12 px per module
 * and are undecodable before the page is even printed, which both prior audits confirmed
 * independently (`TEACHER_NOTES.md` section 1.5, `NOTES.md` section 5).
 *
 * Everything here is internal and takes its geometry as an argument, so the golden test can
 * reach the transform without going through PNG encoding. That mirrors how
 * `LocalGradingService.parseGrade` is internal so `GradeParsingTest` can run without an engine.
 */
internal object Registration {

    /** Matches the Python: cv2.findHomography(..., cv2.RANSAC, 5.0). */
    private const val RANSAC_REPROJECTION_THRESHOLD = 5.0

    /** One square pixel, in image space, where a photo carries more noise than a rendered page. */
    private const val DETECTED_COLLINEAR_AREA_EPSILON = 1.0

    private val detector: ArucoDetector by lazy {
        // DICT_4X4_50 is the dictionary both Python sides agree on (audit section 1.3). It is
        // an encoding, not page geometry - a marker drawn from a different dictionary does not
        // decode at all, which is a loud failure rather than a silent displacement.
        ArucoDetector(
            Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50),
            DetectorParameters(),
        )
    }

    /**
     * Ports `_decode_image`. Returns null when the bytes are not a decodable image.
     *
     * The Python also sniffs a DPI out of the PIL header; nothing downstream ever read it, so
     * it is dropped here rather than carried.
     */
    fun decode(imageBytes: ByteArray): Mat? {
        if (imageBytes.isEmpty()) return null
        val buffer = MatOfByte(*imageBytes)
        return try {
            val img = Imgcodecs.imdecode(buffer, Imgcodecs.IMREAD_COLOR)
            if (img.empty()) null else img
        } finally {
            buffer.release()
        }
    }

    /**
     * Ports `_detect_aruco_markers`: every marker in the image, reduced to the mean of its four
     * corners. Markers carrying ids the caller did not ask about come back too, and are ignored
     * upstream.
     */
    fun detect(img: Mat): Map<Int, Point> {
        val corners = mutableListOf<Mat>()
        val ids = Mat()
        try {
            detector.detectMarkers(img, corners, ids)
            if (ids.empty()) return emptyMap()

            val out = mutableMapOf<Int, Point>()
            for (i in 0 until ids.rows()) {
                val markerId = ids.get(i, 0)[0].toInt()
                out[markerId] = centreOf(corners[i])
            }
            return out
        } finally {
            ids.release()
            corners.forEach { it.release() }
        }
    }

    /**
     * Ports `_compute_transform`, mapping canonical page space onto image space.
     *
     * With exactly four correspondences RANSAC has no outlier headroom, so this is effectively
     * a plain four-point solve - true of the Python too. Returns null when the solve
     * degenerates, a case the Python cannot detect.
     */
    fun solve(canonical: List<Point>, detected: List<Point>, modality: Modality): Mat? {
        val src = MatOfPoint2f(*canonical.toTypedArray())
        val dst = MatOfPoint2f(*detected.toTypedArray())
        try {
            val m = when (modality) {
                Modality.PHOTO -> Calib3d.findHomography(
                    src,
                    dst,
                    Calib3d.RANSAC,
                    RANSAC_REPROJECTION_THRESHOLD,
                )
                // estimateAffine2D returns 2x3; the Python stacks [0, 0, 1] underneath so the
                // rest of the pipeline can treat both transforms the same way.
                Modality.SCANNER -> Calib3d.estimateAffine2D(src, dst).let { affine ->
                    if (affine.empty()) affine else liftToHomogeneous(affine)
                }
            }
            return if (m.empty() || !m.allFinite()) null else m
        } finally {
            src.release()
            dst.release()
        }
    }

    /**
     * Ports `_transform_bbox`: the four corners of a canonical rectangle, in image space, in
     * top-left, top-right, bottom-right, bottom-left order.
     */
    fun transformBbox(rect: Bbox, transform: Mat): List<Point> {
        val corners = MatOfPoint2f(
            Point(rect.x.toDouble(), rect.y.toDouble()),
            Point(rect.right.toDouble(), rect.y.toDouble()),
            Point(rect.right.toDouble(), rect.bottom.toDouble()),
            Point(rect.x.toDouble(), rect.bottom.toDouble()),
        )
        val warped = MatOfPoint2f()
        try {
            Core.perspectiveTransform(corners, warped, transform)
            return warped.toList()
        } finally {
            corners.release()
            warped.release()
        }
    }

    /**
     * Registers one page and returns every box on it, in [Layout.answerBoxes] order.
     *
     * The canonical-to-image correspondence is built by matching [MarkerRef.id] against the
     * detected id, walking [Layout.markers] in whatever order the caller gave. No TL/TR/BL/BR
     * convention exists in this file to be wrong about.
     *
     * Throws [RegistrationException] when the solve degenerates; callers turn that into
     * [ExtractionResult.RegistrationFailed]. The returned transform is released before return,
     * so the caller gets geometry, not a native handle to manage.
     */
    fun registerPage(
        layout: Layout,
        pageIndex: Int,
        modality: Modality,
        inset: Inset,
        detected: Map<Int, Point>,
    ): List<RegisteredBox> {
        val canonicalPts = layout.markers.map { Point(it.x, it.y) }
        val detectedPts = layout.markers.map { marker ->
            detected[marker.id]
                ?: throw RegistrationException("marker " + marker.id + " was not detected")
        }
        assertNotCollinear(detectedPts)

        val transform = solve(canonicalPts, detectedPts, modality)
            ?: throw RegistrationException(
                "the " + modality.name.lowercase() + " solve returned no usable transform for " +
                    layout.markers.size + " marker correspondences",
            )

        return try {
            layout.answerBoxes.mapIndexedNotNull { orderIndex, box ->
                if (box.pageIndex != pageIndex) {
                    null
                } else {
                    val canonicalRect = inset.applyTo(box.bbox)
                    RegisteredBox(
                        box = box,
                        orderIndex = orderIndex,
                        canonicalRect = canonicalRect,
                        imageQuad = transformBbox(canonicalRect, transform),
                    )
                }
            }
        } finally {
            transform.release()
        }
    }

    /**
     * The detected centres have to span an area too, not just the canonical ones. Four markers
     * seen edge-on, or three of them read off one printed strip, produce a matrix that
     * findHomography is perfectly happy to return and that means nothing.
     */
    private fun assertNotCollinear(points: List<Point>) {
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                for (k in j + 1 until points.size) {
                    val a = points[i]
                    val b = points[j]
                    val c = points[k]
                    val area = abs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)) / 2.0
                    if (area < DETECTED_COLLINEAR_AREA_EPSILON) {
                        throw RegistrationException(
                            "detected marker centres at index " + i + ", " + j + ", " + k +
                                " are collinear or coincident (triangle area " +
                                round(area * 100.0) / 100.0 + " px^2)",
                        )
                    }
                }
            }
        }
    }

    private fun centreOf(markerCorners: Mat): Point {
        // detectMarkers hands back a 1x4 CV_32FC2 Mat per marker: four corners, each (x, y).
        val data = FloatArray(8)
        markerCorners.get(0, 0, data)
        var sumX = 0.0
        var sumY = 0.0
        for (i in 0 until 4) {
            sumX += data[i * 2]
            sumY += data[i * 2 + 1]
        }
        return Point(sumX / 4.0, sumY / 4.0)
    }

    private fun liftToHomogeneous(affine: Mat): Mat {
        val m = Mat.eye(3, 3, CvType.CV_64F)
        for (r in 0 until 2) {
            for (c in 0 until 3) {
                m.put(r, c, affine.get(r, c)[0])
            }
        }
        affine.release()
        return m
    }

    private fun Mat.allFinite(): Boolean {
        for (r in 0 until rows()) {
            for (c in 0 until cols()) {
                val v = get(r, c)[0]
                if (v.isNaN() || v.isInfinite()) return false
            }
        }
        return true
    }
}

/** One answer box, registered onto the photo but not yet cut out. */
internal data class RegisteredBox(
    val box: AnswerBoxRef,
    val orderIndex: Int,
    /** The bbox after the inset, still in canonical page pixels. */
    val canonicalRect: Bbox,
    /** [canonicalRect] corners in image pixels: TL, TR, BR, BL. */
    val imageQuad: List<Point>,
) {
    /**
     * The axis-aligned bounds of [imageQuad] as `[xMin, yMin, xMax, yMax]`.
     *
     * This is what the Python calls `warped_bbox`, and it uses truncating `int()`, so this does
     * too - the golden numbers in `NOTES.md` section 5 were produced that way. Nothing in the
     * extraction path consumes it; it exists so recorded output stays comparable.
     */
    fun warpedBounds(): IntArray = intArrayOf(
        imageQuad.minOf { it.x }.toInt(),
        imageQuad.minOf { it.y }.toInt(),
        imageQuad.maxOf { it.x }.toInt(),
        imageQuad.maxOf { it.y }.toInt(),
    )
}

internal class RegistrationException(message: String) : Exception(message)
