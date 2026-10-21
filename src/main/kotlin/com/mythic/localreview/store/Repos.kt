package com.mythic.localreview.store

import com.mythic.localreview.core.GitCli
import com.mythic.localreview.format.Pin
import com.mythic.localreview.format.RepoRef
import com.mythic.localreview.format.ReviewMeta
import com.mythic.localreview.format.ReviewMode
import com.mythic.localreview.format.Ulid
import kotlin.io.path.name

/** What a local clone can say about its identity when deciding which reviews are about it (SPEC 4.1). */
data class LocalRepo(val displayName: String, val originUrl: String?, val rootCommits: Set<String>) {
    companion object {
        /** Asks git in the top level of the clone. */
        fun of(git: GitCli): LocalRepo = LocalRepo(git.toplevel().name, git.remoteUrl(), ChangeSets.rootCommits(git))
    }

    /** The reference a review made from this clone records. */
    fun repoRef(head: String, git: GitCli) = RepoRef(displayName, originUrl, ChangeSets.rootCommit(git, head))

    /**
     * Whether [review] is about this clone: compare, in order, the root commit, the normalised origin URL, the display name, and stop at the
     * first comparison both sides can make (SPEC 4.1). A clone has many root commits if it fetched unrelated histories, so the review's must be among them.
     */
    fun matches(review: RepoRef): Boolean {
        if (review.rootCommit != null && rootCommits.isNotEmpty()) return review.rootCommit in rootCommits
        if (review.originUrl != null && originUrl != null) return ReviewGroups.normalizeUrl(review.originUrl) == ReviewGroups.normalizeUrl(originUrl)
        return review.displayName == displayName
    }
}

/** Turning refs into pinned commits (SPEC 4). */
object Pins {
    /**
     * Resolves [baseRef] and [headRef] in [git]'s repository: `baseTipSha` is what the base ref points to, `headSha` the head; `baseSha` is the
     * merge-base of the two in [ReviewMode.MERGE_BASE] (pull-request style), else the base tip.
     * @throws com.mythic.localreview.core.GitException if a ref cannot be resolved.
     */
    fun resolve(git: GitCli, baseRef: String, headRef: String, mode: ReviewMode): Pin {
        val tip = git.resolveCommit(baseRef)
        val head = git.resolveCommit(headRef)
        val base = if (mode == ReviewMode.MERGE_BASE) git.mergeBase(tip, head) else tip
        return Pin(base, tip, head)
    }
}

object ReviewFactory {
    /** A new review of [git]'s repository, pinned now. The id is a fresh ULID. */
    fun newMeta(git: GitCli, title: String, baseRef: String, headRef: String, mode: ReviewMode, deviceId: String, now: Long): ReviewMeta {
        val pin = Pins.resolve(git, baseRef, headRef, mode)
        val repo = LocalRepo.of(git).repoRef(pin.headSha, git)
        return ReviewMeta(Ulid.next(now), title, now, deviceId, repo, baseRef, headRef, mode, pin)
    }
}
