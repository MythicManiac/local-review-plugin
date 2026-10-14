package com.mythic.localreview.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

object SshKeys {
    private val NOT_KEYS = setOf("config", "authorized_keys", "environment", "rc")

    /** `~` expanded, forward slashes (what `sh`, which runs `core.sshCommand`, understands on every platform). */
    fun normalize(path: String, home: Path = Path.of(System.getProperty("user.home"))): String {
        var p = path.trim()
        if (p == "~") p = home.toString() else if (p.startsWith("~/") || p.startsWith("~\\")) p = home.resolve(p.substring(2)).toString()
        return p.replace('\\', '/')
    }

    /** The value written to `core.sshCommand` for [keyPath] (already normalised). */
    fun command(keyPath: String) = "ssh -i '$keyPath' -o IdentitiesOnly=yes -o BatchMode=yes"

    /** Private keys found in [sshDir]: regular files starting with a PEM/OpenSSH private-key header. Content is never read beyond the first line. */
    fun detect(sshDir: Path = Path.of(System.getProperty("user.home"), ".ssh")): List<String> {
        if (!Files.isDirectory(sshDir)) return emptyList()
        return Files.list(sshDir).use { s ->
            s.filter { Files.isRegularFile(it) }
                .filter { val n = it.fileName.toString(); !n.endsWith(".pub") && !n.startsWith("known_hosts") && n !in NOT_KEYS }
                .filter { looksLikePrivateKey(it) }
                .map { it.toString().replace('\\', '/') }
                .toList().sorted()
        }
    }

    private fun looksLikePrivateKey(f: Path): Boolean = try {
        Files.newBufferedReader(f).use { it.readLine() }?.startsWith("-----BEGIN") == true
    } catch (e: IOException) {
        false
    } catch (e: java.nio.charset.CharacterCodingException) {
        false
    }

    /** Appends a pointer to the key setting when an ssh failure looks like an authentication/host problem. */
    fun withHint(message: String): String = when {
        message.contains("Permission denied", true) || message.contains("publickey", true) ->
            "$message\nSelect the SSH key for this group in Manage Review Groups → SSH Key…."
        message.contains("Host key verification failed", true) ->
            "$message\nConnect once with ssh from a terminal to trust the host, then retry."
        else -> message
    }
}
