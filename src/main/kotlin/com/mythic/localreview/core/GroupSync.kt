package com.mythic.localreview.core

import com.mythic.localreview.format.RepoFiles
import com.mythic.localreview.format.Utf8Order
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.time.Instant

sealed interface SyncResult {
    /** The group has no remote configured. */
    data object NoRemote : SyncResult

    /** Sync completed. [conflicts] are the paths git could not merge by itself and this code resolved by the rules of SPEC 9.3 (`group.json`, `.gitignore`, `.gitattributes`). */
    data class Ok(val pulled: Boolean, val pushed: Boolean, val conflicts: List<String> = emptyList()) : SyncResult

    data class Failed(val message: String) : SyncResult
}

/**
 * Two-way sync of a review group's git repository with its `origin` remote (SPEC 9.3): commit local changes, fetch, merge, push.
 *
 * Devices only ever append to their own logs, and `*.jsonl` uses git's union merge driver, so merges are conflict-free by construction. A merge
 * can still conflict in exactly three ways, which are resolved the way the format says (both devices make the same choice):
 * `group.json` keeps the group created first, `.gitignore` and `.gitattributes` keep every other line sorted and the required lines last, and
 * anything else aborts the merge and reports the path, leaving the user's data exactly as it was: no winner is chosen and no loser is kept aside.
 */
object GroupSync {
    private const val MAX_ATTEMPTS = 3
    private const val GROUP_FILE = "group.json"

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
            return SyncResult.Failed(SshKeys.withHint(e.message ?: "git failed"))
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
        val unmerged = git.run("diff", "--name-only", "--diff-filter=U").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val unresolvable = unmerged.filterNot { it in RESOLVABLE }
        if (unresolvable.isNotEmpty()) {
            runCatching { git.run("merge", "--abort") }
            throw GitException(
                "The merge with the remote conflicts in ${unresolvable.joinToString(", ")}, which is not resolved automatically. " +
                    "The merge was aborted and nothing on this machine changed. Resolve the difference in the group's folder with git, then sync again.",
            )
        }
        try {
            for (path in unmerged) resolve(c, path)
            git.run(*c.identityArgs(), "commit", "-q", "--no-verify", "--no-gpg-sign", "--no-edit")
        } catch (e: Exception) {
            runCatching { git.run("merge", "--abort") }
            throw if (e is GitException) e else GitException("conflict resolution failed: ${e.message}")
        }
        return MergeOutcome(true, unmerged)
    }

    private val RESOLVABLE = setOf(GROUP_FILE, ".gitignore", ".gitattributes")

    private fun resolve(c: GitCommitter, path: String) {
        val git = c.git
        val ours = stage(git, 2, path)
        val theirs = stage(git, 3, path)
        val file = c.dir.resolve(path)
        when (path) {
            GROUP_FILE -> {
                // Two groups created apart were joined: keep the one created first, so both devices choose alike.
                val winner = when {
                    ours == null -> theirs
                    theirs == null -> ours
                    else -> if (groupOrder(ours) <= groupOrder(theirs)) ours else theirs
                }
                if (winner == null) git.run("rm", "--cached", "-q", "--", path)
                else {
                    Files.write(file, winner)
                    git.run("add", "--", path)
                }
            }
            else -> {
                val required = if (path == ".gitignore") RepoFiles.GITIGNORE else RepoFiles.GITATTRIBUTES
                val merged = RepoFiles.merge(required, ours?.toString(Charsets.UTF_8).orEmpty(), theirs?.toString(Charsets.UTF_8).orEmpty())
                Files.write(file, merged.toByteArray(Charsets.UTF_8))
                git.run("add", "--", path)
            }
        }
    }

    private fun stage(git: GitCli, n: Int, path: String): ByteArray? = try {
        git.runBytes("show", ":$n:$path")
    } catch (e: GitException) {
        null
    }

    /** Orders two versions of `group.json`: smaller `createdAt` first, then smaller `id` by UTF-8 bytes. A `createdAt` that cannot be read loses. */
    private data class GroupOrder(val createdAt: Long, val id: String) : Comparable<GroupOrder> {
        override fun compareTo(other: GroupOrder): Int = createdAt.compareTo(other.createdAt).let { if (it != 0) it else Utf8Order.compare(id, other.id) }
    }

    private fun groupOrder(bytes: ByteArray): GroupOrder {
        val o = try {
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) {
            null
        }
        val id = (o?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val raw = o?.get("createdAt") as? JsonPrimitive
        val created = when {
            raw == null -> null
            raw.isString -> runCatching { Instant.parse(raw.content).toEpochMilli() }.getOrNull() // an ISO-8601 string from an older tool
            else -> raw.content.toLongOrNull()
        }
        return GroupOrder(created ?: Long.MAX_VALUE, id)
    }

    /** True if our `group.json` should win over theirs; symmetric. Exposed for tests. */
    internal fun oursWins(ours: ByteArray, theirs: ByteArray) = groupOrder(ours) <= groupOrder(theirs)
}
