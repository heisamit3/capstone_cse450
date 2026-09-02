package com.example.capstone.domain.worksheet

import com.example.capstone.domain.model.Assignment
import com.example.capstone.domain.model.Question
import com.example.capstone.extractor.AnswerCrop
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [QuestionResolver] - the single join between the teacher
 * worksheet system's answer box ids and this app's question ids.
 *
 * Plain JVM: the resolver touches no Android API and no OpenCV, on purpose, so
 * the join can be tested without a device.
 */
class QuestionResolverTest {

    private val questionId = "q_worksheet_3"

    private fun question(
        id: Int,
        boxId: String?,
        text: String = "Q$id"
    ) = Question(
        id = id,
        text = text,
        marks = 5,
        modelAnswer = "answer",
        rubric = null,
        externalAnswerBoxId = boxId
    )

    private fun assignment(
        vararg questions: Question,
        externalQuestionId: String? = this.questionId
    ) = Assignment(
        id = 12,
        title = "Worksheet 3",
        description = null,
        questions = questions.toList(),
        externalQuestionId = externalQuestionId
    )

    private fun crop(
        boxId: String,
        pageIndex: Int = 0,
        orderIndex: Int = 0,
        externalQuestionId: String = this.questionId
    ) = AnswerCrop(
        externalQuestionId = externalQuestionId,
        externalAnswerBoxId = boxId,
        pageIndex = pageIndex,
        orderIndex = orderIndex,
        png = byteArrayOf(1, 2, 3),
        imageQuad = emptyList()
    )

    private fun resolverFor(assignment: Assignment): QuestionResolver {
        val creation = QuestionResolver.forAssignment(assignment)
        assertThat(creation).isInstanceOf(ResolverCreation.Available::class.java)
        return (creation as ResolverCreation.Available).resolver
    }

    // ---- construction -----------------------------------------------------

    @Test
    fun `builds a resolver for a fully linked assignment`() {
        val creation = QuestionResolver.forAssignment(
            assignment(question(1, "ab_one"), question(2, "ab_two"))
        )

        assertThat(creation).isInstanceOf(ResolverCreation.Available::class.java)
        val resolver = (creation as ResolverCreation.Available).resolver
        assertThat(resolver.assignmentId).isEqualTo(12)
        assertThat(resolver.externalQuestionId).isEqualTo(questionId)
        assertThat(resolver.knownBoxIds).containsExactly("ab_one", "ab_two")
    }

    @Test
    fun `refuses an assignment that was never imported`() {
        val creation = QuestionResolver.forAssignment(
            assignment(question(1, null), externalQuestionId = null)
        )

        assertThat(creation).isInstanceOf(ResolverCreation.Unavailable::class.java)
        assertThat((creation as ResolverCreation.Unavailable).reason)
            .contains("no external question id")
    }

    @Test
    fun `refuses an assignment with no questions`() {
        val creation = QuestionResolver.forAssignment(assignment())

        assertThat(creation).isInstanceOf(ResolverCreation.Unavailable::class.java)
        assertThat((creation as ResolverCreation.Unavailable).reason)
            .contains("no questions")
    }

    @Test
    fun `refuses an imported assignment carrying no answer box ids`() {
        val creation = QuestionResolver.forAssignment(
            assignment(question(1, null), question(2, null))
        )

        assertThat(creation).isInstanceOf(ResolverCreation.Unavailable::class.java)
        assertThat((creation as ResolverCreation.Unavailable).reason)
            .contains("carries no answer box ids")
    }

    @Test
    fun `refuses a partially linked assignment rather than dropping the unlinked`() {
        val creation = QuestionResolver.forAssignment(
            assignment(question(1, "ab_one"), question(2, null))
        )

        assertThat(creation).isInstanceOf(ResolverCreation.Unavailable::class.java)
        val reason = (creation as ResolverCreation.Unavailable).reason
        assertThat(reason).contains("partially linked")
        assertThat(reason).contains("[2]")
    }

    @Test
    fun `treats a blank answer box id as unlinked`() {
        val creation = QuestionResolver.forAssignment(
            assignment(question(1, "ab_one"), question(2, "   "))
        )

        assertThat(creation).isInstanceOf(ResolverCreation.Unavailable::class.java)
    }

    @Test
    fun `refuses two questions sharing one answer box id`() {
        val creation = QuestionResolver.forAssignment(
            assignment(question(1, "ab_dup"), question(2, "ab_dup"))
        )

        assertThat(creation).isInstanceOf(ResolverCreation.Unavailable::class.java)
        assertThat((creation as ResolverCreation.Unavailable).reason)
            .contains("ambiguous")
    }

    // ---- resolution -------------------------------------------------------

    @Test
    fun `resolves a complete one to one crop set`() {
        val resolver = resolverFor(assignment(question(1, "ab_one"), question(2, "ab_two")))

        val outcome = resolver.resolve(
            listOf(crop("ab_one"), crop("ab_two", pageIndex = 1, orderIndex = 1))
        )

        assertThat(outcome).isInstanceOf(Resolution.Resolved::class.java)
        val answers = (outcome as Resolution.Resolved).answers
        assertThat(answers.map { it.question.id }).containsExactly(1, 2).inOrder()
        assertThat(answers.map { it.crop.externalAnswerBoxId })
            .containsExactly("ab_one", "ab_two").inOrder()
    }

