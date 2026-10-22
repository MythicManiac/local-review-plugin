package com.mythic.localreview.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.mythic.localreview.core.TestRepo
import com.mythic.localreview.core.lines
import com.mythic.localreview.core.tempDir
import com.mythic.localreview.format.Anchor
import com.mythic.localreview.format.AnnotationStatus
import com.mythic.localreview.format.Kind
import com.mythic.localreview.format.ReviewMode
import com.mythic.localreview.store.ReviewFactory
import com.mythic.localreview.ui.notes.ReviewNotesPanel

/** The panel of the review notes tab: comments with the review scope, threaded like any other, following the review's state live. */
class ReviewNotesTest : BasePlatformTestCase() {
    private var savedRoot = ""

    override fun setUp() {
        super.setUp()
        savedRoot = ReviewSettings.getInstance().storeRootSetting
        ReviewSettings.getInstance().storeRootSetting = tempDir().toString()
    }

    override fun tearDown() {
        try {
            ReviewService.getInstance(project).close()
            ReviewSettings.getInstance().storeRootSetting = savedRoot
        } finally {
            super.tearDown()
        }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!condition()) {
            UIUtil.dispatchAllInvocationEvents()
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    private fun activeReview(): ActiveReview {
        val repo = TestRepo()
        repo.write("a.txt", lines("one", "two"))
        repo.commit("base")
        repo.branch("feature")
        repo.write("a.txt", lines("one", "two", "three"))
        repo.commit("work")
        val service = ReviewService.getInstance(project)
        val groups = service.groups()
        val group = groups.reviewGroup(groups.create("g"))
        service.activate(group.create(ReviewFactory.newMeta(repo.git, "T", "master", "feature", ReviewMode.MERGE_BASE, group.deviceId, System.currentTimeMillis())), repo.dir, "g")
        return service.active!!
    }

    fun testOnlyReviewScopeCommentsAreListedInCreationOrderAndFollowTheStateLive() {
        val review = activeReview()
        val service = ReviewService.getInstance(project)
        val panel = ReviewNotesPanel(project)
        try {
            assertEquals(emptyList<String>(), panel.shownNoteIds())

            val first = service.addComment(Kind.COMMENT, "The cache layer leaks into the domain", Anchor.Review, RuleInput(false, null))!!
            // A comment about a file is not a review note.
            service.addComment(Kind.QUESTION, "about a file", Anchor.File("a.txt", null, review.pin), RuleInput(false, null))
            Thread.sleep(2)
            val second = service.addComment(Kind.CHANGE_REQUEST, "Split the module", Anchor.Review, RuleInput(true, null))!!
            waitFor("both notes") { UIUtil.dispatchAllInvocationEvents(); panel.shownNoteIds() == listOf(first, second) }

            // Resolving hides it until "Show resolved" is on; a reply is part of the thread; deleting removes it.
            assertTrue(service.setStatus(review.state.annotation(first)!!, AnnotationStatus.RESOLVED))
            waitFor("the resolved note to leave the list") { UIUtil.dispatchAllInvocationEvents(); panel.shownNoteIds() == listOf(second) }
            assertTrue(service.addReply(review.state.annotation(second)!!, "agreed"))
            waitFor("the reply") { review.state.annotation(second)!!.replies.size == 1 }
            assertTrue(service.deleteComment(review.state.annotation(second)!!))
            waitFor("the deleted note to go") { UIUtil.dispatchAllInvocationEvents(); panel.shownNoteIds().isEmpty() }
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
        }
    }

    fun testAPanelOpenedOnAReviewThatAlreadyHasNotesListsThemAtOnce() {
        activeReview()
        val service = ReviewService.getInstance(project)
        val id = service.addComment(Kind.COMMENT, "already there", Anchor.Review, RuleInput(false, null))!!
        val panel = ReviewNotesPanel(project)
        try {
            assertEquals(listOf(id), panel.shownNoteIds())
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
        }
    }
}
