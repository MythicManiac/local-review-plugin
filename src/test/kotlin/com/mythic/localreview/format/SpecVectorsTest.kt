package com.mythic.localreview.format

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Runs every case of the JSON files in `spec/vectors` through the Kotlin implementation. The vectors are hand-derived, so a bug here cannot hide. */
class SpecVectorsTest {
    private fun cases(file: String, key: String = "cases") = SpecFiles.vectors(file).arr(key).map { it.jsonObject }

    private fun bytesOf(parts: List<JsonObject>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (p in parts) {
            when {
                "utf8" in p -> out.write(p.str("utf8").toByteArray(Charsets.UTF_8))
                "hex" in p -> out.write(p.str("hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                "fill" in p -> repeat(p.int("count")) { out.write(p.str("fill").toInt(16)) }
                else -> fail("unknown byte part $p")
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `lines of a blob`() {
        val all = cases("lines")
        assertTrue(all.size >= 25)
        for (c in all) {
            val actual = linesOfBlob(bytesOf(c.arr("bytes").map { it.jsonObject }))
            val name = c.str("name")
            when {
                "lineCount" in c -> assertEquals(c.int("lineCount"), actual?.size, name)
                c.getValue("lines").isNullValue() -> assertEquals(null, actual, name)
                else -> assertEquals(c.getValue("lines").strings(), actual?.toList(), name)
            }
        }
    }

    @Test
    fun `hash of lines`() {
        for (c in cases("hash")) assertEquals(c.str("sha256"), hashLines(c.getValue("lines").strings()), c.str("name"))
    }

    @Test
    fun `trim set`() {
        for (c in cases("trim")) {
            val input = c.str("input")
            assertEquals(c.str("trim"), trimSpec(input), c.str("name") + " (trim)")
            assertEquals(c.str("trimEnd"), trimEndSpec(input), c.str("name") + " (trimEnd)")
        }
    }

    @Test
    fun intervals() {
        for (c in cases("intervals")) {
            val list = c.getValue("list").intervals()
            val r = c.getValue("range").interval()
            val actual = if (c.str("op") == "add") addInterval(list, r.start, r.end) else subtractInterval(list, r.start, r.end)
            assertEquals(c.getValue("expected").intervals(), actual, c.str("name"))
        }
    }

    @Test
    fun `relocating a reviewed mark`() {
        for (c in cases("relocate-mark")) {
            val original = c.getValue("original").strings()
            val start = c.int("start")
            val end = c.int("end")
            val range = MarkRange(Side.NEW, start, end, "blob", hashLines(original.subList(start - 1, end)))
            val current = c.getValue("current").let { if (it.isNullValue()) null else Lines(it.strings()) }
            assertEquals(c["expected"].intervalOrNull(), resolveMark(range, current), c.str("name"))
        }
    }

    @Test
    fun `locating a comment's code again`() {
        for (c in cases("locate")) {
            val r = c.obj("range")
            val s = r.obj("snapshot")
            val range = AnchorRange(Side.NEW, r.int("startLine"), r.int("endLine"), Snapshot(s.getValue("lines").strings(), s.getValue("before").strings(), s.getValue("after").strings()))
            assertEquals(c["expected"].intervalOrNull(), locateRange(range, c.getValue("file").strings()), c.str("name"))
        }
    }

    @Test
    fun `repository files`() {
        val spec = SpecFiles.vectors("repo-files")
        val required = spec.obj("required").let { mapOf("gitignore" to it.getValue("gitignore").strings(), "gitattributes" to it.getValue("gitattributes").strings()) }
        assertEquals(RepoFiles.GITIGNORE, required["gitignore"])
        assertEquals(RepoFiles.GITATTRIBUTES, required["gitattributes"])
        for (c in spec.arr("append").map { it.jsonObject }) {
            assertEquals(c.str("expected"), RepoFiles.appendMissing(c.str("text"), required.getValue(c.str("file"))), c.str("name"))
        }
        for (c in spec.arr("merge").map { it.jsonObject }) {
            val req = required.getValue(c.str("file"))
            assertEquals(c.str("expected"), RepoFiles.merge(req, c.str("ours"), c.str("theirs")), c.str("name"))
            if (c.bool("symmetric")) assertEquals(c.str("expected"), RepoFiles.merge(req, c.str("theirs"), c.str("ours")), c.str("name") + " (swapped)")
        }
    }

    /** The submodule must be exactly the baseline the plugin was written against; this is CHANGE_CONTROL's drift test. */
    @Test
    fun `spec copy matches the frozen manifest`() {
        val version = SpecFiles.file("SPEC_VERSION").readLines().first { it.startsWith("baseline:") }.substringAfter(':').trim()
        assertEquals(SpecFiles.BASELINE, version)
        val manifest = SpecFiles.file("MANIFEST.sha256").readText().split(Regex("\r?\n")).filter { it.isNotBlank() }
        assertTrue(manifest.size >= 38, "manifest lists ${manifest.size} files")
        for (line in manifest) {
            val (hash, path) = line.split(Regex("\\s+"), limit = 2)
            val file = SpecFiles.file(path)
            assertTrue(file.isFile, "$path is missing")
            var bytes = file.readBytes()
            // As tools/manifest.mjs does: text outside examples/ is hashed with CRLF read as LF (a checkout may have converted it).
            if (!path.startsWith("examples/")) bytes = String(bytes, Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(hash, actual, "$path differs from the baseline")
        }
        assertNotNull(manifest.firstOrNull { it.endsWith("SPEC.md") })
    }
}
