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
import com.mythic.localreview.core.TestRepo
import com.mythic.localreview.core.lines
import com.mythic.localreview.core.tempDir
import com.mythic.localreview.format.Anchor
import com.mythic.localreview.format.AnchorRange
import com.mythic.localreview.format.CommentState
import com.mythic.localreview.format.FileMarkState
import com.mythic.localreview.format.Kind
import com.mythic.localreview.format.ReviewMode
import com.mythic.localreview.format.Side
import com.mythic.localreview.format.captureSnapshot
import com.mythic.localreview.store.ReviewFactory
import com.mythic.localreview.ui.diff.FileDiffProducer

/** Renders a real review diff in the platform diff viewer and checks what our DiffExtension and the review service do with it. */
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
            ReviewService.getInstance(project).close()
            ReviewSettings.getInstance().storeRootSetting = savedRoot
        } finally {
            super.tearDown()
        }
    }

    private fun body(n: Int) = lines(*(1..n).map { "line $it" }.toTypedArray())

    private fun editorsOf(): List<Editor> = EditorFactory.getInstance().allEditors.toList()

    private fun inlayCount(e: Editor) = e.inlayModel.getBlockElementsInRange(0, e.document.textLength).size

    private fun gutterIcons(e: Editor) = e.markupModel.allHighlighters.count { it.gutterIconRenderer != null }

    /** Pumps the UI thread until [condition] holds: analysis and writes finish on other threads. */
    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!condition()) {
            UIUtil.dispatchAllInvocationEvents()
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    private fun activate(repo: TestRepo): ActiveReview {
        val service = ReviewService.getInstance(project)
        val groups = service.groups()
        val group = groups.reviewGroup(groups.create("g"))
        val open = group.create(ReviewFactory.newMeta(repo.git, "T", "master", "feature", ReviewMode.MERGE_BASE, group.deviceId, System.currentTimeMillis()))
        service.activate(open, repo.dir, "g")
        val review = service.active!!
        waitFor("the analysis") { review.analysis != null }
        return review
    }

    private fun lineComment(review: ActiveReview, path: String, line: Int): Anchor.Lines {
        val file = review.file(path)!!
        val lines = review.lines!!.of(file, Side.NEW)!!
        return Anchor.Lines(path, file.oldPath, review.pin, listOf(AnchorRange(Side.NEW, line, line, captureSnapshot(lines, line, line))))
    }

    fun testCommentsAndHunkMarkersRenderAndPersist() {
        val repo = TestRepo()
        repo.write("src/a.txt", body(30))
        repo.commit("base")
        repo.branch("feature")
        repo.write("src/a.txt", body(30).replace("line 5\n", "line 5 changed\n").replace("line 20\n", "line 20 changed\n"))
        repo.commit("work")

        val service = ReviewService.getInstance(project)
        val review = activate(repo)
        val change = review.changes.single()

        val first = service.addComment(Kind.CHANGE_REQUEST, "Please revert this", lineComment(review, "src/a.txt", 5), RuleInput(false, null))!!
        // A comment on a different file must not show up here.
        service.addComment(Kind.COMMENT, "elsewhere", Anchor.File("other.txt", null, review.pin), RuleInput(false, null))

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

            // Mutations from elsewhere (e.g. the comments tab) re-render live.
            service.addComment(Kind.QUESTION, "why?", lineComment(review, "src/a.txt", 20), RuleInput(false, null))
            waitFor("the second inlay") { UIUtil.dispatchAllInvocationEvents(); inlayCount(right) == 2 }

            val comment = review.state.annotation(first)!!
            service.deleteComment(comment)
            waitFor("the tombstoned comment to leave the diff") { UIUtil.dispatchAllInvocationEvents(); inlayCount(right) == 1 }
            service.restoreComment(comment)
            waitFor("the restored comment") { UIUtil.dispatchAllInvocationEvents(); inlayCount(right) == 2 }

            // A reply is part of the card.
            assertTrue(service.addReply(review.state.annotation(first)!!, "because"))
            waitFor("the reply to be saved") { review.state.annotation(first)!!.replies.size == 1 }
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun headDocumentFile(request: com.intellij.diff.requests.DiffRequest): com.intellij.openapi.vfs.VirtualFile? {
        val right = (request as com.intellij.diff.requests.ContentDiffRequest).contents[1] as com.intellij.diff.contents.DocumentContent
        return com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(right.document)
    }

    fun testHeadSideIsTheRealProjectFileWhenItHoldsTheReviewedCodeSoGoToDefinitionCanWork() {
        val repo = TestRepo()
        repo.write("src/a.txt", body(10))
        repo.commit("base")
        repo.branch("feature")
        repo.write("src/a.txt", body(10).replace("line 5\n", "line 5 changed\n"))
        repo.commit("work") // feature is checked out: the working tree holds the head

        val review = activate(repo)
        val request = FileDiffProducer(project, review, review.changes.single(), null).process(UserDataHolderBase(), EmptyProgressIndicator())
        val file = headDocumentFile(request)!!
        assertFalse("a real file, not a copy in memory: $file", file is com.intellij.testFramework.LightVirtualFile)
        assertEquals(repo.dir.resolve("src/a.txt").toString().replace('\\', '/'), file.path)
        // The base side is never on disk.
        val left = (request as com.intellij.diff.requests.ContentDiffRequest).contents[0] as com.intellij.diff.contents.DocumentContent
        assertTrue(com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(left.document).let { it == null || it is com.intellij.testFramework.LightVirtualFile })
    }

    fun testAFileThatDiffersFromTheReviewedCodeIsNeverPassedOffAsIt() {
        val repo = TestRepo()
        repo.write("src/a.txt", body(10))
        repo.commit("base")
        repo.branch("feature")
        repo.write("src/a.txt", body(10).replace("line 5\n", "line 5 changed\n"))
        repo.commit("work")
        // Uncommitted edit in the working tree: the file is not the reviewed head any more.
        repo.write("src/a.txt", body(10).replace("line 5\n", "line 5 edited later\n"))

        val review = activate(repo)
        val request = FileDiffProducer(project, review, review.changes.single(), null).process(UserDataHolderBase(), EmptyProgressIndicator())
        val file = headDocumentFile(request)
        assertTrue("a copy, not the project file: $file", file == null || file is com.intellij.testFramework.LightVirtualFile)
        val right = (request as com.intellij.diff.requests.ContentDiffRequest).contents[1] as com.intellij.diff.contents.DocumentContent
        assertTrue("shows the reviewed text", right.document.text.contains("line 5 changed") && !right.document.text.contains("edited later"))

        // The same when the branch checked out is another one: the file on disk is the base version.
        repo.git.run("checkout", "-q", "--", "src/a.txt")
        repo.checkout("master")
        val onOther = FileDiffProducer(project, review, review.changes.single(), null).process(UserDataHolderBase(), EmptyProgressIndicator())
        val f2 = headDocumentFile(onOther)
        assertTrue(f2 == null || f2 is com.intellij.testFramework.LightVirtualFile)
    }

    fun testViewerIsFedTheLinesOfTheFormatNotTheIdesOwnDecoding() {
        val repo = TestRepo()
        // A byte order mark and CRLF line ends: the IDE would drop the mark and normalise the ends; the format's line model does the same, so the numbers agree.
        repo.git.run("config", "core.autocrlf", "false")
        java.nio.file.Files.write(repo.dir.resolve("a.txt"), "\uFEFFone\r\ntwo\r\nthree\r\n".toByteArray())
        repo.commit("base")
        repo.branch("feature")
        java.nio.file.Files.write(repo.dir.resolve("a.txt"), "\uFEFFone\r\ntwo changed\r\nthree\r\n".toByteArray())
        repo.commit("work")

        val review = activate(repo)
        val change = review.changes.single()
        assertEquals(listOf("one", "two changed", "three"), review.lines!!.of(change, Side.NEW)!!.toList())

        val request = FileDiffProducer(project, review, change, null).process(UserDataHolderBase(), EmptyProgressIndicator())
        val disposable = Disposer.newDisposable()
        try {
            DiffManager.getInstance().createRequestPanel(project, disposable, null).setRequest(request)
            UIUtil.dispatchAllInvocationEvents()
            val right = editorsOf().first { it.document.text.contains("two changed") }
            assertEquals("no byte order mark, LF ends, as the format counts lines", "one\ntwo changed\nthree\n", right.document.text)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    fun testFileMarkAndHunkMarksAgreeAndTheMarkGoesStaleOnANewCommit() {
        val repo = TestRepo()
        repo.write("a.txt", body(30))
        repo.commit("base")
        repo.branch("feature")
        repo.write("a.txt", body(30).replace("line 5\n", "line 5 changed\n").replace("line 25\n", "line 25 changed\n"))
        repo.commit("work")

        val service = ReviewService.getInstance(project)
        val review = activate(repo)
        val change = review.changes.single()
        val hunks = review.changedLines(change)!!.hunks
        assertEquals("two separate edits are two hunks", 2, hunks.size)
        assertFalse(review.isReviewed(change))

        // Marking one hunk reviews just its lines; the file is not reviewed yet.
        assertTrue(service.setHunkReviewed(change, hunks, hunks[0], true))
        waitFor("the first hunk") { review.progress("a.txt")?.reviewed == 2 }
        assertTrue(service.isHunkReviewed(change, hunks[0]))
        assertFalse(service.isHunkReviewed(change, hunks[1]))
        assertFalse(review.isReviewed(change))
        assertTrue("no file mark for half a file", review.state.fileMarks.isEmpty())

        // Marking the last one completes the file, and the file mark is written too.
        assertTrue(service.setHunkReviewed(change, hunks, hunks[1], true))
        waitFor("the file to count as reviewed") { review.isReviewed(change) }
        assertEquals(listOf("a.txt"), review.state.fileMarks.map { it.path })
        assertEquals(FileMarkState.VALID, review.fileMark("a.txt"))

        // Un-marking a hunk of a file that has its mark clears the mark but keeps the other hunk's region.
        assertTrue(service.setHunkReviewed(change, hunks, hunks[0], false))
        waitFor("the file to stop counting as reviewed") { !review.isReviewed(change) && review.fileMark("a.txt") == null }
        assertTrue(review.state.fileMarks.isEmpty())
        assertFalse(service.isHunkReviewed(change, hunks[0]))
        assertTrue(service.isHunkReviewed(change, hunks[1]))

        // The tree's checkbox marks the whole file, and a new commit that changes it makes the mark stale rather than silently valid.
        assertTrue(service.setFilesReviewed(listOf(change), true))
        waitFor("the file reviewed again") { review.isReviewed(change) }
        repo.write("a.txt", body(30).replace("line 5\n", "line 5 changed again\n").replace("line 25\n", "line 25 changed\n"))
        repo.commit("more work")
        val result = service.retarget()
        assertTrue(result.changed)
        val after = service.active!!
        waitFor("the analysis of the new commit") { after.analysis != null }
        val changed = after.changes.single()
        assertTrue("its change key moved", changed.changeKey != change.changeKey)
        assertEquals(FileMarkState.STALE, after.fileMark("a.txt"))
        assertFalse(after.isReviewed(changed))
        assertTrue(after.isStale(changed))
        // The untouched hunk is still reviewed (its lines moved nowhere), the edited one is not.
        val progress = after.progress("a.txt")!!
        assertTrue("some of the file is still covered: ${progress}", progress.reviewed in 1 until progress.changed)
    }

    fun testAnOutdatedCommentGetsASuggestionAndAcceptingItMovesIt() {
        val repo = TestRepo()
        repo.write("a.txt", body(30))
        repo.commit("base")
        repo.branch("feature")
        repo.write("a.txt", body(30).replace("line 10\n", "line 10 changed\n"))
        repo.commit("work")

        val service = ReviewService.getInstance(project)
        val review = activate(repo)
        val id = service.addComment(Kind.CHANGE_REQUEST, "check this", lineComment(review, "a.txt", 10), RuleInput(true, null))!!

        // Two lines are added above it: the code moved to line 12, and the comment's numbers are now wrong.
        repo.write("a.txt", "new 1\nnew 2\n" + body(30).replace("line 10\n", "line 10 changed\n"))
        repo.commit("more work")
        val result = service.retarget()
        assertEquals(1, result.outdated)
        assertEquals(1, result.withSuggestion)
        val after = service.active!!
        waitFor("the analysis of the new commit") { after.analysis != null }
        val c = after.state.annotation(id)!!
        val state = after.commentState(c)
        assertTrue("outdated with a suggestion: $state", state is CommentState.Outdated && state.suggestion != null)
        assertEquals(12, (after.suggestionOf(c)!!.ranges.single()).startLine)
        // Nothing moved by itself.
        assertEquals(10, (c.anchor as Anchor.Lines).ranges.single().startLine)

        assertTrue(service.acceptSuggestion(c))
        waitFor("the comment to be current") { after.state.annotation(id)!!.let { after.commentState(it) == CommentState.Current } }
        val moved = after.state.annotation(id)!!.anchor as Anchor.Lines
        assertEquals(12, moved.ranges.single().startLine)
        assertEquals(listOf("line 10 changed"), moved.ranges.single().snapshot.lines)
        assertEquals(after.pin, moved.pin)
        // The tag the user set and the text survived the move.
        assertTrue(after.state.annotation(id)!!.isRuleCandidate)
        assertEquals("check this", after.state.annotation(id)!!.body)
    }

}
