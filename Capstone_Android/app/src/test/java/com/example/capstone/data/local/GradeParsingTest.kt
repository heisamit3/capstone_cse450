package com.example.capstone.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The model returns text, not a JSON API response. Every case here is a shape a
 * chatty instruction-tuned model actually emits around the object we asked for,
 * so the parser has to survive all of them without inventing a grade.
 */
class GradeParsingTest {

    @Test
    fun `clean json parses into a grade`() {
        val raw =
            """{"transcription":"7 apples","legible":true,"marks":3,"certainty":0.9,"feedback":"Correct."}"""

        val result = LocalGradingService.parseGrade(raw, maxMarks = 5)

        assertThat(result).isNotNull()
        assertThat(result!!.transcription).isEqualTo("7 apples")
        assertThat(result.legible).isTrue()
        assertThat(result.marks).isEqualTo(3)
        assertThat(result.certainty).isEqualTo(0.9)
        assertThat(result.feedback).isEqualTo("Correct.")
        assertThat(result.needsManualReview).isFalse()
    }

    @Test
    fun `json wrapped in markdown fences parses`() {
        val fence = "```"
        val raw = fence + "json\n" +
            """{"transcription":"x = 4","legible":true,"marks":2,"certainty":0.75,"feedback":"Good working."}""" +
            "\n" + fence

        val result = LocalGradingService.parseGrade(raw, maxMarks = 4)

        assertThat(result).isNotNull()
        assertThat(result!!.marks).isEqualTo(2)
        assertThat(result.transcription).isEqualTo("x = 4")
        assertThat(result.needsManualReview).isFalse()
    }

    @Test
    fun `chatty preamble before the json is ignored`() {
        val raw = "Here is my analysis:\n" +
            """{"transcription":"Paris","legible":true,"marks":1,"certainty":0.95,"feedback":"Right."}"""

        val result = LocalGradingService.parseGrade(raw, maxMarks = 1)

        assertThat(result).isNotNull()
        assertThat(result!!.transcription).isEqualTo("Paris")
        assertThat(result.marks).isEqualTo(1)
    }

    @Test
    fun `commentary after the closing brace is ignored`() {
        val raw =
            """{"transcription":"H2O","legible":true,"marks":2,"certainty":0.8,"feedback":"Correct."}""" +
                "\n\nI hope this helps! Let me know if you would like me to re-check the working."

        val result = LocalGradingService.parseGrade(raw, maxMarks = 2)

        assertThat(result).isNotNull()
        assertThat(result!!.transcription).isEqualTo("H2O")
        assertThat(result.marks).isEqualTo(2)
    }

    @Test
    fun `when two objects are returned the first one wins`() {
        val raw =
            """{"transcription":"first","legible":true,"marks":1,"certainty":0.4,"feedback":"Draft."}""" +
                "\n" +
                """{"transcription":"second","legible":true,"marks":5,"certainty":0.9,"feedback":"Final."}"""

        val result = LocalGradingService.parseGrade(raw, maxMarks = 5)

        assertThat(result).isNotNull()
        assertThat(result!!.transcription).isEqualTo("first")
        assertThat(result.marks).isEqualTo(1)
    }

    @Test
    fun `braces inside string values do not end the object early`() {
        val raw =
            """{"transcription":"the set {a, b}","legible":true,"marks":1,"certainty":0.5,"feedback":"ok"}"""

        val result = LocalGradingService.parseGrade(raw, maxMarks = 1)

        assertThat(result).isNotNull()
        assertThat(result!!.transcription).isEqualTo("the set {a, b}")
    }

    @Test
    fun `truncated json does not throw and yields no grade`() {
        val raw = """{"transcription":"the student wrote","legible":true,"marks":3,"certa"""

        assertThat(LocalGradingService.parseGrade(raw, maxMarks = 5)).isNull()
    }

    @Test
    fun `malformed json does not throw and yields no grade`() {
        val raw = """{"transcription":"x", "legible": true, "marks": , "certainty": 0.5}"""

        assertThat(LocalGradingService.parseGrade(raw, maxMarks = 5)).isNull()
    }

    @Test
    fun `json without a marks field yields no grade rather than a silent zero`() {
        val raw = """{"transcription":"x","legible":true,"certainty":0.5,"feedback":"ok"}"""

        assertThat(LocalGradingService.parseGrade(raw, maxMarks = 5)).isNull()
    }

