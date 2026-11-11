package com.tally.book.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * Port of app/js/lib/util.js. Dates are device-local, never UTC (CLAUDE.md "Dates are device-local").
 * Every "now" in the app goes through [Clock], so tests can pin it like tools/test-money.mjs does.
 */

object Clock {
    /** Pinned in tests; null means the real clock. */
    @Volatile var fixed: LocalDateTime? = null
    fun now(): LocalDateTime = fixed ?: LocalDateTime.now()
    fun today(): LocalDate = now().toLocalDate()
    /** `Date.now()` */
    fun millis(): Long = fixed?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: System.currentTimeMillis()
}

val MN = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
val MON3 = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** JS Math.round (half up toward +Infinity). */
fun jsRound(v: Double): Double = kotlin.math.floor(v + 0.5)

/** 1234567 -> "12,34,567" (en-IN grouping of an integer). */
fun groupIN(v: Long): String {
    val s = abs(v).toString()
    if (s.length <= 3) return (if (v < 0) "-" else "") + s
    val last3 = s.takeLast(3)
    var rest = s.dropLast(3)
    val parts = ArrayList<String>()
    while (rest.length > 2) { parts.add(0, rest.takeLast(2)); rest = rest.dropLast(2) }
    if (rest.isNotEmpty()) parts.add(0, rest)
    return (if (v < 0) "-" else "") + parts.joinToString(",") + "," + last3
}

/** `inr(v)`: ₹, rounded, en-IN grouping; a residue like -0.3 is ₹0, not -₹0. */
fun inr(v: Double): String {
    val r = jsRound(abs(v)).toLong()
    val neg = v < 0 && r != 0L
    return (if (neg) "-₹" else "₹") + groupIN(r)
}

/** `toLocaleString('en-IN', { maximumFractionDigits: k })` for a number. */
fun fmtIN(v: Double, maxFrac: Int = 3): String {
    val scaled = java.math.BigDecimal(v).setScale(maxFrac, java.math.RoundingMode.HALF_EVEN).stripTrailingZeros()
    val intPart = scaled.toBigInteger().toLong()
    val frac = scaled.subtract(java.math.BigDecimal(intPart)).abs()
    val fracStr = if (frac.signum() == 0) "" else frac.toPlainString().removePrefix("0")
    val neg = v < 0 && (intPart != 0L || fracStr.isNotEmpty())
    return (if (neg && intPart == 0L) "-" else "") + groupIN(intPart) + fracStr
}

fun gram(v: Any?): String = fmtIN(n(v), 3)

fun prevKey(k: String): String = YearMonth.parse(k).minusMonths(1).toString()
fun nextKey(k: String): String = YearMonth.parse(k).plusMonths(1).toString()
fun addMonths(key: String, count: Int): String = YearMonth.parse(key).plusMonths(count.toLong()).toString()
fun daysIn(key: String): Int = YearMonth.parse(key).lengthOfMonth()

/** `localISO(d)`: YYYY-MM-DD in the device's own timezone. */
fun localISO(d: LocalDate = Clock.today()): String = d.toString()
fun todayISO(): String = localISO()
fun yesterdayISO(): String = localISO(Clock.today().minusDays(1))
fun todayKey(): String = YearMonth.from(Clock.today()).toString()

/** Parses YYYY-MM-DD (or a longer ISO string) into a date, or null. */
fun parseISO(s: Any?): LocalDate? {
    val t = str(s); if (t.length < 10) return null
    return runCatching { LocalDate.parse(t.substring(0, 10)) }.getOrNull()
}

/** `new Date(y, m-1, d)` with JS rollover (day 0 = last day of the previous month, day 32 rolls forward). */
fun jsDate(y: Int, month0: Int, day: Int): LocalDate =
    LocalDate.of(y, 1, 1).plusMonths(month0.toLong()).plusDays((day - 1).toLong())

/** Whole days from a to b, like `Math.round((b - a) / 864e5)` over local midnights. */
fun daysBetween(a: LocalDate, b: LocalDate): Long = java.time.temporal.ChronoUnit.DAYS.between(a, b)

fun monthLabel(key: String): String {
    val ym = YearMonth.parse(key)
    return monthNameL(ym.monthValue - 1) + " " + ym.year
}

/** dd-Mon-yyyy with English month names, for anything stored or sent out. */
fun fmtISODateEn(iso: Any?): String {
    val p = str(iso).split("-")
    if (p.size != 3) return ""
    val y = p[0].toIntOrNull() ?: 0; val mo = p[1].toIntOrNull() ?: 0; val d = p[2].take(2).toIntOrNull() ?: 0
    if (y == 0 || mo == 0 || d == 0 || mo > 12) return ""
    return d.toString().padStart(2, '0') + "-" + MON3[mo - 1] + "-" + y
}

/** The same for the screen, with the month in the chosen language. */
fun fmtISODate(iso: Any?): String {
    val en = fmtISODateEn(iso)
    if (en.isEmpty() || I18n.lang == "en") return en
    val p = en.split("-")
    return p[0] + "-" + monthNameL(MON3.indexOf(p[1]), true) + "-" + p[2]
}

fun fmtISODateNoYear(iso: Any?): String {
    val en = fmtISODate(iso)
    if (en.isEmpty()) return ""
    return en.replace(Regex("-[^-]+$"), "").replaceFirst("-", " ")
}

fun ord(d: Int): String {
    val s = listOf("th", "st", "nd", "rd"); val v = d % 100
    val i = (v - 20) % 10
    return d.toString() + (s.getOrNull(if (i >= 0) i else -1) ?: s.getOrNull(v) ?: s[0])
}

private var uidSeq = 0L
fun resetUidSeq() { uidSeq = 0L }
private val rnd = java.security.SecureRandom()
/** `uid(prefix)`: prefix + time + counter + random, all base 36. */
fun uid(prefix: String = ""): String {
    val r = (0 until 3).map { "0123456789abcdefghijklmnopqrstuvwxyz"[rnd.nextInt(36)] }.joinToString("")
    return prefix + Clock.millis().toString(36) + (uidSeq++).toString(36) + r
}

fun Double.roundTo(digits: Int): Double {
    val f = Math.pow(10.0, digits.toDouble()); return (this * f).roundToLong() / f
}
