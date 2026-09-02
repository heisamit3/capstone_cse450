package com.example.capstone.domain.worksheet

import com.example.capstone.extractor.AnswerCrop

/**
 * Carries one worksheet's crops from the scan screen to the grading screen.
 *
 * The crops are megabytes of PNG. `SavedStateHandle` is the usual way to hand
 * data between destinations and is the wrong tool here: it is a `Bundle`, it
 * crosses a Binder transaction on save, and a Binder transaction has a hard
 * limit around 1 MB for the whole process. The `photo_bytes` hop this replaces
 * did exactly that and was a crash waiting for a big enough photo. A plain
 * in-memory holder is honest about what it is instead.
 *
 * The cost of that honesty, stated: **this does not survive process death.** If
 * Android kills the app while the grading screen is backgrounded, the crops are
 * gone and [crops] returns null, which the grading screen reports as "photo no
 * longer available, scan again" rather than pretending. Re-photographing is
 * cheap; silently grading a stale worksheet is not.
 *
 * Scoped by assignment id so a crop set can never be read back against a
 * different assignment than the one it was taken for.
 */
class WorksheetSession {

    private var assignmentId: Int? = null
    private var crops: List<AnswerCrop> = emptyList()

    /** Replaces whatever was held. A new scan always supersedes the last one. */
    @Synchronized
    fun put(assignmentId: Int, crops: List<AnswerCrop>) {
        this.assignmentId = assignmentId
        this.crops = crops
    }

    /**
     * The crops taken for [assignmentId], or null if there are none - either
     * nothing was scanned, or what is held belongs to a different assignment,
     * or the process was restarted since.
     */
    @Synchronized
    fun crops(assignmentId: Int): List<AnswerCrop>? =
        if (this.assignmentId == assignmentId) crops else null

    /** Drops the held crops once they have been graded and uploaded. */
    @Synchronized
    fun clear() {
        assignmentId = null
        crops = emptyList()
    }
}