    @Test
    fun `empty response does not throw and yields no grade`() {
        assertThat(LocalGradingService.parseGrade("", maxMarks = 5)).isNull()
        assertThat(LocalGradingService.parseGrade("   \n  ", maxMarks = 5)).isNull()
        assertThat(LocalGradingService.parseGrade("I cannot read this image.", maxMarks = 5)).isNull()
    }

    /**
     * A null parse is what [LocalGradingService.grade] turns into the manual
     * review result once its attempts are spent, so that fallback must always be
     * flagged and must never carry a made-up mark.
     */
    @Test
    fun `the unparseable fallback is flagged for manual review with no marks`() {
        val fallback = LocalGradingService.manualReviewResult()

        assertThat(fallback.needsManualReview).isTrue()
        assertThat(fallback.legible).isFalse()
        assertThat(fallback.marks).isEqualTo(0)
        assertThat(fallback.certainty).isEqualTo(0.0)
    }

    @Test
    fun `marks above the maximum are clamped`() {
        val raw = """{"transcription":"x","legible":true,"marks":12,"certainty":0.9,"feedback":"ok"}"""

        val result = LocalGradingService.parseGrade(raw, maxMarks = 5)

        assertThat(result).isNotNull()
        assertThat(result!!.marks).isEqualTo(5)
    }

    @Test
    fun `negative marks are clamped to zero`() {
        val raw = """{"transcription":"x","legible":true,"marks":-4,"certainty":0.9,"feedback":"ok"}"""

        val result = LocalGradingService.parseGrade(raw, maxMarks = 5)

        assertThat(result).isNotNull()
        assertThat(result!!.marks).isEqualTo(0)
    }

    @Test
    fun `fractional marks are rounded before clamping`() {
        val raw = """{"transcription":"x","legible":true,"marks":2.6,"certainty":0.9,"feedback":"ok"}"""

        val result = LocalGradingService.parseGrade(raw, maxMarks = 5)

        assertThat(result).isNotNull()
        assertThat(result!!.marks).isEqualTo(3)
    }

    @Test
    fun `certainty above one is clamped`() {
        val raw = """{"transcription":"x","legible":true,"marks":1,"certainty":1.7,"feedback":"ok"}"""

        val result = LocalGradingService.parseGrade(raw, maxMarks = 5)

        assertThat(result).isNotNull()
        assertThat(result!!.certainty).isEqualTo(1.0)
    }

    @Test
    fun `certainty below zero is clamped`() {
        val raw = """{"transcription":"x","legible":true,"marks":1,"certainty":-0.5,"feedback":"ok"}"""

        val result = LocalGradingService.parseGrade(raw, maxMarks = 5)

        assertThat(result).isNotNull()
        assertThat(result!!.certainty).isEqualTo(0.0)
    }

    @Test
    fun `an illegible answer is flagged for manual review`() {
        val raw =
            """{"transcription":"","legible":false,"marks":0,"certainty":0.2,"feedback":"Cannot read."}"""

        val result = LocalGradingService.parseGrade(raw, maxMarks = 5)

        assertThat(result).isNotNull()
        assertThat(result!!.legible).isFalse()
        assertThat(result.needsManualReview).isTrue()
    }

    /** legible=false wins even when the model was happy to attach a mark to it. */
    @Test
    fun `an illegible answer with confident marks is still flagged`() {
        val raw =
            """{"transcription":"maybe 42","legible":false,"marks":5,"certainty":0.99,"feedback":"Guess."}"""

        val result = LocalGradingService.parseGrade(raw, maxMarks = 5)

        assertThat(result).isNotNull()
        assertThat(result!!.needsManualReview).isTrue()
    }

    /** A missing legible field is treated as illegible, not as a pass. */
    @Test
    fun `a missing legible field is flagged for manual review`() {
        val raw = """{"transcription":"x","marks":3,"certainty":0.9,"feedback":"ok"}"""

        val result = LocalGradingService.parseGrade(raw, maxMarks = 5)

        assertThat(result).isNotNull()
        assertThat(result!!.legible).isFalse()
        assertThat(result.needsManualReview).isTrue()
    }
}
