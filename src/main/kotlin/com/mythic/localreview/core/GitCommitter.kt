package com.mythic.localreview.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Owns the git repository of one review group (or, in tests, a single review folder): initialisation, coalesced
 * auto-commits, the repository-wide write lock, and the remote/auto-sync settings stored in the repo's own git config.
 *
 * Files are already durable before [request] is called, so a failed or delayed commit never loses data; it only delays
 * history. All stores of a group share one instance so that commits and sync merges never interleave with file writes
 * (see [exclusive]).
 */
class GitCommitter(
    val dir: Path,
    gitExe: String = "git",
    private val debounceMs: Long = 300,
) : AutoCloseable {
    val git = GitCli(dir, gitExe)
    private val lock = Any()
    private val pending = ArrayList<String>()
    private var scheduled: ScheduledFuture<*>? = null
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "local-review-commit").apply { isDaemon = true }
    }

    /** Called (outside the lock, on the commit thread) after a commit was actually created. */
    @Volatile
    var onCommitted: (() -> Unit)? = null

    /** Last commit failure, or null if the most recent attempt succeeded. */
    @Volatile
    var lastError: String? = null
        private set

    /** Runs [block] holding the repository lock: file writes take it so a sync merge never sees a half-written state. */
    fun <T> exclusive(block: () -> T): T = synchronized(lock) { block() }

    /** Initialises the repo if needed on branch [BRANCH] (synchronous, idempotent). Writes no commit by itself. */
    fun ensureRepo() {
        synchronized(lock) {
            if (!Files.exists(dir.resolve(".git"))) {
                Files.createDirectories(dir)
                git.run("init", "-q")
                // `init -b` needs git >= 2.28; this works everywhere and the repo has no commits yet.
                git.run("symbolic-ref", "HEAD", "refs/heads/$BRANCH")
                git.run("config", "core.autocrlf", "false")
            }
            val ignore = dir.resolve(".gitignore")
            if (!Files.exists(ignore)) AtomicFiles.write(ignore, "*.tmp\n".toByteArray())
        }
    }

    // ---- remote + auto-sync (stored in the repo's .git/config: visible with plain git, never synced) -----------

    fun remoteUrl(): String? = synchronized(lock) {
        try {
            git.run("remote", "get-url", "origin").trim().ifEmpty { null }
        } catch (e: GitException) {
            null
        }
    }

    /** Sets, changes or (null) removes the `origin` remote. */
    fun setRemote(url: String?) {
        synchronized(lock) {
            val current = remoteUrl()
            when {
                url == null && current != null -> git.run("remote", "remove", "origin")
                url != null && current == null -> git.run("remote", "add", "origin", url)
                url != null && current != url -> git.run("remote", "set-url", "origin", url)
            }
        }
    }

    /** Auto-sync is on by default once a remote exists; stored per machine in the repo config. */
    fun autoSync(): Boolean = synchronized(lock) {
        try {
            git.run("config", "--local", "--get", "localreview.autosync").trim() != "false"
        } catch (e: GitException) {
            true
        }
    }

    fun setAutoSync(enabled: Boolean) {
        synchronized(lock) { git.run("config", "--local", "localreview.autosync", enabled.toString()) }
    }

    // ---- ssh key (per group, per machine; mirrors `includeIf` + `core.sshCommand` setups) ----------------------------

    /** The key selected for this group (`localreview.sshkey`), or null to leave it to the user's git/ssh configuration. */
    fun sshKey(): String? = synchronized(lock) {
        try {
            git.run("config", "--local", "--get", "localreview.sshkey").trim().ifEmpty { null }
        } catch (e: GitException) {
            null
        }
    }

    /**
     * Selects the SSH identity used to reach this group's remote by writing `core.sshCommand` into the repo's own config
     * (so plain `git` in the folder behaves the same). `IdentitiesOnly` stops ssh from offering other keys; `BatchMode`
     * makes a missing passphrase/agent fail fast instead of hanging. null clears the selection, leaving a hand-written
     * `core.sshCommand` untouched.
     * @throws IllegalArgumentException if the key file does not exist or the path cannot be quoted safely.
     */
    fun setSshKey(path: String?) {
        synchronized(lock) {
            if (path == null) {
                if (sshKey() != null) {
                    git.run("config", "--local", "--unset", "localreview.sshkey")
                    git.run("config", "--local", "--unset-all", "core.sshCommand", okExitCodes = setOf(0, 5))
                }
                return
            }
            val key = SshKeys.normalize(path)
            require(Files.isRegularFile(Path.of(key))) { "SSH key file not found: $key" }
            require(!key.endsWith(".pub")) { "Select the private key, not the .pub file." }
            require('\'' !in key && '"' !in key) { "Quotes in the key path are not supported." }
            git.run("config", "--local", "localreview.sshkey", key)
            git.run("config", "--local", "core.sshCommand", SshKeys.command(key))
        }
    }

    // ---- committing -------------------------------------------------------------------------------------------

    fun request(message: String) {
        synchronized(lock) {
            pending += message
            if (scheduled == null || scheduled!!.isDone) {
                scheduled = executor.schedule({ commitPending() }, debounceMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** Commits everything pending right now and waits for completion. */
    fun flush() {
        synchronized(lock) {
            scheduled?.cancel(false)
            scheduled = null
        }
        commitPending()
    }

    private fun commitPending() {
        var committed = false
        synchronized(lock) {
            val messages = ArrayList(pending)
            pending.clear()
            val first = messages.firstOrNull() ?: "Update"
            val message = if (messages.size > 1) "$first (+${messages.size - 1} more)" else first
            try {
                ensureRepo()
                committed = commitAllLocked(message)
                lastError = null
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
            }
        }
        if (committed) onCommitted?.invoke()
    }

    /** `git add -A` + commit if anything is staged. Caller holds the lock. Returns true if a commit was created. */
    fun commitAllLocked(message: String): Boolean {
        git.run("add", "-A")
        if (!stagedChanges()) return false
        git.run(*identityArgs(), "commit", "-q", "--no-verify", "--no-gpg-sign", "-m", message)
        return true
    }

    private fun stagedChanges(): Boolean = try {
        git.run("diff", "--cached", "--quiet")
        false
    } catch (e: GitException) {
        e.exitCode == 1
    }

    /** `-c user.name/-c user.email` overrides when the machine has no git identity configured. */
    fun identityArgs(): Array<String> {
        fun configured(key: String) = try {
            git.run("config", key).isNotBlank()
        } catch (e: GitException) {
            false
        }
        return if (configured("user.name") && configured("user.email")) {
            emptyArray()
        } else {
            arrayOf("-c", "user.name=Local Review", "-c", "user.email=local-review@localhost")
        }
    }

    override fun close() {
        flush()
        executor.shutdown()
    }

    companion object {
        /** Every review group uses the same branch name so devices always agree on what to sync. */
        const val BRANCH = "main"
    }
}
