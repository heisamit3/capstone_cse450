package com.example.capstone.data.remote

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.Test

/**
 * Pins the wire shape of the student assignment endpoints against the JSON the
 * server actually sends, using the same Gson the Retrofit converter uses.
 *
 * The point of these tests is the fields the client used to discard: `marks`,
 * `model_answer`, `rubric` and now `external_answer_box_id` and `layout`. The
 * previous failure mode was silent - Gson simply dropped keys the DTO had no
 * field for, and grading had nothing to grade against.
 */
class AssignmentDtoParsingTest {

    private val gson = Gson()

    /** GET /api/assignments/{id} for an imported assignment, student branch. */
    private val detailJson = """
        {
          "id": 12,
          "title": "Worksheet 3 - Kinematics",
          "description": null,
          "total_marks": 3,
          "external_question_id": "e9cce9d0-34b1-4657-87ad-65898e6a71ab",
          "questions": [
            {
              "id": 41,
              "question_text": "1a",
              "marks": 1,
              "model_answer": "F = ma",
              "rubric": "One mark for the relationship.",
              "external_answer_box_id": "ab_syzn1vsmmsrm6jat"
            },
            {
              "id": 42,
              "question_text": "1b",
              "marks": 2,
              "model_answer": "",
              "rubric": null,
              "external_answer_box_id": "ab_uub03qhomsrm71en"
            }
          ],
          "layout": {
            "page_w_px": 1240,
            "page_h_px": 1754,
            "aruco_dict": "DICT_4X4_50",
            "markers": {
              "aruco_dict": "DICT_4X4_50",
              "marker_size_px": 60,
              "marker_margin_px": 40,
              "centres": {
                "0": [70, 70],
                "1": [1170, 70],
                "2": [70, 1684],
                "3": [1170, 1684]
              },
              "source": "computed_from_constants"
            },
            "answer_boxes": [
              {
                "id": "ab_syzn1vsmmsrm6jat",
                "label": "1a",
                "points": 1,
                "bbox": [186, 334, 930, 90],
                "page_index": 0,
                "order_index": 0
              },
              {
                "id": "ab_uub03qhomsrm71en",
                "label": "1b",
                "points": 2,
                "bbox": [186, 124, 465, 130],
                "page_index": 1,
                "order_index": 1
              }
            ],
            "layout_version": 1
          }
        }
    """.trimIndent()

    /** GET /api/assignments, student branch: id and question_text only. */
    private val listJson = """
        [
          {
            "id": 12,
            "title": "Worksheet 3 - Kinematics",
            "description": null,
            "total_marks": 3,
            "questions": [
              { "id": 41, "question_text": "1a" },
              { "id": 42, "question_text": "1b" }
            ],
            "submission_status": "pending"
          }
        ]
    """.trimIndent()

    @Test
    fun `detail response carries marks, model answer, rubric and box id`() {
        val dto = gson.fromJson(detailJson, AssignmentDto::class.java)

        val first = dto.questions!!.first()
        assertThat(first.marks).isEqualTo(1)
        assertThat(first.modelAnswer).isEqualTo("F = ma")
        assertThat(first.rubric).isEqualTo("One mark for the relationship.")
        assertThat(first.externalAnswerBoxId).isEqualTo("ab_syzn1vsmmsrm6jat")
    }

    @Test
    fun `an imported question with no model answer yet parses as empty, not null`() {
        // Empty means "imported, teacher has not supplied it"; null means "this
        // endpoint did not send it". Both are ungradeable, only one is a bug.
        val dto = gson.fromJson(detailJson, AssignmentDto::class.java)

        val second = dto.questions!![1]
        assertThat(second.modelAnswer).isEqualTo("")
        assertThat(second.rubric).isNull()
    }

    @Test
    fun `detail response carries the external question id`() {
        val dto = gson.fromJson(detailJson, AssignmentDto::class.java)

        assertThat(dto.externalQuestionId)
            .isEqualTo("e9cce9d0-34b1-4657-87ad-65898e6a71ab")
    }

    @Test
    fun `layout parses page size, dictionary and version`() {
        val layout = gson.fromJson(detailJson, AssignmentDto::class.java).layout!!

        assertThat(layout.pageWidthPx).isEqualTo(1240)
        assertThat(layout.pageHeightPx).isEqualTo(1754)
        assertThat(layout.arucoDict).isEqualTo("DICT_4X4_50")
        assertThat(layout.layoutVersion).isEqualTo(1)
    }

    @Test
    fun `layout parses all four marker centres in row major order`() {
        val markers = gson.fromJson(detailJson, AssignmentDto::class.java).layout!!.markers

        assertThat(markers.markerSizePx).isEqualTo(60)
        assertThat(markers.markerMarginPx).isEqualTo(40)
        assertThat(markers.source).isEqualTo("computed_from_constants")
        assertThat(markers.centres.keys).containsExactly("0", "1", "2", "3")
        // 0 top-left, 1 top-right, 2 BOTTOM-left, 3 bottom-right. Not clockwise.
        assertThat(markers.centres["0"]).containsExactly(70, 70).inOrder()
        assertThat(markers.centres["1"]).containsExactly(1170, 70).inOrder()
        assertThat(markers.centres["2"]).containsExactly(70, 1684).inOrder()
        assertThat(markers.centres["3"]).containsExactly(1170, 1684).inOrder()
    }

    @Test
    fun `layout preserves answer box array order and geometry`() {
        val boxes = gson.fromJson(detailJson, AssignmentDto::class.java).layout!!.answerBoxes

        assertThat(boxes.map { it.id })
            .containsExactly("ab_syzn1vsmmsrm6jat", "ab_uub03qhomsrm71en").inOrder()
        assertThat(boxes.map { it.orderIndex }).containsExactly(0, 1).inOrder()
        assertThat(boxes[0].bbox).containsExactly(186, 334, 930, 90).inOrder()
        assertThat(boxes[0].pageIndex).isEqualTo(0)
        assertThat(boxes[1].pageIndex).isEqualTo(1)
        assertThat(boxes[1].points).isEqualTo(2)
    }

    @Test
    fun `list response leaves the unsent fields null rather than defaulting them`() {
        // The list endpoint selects id and question_text only. A fabricated
        // marks value here would be graded against as if it were real.
        val dtos = gson.fromJson(listJson, Array<AssignmentDto>::class.java)

        val question = dtos.single().questions!!.first()
        assertThat(question.id).isEqualTo(41)
        assertThat(question.questionText).isEqualTo("1a")
        assertThat(question.marks).isNull()
        assertThat(question.modelAnswer).isNull()
        assertThat(question.rubric).isNull()
        assertThat(question.externalAnswerBoxId).isNull()
        assertThat(dtos.single().externalQuestionId).isNull()
        assertThat(dtos.single().layout).isNull()
    }
}
