package com.mythic.localreview.ui

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.progress.DumbProgressIndicator
import com.mythic.localreview.core.GitCli
import com.mythic.localreview.format.Anchor
import com.mythic.localreview.format.Annotation
import com.mythic.localreview.format.AnnotationStatus
import com.mythic.localreview.format.ChangeSet
import com.mythic.localreview.format.ChangedFile
import com.mythic.localreview.format.CommentState
import com.mythic.localreview.format.Evaluate
import com.mythic.localreview.format.Evaluation
import com.mythic.localreview.format.FileMarkState
import com.mythic.localreview.format.Interval
import com.mythic.localreview.format.Pin
import com.mythic.localreview.format.ReviewHeader
import com.mythic.localreview.format.ReviewState
import com.mythic.localreview.format.Side
import com.mythic.localreview.format.SideLines
import com.mythic.localreview.format.Suggestion
import com.mythic.localreview.store.GitBlobs
import com.mythic.localreview.store.OpenReview
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** One hunk: the changed lines on each side (1-based, inclusive); a side with no changed lines is null (a pure addition has no old range). */
data class Hunk(val old: Interval?, val new: Interval?) {
    fun of(side: Side) = if (side == Side.OLD) old else new
}

/** The lines of a file that its change touches, as hunks, in the numbering of the blobs (ALGORITHMS 6, "showing hunk progress"). */
data class ChangedLines(val hunks: List<Hunk>) {
    val old: List<Interval> get() = hunks.mapNotNull { it.old }
    val new: List<Interval> get() = hunks.mapNotNull { it.new }
    val total get() = old.sumOf { it.length } + new.sumOf { it.length }
    fun of(side: Side) = if (side == Side.OLD) old else new
}

/** How much of one file's change is covered by region marks. Counts changed lines; nothing of it is stored. */
data class FileProgress(val changed: Int, val reviewed: Int) {
    val complete get() = changed > 0 && reviewed >= changed
}

/** What is computed from the review state against the code: comment states, mark states, region coverage and per-file progress. */
class Analysis(
    val evaluation: Evaluation,
    val progress: Map<String, FileProgress>,
    private val fileMarks: Map<String, FileMarkState>,
) {
    fun commentState(id: String): CommentState? = evaluation.annotations[id]
    fun fileMark(path: String): FileMarkState? = fileMarks[path]

    /** The lines of [path] on [side] that are reviewed now (ALGORITHMS 3), in the numbering of the current blob. */
    fun covered(path: String, side: Side): List<Interval> =
        evaluation.regions.firstOrNull { it.path == path && it.side == side }?.coverage?.reviewed.orEmpty()
}

/**
 * The review open in a project: its store, the repository it is about, the change set of its current pin, and the analysis of the state against
 * that code. [problem] is set when the code is not available on this machine; then the analysis stays null and every mark and comment state is
 * "unverified", never valid or stale (SPEC 8).
 */
