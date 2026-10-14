package com.mythic.localreview.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SshKeysTest {
    private fun fakeKey(dir: Path, name: String = "work.id_ed25519"): Path =
        dir.resolve(name).also { Files.createDirectories(dir); Files.writeString(it, "-----BEGIN OPENSSH PRIVATE KEY-----\nxxx\n-----END OPENSSH PRIVATE KEY-----\n") }

    private fun cfg(groups: ReviewGroups, name: String, key: String): String? = try {
        GitCli(groups.group(name)!!.dir).run("config", "--local", "--get", key).trim()
    } catch (e: GitException) {
        null
    }

    @Test
    fun selectedKeyBecomesCoreSshCommandOfThatGroupOnly() {
        val keys = tempDir().resolve("my keys") // a path with a space must survive quoting
        val k = fakeKey(keys)
        val groups = ReviewGroups(tempDir())
        groups.create("work")
        groups.create("oss")
        groups.setSshKey("work", k.toString())

        val expected = k.toString().replace('\\', '/')
        assertEquals(expected, groups.group("work")!!.sshKey)
        assertEquals("ssh -i '$expected' -o IdentitiesOnly=yes -o BatchMode=yes", cfg(groups, "work", "core.sshCommand"))
        assertNull(groups.group("oss")!!.sshKey, "other groups keep the user's own configuration")
        assertNull(cfg(groups, "oss", "core.sshCommand"))
        // Per machine: it lives in .git/config, not in tracked files.
        assertFalse(GitCli(groups.group("work")!!.dir).run("ls-files").contains("ssh"))
    }

    @Test
    fun differentGroupsCanUseDifferentKeys() {
        val dir = tempDir()
        val a = fakeKey(dir, "a.id_ed25519")
        val b = fakeKey(dir, "b.id_ed25519")
        val groups = ReviewGroups(tempDir())
        groups.create("orgA"); groups.create("orgB")
        groups.setSshKey("orgA", a.toString()); groups.setSshKey("orgB", b.toString())
        assertTrue(cfg(groups, "orgA", "core.sshCommand")!!.contains("a.id_ed25519"))
        assertTrue(cfg(groups, "orgB", "core.sshCommand")!!.contains("b.id_ed25519"))
        groups.setSshKey("orgA", b.toString())
        assertFalse(cfg(groups, "orgA", "core.sshCommand")!!.contains("a.id_ed25519"), "changing the key replaces, not appends")
    }

    @Test
    fun clearingRemovesOnlyWhatWeSetAndKeepsHandWrittenCommands() {
        val k = fakeKey(tempDir())
        val groups = ReviewGroups(tempDir())
        groups.create("g")
        groups.setSshKey("g", k.toString())
        groups.setSshKey("g", null)
        assertNull(cfg(groups, "g", "core.sshCommand"))
        assertNull(groups.group("g")!!.sshKey)

        // A user's own core.sshCommand is not ours to remove.
        GitCli(groups.group("g")!!.dir).run("config", "--local", "core.sshCommand", "ssh -i /custom/key")
        groups.setSshKey("g", null)
        assertEquals("ssh -i /custom/key", cfg(groups, "g", "core.sshCommand"))
    }

    @Test
    fun invalidKeysAreRejectedBeforeAnythingIsWritten() {
        val dir = tempDir()
        val groups = ReviewGroups(tempDir())
        groups.create("g")
        assertFailsWith<IllegalArgumentException> { groups.setSshKey("g", dir.resolve("missing").toString()) }
        val pub = dir.resolve("k.pub").also { Files.writeString(it, "ssh-ed25519 AAAA") }
        assertFailsWith<IllegalArgumentException> { groups.setSshKey("g", pub.toString()) }
        val quoted = dir.resolve("it's").also { Files.writeString(it, "-----BEGIN X") }
        assertFailsWith<IllegalArgumentException> { groups.setSshKey("g", quoted.toString()) }
        assertNull(groups.group("g")!!.sshKey)
        assertNull(cfg(groups, "g", "core.sshCommand"))
    }

    @Test
    fun keyIsAppliedBeforeTheRemoteIsContactedWhenCreatingAGroup() {
        val groups = ReviewGroups(tempDir())
        val k = fakeKey(tempDir())
        val g = groups.create("work", bareRemote().toString(), k.toString())
        assertEquals(k.toString().replace('\\', '/'), g.sshKey)

        val root = tempDir()
        val failing = ReviewGroups(root)
        assertFailsWith<IllegalArgumentException> { failing.create("bad", bareRemote().toString(), root.resolve("nokey").toString()) }
        assertFalse(root.resolve("bad").exists(), "a rejected key leaves no half-made group")
    }

    @Test
    fun detectFindsPrivateKeysOnly() {
        val ssh = tempDir()
        fakeKey(ssh, "mythic.id_ed25519")
        fakeKey(ssh, "joariski.id_rsa")
        Files.writeString(ssh.resolve("mythic.id_ed25519.pub"), "ssh-ed25519 AAAA")
        Files.writeString(ssh.resolve("config"), "Host x\n")
        Files.writeString(ssh.resolve("known_hosts"), "github.com ssh-ed25519 AAAA")
        Files.writeString(ssh.resolve("notes.txt"), "just text")
        Files.write(ssh.resolve("binary.bin"), byteArrayOf(0x80.toByte(), 0xFF.toByte(), 0x00))
        assertEquals(listOf("joariski.id_rsa", "mythic.id_ed25519"), SshKeys.detect(ssh).map { it.substringAfterLast('/') })
        assertTrue(SshKeys.detect(ssh.resolve("nope")).isEmpty())
    }

    @Test
    fun tildeAndBackslashPathsAreNormalised() {
        val home = Path.of("C:/Users/me")
        assertEquals("C:/Users/me/.ssh/k", SshKeys.normalize("~/.ssh/k", home))
        assertEquals("C:/Users/me/.ssh/k", SshKeys.normalize("~\\.ssh\\k", home))
        assertEquals("D:/keys/k", SshKeys.normalize("D:\\keys\\k", home))
    }

    @Test
    fun authenticationFailuresPointAtTheKeySetting() {
        assertTrue(SshKeys.withHint("git@github.com: Permission denied (publickey).").contains("SSH Key"))
        assertTrue(SshKeys.withHint("Host key verification failed.").contains("trust the host"))
        assertEquals("boom", SshKeys.withHint("boom"))
    }
}
