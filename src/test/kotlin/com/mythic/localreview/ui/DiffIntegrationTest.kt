package com.mythic.localreview.ui

import com.intellij.diff.DiffManager
import com.intellij.diff.util.Side as DiffSide
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.mythic.localreview.core.Anchor
import com.mythic.localreview.core.AnchorScope
import com.mythic.localreview.core.Anchoring
import com.mythic.localreview.core.CommentKind
import com.mythic.localreview.core.ReviewRepository
import com.mythic.localreview.core.Side
import com.mythic.localreview.core.TestRepo
import com.mythic.localreview.core.lines
import com.mythic.localreview.core.tempDir
import com.mythic.localreview.ui.diff.FileDiffProducer

/** Renders a real review diff in the platform diff viewer and checks what our DiffExtension attaches. */
class DiffIntegrationTest : BasePlatformTestCase() {
    private var savedRoot = ""

    override fun setUp() {
        super.setUp()
        // Never touch the real ~/.local-review from tests.
        savedRoot = ReviewSettings.getInstance().storeRootSetting
        ReviewSettings.getInstance().storeRootSetting = tempDir().toString()
    }

    override fun tearDown() {
        try {
            ReviewSettings.getInstance().storeRootSetting = savedRoot
        } finally {
            super.tearDown()
        }
    }

    private fun body(n: Int) = lines(*(1..n).map { "line $it" }.toTypedArray())

    private fun editorsOf(): List<Editor> = EditorFactory.getInstance().allEditors.toList()

    private fun inlayCount(e: Editor) = e.inlayModel.getBlockElementsInRange(0, e.document.textLength).size

    private fun gutterIcons(e: Editor) = e.markupModel.allHighlighters.count { it.gutterIconRenderer != null }

    fun testCommentsAndHunkMarkersRenderAndPersist() {
        val repo = TestRepo()
        repo.write("src/a.txt", body(30))
        repo.commit("base")
        repo.branch("feature")
        repo.write("src/a.txt", body(30).replace("line 5\n", "line 5 changed\n").replace("line 20\n", "line 20 changed\n"))
        repo.commit("work")

        val service = ReviewService.getInstance(project)
        val store = ReviewRepository(tempDir()).create(repo.git, "T", "master", "feature")
        service.activate(store, repo.dir)
        val review = service.active!!
        val change = review.changes.single()

        val headLines = Anchoring.splitLines(String(repo.git.showFile(store.review().current.headSha, "src/a.txt")!!))
        val cap = Anchoring.capture(headLines, 5, 5)
        val comment = store.addComment(
            CommentKind.CHANGE_REQUEST, "Please revert this",
            Anchor(AnchorScope.LINES, "src/a.txt", Side.HEAD, 5, 5, cap.snippet, cap.before, cap.after, 1),
        )
        // A comment on a different file must not show up here.
        store.addComment(CommentKind.NOTE, "elsewhere", Anchor(AnchorScope.FILE, "other.txt", Side.HEAD, revision = 1))

        val request = FileDiffProducer(project, review, change, DiffSide.RIGHT to 4).process(UserDataHolderBase(), EmptyProgressIndicator())
        val disposable = Disposer.newDisposable()
        try {
            val panel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
            panel.setRequest(request)
            UIUtil.dispatchAllInvocationEvents()

            val right = editorsOf().first { it.document.text.contains("line 5 changed") }
            val left = editorsOf().first { it.document.text.contains("line 5\n") && !it.document.text.contains("changed") }
            assertEquals("head-side comment is an inlay in the right editor", 1, inlayCount(right))
            assertEquals(0, inlayCount(left))
            assertTrue("hunk markers for the 2 changes on the right", gutterIcons(right) >= 2)

            // Hunk count is recorded for the tree's "n/m hunks" display.
            assertEquals(2, store.progress("src/a.txt")?.hunkCount)

            // Mutations from elsewhere (e.g. the comments tab) re-render live.
            store.addComment(
                CommentKind.QUESTION, "why?",
                Anchor(AnchorScope.LINES, "src/a.txt", Side.HEAD, 20, 20, listOf("line 20 changed"), emptyList(), emptyList(), 1),
            )
            UIUtil.dispatchAllInvocationEvents()
            assertEquals(2, inlayCount(right))

            store.deleteComment(comment.id)
            UIUtil.dispatchAllInvocationEvents()
            assertEquals("tombstoned comment disappears from the diff", 1, inlayCount(right))
            store.restoreComment(comment.id)
            UIUtil.dispatchAllInvocationEvents()
            assertEquals(2, inlayCount(right))
        } finally {
            Disposer.dispose(disposable)
            store.close()
        }
    }

    fun testHunkViewedStateDrivesFileViewedAndInvalidatesOnChange() {
        val repo = TestRepo()
        repo.write("a.txt", body(30))
        repo.commit("base")
        repo.branch("feature")
        repo.write("a.txt", body(30).replace("line 5\n", "line 5 changed\n"))
        repo.commit("work")

        val service = ReviewService.getInstance(project)
        val store = ReviewRepository(tempDir()).create(repo.git, "T", "master", "feature")
        service.activate(store, repo.dir)
        val review = service.active!!
        val change = review.changes.single()
        assertFalse(review.isViewed(change))

        store.setFileViewed(change.path, change.fingerprint, true)
        assertTrue(review.isViewed(change))

        // New commit changes the file's diff -> fingerprint differs -> the old mark no longer counts, and says why.
        repo.write("a.txt", body(30).replace("line 5\n", "line 5 changed again\n"))
        repo.commit("more work")
        service.update()
        val after = service.active!!
        val changed = after.changes.single()
        assertFalse(change.fingerprint == changed.fingerprint)
        assertFalse(after.isViewed(changed))
        assertTrue(after.isStale(changed))
        store.close()
    }
}
