package com.mythic.localreview.core

import java.nio.file.Files
import java.nio.file.Path

/** A throwaway real git repository for tests. */
class TestRepo(val dir: Path = Files.createTempDirectory("lr-repo")) {
    val git = GitCli(dir)

    init {
        git.run("init", "-q", "-b", "master")
        git.run("config", "user.name", "Test")
        git.run("config", "user.email", "test@example.com")
        git.run("config", "commit.gpgsign", "false")
        git.run("config", "core.autocrlf", "false")
    }

    fun write(path: String, content: String) {
        val f = dir.resolve(path)
        Files.createDirectories(f.parent)
        Files.writeString(f, content)
    }

    fun delete(path: String) {
        Files.delete(dir.resolve(path))
    }

    fun commit(message: String): String {
        git.run("add", "-A")
        git.run("commit", "-q", "-m", message)
        return git.run("rev-parse", "HEAD").trim()
    }

    fun branch(name: String) {
        git.run("checkout", "-q", "-b", name)
    }

    fun checkout(name: String) {
        git.run("checkout", "-q", name)
    }

    fun mv(from: String, to: String) {
        Files.createDirectories(dir.resolve(to).parent)
        git.run("mv", from, to)
    }
}

fun tempDir(): Path = Files.createTempDirectory("lr-store")

fun lines(vararg l: String) = l.joinToString("\n", postfix = "\n")
