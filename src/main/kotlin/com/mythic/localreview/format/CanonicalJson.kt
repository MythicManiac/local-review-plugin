package com.mythic.localreview.format

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON text exactly as `JSON.stringify(value, null, 2)` produces it, plus a final `\n`. Two devices that write the same value write the same
 * bytes, so git merges their additions without a conflict (CONFORMANCE, "Plugin 0.1.0 files"; the property order is the order of the object).
 */
object CanonicalJson {
    fun write(value: JsonElement): ByteArray = (StringBuilder().also { append(it, value, 0) }.append('\n')).toString().toByteArray(Charsets.UTF_8)

    private fun indent(sb: StringBuilder, level: Int) {
        repeat(level) { sb.append("  ") }
    }

    private fun append(sb: StringBuilder, v: JsonElement, level: Int) {
        when (v) {
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> if (v.isString) quote(sb, v.content) else sb.append(v.content)
            is JsonArray -> {
                if (v.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append("[\n")
                v.forEachIndexed { i, e ->
                    if (i > 0) sb.append(",\n")
                    indent(sb, level + 1)
                    append(sb, e, level + 1)
                }
                sb.append('\n')
                indent(sb, level)
                sb.append(']')
            }
            is JsonObject -> {
                if (v.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append("{\n")
                var first = true
                for ((k, e) in v) {
                    if (!first) sb.append(",\n")
                    first = false
                    indent(sb, level + 1)
                    quote(sb, k)
                    sb.append(": ")
                    append(sb, e, level + 1)
                }
                sb.append('\n')
                indent(sb, level)
                sb.append('}')
            }
        }
    }

    /** JSON.stringify's string escaping: quote, backslash, the short escapes, other control characters and lone surrogates as `\u` + 4 lowercase hex digits. */
    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append("%04x".format(c.code))
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    sb.append(c).append(s[i + 1])
                    i++
                }
                Character.isSurrogate(c) -> sb.append("\\u").append("%04x".format(c.code))
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }
}
