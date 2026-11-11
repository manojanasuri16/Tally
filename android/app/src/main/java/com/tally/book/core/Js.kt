package com.tally.book.core

/*
 * The book is a tree of JSON objects, arrays, strings, numbers, booleans and nulls (the same
 * conventions as Balance's ledger). Fields this version does not know load and are written back
 * without loss.
 *
 * Representation: object -> Obj (LinkedHashMap, key order kept), array -> Arr (ArrayList),
 * number -> Double (always), string -> String, boolean -> Boolean, null -> null.
 * Helpers below give the JavaScript semantics the web code relies on (`n()` is parseFloat with
 * NaN -> 0, `truthy()` is JS truthiness, `str()` is String(v || '')).
 */

typealias Obj = MutableMap<String, Any?>
typealias Arr = MutableList<Any?>

fun obj(vararg pairs: Pair<String, Any?>): Obj = LinkedHashMap<String, Any?>().apply { pairs.forEach { (k, v) -> put(k, norm(v)) } }
fun arr(vararg values: Any?): Arr = ArrayList<Any?>().apply { values.forEach { add(norm(it)) } }

/** Ints and Longs become Double, so every number in the tree is a Double, as in JS. */
fun norm(v: Any?): Any? = when (v) {
    is Double -> v
    is Number -> v.toDouble()
    else -> v
}

private val FLOAT_PREFIX = Regex("^[+-]?(Infinity|(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?)")

/** JS `parseFloat(v)`, then NaN/Infinity -> 0. The web app's `n()`. */
fun n(v: Any?): Double = when (v) {
    null -> 0.0
    is Double -> if (v.isFinite()) v else 0.0
    is Number -> v.toDouble().let { if (it.isFinite()) it else 0.0 }
    is String -> parseFloatJs(v).let { if (it.isFinite()) it else 0.0 }
    is Boolean -> 0.0
    else -> 0.0
}

/** JS `parseFloat` (NaN when nothing parses). */
fun parseFloatJs(s: String): Double {
    val m = FLOAT_PREFIX.find(s.trimStart()) ?: return Double.NaN
    val t = m.value
    return when {
        t.endsWith("Infinity") -> if (t.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        else -> t.toDoubleOrNull() ?: Double.NaN
    }
}

/** JS truthiness. */
fun truthy(v: Any?): Boolean = when (v) {
    null -> false
    is Boolean -> v
    is Double -> v != 0.0 && !v.isNaN()
    is Number -> v.toDouble().let { it != 0.0 && !it.isNaN() }
    is String -> v.isNotEmpty()
    else -> true
}

/** JS `String(v || '')`. Numbers print the way JS prints them (3, not 3.0). */
fun str(v: Any?): String = if (!truthy(v)) "" else jsString(v)

/** JS `String(v)` for any value. */
fun jsString(v: Any?): String = when (v) {
    null -> "null"
    is String -> v
    is Double -> jsNumber(v)
    is Number -> jsNumber(v.toDouble())
    is Boolean -> v.toString()
    is List<*> -> v.joinToString(",") { if (it == null) "" else jsString(it) }
    is Map<*, *> -> "[object Object]"
    else -> v.toString()
}

/** Number.prototype.toString for the values a ledger holds. */
fun jsNumber(d: Double): String {
    if (d.isNaN()) return "NaN"
    if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
    if (d == 0.0) return "0"
    if (d == Math.rint(d) && kotlin.math.abs(d) < 1e21) return d.toLong().toString()
    val a = kotlin.math.abs(d)
    return if (a >= 1e-6 && a < 1e21) java.math.BigDecimal(d.toString()).stripTrailingZeros().toPlainString()
    else {
        val bd = java.math.BigDecimal(d.toString()).stripTrailingZeros()
        val digits = bd.unscaledValue().abs().toString()
        val exp = digits.length - 1 - bd.scale()
        val mant = if (digits.length > 1) digits[0] + "." + digits.substring(1) else digits
        (if (d < 0) "-" else "") + mant + "e" + (if (exp >= 0) "+" else "") + exp
    }
}

// ---------- typed reads from a JSON object (never throw, like JS property access) ----------

@Suppress("UNCHECKED_CAST")
fun Any?.asObj(): Obj? = this as? MutableMap<String, Any?>
@Suppress("UNCHECKED_CAST")
fun Any?.asArr(): Arr? = this as? MutableList<Any?>

/** `o[k]` as an object, or null. */
fun Map<String, Any?>?.o(k: String): Obj? = this?.get(k).asObj()
/** `o[k] || (o[k] = {})` */
fun Obj.oo(k: String): Obj = get(k).asObj() ?: obj().also { put(k, it) }
/** `o[k]` as an array, or null. */
fun Map<String, Any?>?.a(k: String): Arr? = this?.get(k).asArr()
/** `o[k] || (o[k] = [])` */
fun Obj.aa(k: String): Arr = get(k).asArr() ?: arr().also { put(k, it) }
/** `(o[k] || [])` as a list of objects (non-objects skipped). */
fun Map<String, Any?>?.objs(k: String): List<Obj> = this?.get(k).asArr()?.mapNotNull { it.asObj() } ?: emptyList()
/** `n(o[k])` */
fun Map<String, Any?>?.num(k: String): Double = n(this?.get(k))
/** `String(o[k] || '')` */
fun Map<String, Any?>?.s(k: String): String = str(this?.get(k))
/** `!!o[k]` */
fun Map<String, Any?>?.b(k: String): Boolean = truthy(this?.get(k))
/** `o[k] = v`, numbers normalised to Double. */
operator fun Obj.set(k: String, v: Any?) { put(k, norm(v)) }

/** A list's objects (non-objects skipped). */
fun Any?.objList(): List<Obj> = this.asArr()?.mapNotNull { it.asObj() } ?: emptyList()

/** `JSON.parse(JSON.stringify(v))` */
fun deepCopy(v: Any?): Any? = when (v) {
    is Map<*, *> -> LinkedHashMap<String, Any?>().also { m -> v.forEach { (k, x) -> m[k.toString()] = deepCopy(x) } }
    is List<*> -> ArrayList<Any?>().also { l -> v.forEach { l.add(deepCopy(it)) } }
    is Number -> v.toDouble()
    else -> v
}

@Suppress("UNCHECKED_CAST")
fun Obj.copyObj(): Obj = deepCopy(this) as Obj
