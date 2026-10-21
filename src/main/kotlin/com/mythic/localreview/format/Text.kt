package com.mythic.localreview.format

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * The lines of one side of a file, as the line model of ALGORITHMS 1.1 defines them. Behaves as a list of the
 * lines; additionally keeps the UTF-8 bytes of each line so hashing many windows of one file encodes each line once.
 */
class Lines(private val list: List<String>) : AbstractList<String>() {
    private val encoded: Array<ByteArray> by lazy(LazyThreadSafetyMode.PUBLICATION) { Array(list.size) { list[it].toByteArray(Charsets.UTF_8) } }

    override val size: Int get() = list.size

    override fun get(index: Int): String = list[index]

    /** [hashLines] of the lines `from until to` (0-based, exclusive end), without re-encoding. */
    fun hashRange(from: Int, to: Int): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (i in from until to) {
            md.update(encoded[i])
            md.update(LF)
        }
        return toHex(md.digest())
    }
}

private val LF = byteArrayOf('\n'.code.toByte())
private val HEX = "0123456789abcdef".toCharArray()

private fun toHex(bytes: ByteArray): String {
    val out = CharArray(bytes.size * 2)
    for (i in bytes.indices) {
        val v = bytes[i].toInt() and 0xff
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 15]
    }
    return String(out)
}

/** The number of leading bytes searched for a NUL when deciding whether a blob is text (ALGORITHMS 1.3, rule 1). */
private const val NUL_SCAN_BYTES = 8000

/** Splits text at every LF, drops one CR before it, and does not count the empty piece after a final LF (1.1). */
fun splitLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val out = ArrayList<String>()
    var start = 0
    while (true) {
        val lf = text.indexOf('\n', start)
        if (lf < 0) {
            out += text.substring(start).dropOneCr()
            break
        }
        out += text.substring(start, lf).dropOneCr()
        start = lf + 1
        if (start == text.length) break
    }
    return out
}

private fun String.dropOneCr() = if (endsWith('\r')) substring(0, length - 1) else this

/**
 * The lines of a blob (ALGORITHMS 1.1, 1.3), or null if the blob is not text: a NUL in its first 8000 bytes, bytes that are
 * not well-formed UTF-8 (RFC 3629: no overlong forms, no surrogates, nothing above U+10FFFF), or a CR not directly followed
 * by LF. A leading byte order mark is not part of line 1.
 */
fun linesOfBlob(bytes: ByteArray): Lines? {
    val scan = minOf(bytes.size, NUL_SCAN_BYTES)
    for (i in 0 until scan) if (bytes[i].toInt() == 0) return null
    var text = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        return null
    }
    if (text.isNotEmpty() && text[0] == '\uFEFF') text = text.substring(1)
    var cr = text.indexOf('\r')
    while (cr >= 0) {
        if (cr + 1 >= text.length || text[cr + 1] != '\n') return null
        cr = text.indexOf('\r', cr + 1)
    }
    return Lines(splitLines(text))
}

/** SHA-256 over the lines, each in UTF-8 followed by one LF, as 64 lowercase hex characters (ALGORITHMS 1.2). */
fun hashLines(lines: List<String>): String {
    val md = MessageDigest.getInstance("SHA-256")
    for (line in lines) {
        md.update(line.toByteArray(Charsets.UTF_8))
        md.update(LF)
    }
    return toHex(md.digest())
}

/** Whether a character is in the whitespace set of ALGORITHMS 1.4: exactly U+0009, U+000B, U+000C and U+0020. */
private fun isSpecSpace(c: Char) = c == '\t' || c == '\u000B' || c == '\u000C' || c == ' '

/** `trim` of ALGORITHMS 1.4. */
fun trimSpec(s: String): String {
    var b = 0
    var e = s.length
    while (b < e && isSpecSpace(s[b])) b++
    while (e > b && isSpecSpace(s[e - 1])) e--
    return s.substring(b, e)
}

/** `trimEnd` of ALGORITHMS 1.4. */
fun trimEndSpec(s: String): String {
    var e = s.length
    while (e > 0 && isSpecSpace(s[e - 1])) e--
    return s.substring(0, e)
}
