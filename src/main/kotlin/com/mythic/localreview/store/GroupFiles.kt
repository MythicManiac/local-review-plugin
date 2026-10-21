package com.mythic.localreview.store

import com.mythic.localreview.core.AtomicFiles
import com.mythic.localreview.core.GitCli
import com.mythic.localreview.core.GitException
import com.mythic.localreview.format.RepoFiles
import java.nio.file.Files
import java.nio.file.Path

/** What [GroupFiles.ensure] did and found. */
data class RepoFilesReport(
    /** Whether a file was created or changed; the caller commits it. */
    val changed: Boolean,
    /** Required rules git still does not honour after appending them again (an override from outside the group's own files). Names the rule. */
    val notHonoured: List<String>,
)

/**
 * The repository files a group must have (SPEC 2.1): `.gitattributes` and `.gitignore` containing the required lines. Opening a group appends
 * what is missing, then asks git whether each rule is honoured and appends the line again where a later line overrides it. Never removes or
 * rewrites any other line, never touches `.git/info`.
 */
object GroupFiles {
    private class Rule(val file: String, val line: String, val probe: String, val honoured: (GitCli, String) -> Boolean)

    private const val EVENT_PROBE = "reviews/probe/events/probe.jsonl"

    private fun attr(name: String, expected: String) = { git: GitCli, path: String ->
        // `git check-attr` prints `<path>: <attribute>: <value>`.
        git.run("check-attr", name, "--", path).trim().substringAfterLast(": ") == expected
    }

    private fun checkIgnore(git: GitCli, path: String): Boolean = try {
        git.run("check-ignore", "-q", "--", path)
        true
    } catch (e: GitException) {
        if (e.exitCode == 1) false else throw e
    }

    private val rules = listOf(
        Rule(".gitattributes", "* -text", EVENT_PROBE, attr("text", "unset")),
        Rule(".gitattributes", "*.jsonl merge=union", EVENT_PROBE, attr("merge", "union")),
        Rule(".gitignore", "*.lock", "reviews/probe/events/probe-device.lock") { g, p -> checkIgnore(g, p) },
        Rule(".gitignore", "*.tmp", "reviews/probe/review.json.tmp") { g, p -> checkIgnore(g, p) },
        Rule(".gitignore", "/reviews/*/local/", "reviews/probe/local/probe-device/position.json") { g, p -> checkIgnore(g, p) },
    )

    /** The required lines of each file; the order is the order of SPEC 2.1. */
    private val required = mapOf(".gitattributes" to RepoFiles.GITATTRIBUTES, ".gitignore" to RepoFiles.GITIGNORE)

    /** Creates or completes the two files in [dir] (a git working tree), as for opening a group. The caller holds the group's lock and commits. */
    fun ensure(dir: Path, git: GitCli): RepoFilesReport {
        var changed = false
        for ((name, lines) in required) {
            val file = dir.resolve(name)
            val text = if (Files.isRegularFile(file)) String(Files.readAllBytes(file), Charsets.UTF_8) else ""
            val next = RepoFiles.appendMissing(text, lines)
            if (next != text || !Files.exists(file)) {
                AtomicFiles.write(file, next.toByteArray(Charsets.UTF_8))
                changed = true
            }
        }
        // A required line that is present can still be overridden by a later line (`* text=auto` after `* -text`), so ask git.
        for (rule in rules.filterNot { it.honoured(git, it.probe) }) {
            appendAgain(dir.resolve(rule.file), rule.line)
            changed = true
        }
        val still = rules.filterNot { it.honoured(git, it.probe) }.map { "${it.line} (${it.file})" }
        return RepoFilesReport(changed, still)
    }

    /** Appends [line] at the end, where it is the last match, without touching the line that overrode it. */
    private fun appendAgain(file: Path, line: String) {
        val text = String(Files.readAllBytes(file), Charsets.UTF_8)
        val base = if (text.isEmpty() || text.endsWith("\n")) text else "$text\n"
        AtomicFiles.write(file, "$base$line\n".toByteArray(Charsets.UTF_8))
    }
}
