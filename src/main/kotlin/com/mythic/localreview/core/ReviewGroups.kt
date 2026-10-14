package com.mythic.localreview.core

import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** Marker file at the root of every group repo; its presence is what makes a folder a group. */
@Serializable
data class GroupFile(val schemaVersion: Int = SCHEMA_VERSION, val id: String, val createdAt: String)

data class GroupInfo(val name: String, val dir: Path, val remoteUrl: String?, val autoSync: Boolean)

/**
 * All review groups under a store root. A **group is one git repository** holding the reviews of any number of projects
 * (`<root>/<group>/<repo-name>/<review-id>/`); give a group a remote and it syncs between devices. Use several groups to
 * keep unrelated work (e.g. different organisations) isolated, each with its own history and remote.
 *
 * Per-machine state that must not sync (repo→group preference, last opened review) lives outside the group repos in
 * `<root>/.state/`.
 */
class ReviewGroups(
    val root: Path,
    private val gitExe: String = "git",
    /** Invoked (on the commit thread) with the group name after a commit was created in that group. */
    private val onCommitted: (String) -> Unit = {},
) {
    private val committers = ConcurrentHashMap<Path, GitCommitter>()

    /** The single shared committer of a group directory (all stores in the group must use this instance). */
    fun committer(dir: Path): GitCommitter {
        val key = dir.toAbsolutePath().normalize()
        return committers.computeIfAbsent(key) { k ->
            GitCommitter(k, gitExe).also { c -> c.onCommitted = { onCommitted(k.name) } }
        }
    }

    /** Flushes pending commits of every group and stops their commit threads. */
    fun close() {
        committers.values.forEach { runCatching { it.close() } }
        committers.clear()
    }

    // ---- groups -----------------------------------------------------------------------------------------------

    fun list(): List<GroupInfo> {
        if (!root.isDirectory()) return emptyList()
        return Files.list(root).use { s ->
            s.filter { it.isDirectory() && !it.name.startsWith(".") && it.resolve(GROUP_FILE).exists() }
                .map { info(it) }.toList().sortedBy { it.name.lowercase() }
        }
    }

    fun group(name: String): GroupInfo? = list().firstOrNull { it.name == name }

    private fun info(dir: Path): GroupInfo {
        val c = committer(dir)
        return GroupInfo(dir.name, dir, c.remoteUrl(), c.autoSync())
    }

    /**
     * Creates a group. With [remoteUrl] the remote is attached and, if it already holds a group (created on another
     * device), its content is checked out; otherwise the group starts empty and the first sync publishes it.
     * @throws IllegalArgumentException for an invalid or taken name; [GitException] if the remote cannot be reached.
     */
    fun create(name: String, remoteUrl: String? = null): GroupInfo {
        val clean = name.trim()
        require(validName(clean)) { "Invalid group name '$name': use letters, digits, space, '.', '_' or '-' (max 64, not starting with '.')." }
        require(list().none { it.name.equals(clean, ignoreCase = true) }) { "A group named '$clean' already exists." }
        val dir = root.resolve(clean)
        require(!dir.exists()) { "$dir already exists and is not a review group." }
        val c = committer(dir)
        try {
            c.ensureRepo()
            if (remoteUrl != null) attach(c, remoteUrl)
            c.exclusive {
                if (!dir.resolve(GROUP_FILE).exists()) {
                    val f = GroupFile(id = UUID.randomUUID().toString(), createdAt = Instant.now().toString())
                    AtomicFiles.write(dir.resolve(GROUP_FILE), (StoreJson.encodeToString(GroupFile.serializer(), f) + "\n").toByteArray())
                }
                c.ensureRepo()
                c.commitAllLocked("Create group '$clean'")
            }
        } catch (e: Exception) {
            // The folder is brand new and ours: remove the half-made group so a retry starts clean.
            committers.remove(dir.toAbsolutePath().normalize())?.close()
            runCatching { dir.toFile().deleteRecursively() }
            throw e
        }
        return info(dir)
    }

    fun ensureDefault(): GroupInfo = group(DEFAULT_GROUP) ?: create(DEFAULT_GROUP)

    /** Fetches [url] into a brand-new repo and checks out its branch if it has one. */
    private fun attach(c: GitCommitter, url: String) {
        c.setRemote(url)
        c.git.run("fetch", "--prune", "origin")
        val has = c.git.run("rev-parse", "--verify", "-q", "refs/remotes/origin/${GitCommitter.BRANCH}", okExitCodes = setOf(0, 1)).isNotBlank()
        if (has) {
            // Our own freshly written .gitignore would block the checkout; the remote's content replaces it.
            Files.deleteIfExists(c.dir.resolve(".gitignore"))
            c.git.run("checkout", "-q", "-B", GitCommitter.BRANCH, "origin/${GitCommitter.BRANCH}")
        }
    }

    /** Sets, changes or (null) removes a group's remote. Changing it does not sync; call [sync] afterwards. */
    fun setRemote(name: String, url: String?) {
        val g = group(name) ?: throw IllegalArgumentException("No group '$name'")
        committer(g.dir).setRemote(url?.trim()?.ifEmpty { null })
    }

    fun setAutoSync(name: String, enabled: Boolean) {
        val g = group(name) ?: throw IllegalArgumentException("No group '$name'")
        committer(g.dir).setAutoSync(enabled)
    }

    fun sync(name: String): SyncResult {
        val g = group(name) ?: return SyncResult.Failed("No group '$name'")
        return GroupSync.sync(committer(g.dir))
    }

    fun repository(group: GroupInfo) = ReviewRepository(group.dir, group.name, committer(group.dir))

    /** Reviews of [repo] (all reviews if null) across every group, newest first. */
    fun listReviews(repo: RepoInfo?): List<ReviewSummary> =
        list().flatMap { repository(it).list(repo) }.sortedByDescending { it.review.createdAt }

    /** The group that holds the review stored at [reviewDir]. */
    fun groupOf(reviewDir: Path): String? {
        val rel = try {
            root.toAbsolutePath().normalize().relativize(reviewDir.toAbsolutePath().normalize())
        } catch (e: IllegalArgumentException) {
            return null
        }
        return if (rel.nameCount > 0 && rel.getName(0).toString() != "..") rel.getName(0).toString() else null
    }

    // ---- per-machine pointers (not synced) ---------------------------------------------------------------------

    private fun stateFile(kind: String, key: String): Path = root.resolve(".state").resolve(kind).resolve(Fingerprints.pathKey(key) + ".txt")

    private fun readState(f: Path): String? = try {
        Files.readString(f).trim().takeIf { it.isNotEmpty() }
    } catch (e: IOException) {
        null
    }

    /** The group last used for [repo]'s reviews on this machine, if it still exists. */
    fun groupFor(repo: RepoInfo): String? =
        readState(stateFile("repo-groups", ReviewRepository.repoKey(repo)))?.takeIf { n -> list().any { it.name == n } }

    fun rememberGroup(repo: RepoInfo, group: String) =
        AtomicFiles.write(stateFile("repo-groups", ReviewRepository.repoKey(repo)), group.toByteArray(Charsets.UTF_8))

    /** Records which review the project had open; null = none (explicitly closed). */
    fun rememberLastOpened(projectKey: String, reviewDir: Path?) =
        AtomicFiles.write(stateFile("last-opened", projectKey), (reviewDir?.toString() ?: "").toByteArray(Charsets.UTF_8))

    fun lastOpened(projectKey: String): Path? =
        readState(stateFile("last-opened", projectKey))?.let { Path.of(it) }?.takeIf { it.resolve(ReviewStore.REVIEW_FILE).exists() }

    // ---- migration from the previous layout --------------------------------------------------------------------

    /**
     * Previous versions kept every review in its own repo at `<root>/<repo-name>/<review-id>/`. Moves those into the
     * `default` group. Each review's old `.git` is preserved (moved to `<root>/.legacy/<repo>/<id>.git`), never deleted.
     * @return number of reviews migrated.
     */
    fun migrateLegacy(): Int {
        if (!root.isDirectory()) return 0
        val legacyRepoDirs = Files.list(root).use { s ->
            s.filter { it.isDirectory() && !it.name.startsWith(".") && !it.resolve(GROUP_FILE).exists() && hasReviews(it) }.toList()
        }
        if (legacyRepoDirs.isEmpty()) return 0
        val group = ensureDefault()
        val c = committer(group.dir)
        var moved = 0
        val relocated = HashMap<String, Path>() // old review dir -> new review dir, to fix last-opened pointers
        c.exclusive {
            for (repoDir in legacyRepoDirs) {
                val reviewDirs = Files.list(repoDir).use { s -> s.filter { it.resolve(ReviewStore.REVIEW_FILE).exists() }.toList() }
                for (rd in reviewDirs) {
                    val target = group.dir.resolve(repoDir.name).resolve(rd.name)
                    if (target.exists()) continue
                    val oldGit = rd.resolve(".git")
                    if (oldGit.exists()) {
                        val keep = root.resolve(".legacy").resolve(repoDir.name).resolve(rd.name + ".git")
                        Files.createDirectories(keep.parent)
                        Files.move(oldGit, keep)
                    }
                    Files.createDirectories(target.parent)
                    Files.move(rd, target)
                    relocated[rd.toAbsolutePath().normalize().toString()] = target
                    moved++
                }
                runCatching { Files.delete(repoDir) } // only succeeds when empty
            }
            if (moved > 0) c.commitAllLocked("Import $moved review(s) from the per-review layout")
        }
        remapPointers(relocated)
        return moved
    }

    /** Points "last opened" entries that referenced a migrated review at its new location. */
    private fun remapPointers(relocated: Map<String, Path>) {
        val dir = root.resolve(".state").resolve("last-opened")
        if (relocated.isEmpty() || !dir.isDirectory()) return
        Files.list(dir).use { it.toList() }.forEach { f ->
            val old = readState(f)?.let { Path.of(it).toAbsolutePath().normalize().toString() } ?: return@forEach
            relocated[old]?.let { AtomicFiles.write(f, it.toString().toByteArray(Charsets.UTF_8)) }
        }
    }

    private fun hasReviews(dir: Path): Boolean = try {
        Files.list(dir).use { s -> s.anyMatch { it.isDirectory() && it.resolve(ReviewStore.REVIEW_FILE).exists() } }
    } catch (e: IOException) {
        false
    }

    companion object {
        const val DEFAULT_GROUP = "default"
        const val GROUP_FILE = "group.json"

        fun validName(n: String) = Regex("^[A-Za-z0-9][A-Za-z0-9._ -]{0,63}$").matches(n) && !n.endsWith(" ") && !n.endsWith(".")
    }
}
