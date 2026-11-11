package com.tally.book.core

import android.content.Context
import java.time.format.TextStyle
import java.util.Locale

/*
 * English lives at the call site: t("nav.home", "Home"). The Telugu and Hindi dictionaries are
 * assets/i18n/te.json and hi.json, which are the source: every new key is added to both.
 */
object I18n {
    val LANGS = listOf("en", "te", "hi")
    @Volatile var lang: String = "en"
        private set
    private val dicts = HashMap<String, Map<String, String>>()

    fun init(ctx: Context, language: String) {
        for (l in listOf("te", "hi")) if (l !in dicts) {
            runCatching {
                val text = ctx.assets.open("i18n/$l.json").bufferedReader().readText()
                @Suppress("UNCHECKED_CAST")
                dicts[l] = (JsonCodec.parse(text) as Map<String, Any?>).mapValues { it.value.toString() }
            }
        }
        lang = if (language in LANGS) language else "en"
    }

    fun setLanguage(language: String) { lang = if (language in LANGS) language else "en" }

    fun locale(): Locale = when (lang) { "te" -> Locale("te", "IN"); "hi" -> Locale("hi", "IN"); else -> Locale("en", "IN") }

    fun lookup(key: String): String? = dicts[lang]?.get(key)
}

fun i18nFill(s: String, vars: Map<String, Any?>?): String {
    if (vars.isNullOrEmpty()) return s
    return Regex("\\{(\\w+)\\}").replace(s) { m -> vars[m.groupValues[1]]?.let { if (it is Double) jsNumber(it) else it.toString() } ?: m.value }
}

/** `t(key, en, vars)` */
fun t(key: String, en: String, vars: Map<String, Any?>? = null): String = i18nFill(I18n.lookup(key) ?: en, vars)
fun t(key: String, en: String, vararg vars: Pair<String, Any?>): String = t(key, en, vars.toMap())

/** `tp(key, count, one, many, vars)`: plural-aware t(). */
fun tp(key: String, count: Int, one: String, many: String, vars: Map<String, Any?>? = null): String =
    t(key + if (count == 1) ".one" else ".many", if (count == 1) one else many, mapOf<String, Any?>("n" to count) + (vars ?: emptyMap()))

/** Month name in the current language (English table for "en"). i is 0..11. */
fun monthNameL(i: Int, short: Boolean = false): String {
    if (I18n.lang == "en") return if (short) MN[i].take(3) else MN[i]
    return java.time.Month.of(i + 1).getDisplayName(if (short) TextStyle.SHORT_STANDALONE else TextStyle.FULL_STANDALONE, I18n.locale())
}

fun weekdayNameL(d: java.time.LocalDate, short: Boolean = false): String =
    d.dayOfWeek.getDisplayName(if (short) TextStyle.SHORT else TextStyle.FULL, I18n.locale())
