package com.example.capstone.domain.worksheet

import com.example.capstone.domain.model.Assignment
import com.example.capstone.domain.model.Question
import com.example.capstone.extractor.AnswerCrop

/**
 * Maps extracted [AnswerCrop]s onto the [Question]s they answer.
 *
 * This is the single place where the teacher worksheet system's answer box ids
 * meet this app's question ids. Everything downstream - grading, upload, the
 * per-question grade rows - depends on this class and on nothing else about how
 * ids arrive. If the join ever changes shape, it changes here.
 *
 * Three rules, all deliberate:
 *
 * 1. **The key is the pair, never the bare box id.** A resolver is scoped to
 *    one assignment *and* one external question id, and a crop carrying a
 *    different question id is rejected even if its box id matches. On the
 *    teacher side a bare answer box id is globally unique today only because a
 *    collision crashes his insert rather than coexisting; if that is ever fixed
 *    with a per-question key, bare ids stop being unique and an unscoped lookup
 *    starts joining across worksheets with no visible symptom.
 *
 * 2. **Never a silent skip.** A crop with no question, or a question with no
 *    crop, fails the whole resolution and names the offending ids. The failure
 *    being avoided is a student submitting a worksheet where one answer quietly
 *    never got graded - or, worse, got graded against the wrong question. Both
 *    look like success from the outside.
 *
 * 3. **No partial result is offered.** A caller handed a shorter list than it
 *    asked for is very likely to grade it and report success.
 */
class QuestionResolver private constructor(
    /** The assignment this resolver is scoped to. */
    val assignmentId: Int,
    /** The teacher worksheet question id this resolver is scoped to. */
    val externalQuestionId: String,
    private val questionsByBoxId: Map<String, Question>
) {

    /** Answer box ids this resolver can resolve, in question order. */
    val knownBoxIds: Set<String>
        get() = questionsByBoxId.keys

    /**
     * Pairs every crop with its question.
     *
     * Returns [Resolution.Resolved] only when the crop set and the question set
     * correspond exactly, one to one, and every crop came from this resolver's
     * external question. Anything else is [Resolution.Failed], carrying the
     * specific ids so a caller can say which answer box could not be read
     * rather than "something went wrong".
     *
     * The returned pairs are ordered by question, which for an imported
     * assignment is the teacher's document order, regardless of the order the
     * extractor happened to emit crops in.
     */
    fun resolve(crops: List<AnswerCrop>): Resolution {
        val fromOtherQuestion = crops
            .filter { it.externalQuestionId != externalQuestionId }
            .map { it.externalQuestionId }
            .distinct()
            .sorted()

        val mine = crops.filter { it.externalQuestionId == externalQuestionId }

        val duplicateCropIds = mine
            .groupingBy { it.externalAnswerBoxId }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()

        val cropsByBoxId = mine.associateBy { it.externalAnswerBoxId }

        val cropsWithoutQuestion = mine
            .map { it.externalAnswerBoxId }
            .filterNot { questionsByBoxId.containsKey(it) }
            .distinct()
            .sorted()

        val questionsWithoutCrop = questionsByBoxId
            .filterKeys { !cropsByBoxId.containsKey(it) }
            .values
            .map { it.id }
            .sorted()

        if (fromOtherQuestion.isNotEmpty() ||
            duplicateCropIds.isNotEmpty() ||
            cropsWithoutQuestion.isNotEmpty() ||
            questionsWithoutCrop.isNotEmpty()
        ) {
            return Resolution.Failed(
                assignmentId = assignmentId,
                externalQuestionId = externalQuestionId,
                cropsFromOtherQuestion = fromOtherQuestion,
                cropsWithoutQuestion = cropsWithoutQuestion,
                questionsWithoutCrop = questionsWithoutCrop,
                duplicateCropIds = duplicateCropIds
            )
        }

        val resolved = questionsByBoxId.entries.map { (boxId, question) ->
            ResolvedAnswer(question = question, crop = cropsByBoxId.getValue(boxId))
        }

        return Resolution.Resolved(resolved)
    }

    companion object {
        /**
         * Builds a resolver for [assignment].
         *
         * Fails rather than resolving anything when the assignment cannot
         * support the join at all:
         *
         * - it has no questions;
         * - it has no external question id, i.e. it was never imported from the
         *   teacher worksheet system and there is nothing on paper to match;
         * - no question carries an external answer box id;
         * - only some questions carry one, which would make any resolution
         *   necessarily incomplete;
         * - two questions carry the same one, which the server's
         *   `(assignment_id, external_answer_box_id)` unique index is supposed
         *   to prevent - if it is ever seen here, the join is not trustworthy
         *   and must not be used.
         */
        fun forAssignment(assignment: Assignment): ResolverCreation {
            val externalQuestionId = assignment.externalQuestionId
            if (externalQuestionId.isNullOrBlank()) {
                return ResolverCreation.Unavailable(
                    assignmentId = assignment.id,
                    reason = "Assignment ${assignment.id} has no external question id: " +
                        "it was not imported from the teacher worksheet system, so " +
                        "there is no printed geometry to resolve crops against."
                )
            }

            if (assignment.questions.isEmpty()) {
                return ResolverCreation.Unavailable(
                    assignmentId = assignment.id,
                    reason = "Assignment ${assignment.id} has no questions."
                )
            }

            val unlinked = assignment.questions
                .filter { it.externalAnswerBoxId.isNullOrBlank() }
                .map { it.id }

            if (unlinked.size == assignment.questions.size) {
                return ResolverCreation.Unavailable(
                    assignmentId = assignment.id,
                    reason = "Assignment ${assignment.id} carries no answer box ids, " +
                        "although it names external question $externalQuestionId. " +
                        "The import is incomplete."
                )
            }

            if (unlinked.isNotEmpty()) {
                return ResolverCreation.Unavailable(
                    assignmentId = assignment.id,
                    reason = "Assignment ${assignment.id} is partially linked: " +
                        "questions $unlinked have no answer box id while the rest " +
                        "do. Resolving would silently drop them."
                )
            }

            val byBoxId = LinkedHashMap<String, Question>(assignment.questions.size)
            for (question in assignment.questions) {
                val boxId = question.externalAnswerBoxId!!
                val previous = byBoxId.put(boxId, question)
                if (previous != null) {
                    return ResolverCreation.Unavailable(
                        assignmentId = assignment.id,
                        reason = "Assignment ${assignment.id} has two questions " +
                            "(${previous.id} and ${question.id}) sharing answer box " +
                            "id \"$boxId\". The join is ambiguous and must not be used."
                    )
                }
            }

            return ResolverCreation.Available(
                QuestionResolver(
                    assignmentId = assignment.id,
                    externalQuestionId = externalQuestionId,
                    questionsByBoxId = byBoxId
                )
            )
        }
    }
}

