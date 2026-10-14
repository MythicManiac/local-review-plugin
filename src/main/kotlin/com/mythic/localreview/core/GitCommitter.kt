package com.mythic.localreview.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps a review folder under version control. Files are already durable before [request] is called, so a failed or
 * delayed commit never loses data; it only delays history. Requests are coalesced into one commit.
 */
class GitCommitter(
    private val dir: Path,
    gitExe: String = "git",
    private val debounceMs: Long = 300,
) : AutoCloseable {
    private val git = GitCli(dir, gitExe)
    private val lock = Any()
    private val pending = ArrayList<String>()
    private var scheduled: ScheduledFuture<*>? = null
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "local-review-commit").apply { isDaemon = true }
    }

    /** Last commit failure, or null if the most recent attempt succeeded. */
    @Volatile
    var lastError: String? = null
        private set

    /** Initialises the repo if needed (synchronous). */
    fun ensureRepo() {
        synchronized(lock) {
            if (!Files.exists(dir.resolve(".git"))) {
                git.run("init", "-q")
                git.run("config", "core.autocrlf", "false")
            }
            val ignore = dir.resolve(".gitignore")
            if (!Files.exists(ignore)) AtomicFiles.write(ignore, "*.tmp\n".toByteArray())
        }
    }

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
        synchronized(lock) {
            val messages = ArrayList(pending)
            pending.clear()
            val first = messages.firstOrNull() ?: "Update review"
            val message = if (messages.size > 1) "$first (+${messages.size - 1} more)" else first
            try {
                ensureRepo()
                git.run("add", "-A")
                if (stagedChanges()) {
                    git.run(*identityArgs(), "commit", "-q", "--no-verify", "--no-gpg-sign", "-m", message)
                }
                lastError = null
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
            }
        }
    }

    private fun stagedChanges(): Boolean = try {
        git.run("diff", "--cached", "--quiet")
        false
    } catch (e: GitException) {
        e.exitCode == 1
    }

    private fun identityArgs(): Array<String> {
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
}
