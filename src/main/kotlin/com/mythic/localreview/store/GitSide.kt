package com.mythic.localreview.store

import com.mythic.localreview.core.GitCli
import com.mythic.localreview.format.BlobSource
import com.mythic.localreview.format.ChangeSet
import com.mythic.localreview.format.ChangedFile
import com.mythic.localreview.format.Pin
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path

/** The change set of a review (SPEC 8): the files between `pin.baseSha` and `pin.headSha` as the pinned `git diff --raw` command reports them. */
object ChangeSets {
    /** The command of SPEC 8, without the two commits and the trailing `--`. Every flag stops a user's git configuration from changing the result. */
    val GIT_ARGS = listOf(
        "-c", "diff.renameLimit=5000",
        "diff", "--raw", "-z", "-M",
        "--no-color", "--no-ext-diff", "--no-textconv", "--ignore-submodules=none", "--no-abbrev",
    )

    /** Runs the pinned command in [git]'s working directory, which MUST be the top level of the reviewed repository (SPEC 8). */
    fun read(git: GitCli, pin: Pin): ChangeSet = parse(git.runBytes(*GIT_ARGS.toTypedArray(), pin.baseSha, pin.headSha, "--"))

    /** Parses `git diff --raw -z` output: `:<oldmode> <newmode> <oldblob> <newblob> <status>` NUL path NUL (two paths for a rename or copy) NUL. */
    internal fun parse(raw: ByteArray): ChangeSet {
        val tokens = String(raw, Charsets.UTF_8).split('\u0000')
        val files = ArrayList<ChangedFile>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i++]
            if (!t.startsWith(":")) continue
            val f = t.substring(1).split(' ')
            if (f.size < 5) continue
            val letter = f[4].first()
            if (letter == 'R' || letter == 'C') {
                val oldPath = tokens.getOrNull(i++) ?: break
                val newPath = tokens.getOrNull(i++) ?: break
                files += ChangedFile(newPath, oldPath, statusWord(letter), f[0], f[1], f[2], f[3])
            } else {
                val path = tokens.getOrNull(i++) ?: break
                files += ChangedFile(path, null, statusWord(letter), f[0], f[1], f[2], f[3])
            }
        }
        return ChangeSet(files)
    }

    private fun statusWord(letter: Char) = when (letter) {
        'A' -> "added"
        'D' -> "deleted"
        'R' -> "renamed"
        'C' -> "copied"
        'T' -> "typeChanged"
        else -> "modified"
    }

    /** The smallest root commit reachable from [head] (SPEC 4), or null if there is none or it cannot be asked. */
    fun rootCommit(git: GitCli, head: String): String? = try {
        git.run("rev-list", "--max-parents=0", head).lines().map { it.trim() }.filter { it.isNotEmpty() }.minOrNull()
    } catch (_: Exception) {
        null
    }

    /** Every root commit of every ref: what a local clone can say about its identity when matching a review (SPEC 4.1). */
    fun rootCommits(git: GitCli): Set<String> = try {
        git.run("rev-list", "--max-parents=0", "--all").lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    } catch (_: Exception) {
        emptySet()
    }
}

/**
 * Reads the bytes of git blobs through one long-lived `git cat-file --batch` process, which is much cheaper than a process per blob when the
 * Changes tree evaluates a whole review. Thread-safe. Returns null for an object that is missing or is not a blob. Call [close] when done.
 */
class GitBlobs(private val workDir: Path, private val exe: String = "git") : BlobSource, AutoCloseable {
    private var process: Process? = null
    private var input: BufferedInputStream? = null

    @Synchronized
    override fun read(blob: String): ByteArray? {
        if (!com.mythic.localreview.format.Values.isSha(blob)) return null
        repeat(2) { attempt ->
            try {
                return request(blob)
            } catch (e: java.io.IOException) {
                closeProcess()
                if (attempt == 1) throw e
            }
        }
        return null
    }

    private fun ensure(): Pair<Process, BufferedInputStream> {
        val p = process?.takeIf { it.isAlive }
        if (p != null) return p to input!!
        closeProcess()
        val started = ProcessBuilder(exe, "-c", "core.quotepath=off", "cat-file", "--batch").directory(workDir.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD).also { it.environment()["GIT_TERMINAL_PROMPT"] = "0" }.start()
        process = started
        input = BufferedInputStream(started.inputStream)
        return started to input!!
    }

    private fun request(blob: String): ByteArray? {
        val (p, inp) = ensure()
        p.outputStream.write("$blob\n".toByteArray())
        p.outputStream.flush()
        val header = readLine(inp) ?: throw java.io.IOException("git cat-file ended")
        val parts = header.split(' ')
        if (parts.size < 3 || parts[1] != "blob") return null.also { if (parts.size >= 3) skip(inp, parts[2].toLong() + 1) }
        val size = parts[2].toInt()
        val data = inp.readNBytes(size)
        if (data.size != size || inp.read() != '\n'.code) throw java.io.IOException("git cat-file output was cut short")
        return data
    }

    private fun skip(inp: BufferedInputStream, n: Long) {
        inp.skipNBytes(n)
    }

    private fun readLine(inp: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.UTF_8)
            if (b == '\n'.code) return out.toString(Charsets.UTF_8)
            out.write(b)
        }
    }

    private fun closeProcess() {
        runCatching { process?.outputStream?.close() }
        runCatching { process?.destroyForcibly() }
        process = null
        input = null
    }

    @Synchronized
    override fun close() = closeProcess()
}
