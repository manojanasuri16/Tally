package com.tally.book.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Device settings that are not part of DB (language, theme mode before unlock, lock profile...),
 * kept in the "tally" SharedPreferences. Keys look like "tally-lang".
 * In JVM tests it is a plain in-memory map.
 */
object DevicePrefs {
    private var sp: SharedPreferences? = null
    private val mem = HashMap<String, String>()

    fun init(ctx: Context) { sp = ctx.getSharedPreferences("tally", Context.MODE_PRIVATE) }

    fun get(key: String): String? = sp?.getString(key, null) ?: mem[key]
    fun set(key: String, value: String?) {
        if (value == null) { sp?.edit()?.remove(key)?.apply(); mem.remove(key) }
        else { sp?.edit()?.putString(key, value)?.apply(); if (sp == null) mem[key] = value }
    }
    fun remove(key: String) = set(key, null)
    fun clearForTests() = mem.clear()
}
