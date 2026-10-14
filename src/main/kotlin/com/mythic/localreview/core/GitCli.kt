package com.mythic.localreview.core

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class GitException(message: String, val exitCode: Int = -1) : RuntimeException(message)

/** Thin, IDE-independent wrapper over the git executable. All output is read as raw bytes. */
class GitCli(private val workDir: Path, private val exe: String = "git") {

    fun runBytes(vararg args: String, stdin: ByteArray? = null, okExitCodes: Set<Int> = setOf(0)): ByteArray {
        val cmd = listOf(exe, "-c", "core.quotepath=off", "-c", "color.ui=false") + args
        val pb = ProcessBuilder(cmd).directory(workDir.toFile())
        pb.environment()["GIT_TERMINAL_PROMPT"] = "0"
        pb.environment()["LC_ALL"] = "C"
        val p = try {
            pb.start()
        } catch (e: java.io.IOException) {
            throw GitException("Cannot start git ('$exe'): ${e.message}")
        }
        val err = CompletableFuture.supplyAsync { p.errorStream.readAllBytes() }
        val out = CompletableFuture.supplyAsync { p.inputStream.readAllBytes() }
        p.outputStream.use { if (stdin != null) it.write(stdin) }
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw GitException("git ${args.joinToString(" ")} timed out")
        }
        val code = p.exitValue()
        if (code !in okExitCodes) {
            throw GitException(
                "git ${args.joinToString(" ")} failed ($code): ${String(err.get(), Charsets.UTF_8).trim()}",
                code,
            )
        }
        return out.get()
    }

    fun run(vararg args: String, okExitCodes: Set<Int> = setOf(0)): String =
        String(runBytes(*args, okExitCodes = okExitCodes), Charsets.UTF_8)

    // ---- repository queries -------------------------------------------------------------------------------

    fun toplevel(): Path = Path.of(run("rev-parse", "--show-toplevel").trim())

    fun remoteUrl(): String? = try {
        run("config", "--get", "remote.origin.url").trim().ifEmpty { null }
    } catch (e: GitException) {
        null
    }

    /** Resolves any rev (branch, tag, sha, HEAD~2...) to a full commit SHA. */
    fun resolveCommit(rev: String): String = run("rev-parse", "--verify", "--quiet", "$rev^{commit}").trim().also {
        if (it.isEmpty()) throw GitException("Cannot resolve '$rev' to a commit")
    }

    fun mergeBase(a: String, b: String): String = run("merge-base", a, b).trim().also {
        if (it.isEmpty()) throw GitException("No merge base between $a and $b")
    }

    /** Short display names for local branches, remote branches and tags (for the new-review dialog). */
    fun listRefs(): List<String> = run(
        "for-each-ref", "--format=%(refname:short)", "--sort=-committerdate", "refs/heads", "refs/remotes", "refs/tags",
    ).lines().map { it.trim() }.filter { it.isNotEmpty() && !it.endsWith("/HEAD") }

    fun currentBranch(): String? = run("symbolic-ref", "--short", "-q", "HEAD", okExitCodes = setOf(0, 1)).trim().ifEmpty { null }

    /** File content at a revision, or null if the path does not exist there. */
    fun showFile(sha: String, path: String): ByteArray? = try {
        runBytes("show", "$sha:$path")
    } catch (e: GitException) {
        null
    }

    /** `git diff --raw -z -M --no-abbrev old new`, parsed. */
    fun diffRaw(oldRev: String, newRev: String, detectRenames: Boolean = true): List<FileChange> {
        val args = mutableListOf("diff", "--raw", "-z", "--no-abbrev", "--no-ext-diff")
        if (detectRenames) args += "-M"
        args += listOf(oldRev, newRev, "--")
        return FileChange.parseRaw(runBytes(*args.toTypedArray()))
    }
}

enum class ChangeStatus { ADDED, MODIFIED, DELETED, RENAMED, COPIED, TYPE_CHANGED }

data class FileChange(
    val status: ChangeStatus,
    val oldPath: String?,
    val newPath: String?,
    val oldBlob: String,
    val newBlob: String,
    val similarity: Int? = null,
) {
    /** Display/primary path: the new path unless the file was deleted. */
    val path: String get() = newPath ?: oldPath!!

    /** Identifies this exact file diff; changes whenever either side's content, path or status changes. */
    val fingerprint: String get() = Fingerprints.sha1Hex("$status|$oldPath|$newPath|$oldBlob|$newBlob")

    companion object {
        private val NULL_BLOB = "0".repeat(40)

        fun parseRaw(bytes: ByteArray): List<FileChange> {
            val fields = String(bytes, Charsets.UTF_8).split('\u0000')
            val result = mutableListOf<FileChange>()
            var i = 0
            while (i < fields.size) {
                val meta = fields[i]
                if (meta.isEmpty()) {
                    i++; continue
                }
                // ":100644 100644 <oldsha> <newsha> R087"
                val parts = meta.removePrefix(":").split(' ')
                val oldBlob = parts[2]
                val newBlob = parts[3]
                val code = parts[4]
                val letter = code[0]
                val similarity = code.drop(1).toIntOrNull()
                i++
                val status = when (letter) {
                    'A' -> ChangeStatus.ADDED
                    'D' -> ChangeStatus.DELETED
                    'R' -> ChangeStatus.RENAMED
                    'C' -> ChangeStatus.COPIED
                    'T' -> ChangeStatus.TYPE_CHANGED
                    else -> ChangeStatus.MODIFIED
                }
                if (letter == 'R' || letter == 'C') {
                    result += FileChange(status, fields[i], fields[i + 1], oldBlob, newBlob, similarity)
                    i += 2
                } else {
                    val p = fields[i++]
                    result += FileChange(
                        status,
                        if (status == ChangeStatus.ADDED) null else p,
                        if (status == ChangeStatus.DELETED) null else p,
                        if (oldBlob == NULL_BLOB) "" else oldBlob,
                        if (newBlob == NULL_BLOB) "" else newBlob,
                    )
                }
            }
            return result
        }
    }
}
