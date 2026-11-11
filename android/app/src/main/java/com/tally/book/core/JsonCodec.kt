package com.tally.book.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** JSON.parse / JSON.stringify over the [Obj]/[Arr] tree, with JS's number and string output. */
object JsonCodec {
    private val json = Json { isLenient = false }

    fun parse(text: String): Any? = fromElement(json.parseToJsonElement(text))

    fun parseObj(text: String): Obj = parse(text).asObj() ?: error("not a JSON object")

    private fun fromElement(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> LinkedHashMap<String, Any?>().also { m -> e.forEach { (k, v) -> m[k] = fromElement(v) } }
        is JsonArray -> ArrayList<Any?>(e.size).also { l -> e.forEach { l.add(fromElement(it)) } }
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.content == "true" -> true
            e.content == "false" -> false
            else -> e.content.toDouble()
        }
    }

    /** JSON.stringify(v): undefined-like values (functions) don't exist here; NaN/Infinity -> null. */
    fun stringify(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Number -> v.toDouble().let { if (it.isFinite()) sb.append(jsNumber(it)) else sb.append("null") }
            is Map<*, *> -> {
                sb.append('{'); var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(','); first = false
                    quote(sb, k.toString()); sb.append(':'); write(sb, x)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('['); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); write(sb, x) }; sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    fun quote(sb: StringBuilder, s: String) {
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
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> { sb.append(c).append(s[i + 1]); i++ }
                c.isSurrogate() -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }
}
