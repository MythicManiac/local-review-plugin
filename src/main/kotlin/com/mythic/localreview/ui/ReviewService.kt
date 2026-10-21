package com.mythic.localreview.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import com.mythic.localreview.core.GitCli
import com.mythic.localreview.core.GitException
import com.mythic.localreview.core.SyncResult
import com.mythic.localreview.format.Anchor
import com.mythic.localreview.format.AnchorRange
import com.mythic.localreview.format.Annotation
import com.mythic.localreview.format.AnnotationStatus
import com.mythic.localreview.format.CommentState
import com.mythic.localreview.format.Decision
import com.mythic.localreview.format.Event
import com.mythic.localreview.format.Field
import com.mythic.localreview.format.Interval
import com.mythic.localreview.format.Kind
import com.mythic.localreview.format.MarkRange
import com.mythic.localreview.format.Pin
import com.mythic.localreview.format.RULE_CANDIDATE_TAG
import com.mythic.localreview.format.ReanchorMethod
import com.mythic.localreview.format.ReviewMode
import com.mythic.localreview.format.ReviewStatus
import com.mythic.localreview.format.Side
import com.mythic.localreview.format.Suggestion
import com.mythic.localreview.format.Ulid
import com.mythic.localreview.format.captureSnapshot
import com.mythic.localreview.store.ChangeSets
import com.mythic.localreview.store.GitBlobs
import com.mythic.localreview.store.GroupInfo
import com.mythic.localreview.store.LegacyImporter
import com.mythic.localreview.store.LocalRepo
import com.mythic.localreview.store.LogBusyException
import com.mythic.localreview.store.OpenReview
import com.mythic.localreview.format.label
import com.mythic.localreview.store.Pins
import com.mythic.localreview.store.ReviewFactory
import com.mythic.localreview.store.ReviewGroups
import com.mythic.localreview.store.ReviewSummary
import git4idea.repo.GitRepositoryManager
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

fun interface ReviewListener {
    fun changed(kind: Kind)
    enum class Kind { ACTIVE, DATA }
}

/** What the user chose for the "recurring pattern" part of a comment: whether it is a rule candidate, and the rule text if one was written. */
data class RuleInput(val candidate: Boolean, val text: String?)

/** The outcome of "Update review": whether the review moved to new commits, and what that did to the comments. */
data class RetargetResult(val changed: Boolean, val outdated: Int, val withSuggestion: Int)

@Service(Service.Level.PROJECT)
class ReviewService(private val project: Project) : Disposable {
    @Volatile
    var active: ActiveReview? = null
        private set

    /** Cached info of the active review's group (read off the EDT; the header renders from this). */
    @Volatile
    var groupInfo: GroupInfo? = null
        private set

    private val listeners = CopyOnWriteArrayList<ReviewListener>()
    private val generation = AtomicInteger()
    private val localRepos = java.util.concurrent.ConcurrentHashMap<Path, LocalRepo>()
    private val notified = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val poller: ScheduledFuture<*>

    /** The stamp of the active review's logs as of our own last read or write, to tell another IDE's appends from ours. */
    @Volatile
    private var seenStamp = 0L

