package com.mythic.localreview.store

import com.mythic.localreview.core.AtomicFiles
import com.mythic.localreview.core.GitCommitter
import com.mythic.localreview.core.GitException
import com.mythic.localreview.core.GroupSync
import com.mythic.localreview.core.SshKeys
import com.mythic.localreview.core.SyncResult
import com.mythic.localreview.format.RepoRef
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

data class GroupInfo(val name: String, val dir: Path, val remoteUrl: String?, val autoSync: Boolean, val sshKey: String? = null)

/**
 * All review groups under a store root (`~/.local-review` by default). A **group is one git repository** holding the reviews of any number of
 * projects (`<root>/<group>/reviews/<id>/`); give a group a remote and it syncs between devices. Use several groups to keep unrelated work
 * isolated, each with its own history and remote.
 *
 * Per-machine state that must not sync (the device id, which group a repository's reviews go to, the review each project had open) lives outside
 * the group repositories, in `<root>/.state/`.
 */
class ReviewGroups(
    val root: Path,
    private val gitExe: String = "git",
    private val machine: String = MachineFingerprint.current,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Invoked (on the commit thread) with the group name after a commit was created in that group. */
    private val onCommitted: (String) -> Unit = {},
) {
    private val committers = ConcurrentHashMap<Path, GitCommitter>()
    private val groups = ConcurrentHashMap<Path, ReviewGroup>()
    private val stateDir: Path get() = root.resolve(".state")

    /** This machine's device id, shared by every IDE that runs the plugin (SPEC 5.1). */
    val device = DeviceIdentity(stateDir, machine)

    private val writer = EventLogWriter(clock)

    /** The single shared committer of a group directory (everything in the group must use this instance). */
    fun committer(dir: Path): GitCommitter {
        val key = dir.toAbsolutePath().normalize()
        return committers.computeIfAbsent(key) { k -> GitCommitter(k, gitExe).also { c -> c.onCommitted = { onCommitted(k.name) } } }
    }

    /** The single shared [ReviewGroup] of a group directory. */
    fun reviewGroup(info: GroupInfo): ReviewGroup {
        val key = info.dir.toAbsolutePath().normalize()
        return groups.computeIfAbsent(key) { ReviewGroup(info.name, key, committer(key), device, writer, clock) }
    }

    /** Flushes pending commits of every group and stops their commit threads. */
    fun close() {
        committers.values.forEach { runCatching { it.close() } }
        committers.clear()
        groups.clear()
    }

    // ---- groups -----------------------------------------------------------------------------------------------

    fun list(): List<GroupInfo> {
        if (!root.isDirectory()) return emptyList()
        return Files.list(root).use { s ->
            s.filter { it.isDirectory() && !it.name.startsWith(".") && it.resolve(ReviewGroup.GROUP_FILE).exists() }
                .map { info(it) }.toList().sortedBy { it.name.lowercase() }
        }
    }

    fun group(name: String): GroupInfo? = list().firstOrNull { it.name == name }

    private fun info(dir: Path): GroupInfo {
        val c = committer(dir)
        return GroupInfo(dir.name, dir, c.remoteUrl(), c.autoSync(), c.sshKey())
    }

    /**
     * Creates a group. With [remoteUrl] the remote is attached and, if it already holds a group (created on another device), its content is
     * checked out; otherwise the group starts empty and the first sync publishes it. [sshKey] (path to a private key) is applied before the
     * remote is contacted, for remotes that need a specific identity.
     * @throws IllegalArgumentException for an invalid or taken name; [GitException] if the remote cannot be reached.
     */
    fun create(name: String, remoteUrl: String? = null, sshKey: String? = null): GroupInfo {
        val clean = name.trim()
        require(validName(clean)) { "Invalid group name '$name': use letters, digits, space, '.', '_' or '-' (max 64, not starting with '.')." }
        require(list().none { it.name.equals(clean, ignoreCase = true) }) { "A group named '$clean' already exists." }
        val dir = root.resolve(clean)
        require(!dir.exists()) { "$dir already exists and is not a review group." }
        val c = committer(dir)
        try {
            c.ensureRepo()
            if (sshKey != null) c.setSshKey(sshKey)
            if (remoteUrl != null) attach(c, remoteUrl)
            val g = reviewGroup(GroupInfo(clean, dir, null, true))
            val report = g.prepare("Create group '$clean'")
            if (report.refused != null) throw IllegalStateException(report.refused)
        } catch (e: Exception) {
            // The folder is brand new and ours: remove the half-made group so a retry starts clean.
            val key = dir.toAbsolutePath().normalize()
            groups.remove(key)
            committers.remove(key)?.close()
            runCatching { dir.toFile().deleteRecursively() }
            throw if (e is GitException) GitException(SshKeys.withHint(e.message.orEmpty()), e.exitCode) else e
        }
        return info(dir)
    }

    fun ensureDefault(): GroupInfo = group(DEFAULT_GROUP) ?: create(DEFAULT_GROUP)

    /** Fetches [url] into a brand-new repo and checks out its branch if it has one. */
    private fun attach(c: GitCommitter, url: String) {
        c.setRemote(url)
        c.git.run("fetch", "--prune", "origin")
        val has = c.git.run("rev-parse", "--verify", "-q", "refs/remotes/origin/${GitCommitter.BRANCH}", okExitCodes = setOf(0, 1)).isNotBlank()
        if (has) c.git.run("checkout", "-q", "-B", GitCommitter.BRANCH, "origin/${GitCommitter.BRANCH}")
    }

    /** Sets, changes or (null) removes a group's remote. Changing it does not sync; call [sync] afterwards. */
    fun setRemote(name: String, url: String?) {
        val g = group(name) ?: throw IllegalArgumentException("No group '$name'")
        committer(g.dir).setRemote(url?.trim()?.ifEmpty { null })
    }

    /** Selects the SSH private key used for this group's remote; null = use the user's git/ssh configuration. */
    fun setSshKey(name: String, keyPath: String?) {
        val g = group(name) ?: throw IllegalArgumentException("No group '$name'")
        committer(g.dir).setSshKey(keyPath?.trim()?.ifEmpty { null })
    }

    fun setAutoSync(name: String, enabled: Boolean) {
        val g = group(name) ?: throw IllegalArgumentException("No group '$name'")
        committer(g.dir).setAutoSync(enabled)
    }

    fun sync(name: String): SyncResult {
        val g = group(name) ?: return SyncResult.Failed("No group '$name'")
        val result = GroupSync.sync(committer(g.dir))
        // A pull can bring a group.json or repository files from another device or version of the format: open the group again.
        if (result is SyncResult.Ok && result.pulled) reviewGroup(g).prepare()
        return result
    }

    /** Reviews across every group, newest first. */
    fun allReviews(): List<ReviewSummary> = list().flatMap { reviewGroup(it).reviews().reviews }.sortedByDescending { it.meta.createdAt }

    // ---- per-machine pointers (not synced) ---------------------------------------------------------------------

    private fun stateFile(kind: String, key: String): Path = stateDir.resolve(kind).resolve(sha256Hex(key).take(16) + ".txt")

    private fun readState(f: Path): String? = try {
        Files.readString(f).trim().takeIf { it.isNotEmpty() }
    } catch (e: IOException) {
        null
    }

    /** The group last used for reviews of [repo] on this machine, if it still exists. */
    fun groupFor(repo: RepoRef): String? = readState(stateFile("repo-groups", repoKey(repo)))?.takeIf { n -> list().any { it.name == n } }

    fun rememberGroup(repo: RepoRef, group: String) = AtomicFiles.write(stateFile("repo-groups", repoKey(repo)), group.toByteArray(Charsets.UTF_8))

    /** Records which review the project had open (`group` and review id); null = none (explicitly closed). */
    fun rememberLastOpened(projectKey: String, review: Pair<String, String>?) =
        AtomicFiles.write(stateFile("last-opened", projectKey), (review?.let { "${it.first}\n${it.second}" } ?: "").toByteArray(Charsets.UTF_8))

    /** The review the project had open, if it still exists. */
    fun lastOpened(projectKey: String): Pair<String, String>? {
        val lines = readState(stateFile("last-opened", projectKey))?.lines() ?: return null
        if (lines.size < 2) return null
        val (g, id) = lines[0] to lines[1].trim()
        val info = group(g) ?: return null
        return (g to id).takeIf { info.dir.resolve("reviews").resolve(id).resolve(ReviewGroup.REVIEW_FILE).exists() }
    }

    companion object {
        const val DEFAULT_GROUP = "default"

        fun validName(n: String) = Regex("^[A-Za-z0-9][A-Za-z0-9._ -]{0,63}$").matches(n) && !n.endsWith(" ") && !n.endsWith(".")

        private fun sha256Hex(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /** SPEC 4.1: URL normalisation: trim, drop a trailing `/`, drop a trailing `.git`, lowercase. */
        fun normalizeUrl(u: String) = u.trim().removeSuffix("/").removeSuffix(".git").lowercase()

        /** A stable key for "which repository is this" on this machine's pointers: the root commit if known, else the normalised URL, else the name. */
        fun repoKey(r: RepoRef): String = r.rootCommit?.let { "root:$it" } ?: r.originUrl?.let { "url:${normalizeUrl(it)}" } ?: "name:${r.displayName}"
    }
}
