package com.mythic.localreview.format

data class Reply(val id: String, val body: String, val deleted: Boolean, val createdAt: Long, val createdBy: String, val updatedAt: Long)

data class RuleDecision(val decision: Decision, val targetPath: String)

/** A comment after the fold (SPEC 7). The state of its anchor against the code is not here: it is computed (SPEC 8). */
data class Annotation(
    val id: String,
    val kind: Kind,
    val tags: List<String>,
    val body: String,
    val ruleText: String?,
    val status: AnnotationStatus,
    val statusNote: String?,
    val deleted: Boolean,
    val createdAt: Long,
    val createdBy: String,
    val updatedAt: Long,
    val anchor: Anchor,
    val ruleDecision: RuleDecision?,
    val replies: List<Reply>,
) {
    val isRuleCandidate get() = RULE_CANDIDATE_TAG in tags
}

/** The review's header after events: `review.json` with title, status and pin superseded (SPEC 7). */
data class ReviewHeader(
    val id: String,
    val title: String,
    val status: ReviewStatus,
    val createdAt: Long,
    val createdBy: String,
    val repo: RepoRef,
    val baseRef: String,
    val headRef: String,
    val mode: ReviewMode,
    val pin: Pin,
)

data class FileMark(val path: String, val changeKey: String)

/** A `regionReviewed` or `regionUnreviewed` event in fold order, evaluated later against the code (ALGORITHMS 3). */
data class RegionEvent(val id: String, val ts: Long, val dev: String, val seq: Long, val reviewed: Boolean, val path: String, val ranges: List<MarkRange>)

/** The state of a review: a pure function of its `review.json` and the events of all its logs (SPEC 7). */
data class ReviewState(
    val header: ReviewHeader,
    val annotations: List<Annotation>,
    val fileMarks: List<FileMark>,
    val regionEvents: List<RegionEvent>,
    val report: ReadReport,
) {
    private val byId by lazy(LazyThreadSafetyMode.PUBLICATION) { annotations.associateBy { it.id } }
    fun annotation(id: String): Annotation? = byId[id]
}

object Fold {
    /** Folds the logs of one review. Never throws on log content. */
    fun fold(meta: ReviewMeta, logs: List<LogBytes>): ReviewState {
        val report = ReportBuilder()
        val envelopes = logs.flatMap { Logs.parse(it.name, it.bytes, report) }
        return foldEnvelopes(meta, envelopes, report)
    }

    private val foldOrder: Comparator<RawEnvelope> =
        compareBy<RawEnvelope> { it.ts }.thenComparing({ it.dev }, Utf8Order).thenBy { it.seq }.thenComparing({ it.id }, Utf8Order)

    private class MutableReply(val id: String, var body: String, var deleted: Boolean, val createdAt: Long, val createdBy: String, var updatedAt: Long)

    private class MutableAnnotation(
        val id: String, var kind: Kind, var tags: List<String>, var body: String, var ruleText: String?, val createdAt: Long, val createdBy: String, var anchor: Anchor,
    ) {
        var status = AnnotationStatus.OPEN
        var statusNote: String? = null
        var deleted = false
        var updatedAt = createdAt
        var ruleDecision: RuleDecision? = null
        val replies = ArrayList<MutableReply>()

        fun touch(ts: Long) {
            if (ts > updatedAt) updatedAt = ts
        }

        fun reply(id: String) = replies.firstOrNull { it.id == id }
    }