/** One crop matched to the question it answers. */
data class ResolvedAnswer(
    val question: Question,
    val crop: AnswerCrop
)

/** Outcome of building a [QuestionResolver] for an assignment. */
sealed interface ResolverCreation {
    data class Available(val resolver: QuestionResolver) : ResolverCreation

    /**
     * The assignment cannot be resolved against at all. [reason] is written to
     * be logged verbatim, not shown to a student.
     */
    data class Unavailable(
        val assignmentId: Int,
        val reason: String
    ) : ResolverCreation
}

/** Outcome of resolving a set of crops. */
sealed interface Resolution {

    /** Every crop matched exactly one question and every question got a crop. */
    data class Resolved(val answers: List<ResolvedAnswer>) : Resolution

    /**
     * The crop set and the question set do not correspond.
     *
     * @param cropsFromOtherQuestion external question ids on crops that did not
     *   come from this assignment's worksheet - a photo of the wrong sheet.
     * @param cropsWithoutQuestion answer box ids extracted from the right
     *   worksheet that belong to no question here - a stale layout.
     * @param questionsWithoutCrop our question ids that no crop was produced
     *   for - a missing page, or a box the extractor could not cut.
     * @param duplicateCropIds answer box ids extracted more than once, e.g. the
     *   same page photographed twice.
     */
    data class Failed(
        val assignmentId: Int,
        val externalQuestionId: String,
        val cropsFromOtherQuestion: List<String>,
        val cropsWithoutQuestion: List<String>,
        val questionsWithoutCrop: List<Int>,
        val duplicateCropIds: List<String>
    ) : Resolution {

        /** One line naming every mismatch. Log this; do not discard it. */
        val message: String
            get() = buildString {
                append("Cannot resolve crops for assignment ")
                append(assignmentId)
                append(" (external question ")
                append(externalQuestionId)
                append("): ")
                val parts = mutableListOf<String>()
                if (cropsFromOtherQuestion.isNotEmpty()) {
                    parts += "crops from another question $cropsFromOtherQuestion"
                }
                if (cropsWithoutQuestion.isNotEmpty()) {
                    parts += "crops with no question $cropsWithoutQuestion"
                }
                if (questionsWithoutCrop.isNotEmpty()) {
                    parts += "questions with no crop $questionsWithoutCrop"
                }
                if (duplicateCropIds.isNotEmpty()) {
                    parts += "duplicate crops $duplicateCropIds"
                }
                append(parts.joinToString("; "))
            }
    }
}
