package com.mythic.localreview.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

data class ReviewSummary(val dir: Path, val review: Review)

/** The folder tree of all reviews: `<root>/<repo-name>/<review-id>/`. */
class ReviewRepository(val root: Path, private val gitExe: String = "git") {

    /** Reviews belonging to [repo] (matched by normalised remote URL if both have one, else by repo name). */
    fun list(repo: RepoInfo): List<ReviewSummary> {
        if (!root.isDirectory()) return emptyList()
        val out = ArrayList<ReviewSummary>()
        Files.list(root).use { repos ->
            repos.filter { it.isDirectory() && !it.name.startsWith(".") }.forEach { repoDir ->
                Files.list(repoDir).use { reviews ->
                    reviews.filter { it.resolve(ReviewStore.REVIEW_FILE).exists() }.forEach { d ->
                        val r = ReviewStore.readReviewOrNull(d) ?: return@forEach
                        if (sameRepo(r.repo, repo)) out += ReviewSummary(d, r)
                    }
                }
            }
        }
        return out.sortedByDescending { it.review.createdAt }
    }

    private fun sameRepo(a: RepoInfo, b: RepoInfo): Boolean {
        val ua = a.remoteUrl?.let(::normalizeUrl)
        val ub = b.remoteUrl?.let(::normalizeUrl)
        return if (ua != null && ub != null) ua == ub else a.name == b.name
    }

    private fun normalizeUrl(u: String) = u.trim().removeSuffix("/").removeSuffix(".git").lowercase()

    fun open(summary: ReviewSummary): ReviewStore = ReviewStore.open(summary.dir, GitCommitter(summary.dir, gitExe))

    // ---- per-project "last opened review" pointer, stored beside the reviews so it survives crashes ----------

    private fun pointerFile(projectKey: String): Path =
        root.resolve(".state").resolve("last-opened").resolve(Fingerprints.pathKey(projectKey) + ".txt")

    /** Records which review the project had open; null = none (explicitly closed). */
    fun rememberLastOpened(projectKey: String, reviewDir: Path?) =
        AtomicFiles.write(pointerFile(projectKey), (reviewDir?.toString() ?: "").toByteArray(Charsets.UTF_8))

    fun lastOpened(projectKey: String): Path? = try {
        Files.readString(pointerFile(projectKey)).trim().takeIf { it.isNotEmpty() }?.let { Path.of(it) }
            ?.takeIf { it.resolve(ReviewStore.REVIEW_FILE).exists() }
    } catch (e: java.io.IOException) {
        null
    }

    /**
     * Resolves the refs in [repoGit]'s repository, pins them, and creates the review folder.
     * @throws GitException if a ref cannot be resolved.
     */
    fun create(
        repoGit: GitCli,
        title: String,
        baseRef: String,
        headRef: String,
        mergeBase: Boolean = true,
        clock: () -> Instant = Instant::now,
    ): ReviewStore {
        val now = clock()
        val top = repoGit.toplevel()
        val repo = RepoInfo(name = top.name, remoteUrl = repoGit.remoteUrl(), lastKnownPath = top.toString())
        val revision = resolveRevision(repoGit, baseRef, headRef, mergeBase, 1, now.toString())
        val review = Review(
            id = uniqueId(repo.name, title, now), title = title, repo = repo, baseRef = baseRef, headRef = headRef,
            mergeBase = mergeBase, revisions = listOf(revision), createdAt = now.toString(), updatedAt = now.toString(),
        )
        val dir = root.resolve(sanitize(repo.name)).resolve(review.id)
        return ReviewStore.create(dir, review, GitCommitter(dir, gitExe), clock)
    }

    private fun uniqueId(repoName: String, title: String, now: Instant): String {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.systemDefault()).format(now)
        val base = "$stamp-${slug(title)}"
        var id = base
        var n = 2
        while (root.resolve(sanitize(repoName)).resolve(id).exists()) id = "$base-${n++}"
        return id
    }

    companion object {
        fun slug(s: String): String =
            s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(50).ifEmpty { "review" }

        fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]+"), "_").ifEmpty { "repo" }

        fun resolveRevision(git: GitCli, baseRef: String, headRef: String, mergeBase: Boolean, n: Int, at: String): Revision {
            val base = git.resolveCommit(baseRef)
            val head = git.resolveCommit(headRef)
            val effective = if (mergeBase) git.mergeBase(base, head) else base
            return Revision(n = n, at = at, baseSha = base, headSha = head, effectiveBaseSha = effective)
        }
    }
}

data class UpdateResult(val newRevision: Revision?, val reanchored: Int, val outdated: Int)

/** "Update review": re-resolve refs, append a revision, re-anchor comments. See docs/DESIGN.md. */
object ReviewUpdater {
    fun update(store: ReviewStore, git: GitCli, clock: () -> Instant = Instant::now): UpdateResult {
        val review = store.review()
        val cur = review.current
        val rev = ReviewRepository.resolveRevision(
            git, review.baseRef, review.headRef, review.mergeBase, cur.n + 1, clock().toString(),
        )
        if (rev.baseSha == cur.baseSha && rev.headSha == cur.headSha && rev.effectiveBaseSha == cur.effectiveBaseSha) {
            return UpdateResult(null, 0, 0)
        }
        val newReview = review.copy(revisions = review.revisions + rev)
        // Review first: comments keep pointing at older (still valid) revisions until rewritten below.
        store.saveReview(newReview)

        val diffCache = HashMap<Pair<String, String>, List<FileChange>>()
        val contentCache = HashMap<Pair<String, String>, List<String>?>()
        fun diff(a: String, b: String) = diffCache.getOrPut(a to b) { if (a == b) emptyList() else git.diffRaw(a, b) }
        fun lines(sha: String, path: String) = contentCache.getOrPut(sha to path) {
            git.showFile(sha, path)?.let { Anchoring.splitLines(String(it, Charsets.UTF_8)) }
        }

        var reanchored = 0
        var outdated = 0
        val updates = ArrayList<Comment>()
        for (c in store.comments(includeDeleted = true)) {
            val a = c.anchor
            if (a.scope == AnchorScope.REVIEW) continue
            if (a.revision == rev.n && !c.outdated) continue
            val old = review.revisions.firstOrNull { it.n == a.revision } ?: continue
            val oldSha = if (a.side == Side.HEAD) old.headSha else old.effectiveBaseSha
            val newSha = if (a.side == Side.HEAD) rev.headSha else rev.effectiveBaseSha
            val path = a.path!!

            val change = diff(oldSha, newSha).firstOrNull { it.oldPath == path }
            val newPath = when {
                change == null -> path
                change.status == ChangeStatus.DELETED -> null
                else -> change.newPath
            }
            val next: Comment = when {
                newPath == null -> c.copy(outdated = true)
                a.scope == AnchorScope.FILE -> c.copy(anchor = a.copy(path = newPath, revision = rev.n), outdated = false)
                change == null || change.oldBlob == change.newBlob ->
                    c.copy(anchor = a.copy(path = newPath, revision = rev.n), outdated = false)
                else -> {
                    val found = lines(newSha, newPath)?.let { Anchoring.locate(a, it) }
                    if (found == null) c.copy(outdated = true)
                    else c.copy(anchor = a.copy(path = newPath, startLine = found.start, endLine = found.end, revision = rev.n), outdated = false)
                }
            }
            if (next != c) {
                if (next.outdated) outdated++ else reanchored++
                updates += next
            }
        }
        store.replaceComments(updates, "Update review to revision ${rev.n}: $reanchored re-anchored, $outdated outdated")
        return UpdateResult(rev, reanchored, outdated)
    }
}
