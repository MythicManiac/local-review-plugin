package com.mythic.localreview.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.Instant

sealed interface SyncResult {
    /** The group has no remote configured. */
    data object NoRemote : SyncResult

    /** Sync completed. [conflicts] are the paths where both sides changed the same file (both versions were kept). */
    data class Ok(val pulled: Boolean, val pushed: Boolean, val conflicts: List<String> = emptyList()) : SyncResult

    data class Failed(val message: String) : SyncResult
}

/**
 * Two-way sync of a review group's git repository with its `origin` remote: commit local changes, fetch, merge, push.
 *
 * Reviews are stored as one small file per entity, so merges are almost always conflict-free. When both devices edited
 * the very same file, **neither version is dropped**: a deterministic winner (newer `updatedAt`; for `review.json` the one
 * with more revisions) stays in place and the loser is kept beside it as `<file>.conflict-<hash>`. Both devices pick the
 * same winner and the same loser file name, so they converge without further conflicts.
 */
object GroupSync {
    private const val MAX_ATTEMPTS = 3

    fun sync(c: GitCommitter): SyncResult {
        if (c.remoteUrl() == null) return SyncResult.NoRemote
        val git = c.git
        var pulled = false
        var pushed = false
        val conflicts = ArrayList<String>()
        try {
            c.flush()
            repeat(MAX_ATTEMPTS) {
                git.run("fetch", "--prune", "origin")
                val remote = git.run("rev-parse", "--verify", "-q", "refs/remotes/origin/${GitCommitter.BRANCH}", okExitCodes = setOf(0, 1))
                    .trim().ifEmpty { null }
                if (remote != null) {
                    val m = c.exclusive { merge(c, remote) }
                    pulled = pulled || m.pulled
                    conflicts += m.conflicts
                }
                if (!hasCommits(git)) return SyncResult.Ok(pulled, false, conflicts)
                val ahead = if (remote == null) true else git.run("rev-list", "--count", "$remote..HEAD").trim() != "0"
                if (!ahead) return SyncResult.Ok(pulled, pushed, conflicts)
                try {
                    git.run("push", "origin", "HEAD:refs/heads/${GitCommitter.BRANCH}")
                    return SyncResult.Ok(pulled, true, conflicts)
                } catch (e: GitException) {
                    val msg = e.message.orEmpty()
                    // Somebody pushed between our fetch and push: go round again (fetch, merge, push).
                    if (!(msg.contains("rejected") || msg.contains("non-fast-forward") || msg.contains("fetch first"))) throw e
                }
            }
            return SyncResult.Failed("The remote kept changing during sync; try again.")
        } catch (e: GitException) {
            return SyncResult.Failed(e.message ?: "git failed")
        }
    }

    private class MergeOutcome(val pulled: Boolean, val conflicts: List<String>)

    private fun hasCommits(git: GitCli) =
        git.run("rev-parse", "--verify", "-q", "HEAD", okExitCodes = setOf(0, 1)).isNotBlank()

    private fun isAncestor(git: GitCli, ancestor: String, of: String): Boolean = try {
        git.run("merge-base", "--is-ancestor", ancestor, of)
        true
    } catch (e: GitException) {
        if (e.exitCode == 1) false else throw e
    }

    /** Merges [remote] into HEAD. Caller holds the repository lock. */
    private fun merge(c: GitCommitter, remote: String): MergeOutcome {
        val git = c.git
        if (!hasCommits(git)) {
            git.run("checkout", "-q", "-B", GitCommitter.BRANCH, remote)
            return MergeOutcome(true, emptyList())
        }
        c.commitAllLocked("Update")
        if (isAncestor(git, remote, "HEAD")) return MergeOutcome(false, emptyList())

        git.run(*c.identityArgs(), "merge", "--no-edit", "--allow-unrelated-histories", "-m", "Sync with remote", remote, okExitCodes = setOf(0, 1))
        val inProgress = git.run("rev-parse", "-q", "--verify", "MERGE_HEAD", okExitCodes = setOf(0, 1)).isNotBlank()
        if (!inProgress) {
            if (!isAncestor(git, remote, "HEAD")) throw GitException("merge with the remote failed")
            return MergeOutcome(true, emptyList())
        }
        val resolved = ArrayList<String>()
        try {
            val unmerged = git.run("diff", "--name-only", "--diff-filter=U").lines().map { it.trim() }.filter { it.isNotEmpty() }
            for (path in unmerged) {
                resolve(c, path)
                resolved += path
            }
            git.run(*c.identityArgs(), "commit", "-q", "--no-verify", "--no-gpg-sign", "--no-edit")
        } catch (e: Exception) {
            runCatching { git.run("merge", "--abort") }
            throw if (e is GitException) e else GitException("conflict resolution failed: ${e.message}")
        }
        return MergeOutcome(true, resolved)
    }

    private fun resolve(c: GitCommitter, path: String) {
        val git = c.git
        val ours = stage(git, 2, path)
        val theirs = stage(git, 3, path)
        val file = c.dir.resolve(path)
        if (ours == null || theirs == null) {
            // Modify/delete (never produced by this plugin, which only tombstones): keep whichever side still has content.
            if (Files.exists(file)) git.run("add", "--", path) else git.run("rm", "--cached", "-q", "--", path)
            return
        }
        val oursWins = pickOurs(path, ours, theirs)
        git.run("checkout", if (oursWins) "--ours" else "--theirs", "--", path)
        val loser = if (oursWins) theirs else ours
        val loserPath = "$path.conflict-${Fingerprints.sha1Hex(String(loser, Charsets.UTF_8)).take(8)}"
        Files.write(c.dir.resolve(loserPath), loser)
        git.run("add", "--", path, loserPath)
    }

    private fun stage(git: GitCli, n: Int, path: String): ByteArray? = try {
        git.runBytes("show", ":$n:$path")
    } catch (e: GitException) {
        null
    }

    /** True if our version should win. Symmetric, so both devices choose the same winner. */
    internal fun pickOurs(path: String, ours: ByteArray, theirs: ByteArray): Boolean {
        return rank(path, ours) > rank(path, theirs)
    }

    /** Orders versions: (revisions for review.json, updatedAt, content hash as a deterministic tie-break). */
    private fun rank(path: String, bytes: ByteArray): Rank {
        val text = String(bytes, Charsets.UTF_8)
        var revisions = 0
        var updated = Instant.MIN
        if (path.endsWith(".json")) {
            try {
                val o = StoreJson.parseToJsonElement(text).jsonObject
                revisions = (o["revisions"] as? JsonArray)?.size ?: 0
                updated = o["updatedAt"]?.jsonPrimitive?.content?.let { Instant.parse(it) } ?: Instant.MIN
            } catch (e: Exception) {
                // unparsable: fall through to the hash tie-break
            }
        }
        return Rank(revisions, updated, Fingerprints.sha1Hex(text))
    }

    private data class Rank(val revisions: Int, val updated: Instant, val hash: String) : Comparable<Rank> {
        override fun compareTo(other: Rank): Int =
            compareValuesBy(this, other, { it.revisions }, { it.updated }, { it.hash })
    }
}