    init {
        GroupSyncService.getInstance().addListener(this) { group, result ->
            val a = active
            if (a != null && a.group == group) {
                if (result is SyncResult.Ok && result.pulled) {
                    ApplicationManager.getApplication().executeOnPooledThread { reloadActive() }
                } else {
                    fire(ReviewListener.Kind.DATA) // sync status line changed
                }
            }
        }
        // Another IDE of this machine (SPEC 9.4), or a sync, may have appended to a log: notice it without needing a file watcher.
        poller = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            val a = active
            if (a != null && !project.isDisposed && a.open.stamp() != seenStamp) reloadActive()
        }, 3, 3, TimeUnit.SECONDS)
    }

    fun addListener(parent: Disposable, l: ReviewListener) {
        listeners += l
        Disposer.register(parent) { listeners -= l }
    }

    private fun fire(kind: ReviewListener.Kind) {
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) listeners.forEach { it.changed(kind) } }, ModalityState.any())
    }

    // ---- environment ----------------------------------------------------------------------------------------

    fun groups(): ReviewGroups = GroupSyncService.getInstance().groups()

    fun gitExe(): String = GroupSyncService.getInstance().gitExe()

    fun gitRoots(): List<Path> =
        GitRepositoryManager.getInstance(project).repositories.map { it.root.toNioPath() }

    fun gitFor(root: Path) = GitCli(root, gitExe())

    /** What the clone at [root] can say about its identity, cached for the session. */
    fun localRepoOf(root: Path): LocalRepo = localRepos.getOrPut(root) { LocalRepo.of(gitFor(root)) }

    /** The identity a review made from the clone at [root] would carry, to remember which group this repository's reviews go to. */
    fun repoRefOf(root: Path): com.mythic.localreview.format.RepoRef =
        localRepoOf(root).let { com.mythic.localreview.format.RepoRef(it.displayName, it.originUrl, it.rootCommits.minOrNull()) }

    // ---- discovery ------------------------------------------------------------------------------------------

    /**
     * Opens every group (completing its repository files) and imports old 0.1.0 reviews whose repository is open in this project (SPEC 2.1; CONFORMANCE,
     * "Import"). Cheap when nothing changed. Call from a background thread.
     */
    fun prepareGroups() {
        val groups = groups()
        val clones = gitRoots().map { gitFor(it) }
        for (info in groups.list()) {
            val group = groups.reviewGroup(info)
            val prepared = group.prepare()
            prepared.refused?.let { warnOnce("refused:${info.name}", "Review group '${info.name}': $it") }
            if (prepared.notHonoured.isNotEmpty()) {
                warnOnce("rules:${info.name}", "Review group '${info.name}': git does not honour ${prepared.notHonoured.joinToString(", ")}; something outside the group's own files overrides it.")
            }
            if (prepared.refused != null) continue
            val report = LegacyImporter(group, groups.root.resolve(".state")).run(clones)
            if (report.imported.isNotEmpty()) {
                notify("Imported ${report.imported.size} review(s) from plugin 0.1.0 into group '${info.name}': ${report.imported.joinToString { it.title }}.")
            }
            for (n in report.notYet) {
                warnOnce("notyet:${info.name}:${n.folder}", "A review from plugin 0.1.0 ('${n.title}', group '${info.name}') is not imported yet: ${n.reason}.")
            }
            for (p in report.problems) warnOnce("import:${info.name}:$p", "Importing from plugin 0.1.0 (group '${info.name}'): $p")
        }
    }

    private fun warnOnce(key: String, message: String) {
        if (notified.add(key)) notify(message, NotificationType.WARNING)
    }

    /** Reviews of this project's repositories across all groups, newest first. Archived reviews are left out unless [includeArchived]. */
    fun listReviews(includeArchived: Boolean = false): List<ReviewSummary> {
        prepareGroups()
        val repos = gitRoots().map { localRepoOf(it) }
        return groups().allReviews()
            .filter { s -> repos.any { it.matches(s.meta.repo) } && (includeArchived || s.status != ReviewStatus.ARCHIVED) }
            .sortedByDescending { it.meta.createdAt }
    }

    /** Finds the project git root a review belongs to (SPEC 4.1). */
    private fun rootFor(meta: com.mythic.localreview.format.ReviewMeta): Path? = gitRoots().firstOrNull { localRepoOf(it).matches(meta.repo) }

    // ---- lifecycle (call from a background thread) --------------------------------------------------------------

    fun open(summary: ReviewSummary) {
        val groups = groups()
        val info = groups.group(summary.group) ?: error("Review group '${summary.group}' no longer exists.")
        val open = groups.reviewGroup(info).open(summary.id) ?: error("The review '${summary.title}' can no longer be read.")
        val root = rootFor(open.meta) ?: error("This review belongs to repository '${open.meta.repo.displayName}', which is not open in this project.")
        activate(open, root, summary.group)
    }

    fun create(root: Path, title: String, baseRef: String, headRef: String, mode: ReviewMode, groupName: String) {
        val groups = groups()
        val info = groups.group(groupName) ?: error("Review group '$groupName' does not exist.")
        val group = groups.reviewGroup(info)
        val meta = ReviewFactory.newMeta(gitFor(root), title, baseRef, headRef, mode, group.deviceId, System.currentTimeMillis())
        val open = group.create(meta)
        groups.rememberGroup(repoRefOf(root), groupName)
        activate(open, root, groupName)
    }

    /**
     * "Update review": resolves the review's refs again and, if they moved, writes `reviewRetargeted`. Comments are not touched: each one's state is
     * computed against the new commits, and an outdated one gets a suggestion the user may accept (SPEC 8).
     */
    fun retarget(): RetargetResult {
        val a = active ?: error("No active review")
        val m = a.open.meta
        val pin = Pins.resolve(a.git, m.baseRef, m.headRef, m.mode)
        if (pin == a.pin) return RetargetResult(false, 0, 0)
        a.open.append(Event.ReviewRetargeted(pin), "Update review to ${pin.headSha.take(8)}")
        activate(a.open, a.root, a.group)
        val now = active ?: return RetargetResult(true, 0, 0)
        val analysis = Analyzer.analyze(now)
        now.analysis = analysis
        fire(ReviewListener.Kind.DATA)
        val outdated = analysis?.evaluation?.annotations?.filter { (id, st) -> st is CommentState.Outdated && now.state.annotation(id)?.deleted == false }?.values.orEmpty()
        return RetargetResult(true, outdated.size, outdated.count { (it as CommentState.Outdated).suggestion != null })
    }

    /** Re-reads the active review from disk after a sync pulled changes from another device or another IDE appended to a log. */
    fun reloadActive() {
        val a = active ?: return
        val before = a.pin
        try {
            a.open.refresh()
        } catch (e: Exception) {
            notify("Could not reload review after sync: ${e.message}", NotificationType.WARNING)
            return
        }
        seenStamp = a.open.stamp()
        if (a.pin != before) activate(a.open, a.root, a.group)
        else {
            reanalyze()
            fire(ReviewListener.Kind.DATA)
        }
    }

    /** Re-reads the active group's remote/auto-sync settings (after they were edited) and refreshes the views. */
    fun refreshGroup() {
        val a = active ?: return
        groupInfo = groups().group(a.group)
        fire(ReviewListener.Kind.DATA)
    }

    /** Computes the change set of the review's current pin, publishes it, and starts the analysis in the background. */
    internal fun activate(open: OpenReview, root: Path, group: String) {
        val git = gitFor(root)
        val pin = open.state.header.pin
        var problem: String? = null
        val changeSet = try {
            ChangeSets.read(git, pin)
        } catch (e: GitException) {
            problem = "Commits ${pin.baseSha.take(8)}..${pin.headSha.take(8)} are not in this repository " +
                "(review synced from another device?). Fetch the branch, then run Update Review. Marks and comments are shown as unverified until then."
            com.mythic.localreview.format.ChangeSet(emptyList())
        }
        val next = ActiveReview(open, group, root, git, changeSet, problem, if (problem == null) GitBlobs(root, gitExe()) else null)
        val previous = active
        active = next
        previous?.close()
        groupInfo = groups().group(group)
        seenStamp = open.stamp()
        remember(group to open.id)
        GroupSyncService.getInstance().markInUse(group)
        next.state.report.takeIf { it.lostData }?.let {
            notify("Some events of this review could not be used (${it.unknown} unknown, ${it.orphans} orphaned, ${it.invalidEnvelopes + it.tornTails + it.corruptLines.size} damaged lines). They are left in the files untouched.", NotificationType.WARNING)
        }
        closeDiffTab()
        fire(ReviewListener.Kind.ACTIVE)
        reanalyze()
    }

    /** Recomputes comment states, mark states and file progress off the UI thread; the newest request wins. */
    private fun reanalyze() {
        val a = active ?: return
        if (!a.verified) {
            a.analysis = null
            return
        }
        val mine = generation.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = Analyzer.analyze(a)
                if (generation.get() == mine && active === a) {
                    a.analysis = result
                    fire(ReviewListener.Kind.DATA)
                }
            } catch (e: Exception) {
                com.intellij.openapi.diagnostic.Logger.getInstance(ReviewService::class.java).warn("Analysis of the review failed", e)
            }
        }
    }

    /** The open diff tab shows an older revision/review; close it rather than show stale code with hidden comments. */
    private fun closeDiffTab() {
        ApplicationManager.getApplication().invokeLater({ com.mythic.localreview.ui.diff.ReviewDiff.close(project) }, ModalityState.any())
    }

    private fun projectKey() = project.basePath ?: project.name

    /** Persisted beside the reviews (atomic write), so it survives IDE crashes and forced kills. */
    private fun remember(review: Pair<String, String>?) {
        try {
            groups().rememberLastOpened(projectKey(), review)
        } catch (e: IOException) {
            notify("Could not remember the open review: ${e.message}", NotificationType.WARNING)
        }
    }

    fun close() {
        active?.close()
        active = null
        groupInfo = null
        remember(null)
        closeDiffTab()
        fire(ReviewListener.Kind.ACTIVE)
    }

    /** Reopens the review that was active when the project was last used (background). */
    fun restoreLast() {
        val (group, id) = groups().lastOpened(projectKey()) ?: return
        val summary = listReviews(includeArchived = true).firstOrNull { it.group == group && it.id == id } ?: return
        try {
            open(summary)
        } catch (e: Exception) {
            notify("Could not reopen review '${summary.title}': ${e.message}", NotificationType.WARNING)
        }
    }

    /** Asks all views to re-render from current state (e.g. after a UI-only filter changed). */
    fun refreshUi() = fire(ReviewListener.Kind.DATA)

    // ---- writing: every change is one or more events (SPEC 6) --------------------------------------------------------------

    /**
     * Appends [events] to this device's log of the active review. Returns whether they were saved; when not, the user has been told why (the lock
     * of another instance, a disk error) and nothing was written, so the caller keeps what the user typed.
     */
    fun write(events: List<Event>, message: String): Boolean {
        val a = active ?: return false
        if (events.isEmpty()) return true
        try {
            a.open.append(events, message)
        } catch (e: LogBusyException) {
            notify("Not saved: ${e.message}. Try again in a moment.", NotificationType.WARNING)
            return false
        } catch (e: IOException) {
            notify("Could not save to the review folder: ${e.message}", NotificationType.ERROR)
            return false
        }
        seenStamp = a.open.stamp()
        fire(ReviewListener.Kind.DATA)
        reanalyze()
        return true
    }

    /** The tags of [a] with the rule-candidate tag set or cleared, every other tag kept (a tag this plugin does not know must survive). */
    private fun tagsFor(a: Annotation?, candidate: Boolean): List<String> {
        val base = a?.tags.orEmpty().filter { it != RULE_CANDIDATE_TAG }
        return if (candidate) base + RULE_CANDIDATE_TAG else base
    }

    fun addComment(kind: Kind, body: String, anchor: Anchor, rule: RuleInput): String? {
        val id = Ulid.next()
        val event = Event.AnnotationCreated(id, kind, tagsFor(null, rule.candidate), body, anchor, rule.text?.takeIf { rule.candidate })
        return id.takeIf { write(listOf(event), "Add comment") }
    }

    /** Writes only what changed (an edit event carries just the fields the user touched). */
    fun editComment(a: Annotation, kind: Kind, body: String, rule: RuleInput): Boolean {
        val tags = tagsFor(a, rule.candidate).takeIf { it.toSet() != a.tags.toSet() }
        val ruleText = if (rule.candidate && rule.text != a.ruleText) Field(rule.text) else null
        val event = Event.AnnotationEdited(a.id, body.takeIf { it != a.body }, kind.takeIf { it != a.kind }, tags, ruleText)
        if (event.body == null && event.kind == null && event.tags == null && event.ruleText == null) return true
        return write(listOf(event), "Edit comment")
    }

    fun setStatus(a: Annotation, status: AnnotationStatus, note: String? = null) =
        write(listOf(Event.AnnotationStatusChanged(a.id, status, note)), "${status.label} comment")

    fun deleteComment(a: Annotation) = write(listOf(Event.AnnotationDeleted(a.id)), "Delete comment")
    fun restoreComment(a: Annotation) = write(listOf(Event.AnnotationRestored(a.id)), "Restore comment")
    fun addReply(a: Annotation, body: String) = write(listOf(Event.ReplyAdded(a.id, Ulid.next(), body)), "Reply to comment")
    fun editReply(a: Annotation, replyId: String, body: String) = write(listOf(Event.ReplyEdited(a.id, replyId, body)), "Edit reply")
    fun deleteReply(a: Annotation, replyId: String) = write(listOf(Event.ReplyDeleted(a.id, replyId)), "Delete reply")
    fun restoreReply(a: Annotation, replyId: String) = write(listOf(Event.ReplyRestored(a.id, replyId)), "Restore reply")
    fun setReviewStatus(status: ReviewStatus) = write(listOf(Event.ReviewStatusChanged(status)), "Review ${status.label.lowercase()}")
    fun renameReview(title: String) = write(listOf(Event.ReviewRenamed(title)), "Rename review")

    /** Records what became of rule candidates: the rules were written to [targetPath] (`applied`), or the reviewer declined them (`dismissed`). */
    fun decideRules(annotations: List<Annotation>, targetPath: String, decision: Decision) =
        write(listOf(Event.RuleDecided(annotations.map { it.id }, targetPath, decision)), "Rules ${decision.wire}")

    /**
     * Moves an outdated comment to the lines [suggestion] found (SPEC 8): a new anchor for the current pin with fresh snapshots, written as
     * `annotationReanchored` with method `suggestion`. Never done without the user asking.
     */
    fun acceptSuggestion(a: Annotation): Boolean {
        val act = active ?: return false
        val s = act.suggestionOf(a) ?: return false
        val file = act.file(s.path) ?: return false
        val anchor = anchorFor(act, file.path, file.oldPath, s) ?: return false
        return write(listOf(Event.AnnotationReanchored(a.id, anchor, ReanchorMethod.SUGGESTION)), "Move comment to its new place")
    }

    private fun anchorFor(a: ActiveReview, path: String, oldPath: String?, s: Suggestion): Anchor.Lines? {
        val file = a.file(path) ?: return null
        val ranges = s.ranges.map { r ->
            val lines = a.lines?.of(file, r.side) ?: return null
            AnchorRange(r.side, r.startLine, r.endLine, captureSnapshot(lines, r.startLine, r.endLine))
        }
        return Anchor.Lines(path, oldPath, a.pin, ranges)
    }

    // ---- marks ----------------------------------------------------------------------------------------------------------

    private fun markRange(a: ActiveReview, f: com.mythic.localreview.format.ChangedFile, side: Side, i: Interval): MarkRange? {
        val lines = a.lines?.of(f, side) ?: return null
        val blob = f.blob(side) ?: return null
        if (i.end > lines.size) return null
        return MarkRange(side, i.start, i.end, blob, lines.hashRange(i.start - 1, i.end))
    }

    /** The region event for one hunk: a range per side that has changed lines (ALGORITHMS 6, "marking a hunk reviewed"). */
    fun hunkEvent(f: com.mythic.localreview.format.ChangedFile, h: Hunk, reviewed: Boolean): Event? {
        val a = active ?: return null
        val ranges = listOfNotNull(h.old?.let { markRange(a, f, Side.OLD, it) }, h.new?.let { markRange(a, f, Side.NEW, it) })
        if (ranges.isEmpty()) return null
        return if (reviewed) Event.RegionReviewed(f.path, ranges) else Event.RegionUnreviewed(f.path, ranges)
    }

    private fun covered(a: ActiveReview, path: String, side: Side, i: Interval): Boolean =
        a.analysis?.covered(path, side).orEmpty().any { it.start <= i.start && it.end >= i.end }

    /** Whether every changed line of [h] is reviewed: its file mark is valid, or its lines are in the covered regions. */
    fun isHunkReviewed(f: com.mythic.localreview.format.ChangedFile, h: Hunk): Boolean {
        val a = active ?: return false
        if (a.fileMark(f.path) == com.mythic.localreview.format.FileMarkState.VALID) return true
        return listOfNotNull(h.old?.let { Side.OLD to it }, h.new?.let { Side.NEW to it }).all { (side, i) -> covered(a, f.path, side, i) }
    }

    /**
     * Marks [file] reviewed or not as one action: its file mark, and a region mark for every hunk, so the diff and the tree agree. Hunks come from the
     * IDE's comparison, so that what the diff viewer shows is what is marked.
     */
    fun setFilesReviewed(files: List<com.mythic.localreview.format.ChangedFile>, reviewed: Boolean): Boolean {
        val a = active ?: return false
        val events = ArrayList<Event>()
        for (f in files) {
            val hunks = a.changedLines(f)?.hunks.orEmpty()
            if (reviewed) {
                events += Event.FileReviewed(f.path, f.changeKey)
                hunks.filterNot { isCoveredByRegions(a, f, it) }.mapNotNull { hunkEvent(f, it, true) }.let { events += it }
            } else {
                if (a.state.fileMarks.any { it.path == f.path }) events += Event.FileUnreviewed(f.path)
                hunks.filter { h -> listOfNotNull(h.old?.let { Side.OLD to it }, h.new?.let { Side.NEW to it }).any { (s, i) -> covered(a, f.path, s, i) } }
                    .mapNotNull { hunkEvent(f, it, false) }.let { events += it }
            }
        }
        return write(events, if (reviewed) "Mark ${files.size} file(s) reviewed" else "Mark ${files.size} file(s) not reviewed")
    }

    /**
     * Marks one hunk of [f] reviewed or not. [hunks] are all the hunks of the file in the viewer. When this completes the file, the file mark is
     * written too; un-marking a hunk of a file that is only marked as a whole first turns that mark into region marks of the other hunks.
     */
    fun setHunkReviewed(f: com.mythic.localreview.format.ChangedFile, hunks: List<Hunk>, target: Hunk, reviewed: Boolean): Boolean {
        val a = active ?: return false
        val events = ArrayList<Event>()
        val wholeFileMarked = a.fileMark(f.path) == com.mythic.localreview.format.FileMarkState.VALID
        if (reviewed) {
            hunkEvent(f, target, true)?.let { events += it }
            val others = hunks.filter { it != target }
            if (!wholeFileMarked && others.all { isHunkReviewed(f, it) }) events += Event.FileReviewed(f.path, f.changeKey)
        } else {
            if (wholeFileMarked) {
                // The file mark said "all of it"; keep that fact for the other hunks as region marks.
                hunks.filter { it != target && !isCoveredByRegions(a, f, it) }.mapNotNull { hunkEvent(f, it, true) }.let { events += it }
            }
            hunkEvent(f, target, false)?.let { events += it }
            if (a.state.fileMarks.any { it.path == f.path }) events += Event.FileUnreviewed(f.path)
        }
        return write(events, if (reviewed) "Mark hunk reviewed" else "Mark hunk not reviewed")
    }

    private fun isCoveredByRegions(a: ActiveReview, f: com.mythic.localreview.format.ChangedFile, h: Hunk) =
        listOfNotNull(h.old?.let { Side.OLD to it }, h.new?.let { Side.NEW to it }).all { (s, i) -> covered(a, f.path, s, i) }

    // ---- misc ----------------------------------------------------------------------------------------------------------------

    fun notify(message: String, type: NotificationType = NotificationType.INFORMATION, vararg actions: Pair<String, () -> Unit>) {
        val n = NotificationGroupManager.getInstance().getNotificationGroup("Local Diff Review").createNotification(message, type)
        for ((label, run) in actions) {
            n.addAction(com.intellij.notification.NotificationAction.createSimpleExpiring(label) { run() })
        }
        n.notify(project)
    }

    override fun dispose() {
        poller.cancel(false)
        active?.close()
    }

    companion object {
        fun getInstance(project: Project): ReviewService = project.getService(ReviewService::class.java)
    }
}