    @Test
    fun `returns answers in question order regardless of crop order`() {
        val resolver = resolverFor(
            assignment(question(1, "ab_one"), question(2, "ab_two"), question(3, "ab_three"))
        )

        val outcome = resolver.resolve(
            listOf(crop("ab_three"), crop("ab_one"), crop("ab_two"))
        )

        assertThat(outcome).isInstanceOf(Resolution.Resolved::class.java)
        assertThat((outcome as Resolution.Resolved).answers.map { it.question.id })
            .containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun `fails when a crop has no question`() {
        val resolver = resolverFor(assignment(question(1, "ab_one")))

        val outcome = resolver.resolve(listOf(crop("ab_one"), crop("ab_stranger")))

        assertThat(outcome).isInstanceOf(Resolution.Failed::class.java)
        val failed = outcome as Resolution.Failed
        assertThat(failed.cropsWithoutQuestion).containsExactly("ab_stranger")
        assertThat(failed.questionsWithoutCrop).isEmpty()
        assertThat(failed.message).contains("ab_stranger")
    }

    @Test
    fun `fails when a question has no crop`() {
        val resolver = resolverFor(assignment(question(1, "ab_one"), question(2, "ab_two")))

        val outcome = resolver.resolve(listOf(crop("ab_one")))

        assertThat(outcome).isInstanceOf(Resolution.Failed::class.java)
        val failed = outcome as Resolution.Failed
        assertThat(failed.questionsWithoutCrop).containsExactly(2)
        assertThat(failed.cropsWithoutQuestion).isEmpty()
        assertThat(failed.message).contains("[2]")
    }

    @Test
    fun `fails on an empty crop list rather than resolving nothing`() {
        val resolver = resolverFor(assignment(question(1, "ab_one")))

        val outcome = resolver.resolve(emptyList())

        assertThat(outcome).isInstanceOf(Resolution.Failed::class.java)
        assertThat((outcome as Resolution.Failed).questionsWithoutCrop).containsExactly(1)
    }

    @Test
    fun `fails on a duplicated crop`() {
        val resolver = resolverFor(assignment(question(1, "ab_one"), question(2, "ab_two")))

        val outcome = resolver.resolve(
            listOf(crop("ab_one"), crop("ab_one", pageIndex = 1), crop("ab_two"))
        )

        assertThat(outcome).isInstanceOf(Resolution.Failed::class.java)
        val failed = outcome as Resolution.Failed
        assertThat(failed.duplicateCropIds).containsExactly("ab_one")
        assertThat(failed.message).contains("duplicate")
    }

    @Test
    fun `rejects a crop whose box id matches but whose question id does not`() {
        // The join key is the pair. A bare answer box id is globally unique on
        // the teacher side only by accident, so a matching box id from another
        // worksheet must not resolve - it must fail loudly.
        val resolver = resolverFor(assignment(question(1, "ab_one")))

        val outcome = resolver.resolve(
            listOf(crop("ab_one", externalQuestionId = "q_some_other_worksheet"))
        )

        assertThat(outcome).isInstanceOf(Resolution.Failed::class.java)
        val failed = outcome as Resolution.Failed
        assertThat(failed.cropsFromOtherQuestion)
            .containsExactly("q_some_other_worksheet")
        assertThat(failed.questionsWithoutCrop).containsExactly(1)
        assertThat(failed.message).contains("q_some_other_worksheet")
    }

    @Test
    fun `reports every category of mismatch at once`() {
        val resolver = resolverFor(assignment(question(1, "ab_one"), question(2, "ab_two")))

        val outcome = resolver.resolve(
            listOf(
                crop("ab_one"),
                crop("ab_one"),
                crop("ab_stranger"),
                crop("ab_two", externalQuestionId = "q_other")
            )
        )

        assertThat(outcome).isInstanceOf(Resolution.Failed::class.java)
        val failed = outcome as Resolution.Failed
        assertThat(failed.duplicateCropIds).containsExactly("ab_one")
        assertThat(failed.cropsWithoutQuestion).containsExactly("ab_stranger")
        assertThat(failed.questionsWithoutCrop).containsExactly(2)
        assertThat(failed.cropsFromOtherQuestion).containsExactly("q_other")
        assertThat(failed.assignmentId).isEqualTo(12)
        assertThat(failed.externalQuestionId).isEqualTo(questionId)
    }

    @Test
    fun `carries the full question through to the resolved answer`() {
        val resolver = resolverFor(assignment(question(1, "ab_one", text = "State the law.")))

        val outcome = resolver.resolve(listOf(crop("ab_one")))

        val answer = (outcome as Resolution.Resolved).answers.single()
        assertThat(answer.question.text).isEqualTo("State the law.")
        assertThat(answer.question.marks).isEqualTo(5)
        assertThat(answer.question.modelAnswer).isEqualTo("answer")
        assertThat(answer.question.isGradeable).isTrue()
        assertThat(answer.crop.png).hasLength(3)
    }
}
