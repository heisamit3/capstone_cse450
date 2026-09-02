package com.example.capstone.domain.model

import com.example.capstone.extractor.Layout

/**
 * One question of an assignment.
 *
 * [marks], [modelAnswer], [rubric] and [externalAnswerBoxId] are nullable
 * because the assignment list endpoint does not serve them - only the
 * assignment detail endpoint does. Null therefore means "not fetched on this
 * path", not "the server has no value".
 *
 * [modelAnswer] has a third state: an empty string, meaning the question was
 * imported from the teacher worksheet system, which serves no marking data, and
 * the teacher has not filled it in yet. Ungradeable, like null, but for a
 * different reason - see [isGradeable].
 */
data class Question(
    val id: Int,
    val text: String,
    val marks: Int? = null,
    val modelAnswer: String? = null,
    val rubric: String? = null,
    /**
     * The teacher worksheet system's answer box id, when this question came
     * from an imported worksheet. Null otherwise.
     *
     * Never treat this as globally unique: it is unique only within its
     * assignment. Resolve it through QuestionResolver, which is scoped to one
     * assignment and one external question id.
     */
    val externalAnswerBoxId: String? = null
) {
    /**
     * True when there is enough here to grade against: a mark ceiling and a
     * non-blank model answer. False for a list-endpoint question and for an
     * imported question whose marking data the teacher has not supplied.
     */
    val isGradeable: Boolean
        get() = marks != null && !modelAnswer.isNullOrBlank()
}

data class Assignment(
    val id: Int,
    val title: String,
    /** Optional: the server column is nullable. */
    val description: String?,
    val questions: List<Question> = emptyList(),
    val isCompleted: Boolean = false,
    /**
     * The teacher worksheet system's question id this assignment was imported
     * from. Null for locally created assignments. The other half of the join
     * key: an answer box id means nothing without it.
     */
    val externalQuestionId: String? = null,
    /**
     * Printed-page geometry, present only for imported assignments and only on
     * the detail endpoint. Null everywhere else.
     *
     * This is the extractor's own [Layout] type, built from what the server
     * stored. Nothing on this side computes marker positions or page size: they
     * come from the teacher worksheet system, were captured once by the server
     * import route, and live on a row that can be inspected and corrected. A
     * wrong constant baked into an APK cannot be.
     */
    val layout: Layout? = null
)
