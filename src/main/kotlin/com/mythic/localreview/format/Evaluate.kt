package com.mythic.localreview.format

/** One file of a review's change set (SPEC 8), as `git diff --raw -z -M` reports it. A side that does not exist has mode `000000` and an all-zero blob id. */
data class ChangedFile(
    val path: String,
    val oldPath: String?,
    val status: String,
    val oldMode: String,
    val newMode: String,
    val oldBlob: String,
    val newBlob: String,
) {
    /** `oldMode:newMode:oldBlob:newBlob`: a file mark is valid while this is unchanged (SPEC 6.4). */
    val changeKey get() = "$oldMode:$newMode:$oldBlob:$newBlob"

    /** The blob id of one side, or null if that side does not exist. */
    fun blob(side: Side): String? = (if (side == Side.NEW) newBlob else oldBlob).takeIf { it.isNotEmpty() && it.any { c -> c != '0' } }
}

class ChangeSet(val files: List<ChangedFile>) {
    private val byPath = files.associateBy { it.path }
    fun file(path: String): ChangedFile? = byPath[path]
}

/** Where the bytes of a git blob come from. Returns null if the blob is not available in this clone. */
fun interface BlobSource {
    fun read(blob: String): ByteArray?
}

/** The lines of each side of the changed files, derived from blob bytes by the line model (ALGORITHMS 1.1), never from an editor's document. Caches by blob id. */
class SideLines(private val blobs: BlobSource) {
    private val cache = HashMap<String, Lines?>()

    /** The lines of [side] of [file], or null if that side does not exist, is not available, or is not text (ALGORITHMS 1.3). */
    fun of(file: ChangedFile?, side: Side): Lines? {
        val blob = file?.blob(side) ?: return null
        return synchronized(cache) { cache.getOrPut(blob) { blobs.read(blob)?.let(::linesOfBlob) } }
    }
}

/** A computed position for an outdated comment: one range per range of the anchor (ALGORITHMS 4). It is shown, never applied by itself. */
data class Suggestion(val path: String, val ranges: List<SuggestedRange>)
data class SuggestedRange(val side: Side, val startLine: Int, val endLine: Int)

/** The state of a comment against the code (SPEC 8). Computed, never stored. */
sealed interface CommentState {
    data object Current : CommentState
    data object Unchanged : CommentState
    data class Outdated(val suggestion: Suggestion?) : CommentState
}

enum class FileMarkState { VALID, STALE, GONE }
data class EvaluatedFileMark(val path: String, val state: FileMarkState)

/** The reviewed and unplaceable lines of one path and side (SPEC 8, ALGORITHMS 3.2). */
data class RegionState(val path: String, val side: Side, val coverage: RegionCoverage)

data class Evaluation(
    val pin: Pin,
    val annotations: Map<String, CommentState>,
    val fileMarks: List<EvaluatedFileMark>,
    val regions: List<RegionState>,
)

object Evaluate {
    /** The state of one comment against the change set of the review's current pin (ALGORITHMS 5). */
    fun comment(a: Annotation, pin: Pin, files: ChangeSet, lines: SideLines): CommentState {
        val anchor = a.anchor
        if (anchor is Anchor.Review) return CommentState.Current
        val anchorPin = when (anchor) {
            is Anchor.File -> anchor.pin
            is Anchor.Lines -> anchor.pin
            Anchor.Review -> return CommentState.Current
        }
        if (anchorPin == pin) return CommentState.Current
        val path = if (anchor is Anchor.File) anchor.path else (anchor as Anchor.Lines).path
        val file = files.file(path) ?: return CommentState.Outdated(null)
        if (anchor is Anchor.File) return CommentState.Unchanged
        anchor as Anchor.Lines

        var unchanged = true
        val located = ArrayList<SuggestedRange?>()
        for (r in anchor.ranges) {
            val current = lines.of(file, r.side)
            val n = r.endLine - r.startLine + 1
            val same = current != null && r.endLine <= current.size && r.snapshot.lines.size == n &&
                (0 until n).all { k -> current[r.startLine - 1 + k] == r.snapshot.lines[k] }
            if (!same) unchanged = false
            val at = if (same) Interval(r.startLine, r.endLine) else current?.let { locateRange(r, it) }
            located += at?.let { SuggestedRange(r.side, it.start, it.end) }
        }
        if (unchanged) return CommentState.Unchanged
        val all = located.filterNotNull()
        return CommentState.Outdated(if (all.size == located.size) Suggestion(file.path, all) else null)
    }

    fun fileMark(m: FileMark, files: ChangeSet): FileMarkState {
        val f = files.file(m.path) ?: return FileMarkState.GONE
        return if (f.changeKey == m.changeKey) FileMarkState.VALID else FileMarkState.STALE
    }

    /** The steps of every path and side that region events mention, in fold order (ALGORITHMS 3.2). */
    fun stepsByPathAndSide(events: List<RegionEvent>): LinkedHashMap<Pair<String, Side>, MutableList<MarkStep>> {
        val groups = LinkedHashMap<Pair<String, Side>, MutableList<MarkStep>>()
        for (ev in events) for (r in ev.ranges) groups.getOrPut(ev.path to r.side) { ArrayList() } += MarkStep(ev.reviewed, r)
        return groups
    }

    fun regions(events: List<RegionEvent>, files: ChangeSet, lines: SideLines): List<RegionState> {
        val out = ArrayList<RegionState>()
        for ((key, steps) in stepsByPathAndSide(events)) {
            val (path, side) = key
            val coverage = evaluateSteps(steps, lines.of(files.file(path), side))
            if (coverage.reviewed.isNotEmpty() || coverage.stale.isNotEmpty()) out += RegionState(path, side, coverage)
        }
        out.sortWith(compareBy<RegionState, String>(Utf8Order) { it.path }.thenBy { it.side.wire })
        return out
    }

    fun evaluate(state: ReviewState, files: ChangeSet, lines: SideLines): Evaluation {
        val pin = state.header.pin
        return Evaluation(
            pin,
            state.annotations.associate { it.id to comment(it, pin, files, lines) },
            state.fileMarks.map { EvaluatedFileMark(it.path, fileMark(it, files)) },
            regions(state.regionEvents, files, lines),
        )
    }
}