    internal fun foldEnvelopes(meta: ReviewMeta, envelopes: List<RawEnvelope>, report: ReportBuilder): ReviewState {
        val sorted = envelopes.sortedWith(foldOrder)

        // Duplicates by event id: the first in fold order wins. Then events that are not valid events of a known type are dropped.
        val seen = HashSet<String>()
        val typed = ArrayList<Pair<RawEnvelope, Event>>()
        for (env in sorted) {
            if (!seen.add(env.id)) {
                report.duplicates++
                continue
            }
            val ev = EventCodec.decode(env.ev)
            if (ev == null) {
                report.unknown++
                continue
            }
            typed += env to ev
        }
        report.events = typed.size

        var title = meta.title
        var status = ReviewStatus.OPEN
        var pin = meta.pin
        val annotations = ArrayList<MutableAnnotation>()
        val byId = HashMap<String, MutableAnnotation>()
        val fileMarks = LinkedHashMap<String, String>()
        val regionEvents = ArrayList<RegionEvent>()

        // Pass 1: the first creation of each annotation. A later creation with the same id is not a new comment: pass 3 applies it as an
        // edit at its own place in the order, so importing the same review twice is idempotent and still obeys last-writer-wins.
        val creators = HashSet<String>()
        for ((env, ev) in typed) {
            if (ev !is Event.AnnotationCreated || ev.annotationId in byId) continue
            val a = MutableAnnotation(ev.annotationId, ev.kind, ev.tags, ev.body, ev.ruleText, env.ts, env.dev, ev.anchor)
            byId[a.id] = a
            annotations += a
            creators += env.id
        }

        // Pass 2: the first creation of each reply, for the same reason.
        val replyCreators = HashSet<String>()
        for ((env, ev) in typed) {
            if (ev !is Event.ReplyAdded) continue
            val a = byId[ev.annotationId]
            if (a == null) {
                report.orphans++
                continue
            }
            if (a.reply(ev.replyId) != null) continue
            a.replies += MutableReply(ev.replyId, ev.body, false, env.ts, env.dev, env.ts)
            replyCreators += env.id
            a.touch(env.ts)
        }

        // Pass 3: everything else, in fold order, last writer wins.
        for ((env, ev) in typed) {
            if (env.id in creators || env.id in replyCreators) continue
            when (ev) {
                is Event.AnnotationCreated -> {
                    val a = byId.getValue(ev.annotationId)
                    a.kind = ev.kind
                    a.tags = ev.tags
                    a.body = ev.body
                    a.ruleText = ev.ruleText
                    a.anchor = ev.anchor
                    a.touch(env.ts)
                }
                is Event.ReplyAdded -> {
                    val a = byId[ev.annotationId] ?: continue // already counted as an orphan in pass 2
                    val r = a.reply(ev.replyId)!!
                    r.body = ev.body
                    r.updatedAt = maxOf(r.updatedAt, env.ts)
                    a.touch(env.ts)
                }
                is Event.ReviewRenamed -> title = ev.title
                is Event.ReviewStatusChanged -> status = ev.status
                is Event.ReviewRetargeted -> pin = ev.pin
                is Event.FileReviewed -> fileMarks[ev.path] = ev.changeKey
                is Event.FileUnreviewed -> fileMarks.remove(ev.path)
                is Event.RegionReviewed -> regionEvents += RegionEvent(env.id, env.ts, env.dev, env.seq, true, ev.path, ev.ranges)
                is Event.RegionUnreviewed -> regionEvents += RegionEvent(env.id, env.ts, env.dev, env.seq, false, ev.path, ev.ranges)
                is Event.RuleDecided -> for (id in ev.annotationIds) {
                    val a = byId[id]
                    if (a == null) {
                        report.orphans++
                        continue
                    }
                    a.ruleDecision = RuleDecision(ev.decision, ev.targetPath)
                    a.touch(env.ts)
                }
                // Everything left addresses one annotation, and maybe one reply.
                is Event.AnnotationEdited -> byId.addressed(ev.annotationId, report, env.ts) { a ->
                    ev.body?.let { a.body = it }
                    ev.kind?.let { a.kind = it }
                    ev.tags?.let { a.tags = it }
                    ev.ruleText?.let { a.ruleText = it.value }
                }
                is Event.AnnotationStatusChanged -> byId.addressed(ev.annotationId, report, env.ts) { a ->
                    a.status = ev.status
                    a.statusNote = ev.note
                }
                is Event.AnnotationReanchored -> byId.addressed(ev.annotationId, report, env.ts) { it.anchor = ev.anchor }
                is Event.AnnotationDeleted -> byId.addressed(ev.annotationId, report, env.ts) { it.deleted = true }
                is Event.AnnotationRestored -> byId.addressed(ev.annotationId, report, env.ts) { it.deleted = false }
                is Event.ReplyEdited -> byId.addressed(ev.annotationId, report, env.ts, ev.replyId) { r -> r.body = ev.body }
                is Event.ReplyDeleted -> byId.addressed(ev.annotationId, report, env.ts, ev.replyId) { r -> r.deleted = true }
                is Event.ReplyRestored -> byId.addressed(ev.annotationId, report, env.ts, ev.replyId) { r -> r.deleted = false }
            }
        }

        val finished = annotations
            .sortedWith(compareBy<MutableAnnotation> { it.createdAt }.thenComparing({ it.id }, Utf8Order))
            .map { a ->
                Annotation(
                    a.id, a.kind, a.tags, a.body, a.ruleText, a.status, a.statusNote, a.deleted, a.createdAt, a.createdBy, a.updatedAt, a.anchor, a.ruleDecision,
                    a.replies.sortedWith(compareBy<MutableReply> { it.createdAt }.thenComparing({ it.id }, Utf8Order))
                        .map { Reply(it.id, it.body, it.deleted, it.createdAt, it.createdBy, it.updatedAt) },
                )
            }
        val marks = fileMarks.entries.sortedWith(compareBy(Utf8Order) { it.key }).map { FileMark(it.key, it.value) }
        val header = ReviewHeader(meta.id, title, status, meta.createdAt, meta.createdBy, meta.repo, meta.baseRef, meta.headRef, meta.mode, pin)
        return ReviewState(header, finished, marks, regionEvents, report.build())
    }

    /** Applies [change] to the addressed comment, or counts an orphan (SPEC 5.3). */
    private fun HashMap<String, MutableAnnotation>.addressed(annotationId: String, report: ReportBuilder, ts: Long, change: (MutableAnnotation) -> Unit) {
        val a = this[annotationId]
        if (a == null) {
            report.orphans++
            return
        }
        change(a)
        a.touch(ts)
    }

    /** Applies [change] to the addressed reply, or counts an orphan. */
    private fun HashMap<String, MutableAnnotation>.addressed(annotationId: String, report: ReportBuilder, ts: Long, replyId: String, change: (MutableReply) -> Unit) {
        val a = this[annotationId]
        val r = a?.reply(replyId)
        if (a == null || r == null) {
            report.orphans++
            return
        }
        change(r)
        r.updatedAt = maxOf(r.updatedAt, ts)
        a.touch(ts)
    }
}