class ActiveReview(
    val open: OpenReview,
    val group: String,
    val root: Path,
    val git: GitCli,
    val changeSet: ChangeSet,
    val problem: String?,
    val blobs: GitBlobs?,
) : AutoCloseable {
    val changes: List<ChangedFile> = changeSet.files.sortedBy { it.path }
    val lines: SideLines? = blobs?.let(::SideLines)
    private val changedLinesCache = ConcurrentHashMap<String, ChangedLines?>()

    @Volatile
    var analysis: Analysis? = null

    val state: ReviewState get() = open.state
    val header: ReviewHeader get() = state.header
    val pin: Pin get() = header.pin
    val verified: Boolean get() = problem == null

    fun file(path: String): ChangedFile? = changeSet.file(path)

    /** The changed file a comment's anchor refers to, if it is in the current change set. */
    fun fileOf(a: Annotation): ChangedFile? = anchorPath(a.anchor)?.let(::file)

    fun comments(includeDeleted: Boolean = false): List<Annotation> = state.annotations.filter { includeDeleted || !it.deleted }

    fun openComments(): List<Annotation> = comments().filter { it.status == AnnotationStatus.OPEN }

    /** The state of a comment against the code; null while it is being computed or when the code is not available. */
    fun commentState(a: Annotation): CommentState? {
        val anchor = a.anchor
        if (anchor is Anchor.Review) return CommentState.Current
        val p = (anchor as? Anchor.File)?.pin ?: (anchor as Anchor.Lines).pin
        if (p == pin) return CommentState.Current
        return analysis?.commentState(a.id)
    }

    fun suggestionOf(a: Annotation): Suggestion? = (commentState(a) as? CommentState.Outdated)?.suggestion

    fun fileMark(path: String): FileMarkState? = analysis?.fileMark(path)

    fun progress(path: String): FileProgress? = analysis?.progress?.get(path)

    /** Reviewed means a valid file mark, or every changed line covered by region marks. Unknown (false) until the analysis is there. */
    fun isReviewed(f: ChangedFile): Boolean = fileMark(f.path) == FileMarkState.VALID || progress(f.path)?.complete == true

    /** A file mark exists for [f] but its change has moved on since (SPEC 6.4). */
    fun isStale(f: ChangedFile): Boolean = fileMark(f.path) == FileMarkState.STALE

    /** The changed lines of [f] by the IDE's own comparison, or null if it has no line view (a file that is not text, or no code available). Cached by blob ids. */
    fun changedLines(f: ChangedFile): ChangedLines? {
        val sl = lines ?: return null
        return changedLinesCache.computeIfAbsent("${f.oldBlob}:${f.newBlob}") { Analyzer.changedLines(f, sl) }
    }

    override fun close() {
        blobs?.close()
    }

    companion object {
        fun anchorPath(a: Anchor): String? = when (a) {
            Anchor.Review -> null
            is Anchor.File -> a.path
            is Anchor.Lines -> a.path
        }
    }
}

object Analyzer {
    /** Evaluates marks and comments of [a]'s current state and the per-file progress. Slow: run it off the UI thread. */
    fun analyze(a: ActiveReview): Analysis? {
        val sl = a.lines ?: return null
        val evaluation = Evaluate.evaluate(a.state, a.changeSet, sl)
        val progress = HashMap<String, FileProgress>()
        for (f in a.changes) {
            val changed = a.changedLines(f) ?: continue
            if (changed.total == 0) continue
            val covered = changed.old.coveredBy(evaluation, f.path, Side.OLD) + changed.new.coveredBy(evaluation, f.path, Side.NEW)
            progress[f.path] = FileProgress(changed.total, covered)
        }
        return Analysis(evaluation, progress, evaluation.fileMarks.associate { it.path to it.state })
    }

    private fun List<Interval>.coveredBy(e: Evaluation, path: String, side: Side): Int {
        val reviewed = e.regions.firstOrNull { it.path == path && it.side == side }?.coverage?.reviewed.orEmpty()
        return sumOf { c -> reviewed.sumOf { r -> maxOf(0, minOf(c.end, r.end) - maxOf(c.start, r.start) + 1) } }
    }

    /**
     * The lines the change of [f] touches: all of the new side for an added file, all of the old for a deleted one, else the line fragments of the IDE's
     * comparison (whitespace-sensitive: a whitespace-only change is part of the change, ALGORITHMS 6). Null if a side is not text.
     */
    fun changedLines(f: ChangedFile, lines: SideLines): ChangedLines? {
        val old = lines.of(f, Side.OLD)
        val new = lines.of(f, Side.NEW)
        if (f.blob(Side.OLD) != null && old == null) return null
        if (f.blob(Side.NEW) != null && new == null) return null
        if (old == null && new == null) return ChangedLines(emptyList())
        if (old == null || old.isEmpty()) return ChangedLines(if (new == null || new.isEmpty()) emptyList() else listOf(Hunk(null, Interval(1, new.size))))
        if (new == null || new.isEmpty()) return ChangedLines(listOf(Hunk(Interval(1, old.size), null)))
        val fragments = ComparisonManager.getInstance().compareLines(old.joinToString("\n"), new.joinToString("\n"), ComparisonPolicy.DEFAULT, DumbProgressIndicator.INSTANCE)
        return ChangedLines(
            fragments.map { fr ->
                Hunk(
                    if (fr.endLine1 > fr.startLine1) Interval(fr.startLine1 + 1, fr.endLine1) else null,
                    if (fr.endLine2 > fr.startLine2) Interval(fr.startLine2 + 1, fr.endLine2) else null,
                )
            }.filter { it.old != null || it.new != null },
        )
    }
}
