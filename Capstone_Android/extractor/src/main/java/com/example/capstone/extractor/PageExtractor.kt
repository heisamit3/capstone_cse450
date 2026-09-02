package com.example.capstone.extractor

import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/**
 * Cuts one rectified PNG per answer box out of a photographed worksheet page.
 *
 * The caller must have loaded the OpenCV native library first - [OpenCvNative] on Android,
 * `nu.pattern.OpenCV.loadLocally()` on the JVM.
 *
 * ### How this differs from the Python it is ported from
 *
 * `_crop_region` takes the axis-aligned bounding rectangle of the warped quad and slices it out
 * of the photo. On a flatbed scan that is fine. On a phone photo it keeps the perspective
 * distortion, pulls in whatever surrounds the box, and at mild skew covers 1.32 times the true
 * box area (`TEACHER_NOTES.md` section 9C). Audit item 13 says what to do instead, and this is
 * it: warp the canonical inset rectangle onto its own `[w, h]` and the crop comes out
 * rectified, consistently sized, and the same shape for every photo of the same box.
 *
 * The [inset] is applied to the canonical rectangle **before** the transform, never to the
 * finished crop, because a photo scales differently across the page and insetting afterwards
 * would take a different amount off each edge (audit section 2.6, rule 1).
 */
class PageExtractor(
    /** How much of each bbox is border, padding and caption. Audit section 2.6. */
    private val inset: Inset = Inset.ANSWER_BOX,
) {

    /**
     * Extracts every answer box whose `page_index` is [pageIndex].
     *
     * A page with no boxes is a [ExtractionResult.Success] carrying an empty list: there was
     * nothing to extract and nothing went wrong. The Python reports that as an `error` string
     * beside an HTTP 200, which is the shape this module exists to avoid.
     */
    fun extractPage(
        layout: Layout,
        pageIndex: Int,
        imageBytes: ByteArray,
        modality: Modality = Modality.PHOTO,
    ): ExtractionResult {
        LayoutValidator.validate(layout, inset)?.let {
            return ExtractionResult.InvalidLayout(it)
        }

        val img = try {
            Registration.decode(imageBytes)
                ?: return ExtractionResult.Undecodable(
                    IllegalArgumentException(
                        "imdecode rejected " + imageBytes.size +
                            " bytes: unsupported format or corrupt file",
                    ),
                )
        } catch (t: Throwable) {
            return ExtractionResult.Undecodable(t)
        }

        try {
            val detected = Registration.detect(img)
            val missing = layout.markers.map { it.id }.filterNot { detected.containsKey(it) }
            if (missing.isNotEmpty()) {
                return ExtractionResult.MarkersNotFound(
                    found = layout.markers.size - missing.size,
                    missingIds = missing.sorted(),
                )
            }

            val registered = try {
                Registration.registerPage(layout, pageIndex, modality, inset, detected)
            } catch (e: RegistrationException) {
                return ExtractionResult.RegistrationFailed(e.message.orEmpty())
            }

            return ExtractionResult.Success(registered.map { cut(img, layout, it) })
        } finally {
            img.release()
        }
    }

    /**
     * Rectifies one box onto its canonical size.
     *
     * A box that runs off the edge of the frame comes back padded with black rather than
     * truncated - `warpPerspective` fills outside the source with BORDER_CONSTANT. That is a
     * real behavioural difference from `_crop_region`, which clamped to the image bounds and
     * returned a smaller crop. Black padding is the better of the two: the crop keeps its
     * declared size, so nothing downstream has to guess whether a short crop means a short
     * answer or a clipped photo.
     */
    private fun cut(img: Mat, layout: Layout, registered: RegisteredBox): AnswerCrop {
        val w = registered.canonicalRect.w.toDouble()
        val h = registered.canonicalRect.h.toDouble()

        val src = MatOfPoint2f(*registered.imageQuad.toTypedArray())
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(w, 0.0),
            Point(w, h),
            Point(0.0, h),
        )
        val transform = Imgproc.getPerspectiveTransform(src, dst)
        val rectified = Mat()
        val png = MatOfByte()
        try {
            Imgproc.warpPerspective(img, rectified, transform, Size(w, h))
            check(Imgcodecs.imencode(".png", rectified, png)) {
                "imencode failed for answer box " + registered.box.externalAnswerBoxId
            }
            return AnswerCrop(
                externalQuestionId = layout.externalQuestionId,
                externalAnswerBoxId = registered.box.externalAnswerBoxId,
                pageIndex = registered.box.pageIndex,
                orderIndex = registered.orderIndex,
                png = png.toArray(),
                imageQuad = registered.imageQuad.map { PointPx(it.x, it.y) },
            )
        } finally {
            src.release()
            dst.release()
            transform.release()
            rectified.release()
            png.release()
        }
    }
}
